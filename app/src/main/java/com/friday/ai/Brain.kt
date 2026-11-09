package com.friday.ai

import android.content.Context
import android.util.Base64
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object Gemini {
    @Volatile var lastError = ""
    // 429 (quota தீர்ந்தது) வந்தா கொஞ்ச நேரம் Gemini-ஐ தொந்தரவு பண்ணாம காத்திருக்கும்
    @Volatile var quotaUntil = 0L
    private var saw429 = false
    private var otherFail = false
    private var delaySec = 60.0
    fun inCooldown(): Boolean = System.currentTimeMillis() < quotaUntil
    fun cooldownMsg(): String = "Gemini quota mudinjiduchu. " + ((quotaUntil - System.currentTimeMillis()) / 1000 + 1) + " sec-la thirumba try pannuven (AI Studio-la billing on pannalaam)"
    private fun resetNotes() { saw429 = false; otherFail = false; delaySec = 60.0 }
    private fun note(code: Int, msg: String) {
        if (code == 429) {
            saw429 = true
            val d = Regex("retry in (\\d+(?:\\.\\d+)?)s").find(msg)?.groupValues?.get(1)?.toDouble()
            if (d != null) delaySec = maxOf(delaySec, d)
        } else if (code != 400) otherFail = true
    }
    private fun finishNotes() {
        if (saw429 && !otherFail) quotaUntil = System.currentTimeMillis() + (minOf(delaySec, 300.0) + 2).toLong() * 1000
    }
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

    // Live (நேரடி குரல்) model-கள்
    fun discoverLive(key: String): List<String> {
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("x-goog-api-key", key)
            if (c.responseCode !in 200..299) return emptyList()
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models") ?: return emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("bidiGenerateContent") == true }
                .map { it.getString("name").removePrefix("models/") }
                .filter { (it.contains("live") || it.contains("native-audio")) && !it.contains("translate") }
                .sortedWith(compareByDescending<String> { Regex("gemini-(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDouble() ?: 0.0 }
                    .thenBy { it.contains("preview") })
        } catch (e: Exception) { emptyList() }
    }

    fun call(p: Prefs, body: JSONObject, smart: Boolean = false): String? {
        if (inCooldown()) { lastError = cooldownMsg(); p.lastError = lastError; return null }
        resetNotes()
        val base = if (smart) SMART + FLASH else FLASH
        val userModel = if (p.model.trim() == "gemini-2.5-flash") "" else p.model
        val first = (listOf(p.workingModel, userModel).filter { it.isNotBlank() && !smart } + base).distinct()
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
            errs.add(m.removePrefix("gemini-") + " " + r.code + " " + r.msg.take(60)); note(r.code, r.msg)
            if (r.msg.contains("API key not valid", true) || r.msg.contains("API_KEY_INVALID", true) || r.msg.contains("expired", true)) fatal = true
            return null
        }
        for (m in first) { val t = attempt(m); if (t != null) return t; if (fatal) break }
        if (!fatal) for (m in discover(p.geminiKey, smart).take(4)) { val t = attempt(m); if (t != null) return t; if (fatal) break }
        finishNotes(); lastError = errs.joinToString(" | ").take(240)
        p.lastError = lastError
        return null
    }

    // Gemini 3 "thinking" அமைப்பு இல்லாத model-ல 400 வந்தா தானா நிறுத்தும்
    @Volatile var thinkOk = true

    private class SR(val code: Int, val msg: String, val got: Boolean)

    private fun streamPost(key: String, model: String, body: JSONObject, cancelled: () -> Boolean, onDelta: (String) -> Unit): SR {
        var got = false
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 90000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val txt = c.errorStream?.bufferedReader()?.readText() ?: ""
                val m = try { JSONObject(txt).getJSONObject("error").optString("message") } catch (e: Exception) { txt.take(100) }
                return SR(code, m, false)
            }
            c.inputStream.bufferedReader().use { rd ->
                while (true) {
                    if (cancelled()) { c.disconnect(); break }
                    val line = rd.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val j = try { JSONObject(line.substring(5).trim()) } catch (e: Exception) { continue }
                    val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val t = parts.getJSONObject(i).optString("text")
                        if (t.isNotEmpty()) { got = true; onDelta(t) }
                    }
                }
            }
            SR(200, "", got)
        } catch (e: Exception) { SR(-1, e.javaClass.simpleName + " " + (e.message ?: ""), got) }
    }

    // வார்த்தை வார்த்தையா (stream) பதிலை வாங்கும். முதல் வாக்கியம் வந்ததும் பேச ஆரம்பிக்கலாம்.
    fun stream(p: Prefs, body: JSONObject, smart: Boolean, cancelled: () -> Boolean, onDelta: (String) -> Unit): Boolean {
        if (inCooldown()) { lastError = cooldownMsg(); p.lastError = lastError; return false }
        resetNotes()
        val base = if (smart) SMART + FLASH else FLASH
        val first = (listOf(p.workingModel, p.model).filter { it.isNotBlank() && !smart } + base).distinct()
        val errs = mutableListOf<String>()
        val tried = HashSet<String>()
        var fatal = false
        fun attempt(m: String): Boolean {
            if (!tried.add(m)) return false
            val variants = ArrayList<JSONObject>()
            variants.add(body)
            var noThink: JSONObject? = null
            if (body.optJSONObject("generationConfig")?.has("thinkingConfig") == true) {
                noThink = JSONObject(body.toString()); noThink.getJSONObject("generationConfig").remove("thinkingConfig")
                variants.add(noThink)
            }
            if (body.has("tools")) {
                val v = JSONObject((noThink ?: body).toString()); v.remove("tools"); variants.add(v)
            }
            for ((idx, v) in variants.withIndex()) {
                val r = streamPost(p.geminiKey, m, v, cancelled, onDelta)
                if (r.code in 200..299 || r.got) {
                    if (idx > 0 && noThink != null) thinkOk = false
                    if (!smart) p.workingModel = m
                    p.lastError = ""; lastError = ""
                    return true
                }
                errs.add(m.removePrefix("gemini-") + " " + r.code + " " + r.msg.take(60)); note(r.code, r.msg)
                if (r.msg.contains("API key not valid", true) || r.msg.contains("API_KEY_INVALID", true) || r.msg.contains("expired", true)) { fatal = true; return false }
                if (r.code != 400) return false
            }
            return false
        }
        for (m in first) { if (attempt(m)) return true; if (fatal || cancelled()) break }
        if (!fatal && !cancelled()) for (m in discover(p.geminiKey, smart).take(4)) { if (attempt(m)) return true; if (fatal) break }
        finishNotes(); lastError = errs.joinToString(" | ").take(240)
        p.lastError = lastError
        return false
    }
}

