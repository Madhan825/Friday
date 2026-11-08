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

// Jarvis குரல்: Gemini TTS (ஆழமான, அமைதியான ஆண் குரல்). Network பிரச்சனைனா false திரும்பும் → phone TTS பயன்படும்.
object Voice {
    private val MODELS = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")

    @Synchronized
    fun speak(ctx: Context, text: String): Boolean {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) return false
        val prompt = "Speak in a calm, deep, refined, composed male AI-butler voice like JARVIS from Iron Man: unhurried, polite, subtly witty. " +
            "Pronounce Tamil, English or mixed text naturally. Say exactly this: " + text.take(700)
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
                    play(pcm, rate)
                    return true
                }
            } catch (e: Exception) { }
        }
        return false
    }

    private fun play(pcm: ByteArray, rate: Int) {
        val frames = pcm.size / 2
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(AudioTrack.MODE_STATIC).build()
        at.write(pcm, 0, pcm.size)
        at.play()
        val end = System.currentTimeMillis() + frames * 1000L / rate + 1500
        while (at.playbackHeadPosition < frames && System.currentTimeMillis() < end) Thread.sleep(50)
        at.release()
    }
}
