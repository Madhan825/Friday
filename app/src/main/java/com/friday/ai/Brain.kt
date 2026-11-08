package com.friday.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object Gemini {
    // Google Gemini REST call. பிழை வந்தா null திரும்பும்.
    fun call(p: Prefs, body: JSONObject): String? = try {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/${p.model}:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", p.geminiKey)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (c.responseCode !in 200..299) null else {
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        }
    } catch (e: Exception) { null }
}

data class Reply(val speak: String, val actions: List<JSONObject>)

object Brain {
    // AI brain: உரையாடல் + நினைவு + Google Search + செயல்கள் (ACTION)
    fun ask(ctx: Context, user: String): Reply {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) return Reply("முதல்ல app-ல Google API key போடுங்க", emptyList())
        val mem = Memory(ctx)
        val contents = JSONArray()
        mem.history().takeLast(24).forEach {
            contents.put(JSONObject().put("role", it.role).put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))))
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(ctx, mem)))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject()))) // தெரியாததை Google-ல தேடும்
        val raw = Gemini.call(p, body) ?: return Reply("இப்போ இன்டர்நெட் அல்லது API-ல பிரச்சனை இருக்கு", emptyList())

        val actions = mutableListOf<JSONObject>(); val speak = StringBuilder()
        raw.lines().forEach { l ->
            val t = l.trim()
            when {
                t.startsWith("ACTION:") -> try { actions.add(JSONObject(t.removePrefix("ACTION:").trim())) } catch (_: Exception) {}
                t.startsWith("REMEMBER:") -> mem.addFact(t.removePrefix("REMEMBER:").trim())
                else -> speak.append(t.replace("*", "")).append(' ')
            }
        }
        val s = speak.toString().trim()
        mem.addTurn("user", user); mem.addTurn("model", s)
        Thread { mem.compactIfNeeded(ctx) }.start()
        return Reply(s, actions)
    }

    private fun systemPrompt(ctx: Context, mem: Memory): String {
        val now = SimpleDateFormat("EEEE, dd MMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        val last = mem.lastTs()
        val gap = if (last == 0L) "இது முதல் உரையாடல்" else "கடைசியா பேசி ${(System.currentTimeMillis() - last) / 3600000} மணி நேரம் ஆச்சு"
        val devices = Prefs(ctx).devices().keys.joinToString(", ").ifBlank { "none" }
        return """
நீ "Friday" - Iron Man-ல வர்ற மாதிரியான user-ஓட personal AI assistant, அவரோட Android phone-ல இருக்க.
மொழி: user பேசுற மாதிரியே (Tamil / Tanglish / English) பதில் சொல்லு. பதில் குரல்ல படிக்கப்படும், அதனால சின்னதா, இயல்பா, emoji/markdown இல்லாம.
தெரியாத அல்லது புதிய தகவலுக்கு Google Search பயன்படுத்தி கத்துக்கிட்டு சொல்லு.
வேலை சொன்னா செய்: பதிலுக்குப் பிறகு தனி வரியா ACTION: {json} எழுது (பல வரி இருக்கலாம்). User பத்தி முக்கியமானது (பேரு, விருப்பம், plan) தெரிஞ்சா தனி வரியா REMEMBER: <விஷயம்> எழுது.
ACTION வகைகள்:
{"action":"open_app","app":"whatsapp"} | {"action":"youtube_play","query":"song name"} | {"action":"web_search","query":"..."} | {"action":"open_url","url":"..."}
{"action":"call","to":"contact name or number"} | {"action":"alarm","hour":6,"minute":30,"label":"..."} | {"action":"timer","seconds":300}
{"action":"flashlight","on":true} | {"action":"volume","dir":"up|down|mute"} | {"action":"settings","page":"wifi|bluetooth|display|sound|main"}
{"action":"type_text","text":"..."} | {"action":"tap_text","text":"button label"} | {"action":"scroll","dir":"down|up"} | {"action":"back"} | {"action":"home"}
{"action":"send_device","device":"name","command":"natural language command"} | {"action":"wake_pc","device":"name"}
வழிகாட்டி முறை: user "இதை எப்படி பண்றது" ன்னு கேட்டா ஒரே ஒரு step மட்டும் சின்னதா தெளிவா சொல்லு, கடைசியா "முடிஞ்சதும் சொல்லுங்க" ன்னு சொல்லு. User "முடிஞ்சது / அடுத்து / next" சொன்னா அடுத்த ஒரே step. எந்த step-ல இருக்கோம்ன்னு உரையாடல்ல இருந்து நினைவில வச்சுக்கோ. User சிக்கல் சொன்னா அதை சரி பண்ற step சொல்லு.
தானே செய்யும் முறை: user "நீயே பண்ணு / நீயே பண்றியா / do it for you" சொன்னா, "சரி, நானே பண்றேன்" ன்னு சொல்லிட்டு ACTION: {"action":"do_task","goal":"<முழு இலக்கு, விவரங்களோட>"} எழுது. நடுவுல நிறுத்தச் சொன்னா ACTION: {"action":"stop_task"}.
இணைக்கப்பட்ட devices: $devices
இப்போ: $now ($gap)
User பத்தி தெரிஞ்சவை: ${mem.facts().joinToString("; ").ifBlank { "இன்னும் எதுவுமில்லை" }}
முந்தைய உரையாடல் சுருக்கம்: ${mem.summary().ifBlank { "இல்லை" }}
""".trimIndent()
    }
}