data class Reply(val speak: String, val actions: List<JSONObject>, val heard: String = "", val ignored: Boolean = false)

object Brain {
    val WAKE = Regex("friday|fry day|ஃப்ரைடே|ஃபிரைடே|ப்ரைடே|பிரைடே|ஃப்ரைடை", RegexOption.IGNORE_CASE)

    private const val AUDIO_NOTE = "The user's message is the attached audio, recorded by an always-on phone microphone (may contain background noise or other people). " +
        "Focus on the main, closest speaker. FIRST output one line: HEARD: <what they said, Tamil in Tamil script, English as English>. " +
        "If there is no clear speech meant for you, output only: HEARD: IGNORE. Otherwise continue with your normal reply (1-2 short sentences) and any ACTION / REMEMBER lines."

    // AI brain: உரையாடல் + நினைவு + Google Search + செயல்கள் (ACTION). audio இருந்தா குரலை நேரடியா புரிஞ்சுக்கும் (ஒரே call)
    fun ask(ctx: Context, user: String, audio: ByteArray? = null, needWake: Boolean = false): Reply {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) return Reply("முதல்ல app-ல Google API key போடுங்க", emptyList())
        val mem = Memory(ctx)
        val contents = JSONArray()
        mem.history().takeLast(24).forEach {
            contents.put(JSONObject().put("role", it.role).put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        val parts = JSONArray()
        if (audio == null) parts.put(JSONObject().put("text", user)) else {
            parts.put(JSONObject().put("text", AUDIO_NOTE))
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(ctx, mem)))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val smart = audio == null && (user.length > 70 || Regex("explain|plan|compare|difference|solve|code|விளக்கு|வித்தியாசம்|திட்டம்", RegexOption.IGNORE_CASE).containsMatchIn(user))
        val raw = Gemini.call(p, body, smart) ?: return Reply(
            if (Gemini.lastError.contains("API key", true))
                "உங்க API key சரியில்லை போல இருக்கு. App-ல மறுபடி paste பண்ணுங்க"
            else "Gemini-ல பிரச்சனை: " + Gemini.lastError.take(90), emptyList())

        val actions = mutableListOf<JSONObject>(); val facts = mutableListOf<String>(); val speak = StringBuilder()
        var heard = user
        raw.lines().forEach { l ->
            val t = l.trim()
            when {
                t.startsWith("HEARD:") -> heard = t.removePrefix("HEARD:").trim()
                t.startsWith("ACTION:") -> try { actions.add(JSONObject(t.removePrefix("ACTION:").trim())) } catch (_: Exception) {}
                t.startsWith("REMEMBER:") -> facts.add(t.removePrefix("REMEMBER:").trim())
                else -> speak.append(t.replace("*", "")).append(' ')
            }
        }
        if (audio != null && (heard.isBlank() || heard.contains("IGNORE", true) || (needWake && !WAKE.containsMatchIn(heard))))
            return Reply("", emptyList(), heard, true)
        val s = speak.toString().trim()
        val userText = heard.replace(WAKE, "").trim().ifBlank { heard }
        facts.forEach { mem.addFact(it) }
        mem.addTurn("user", userText); mem.addTurn("model", s)
        Thread { mem.compactIfNeeded(ctx) }.start()
        return Reply(s, actions, userText)
    }

