package com.friday.ai

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// Background-ல எப்பவும் கேட்டுக்கிட்டிருக்கும் foreground service.
// Mic எப்பவும் திறந்திருக்கும் (AudioListener). "Friday" சொன்னா எழுந்து, 30 வினாடி உரையாடல் முறையில இருக்கும்.
class FridayService : Service(), TextToSpeech.OnInitListener {
    companion object { var instance: FridayService? = null }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ear: AudioListener? = null
    private var wl: PowerManager.WakeLock? = null
    @Volatile private var speaking = false
    @Volatile private var speechEnd = 0L
    @Volatile private var lastActive = 0L
    @Volatile private var lastErrShown = 0L
    private val wake = Regex("friday|fry day|ஃப்ரைடே|ஃபிரைடே|ப்ரைடே|பிரைடே|ஃப்ரைடை", RegexOption.IGNORE_CASE)

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    fun isMuted(): Boolean = speaking || System.currentTimeMillis() - speechEnd < 700

    override fun onCreate() {
        instance = this
        val ch = NotificationChannel("friday", "Friday", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, "friday").setContentTitle("Friday கேட்டுக்கிட்டிருக்கு")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        // Screen off ஆனாலும் CPU தூங்காம
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friday:ear").apply { acquire() }
        tts = TextToSpeech(this, this)       // Gemini குரல் வேலை செய்யலைனா மட்டும் இது
        DeviceLink.startServer(this)
        ear = AudioListener(this) { wav -> worker.execute { onAudio(wav) } }
        ear?.start()
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (t.setLanguage(Locale("ta", "IN")) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale("en", "IN"))
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { finishSpeaking() }
            override fun onDone(id: String?) { finishSpeaking() }
        })
    }

    private fun finishSpeaking() { speaking = false; speechEnd = System.currentTimeMillis(); lastActive = speechEnd }

    // பதிவான குரல் → text → (wake word / உரையாடல் நடுவுல இருந்தா) AI
    private fun onAudio(wav: ByteArray) {
        val t = Transcriber.transcribe(this, wav)
        if (t == null) {
            if (System.currentTimeMillis() - lastErrShown > 30000) {
                lastErrShown = System.currentTimeMillis()
                Overlay.show(this, null, "Gemini error: " + Gemini.lastError.take(120))
            }
            return
        }
        val text = t.trim()
        if (text.isEmpty() || text.contains("IGNORE", true)) return
        handleText(text)
    }

    private fun handleText(text: String) {
        val now = System.currentTimeMillis()
        if (!wake.containsMatchIn(text) && now - lastActive > 30000) return      // wake word இல்லை → புறக்கணி
        val cmd = text.replace(wake, "").trim()
        lastActive = now
        if (cmd.isEmpty()) { speak("சொல்லுங்க"); return }
        Overlay.show(this, cmd, "...")
        speak(process(cmd))
    }

    // கட்டளை → AI → செயல்கள் → பதில் text. (Voice-ம், மற்ற devices-ம் இதையே பயன்படுத்தும்)
    fun process(cmd: String): String {
        val r = Brain.ask(this, cmd)
        main.post { r.actions.forEach { Actions.run(this, it) } }
        return r.speak
    }

    fun say(t: String) { speak(t) }

    private fun speak(t: String) {
        val text = t.ifBlank { "சரி" }
        speaking = true
        Overlay.show(this, null, text)                // பேசுறதை மேல ஓரத்துல text-ஆவும் காட்டும்
        thread {
            val ok = Voice.speak(this, text)          // Jarvis குரல்
            if (ok) finishSpeaking()
            else main.post { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "f") }   // fallback: phone குரல்
        }
    }

    override fun onDestroy() {
        instance = null
        ear?.stop(); Overlay.remove()
        try { wl?.release() } catch (e: Exception) { }
        tts?.shutdown(); worker.shutdown()
    }
}
