package com.friday.ai

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Gemini Live API (WebSocket): குரல் உள்ளே → குரல் வெளியே, உடனடியா. குறுக்கீடும் இதுக்கு தெரியும்.
// நாம local-ல பேச்சு ஆரம்பம்/முடிவை கண்டுபிடிச்சு (activityStart/End) பேசுற பகுதியை மட்டும் அனுப்புறோம்.
class LiveSession(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onInterrupted()
        fun onTurnComplete()
        fun onInText(t: String)
        fun onOutText(t: String)
        fun onTool(id: String, name: String, args: JSONObject)
    }

    companion object {
        val client: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    @Volatile var ready = false
    @Volatile var failReason = ""
    @Volatile var lastEvent = 0L
    @Volatile var unhealthy = false
    private var ws: WebSocket? = null
    private val readyL = CountDownLatch(1)
    private val closedL = CountDownLatch(1)
    private val out = ByteArrayOutputStream()

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)

    private fun setup(model: String, instruction: String, voice: String): JSONObject {
        val props = JSONObject()
            .put("action", str("open_app, youtube_play, web_search, open_url, call, alarm, timer, flashlight, volume, settings, type_text, tap_text, scroll, back, home, send_device, wake_pc, overlay, do_task, stop_task"))
            .put("app", str("app name for open_app")).put("query", str("search text or song name"))
            .put("url", str("web address")).put("to", str("contact name or phone number"))
            .put("text", str("text to type, or button label to tap")).put("dir", str("up, down, mute (volume) or up/down (scroll)"))
            .put("page", str("settings page: wifi, bluetooth, display, sound, main")).put("device", str("device name"))
            .put("command", str("command for the other device")).put("goal", str("complete goal for do_task with all details"))
            .put("label", str("alarm label"))
            .put("hour", JSONObject().put("type", "INTEGER")).put("minute", JSONObject().put("type", "INTEGER"))
            .put("seconds", JSONObject().put("type", "INTEGER")).put("on", JSONObject().put("type", "BOOLEAN"))
        val d1 = JSONObject().put("name", "phone_action").put("description", "Perform an action on the user's Android phone")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray().put("action")))
        val d2 = JSONObject().put("name", "remember_fact").put("description", "Remember an important fact about the user")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("fact", str("the fact"))).put("required", JSONArray().put("fact")))
        val tools = JSONArray().put(JSONObject().put("googleSearch", JSONObject()))
            .put(JSONObject().put("functionDeclarations", JSONArray().put(d1).put(d2)))
        val gen = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            .put("tools", tools)
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject())))
    }

    private fun finish() { ready = false; readyL.countDown(); closedL.countDown() }

    fun connectAndWait(model: String, instruction: String, voice: String): Boolean {
        val key = Prefs(ctx).geminiKey
        val req = Request.Builder().url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + key).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { w.send(setup(model, instruction, voice).toString()) }
            override fun onMessage(w: WebSocket, text: String) { handle(text) }
            override fun onMessage(w: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
            override fun onClosing(w: WebSocket, code: Int, reason: String) { if (failReason.isEmpty()) failReason = code.toString() + " " + reason; w.close(1000, null) }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { finish() }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { if (failReason.isEmpty()) failReason = (r?.code?.toString() ?: "") + " " + (t.message ?: ""); finish() }
        })
        readyL.await(10, TimeUnit.SECONDS)
        return ready
    }

    fun awaitClose() { closedL.await() }
    fun close() { try { ws?.close(1000, "bye") } catch (e: Exception) { } }

    private fun handle(text: String) {
        try {
            lastEvent = System.currentTimeMillis()
            val j = JSONObject(text)
            if (j.has("setupComplete")) { ready = true; readyL.countDown(); cb.onReady(); return }
            val sc = j.optJSONObject("serverContent")
            if (sc != null) {
                if (sc.optBoolean("interrupted")) cb.onInterrupted()
                val ps = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
                if (ps != null) for (i in 0 until ps.length()) {
                    val inl = ps.getJSONObject(i).optJSONObject("inlineData")
                    if (inl != null) cb.onAudio(Base64.decode(inl.getString("data"), Base64.DEFAULT))
                }
                val it = sc.optJSONObject("inputTranscription")?.optString("text") ?: ""
                if (it.isNotEmpty()) cb.onInText(it)
                val ot = sc.optJSONObject("outputTranscription")?.optString("text") ?: ""
                if (ot.isNotEmpty()) cb.onOutText(ot)
                if (sc.optBoolean("turnComplete")) cb.onTurnComplete()
            }
            val fc = j.optJSONObject("toolCall")?.optJSONArray("functionCalls")
            if (fc != null) for (i in 0 until fc.length()) {
                val c = fc.getJSONObject(i)
                cb.onTool(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject())
            }
            if (j.has("goAway")) close()
        } catch (e: Exception) { }
    }

    private fun sendJson(o: JSONObject) { try { ws?.send(o.toString()) } catch (e: Exception) { } }

    fun activityStart() { sendJson(JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject()))) }

    fun sendAudio(pcm: ByteArray) {
        synchronized(out) { out.write(pcm); if (out.size() >= 1920) flushAudio() }
    }

    private fun flushAudio() {
        val b = synchronized(out) { val x = out.toByteArray(); out.reset(); x }
        if (b.isEmpty()) return
        sendJson(JSONObject().put("realtimeInput", JSONObject().put("audio",
            JSONObject().put("data", Base64.encodeToString(b, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))))
    }

    fun activityEnd() {
        flushAudio()
        sendJson(JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())))
    }

    fun sendToolResponse(id: String, name: String, result: String) {
        sendJson(JSONObject().put("toolResponse", JSONObject().put("functionResponses",
            JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result))))))
    }
}