    // Live (நேரடி குரல்) முறைக்கான அறிவுறுத்தல்
    fun liveInstruction(ctx: Context): String {
        val mem = Memory(ctx)
        val now = SimpleDateFormat("EEEE, dd MMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        val last = mem.lastTs()
        val gap = if (last == 0L) "first conversation" else "last spoke ${(System.currentTimeMillis() - last) / 3600000} hours ago"
        val devices = Prefs(ctx).devices().keys.joinToString(", ").ifBlank { "none" }
        return """
You are "Friday", a JARVIS-like personal AI assistant (like in Iron Man) living on the user's Android phone. Calm, respectful, subtly witty; call the user "Sir" occasionally.
LANGUAGE: reply in the language the user speaks: Tamil, Tanglish (Tamil written in English style) or English. Use natural spoken Tamil, not bookish Tamil.
STYLE: you are speaking aloud. Answer immediately in 1-2 short sentences like a human friend. No lists, no markdown. The user may interrupt you at any time: if interrupted, drop what you were saying and answer the new thing.
The microphone is always on. If the speech you hear is clearly not meant for you (other people talking, TV, music) and you are not in a conversation with the user, stay completely silent.
Use Google Search whenever you need facts you are not sure about, then answer briefly.
PHONE CONTROL: to do anything on the phone call phone_action (open_app, youtube_play, web_search, open_url, call, alarm, timer, flashlight, volume, settings, type_text, tap_text, scroll, back, home, send_device, wake_pc, overlay, do_task, stop_task). Give a very short confirmation.
GUIDE MODE: if the user asks "how do I ...", give exactly ONE short step, then ask them to tell you when done; give the next single step when they say done/next.
DO-IT-YOURSELF MODE: if the user says "you do it" or "நீயே பண்ணு", say a short okay and call phone_action with action do_task and a complete goal with all details. If they say stop, call stop_task.
Call remember_fact for important things about the user (name, preferences, plans).
Connected devices: $devices
Now: $now ($gap)
Known about the user: ${mem.facts().joinToString("; ").ifBlank { "nothing yet" }}
Earlier conversation summary: ${mem.summary().ifBlank { "none" }}
""".trimIndent()
    }


    interface Sink {
        fun cancelled(): Boolean
        fun onHeard(t: String)
        fun onSentence(s: String)
        fun onAction(a: JSONObject)
    }

    // குரலை நேரடியா Gemini-கிட்ட அனுப்பி: கேட்டது + பதில் + செயல்கள் எல்லாம் ஒரே call-ல. பதில் வாக்கியம் வாக்கியமா உடனே வரும்.
    fun askAudio(ctx: Context, wav: ByteArray, active: Boolean, lastReply: String, sink: Sink): Boolean {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) { sink.onSentence("முதல்ல app-ல Google API key போடுங்க"); return true }
        val mem = Memory(ctx)
        val contents = JSONArray()
        mem.history().takeLast(16).forEach {
            contents.put(JSONObject().put("role", it.role).put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        val instr = "STATE: " + (if (active) "active (conversation going on)" else "idle (needs wake word 'Friday')") + ". " +
            (if (lastReply.isNotBlank()) "You were just saying: \"" + lastReply.take(200) + "\". " else "") +
            "Listen to the audio. The owner speaks Tamil, English or Tanglish. Only the main, closest speaker (the owner) counts; ignore other people, TV and music. " +
            "If STATE is idle, answer only when the owner clearly said the wake word 'Friday'. " +
            "If the audio is background talk, noise, an echo of your own words, or not meant for you, output exactly: IGNORE. " +
            "Otherwise: first line 'HEARD: <transcript>', then your spoken reply (1 to 3 short natural sentences like a friend; if the owner interrupted you, respond straight to the interruption), then any ACTION:/REMEMBER: lines."
        val audio = JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", instr)).put(audio)))
        val gc = JSONObject().put("maxOutputTokens", 600)
        if (Gemini.thinkOk) gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(ctx, mem)))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", gc)
        val parser = Parser(sink, mem)
        val ok = Gemini.stream(p, body, false, { sink.cancelled() }) { d -> parser.feed(d) }
        parser.finish()
        if (ok && !parser.ignored && !sink.cancelled() && parser.spoken.isNotBlank()) {
            mem.addTurn("user", parser.heard.ifBlank { "(voice)" })
            mem.addTurn("model", parser.spoken.toString().trim())
            thread { mem.compactIfNeeded(ctx) }
        }
        return ok
    }

