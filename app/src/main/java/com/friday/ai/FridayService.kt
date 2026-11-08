package com.friday.ai

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.concurrent.thread

// Background-ல எப்பவும் கேட்டுக்கிட்டிருக்கும் foreground service.
// "Friday" சொன்னா எழுந்து, 30 வினாடி உரையாடல் முறையில இருக்கும்.
class FridayService : Service(), TextToSpeech.OnInitListener {
    companion object { var instance: FridayService? = null }

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var speaking = false
    @Volatile private var lastActive = 0L
    private val wake = Regex("friday|fry day|ஃப்ரைடே|ஃபிரைடே|ப்ரைடே|பிரைடே|ஃப்ரைடை", RegexOption.IGNORE_CASE)

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onCreate() {
        instance = this
        val ch = NotificationChannel("friday", "Friday", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, "friday").setContentTitle("Friday கேட்டுக்கிட்டிருக்கு")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        tts = TextToSpeech(this, this)
        DeviceLink.startServer(this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        // தமிழ் குரல் இல்லைனா English-India
        if (t.setLanguage(Locale("ta", "IN")) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale("en", "IN"))
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { speaking = false; listen() }
            override fun onDone(id: String?) { speaking = false; lastActive = System.currentTimeMillis(); listen() }
        })
        listen()
    }

    private fun listen() {
        main.post {
        if (speaking) return@post
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        try { sr?.startListening(i) } catch (_: Exception) { main.postDelayed({ listen() }, 1500) }
        }
    }

    private val listener: RecognitionListener = object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) listen() else handle(text)
        }
        override fun onError(e: Int) { main.postDelayed({ listen() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 500) }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun handle(text: String) {
        val now = System.currentTimeMillis()
        if (!wake.containsMatchIn(text) && now - lastActive > 30000) { listen(); return }  // wake word இல்லை → புறக்கணி
        val cmd = text.replace(wake, "").trim()
        lastActive = now
        if (cmd.isEmpty()) { speak("சொல்லுங்க"); return }
        thread { speak(process(cmd)) }
    }

    // கட்டளை → AI → செயல்கள் → பதில் text. (Voice-ம், மற்ற devices-ம் இதையே பயன்படுத்தும்)
    fun process(cmd: String): String {
        val r = Brain.ask(this, cmd)
        main.post { r.actions.forEach { Actions.run(this, it) } }
        return r.speak
    }

    fun say(t: String) { speak(t) }

    private fun speak(t: String) {
        main.post {
            speaking = true
            sr?.cancel()
            tts?.speak(t.ifBlank { "சரி" }, TextToSpeech.QUEUE_FLUSH, null, "f")
        }
    }

    override fun onDestroy() { instance = null; sr?.destroy(); tts?.shutdown() }
}
