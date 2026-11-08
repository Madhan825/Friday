package com.friday.ai

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

// பதிவான குரலை Gemini-கிட்ட கொடுத்து text ஆக்கும். பின்னணி சத்தம் / மத்தவங்க பேச்சுன்னா "IGNORE".
object Transcriber {
    private const val INSTR = "This audio was recorded by an always-on phone microphone. The owner speaks Tamil, English or Tanglish (Tamil in English letters). " +
        "Transcribe ONLY the main speaker: the closest, clearest voice, the phone owner, talking to an AI assistant named Friday. " +
        "Ignore other people's voices, TV, music, and background noise. " +
        "If there is no clear speech from the main speaker meant for an assistant, or it is only background talk, output exactly: IGNORE. " +
        "Otherwise output only the transcript (Tamil words in Tamil script, English in English), nothing else."

    fun transcribe(ctx: Context, wav: ByteArray): String? {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) return null
        val audio = JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
        val parts = JSONArray().put(JSONObject().put("text", INSTR)).put(audio)
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        return Gemini.call(p, body)
    }
}