    private class Parser(val sink: Sink, val mem: Memory) {
        private val lb = StringBuilder()
        var ignored = false
        var heard = ""
        val spoken = StringBuilder()
        private var emitted = 0
        private val tags = listOf("HEARD:", "ACTION:", "REMEMBER:", "IGNORE")

        fun feed(d: String) {
            lb.append(d)
            while (true) {
                val i = lb.indexOf("\n")
                if (i < 0) break
                val ln = lb.substring(0, i)
                lb.delete(0, i + 1)
                line(ln)
            }
            partial()
        }

        fun finish() { if (lb.isNotBlank()) { line(lb.toString()); lb.setLength(0) } }

        private fun line(raw: String) {
            val t = raw.trim()
            if (t.isEmpty()) return
            when {
                t.startsWith("IGNORE") -> ignored = true
                t.startsWith("HEARD:") -> { heard = t.removePrefix("HEARD:").trim(); sink.onHeard(heard) }
                t.startsWith("ACTION:") -> try { sink.onAction(JSONObject(t.removePrefix("ACTION:").trim())) } catch (e: Exception) { }
                t.startsWith("REMEMBER:") -> mem.addFact(t.removePrefix("REMEMBER:").trim())
                else -> if (!ignored) say(t)
            }
        }

        private fun say(raw: String) {
            val t = raw.replace("*", "").trim()
            if (t.isEmpty()) return
            emitted++
            spoken.append(t).append(' ')
            sink.onSentence(t)
        }

