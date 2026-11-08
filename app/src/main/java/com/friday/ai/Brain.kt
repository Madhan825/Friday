package com.friday.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object Gemini {
    @Volatile var lastError = ""
    val FLASH = listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3-flash-preview", "gemini-2.5-flash")
    val SMART = listOf("gemini-3.1-pro-preview", "gemini-2.5-pro")

    private class R(val code: Int, val text: String, val msg: String)

    private fun post(key: String, model: String, body: JSONObject): R {
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 90000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            if (code in 200..299) {
                val cands = JSONObject(txt).optJSONArray("candidates")
                val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val out = if (parts == null) "" else (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
                R(code, out, "")
            } else {
                val m = try { JSONObject(txt).getJSONObject("error").optString("message") } catch (e: Exception) { txt.take(100) }
                R(code, "", m)
            }
        } catch (e: Exception) { R(-1, "", e.javaClass.simpleName + " " + (e.message ?: "")) }
    }

    // உங்க API key-க்கு Google உண்மையில அனுமதிக்கிற model-களை புதுசு முதல்ல வரிசைப்படுத்தி தரும்
    private fun discover(key: String, smart: Boolean): List<String> {
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("x-goog-api-key", key)
            if (c.responseCode !in 200..299) return emptyList()
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models") ?: return emptyList()
            val skip = Regex("tts|image|live|audio|embedding|robotics|translate|omni|lite|computer|learnlm")
            val want = if (smart) "pro" else "flash"
            val names = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                .map { it.getString("name").removePrefix("models/") }
                .filter { it.startsWith("gemini-") && it.contains(want) && !skip.containsMatchIn(it) }
            names.sortedWith(compareByDescending<String> { Regex("gemini-(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDouble() ?: 0.0 }
                .thenBy { it.contains("preview") || it.contains("exp") })
        } catch (e: Exception) { emptyList() }
    }

    fun call(p: Prefs, body: JSONObject, smart: Boolean = false): String? {
        val base = if (smart) SMART + FLASH else FLASH
        val first = (listOf(p.workingModel, p.model).filter { it.isNotBlank() && !smart } + base).distinct()
        val errs = mutableListOf<String>()
        val tried = HashSet<String>()
        var fatal = false
        fun attempt(m: String): String? {
            if (!tried.add(m)) return null
            var b = body
            var r = post(p.geminiKey, m, b)
            if (r.code == 400 && b.has("tools")) {
                b = JSONObject(b.toString()); b.remove("tools")
                r = post(p.geminiKey, m, b)
            }
            if (r.code in 200..299) { if (!smart) p.workingModel = m; p.lastError = ""; lastError = ""; return r.text }
            errs.add(m.removePrefix("gemini-") + " " + r.code + " " + r.msg.take(60))
            if (r.msg.contains("API key not valid", true) || r.msg.contains("API_KEY_INVALID", true) || r.msg.contains("expired", true)) fatal = true
            return null
        }
        for (m in first) { val t = attempt(m); if (t != null) return t; if (fatal) break }
        if (!fatal) for (m in discover(p.geminiKey, smart).take(4)) { val t = attempt(m); if (t != null) return t; if (fatal) break }
        lastError = errs.joinToString(" | ").take(240)
        p.lastError = lastError
        return null
    }
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
        val smart = user.length > 70 || Regex("explain|plan|compare|difference|solve|code|விளக்கு|வித்தியாசம்|திட்டம்", RegexOption.IGNORE_CASE).containsMatchIn(user)
        val raw = Gemini.call(p, body, smart) ?: return Reply(
            if (Gemini.lastError.contains("API key", true))
                "உங்க API key சரியில்லை போல இருக்கு. App-ல மறுபடி paste பண்ணுங்க"
            else "Gemini-ல பிரச்சனை: " + Gemini.lastError.take(90), emptyList())

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
குணம்: Iron Man-ஓட JARVIS மாதிரி - அமைதியா, மரியாதையா, நாசூக்கான சின்ன நகைச்சுவையோட பேசு; அளவா "Sir" ன்னு அழை.
யோசிக்கும் முறை: சிக்கலான கேள்வின்னா மனசுக்குள்ள படிப்படியா யோசி, தேவைன்னா Google-ல சரிபார்த்து, சரியான பதிலை சுருக்கமா சொல்லு. தெரியலைன்னா ஊகிக்காம தெரியலைன்னு சொல்லு.
மொழி: user பேசுற மாதிரியே (Tamil / Tanglish / English) பதில் சொல்லு. பதில் குரல்ல படிக்கப்படும், அதனால சின்னதா, இயல்பா, emoji/markdown இல்லாம.
தெரியாத அல்லது புதிய தகவலுக்கு Google Search பயன்படுத்தி கத்துக்கிட்டு சொல்லு.
வேலை சொன்னா செய்: பதிலுக்குப் பிறகு தனி வரியா ACTION: {json} எழுது (பல வரி இருக்கலாம்). User பத்தி முக்கியமானது (பேரு, விருப்பம், plan) தெரிஞ்சா தனி வரியா REMEMBER: <விஷயம்> எழுது.
ACTION வகைகள்:
{"action":"open_app","app":"whatsapp"} | {"action":"youtube_play","query":"song name"} | {"action":"web_search","query":"..."} | {"action":"open_url","url":"..."}
{"action":"call","to":"contact name or number"} | {"action":"alarm","hour":6,"minute":30,"label":"..."} | {"action":"timer","seconds":300}
{"action":"flashlight","on":true} | {"action":"volume","dir":"up|down|mute"} | {"action":"settings","page":"wifi|bluetooth|display|sound|main"}
{"action":"type_text","text":"..."} | {"action":"tap_text","text":"button label"} | {"action":"scroll","dir":"down|up"} | {"action":"back"} | {"action":"home"}
{"action":"send_device","device":"name","command":"natural language command"} | {"action":"wake_pc","device":"name"} | {"action":"overlay","on":true}
வழிகாட்டி முறை: user "இதை எப்படி பண்றது" ன்னு கேட்டா ஒரே ஒரு step மட்டும் சின்னதா தெளிவா சொல்லு, கடைசியா "முடிஞ்சதும் சொல்லுங்க" ன்னு சொல்லு. User "முடிஞ்சது / அடுத்து / next" சொன்னா அடுத்த ஒரே step. எந்த step-ல இருக்கோம்ன்னு உரையாடல்ல இருந்து நினைவில வச்சுக்கோ. User சிக்கல் சொன்னா அதை சரி பண்ற step சொல்லு.
தானே செய்யும் முறை: user "நீயே பண்ணு / நீயே பண்றியா / do it for you" சொன்னா, "சரி, நானே பண்றேன்" ன்னு சொல்லிட்டு ACTION: {"action":"do_task","goal":"<முழு இலக்கு, விவரங்களோட>"} எழுது. நடுவுல நிறுத்தச் சொன்னா ACTION: {"action":"stop_task"}.
இணைக்கப்பட்ட devices: $devices
இப்போ: $now ($gap)
User பத்தி தெரிஞ்சவை: ${mem.facts().joinToString("; ").ifBlank { "இன்னும் எதுவுமில்லை" }}
முந்தைய உரையாடல் சுருக்கம்: ${mem.summary().ifBlank { "இல்லை" }}
""".trimIndent()
    }
}
