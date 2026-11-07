package com.friday.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Turn(val role: String, val text: String, val ts: Long)

// நீண்டகால நினைவு: facts + summary + சமீபத்திய உரையாடல். Phone-ல file-ஆ சேமிக்கும்,
// அதனால மாசக்கணக்கு கழிச்சு பேசினாலும் எல்லாம் ஞாபகம் இருக்கும்.
class Memory(ctx: Context) {
    private val file = File(ctx.filesDir, "memory.json")
    private companion object { val lock = Any() }

    private fun load(): JSONObject = synchronized(lock) {
        try { JSONObject(file.readText()) } catch (e: Exception) {
            JSONObject().put("facts", JSONArray()).put("summary", "").put("history", JSONArray())
        }
    }
    private fun save(o: JSONObject) = synchronized(lock) { file.writeText(o.toString()) }

    fun facts(): List<String> = load().getJSONArray("facts").let { a -> (0 until a.length()).map { a.getString(it) } }
    fun summary(): String = load().optString("summary")
    fun history(): List<Turn> = load().getJSONArray("history").let { a ->
        (0 until a.length()).map { val t = a.getJSONObject(it); Turn(t.getString("r"), t.getString("t"), t.getLong("ts")) }
    }
    fun lastTs(): Long = history().lastOrNull()?.ts ?: 0L

    fun addFact(f: String) {
        val o = load(); val a = o.getJSONArray("facts")
        if ((0 until a.length()).none { a.getString(it).equals(f, true) }) a.put(f)
        save(o)
    }
    fun addTurn(role: String, text: String) {
        val o = load()
        o.getJSONArray("history").put(JSONObject().put("r", role).put("t", text).put("ts", System.currentTimeMillis()))
        save(o)
    }

    // History நீண்டா பழசை summary-ஆ சுருக்கிடும்
    fun compactIfNeeded(ctx: Context) {
        val o = load(); val h = o.getJSONArray("history")
        if (h.length() < 60) return
        val old = (0 until 40).joinToString("\n") { h.getJSONObject(it).let { t -> "${t.getString("r")}: ${t.getString("t")}" } }
        val prompt = "Summarize this conversation + previous summary into key points about the user (Tamil/Tanglish ok, under 250 words).\nPrevious summary: ${o.optString("summary")}\n\n$old"
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        val s = Gemini.call(Prefs(ctx), body) ?: return
        val rest = JSONArray(); for (i in 40 until h.length()) rest.put(h.get(i))
        o.put("summary", s).put("history", rest); save(o)
    }
}
