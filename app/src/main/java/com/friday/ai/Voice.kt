package com.friday.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Jarvis குரல். வாக்கியம் வாக்கியமா குரலாக்கி (synth thread), முந்தையது பேசிட்டிருக்கும்போதே
// அடுத்ததை தயார் பண்ணும் (play thread). interrupt() வந்தா உடனே நிறுத்தும்.
object Voice {
    private val MODELS = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")

    private class Item(val text: String, val gen: Int)
    private class Audio(val pcm: ByteArray?, val rate: Int, val text: String, val gen: Int)

    private val textQ = LinkedBlockingQueue<Item>()
    private val audioQ = LinkedBlockingQueue<Audio>(3)
    private val pending = AtomicInteger(0)
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile var lastEnd = 0L
    @Volatile var onIdle: (() -> Unit)? = null
    @Volatile var fallback: ((String, () -> Unit) -> Unit)? = null
    private var started = false

    fun isSpeaking(): Boolean = pending.get() > 0

    @Synchronized
    fun enqueue(ctx: Context, text: String) {
        if (!started) {
            started = true
            val app = ctx.applicationContext
            Thread { synthLoop(app) }.apply { isDaemon = true }.start()
            Thread { playLoop() }.apply { isDaemon = true }.start()
        }
        pending.incrementAndGet()
        textQ.put(Item(text, gen))
    }

    @Synchronized
    fun interrupt() {
        gen++
        pending.set(0)
        textQ.clear()
        audioQ.clear()
        try { track?.stop() } catch (e: Exception) { }
    }

    private fun synthLoop(ctx: Context) {
        while (true) {
            try {
                val item = textQ.take()
                if (item.gen != gen) continue
                val r = synth(ctx, item.text)
                audioQ.put(Audio(r?.first, r?.second ?: 24000, item.text, item.gen))
            } catch (e: InterruptedException) { return } catch (e: Exception) { }
        }
    }

    private fun playLoop() {
        while (true) {
            try {
                val a = audioQ.take()
                if (a.gen != gen) continue
                Status.state = "speaking"
                if (a.pcm != null) play(a.pcm, a.rate, a.gen) else fallbackSpeak(a.text)
                if (a.gen == gen && pending.decrementAndGet() <= 0) {
                    pending.set(0)
                    lastEnd = System.currentTimeMillis()
                    onIdle?.invoke()
                }
            } catch (e: InterruptedException) { return } catch (e: Exception) { }
        }
    }

    private fun fallbackSpeak(text: String) {
        val fb = fallback ?: return
        val latch = CountDownLatch(1)
        fb(text) { latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
    }

    private fun synth(ctx: Context, text: String): Pair<ByteArray, Int>? {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) return null
        val prompt = "Speak in a calm, deep, refined, composed male AI-butler voice like JARVIS from Iron Man: natural pace, polite, subtly witty. " +
            "Pronounce Tamil, English or mixed text naturally. Say exactly this: " + text.take(500)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", p.voice)))))
        for (m in (listOf(p.ttsModel).filter { it.isNotBlank() } + MODELS).distinct()) {
            try {
                val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", p.geminiKey)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode !in 200..299) continue
                val parts = JSONObject(c.inputStream.bufferedReader().readText())
                    .getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inl = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                    val pcm = Base64.decode(inl.getString("data"), Base64.DEFAULT)
                    val rate = Regex("rate=(\\d+)").find(inl.optString("mimeType", inl.optString("mime_type", "")))?.groupValues?.get(1)?.toInt() ?: 24000
                    p.ttsModel = m
                    return Pair(pcm, rate)
                }
            } catch (e: Exception) { }
        }
        return null
    }

    private fun play(pcm: ByteArray, rate: Int, g: Int) {
        val frames = pcm.size / 2
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(AudioTrack.MODE_STATIC).build()
        track = at
        try {
            at.write(pcm, 0, pcm.size)
            at.play()
            val end = System.currentTimeMillis() + frames * 1000L / rate + 1500
            while (g == gen && at.playbackHeadPosition < frames && System.currentTimeMillis() < end) Thread.sleep(30)
        } catch (e: Exception) {
        } finally {
            try { at.stop() } catch (e: Exception) { }
            at.release()
            if (track === at) track = null
        }
    }
}