        // வரி முடியும் முன்னாடியே, முழு வாக்கியம் வந்ததும் பேச அனுப்பும்
        private fun partial() {
            if (ignored) return
            val s = lb.toString()
            val t = s.trimStart()
            if (t.isEmpty()) return
            if (tags.any { t.startsWith(it) }) return
            if (t.length < 9 && tags.any { it.startsWith(t) }) return
            var start = 0
            var min = if (emitted == 0) 10 else 60
            for (i in s.indices) {
                val ch = s[i]
                if ((ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '…') && i + 1 < s.length && s[i + 1].isWhitespace() && i + 1 - start >= min) {
                    say(s.substring(start, i + 1))
                    start = i + 1
                    min = 60
                }
            }
            if (start > 0) lb.delete(0, start)
        }
    }

    private fun systemPrompt(ctx: Context, mem: Memory): String {
        val now = SimpleDateFormat("EEEE, dd MMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        val last = mem.lastTs()
        val gap = if (last == 0L) "this is the first conversation" else "last spoke " + ((System.currentTimeMillis() - last) / 3600000) + " hours ago"
        val devices = Prefs(ctx).devices().keys.joinToString(", ").ifBlank { "none" }
        val mode = Prefs(ctx).lang
        return """
You are "Friday", a JARVIS-like personal AI assistant (from Iron Man) living on the user's Android phone. Calm, respectful, subtly witty; call the user "Sir" occasionally.
LANGUAGE: current reply mode = $mode. Modes: tanglish = Tamil written in English letters, mixed naturally with English words (example: "Seri Sir, ippo time 4:30 aagudhu"); english = plain English; tamil = Tamil script. Default is tanglish. The user may speak Tamil, English or Tanglish: understand all of them. If the user asks to switch language (like "English-la pesu", "tamil-la pesu", "tanglish-la pesu"), confirm briefly in the new language and add the line ACTION: {"action":"language","mode":"tanglish|english|tamil"}. Even in tanglish mode, drop in a short English or Tamil phrase now and then so it sounds natural.
SPEECH: your reply is spoken aloud. Be short, natural and direct: 1 to 3 short sentences, no lists, no markdown, no emojis, no long preamble. Talk like a friend. If the user interrupts you, answer the new thing directly and do not repeat yourself.
THINKING: for hard questions reason step by step silently, use Google Search to verify facts you are not sure about, then answer briefly. If you do not know, say so instead of guessing.
OUTPUT: after your spoken reply, put each phone action on its own line as ACTION: {json}. Put facts worth remembering about the user (name, preferences, plans) on their own line as REMEMBER: <fact>.
ACTION types:
{"action":"open_app","app":"whatsapp"} | {"action":"youtube_play","query":"song name"} | {"action":"web_search","query":"..."} | {"action":"open_url","url":"..."}
{"action":"call","to":"contact name or number"} | {"action":"alarm","hour":6,"minute":30,"label":"..."} | {"action":"timer","seconds":300}
{"action":"flashlight","on":true} | {"action":"volume","dir":"up|down|mute"} | {"action":"settings","page":"wifi|bluetooth|display|sound|main"}
{"action":"type_text","text":"..."} | {"action":"tap_text","text":"button label"} | {"action":"scroll","dir":"down|up"} | {"action":"back"} | {"action":"home"}
{"action":"send_device","device":"name","command":"natural language command"} | {"action":"wake_pc","device":"name"} | {"action":"overlay","on":true}
{"action":"language","mode":"tanglish|english|tamil"}
GUIDE MODE: if the user asks "how do I ...", give exactly ONE short step, then ask them to tell you when done; give the next single step when they say done / next / mudinjiduchu. Remember which step you are on from the conversation. If they hit a problem, give the step that fixes it.
DO-IT-YOURSELF MODE: if the user says "you do it" / "neeye pannu", say a short okay and emit ACTION: {"action":"do_task","goal":"<complete goal with all details>"}. If they say stop, emit ACTION: {"action":"stop_task"}.
Connected devices: $devices
Now: $now ($gap)
Known about the user: ${mem.facts().joinToString("; ").ifBlank { "nothing yet" }}
Earlier conversation summary: ${mem.summary().ifBlank { "none" }}
""".trimIndent()
    }
}
