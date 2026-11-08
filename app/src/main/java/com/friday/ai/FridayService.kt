package com.friday.ai

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Background-ல எப்பவும் கேட்டுக்கிட்டிருக்கும் foreground service.
// Mic திறந்தே இருக்கும்; Friday பேசிட்டிருக்கும்போது குறுக்கிட்டாலும் நிறுத்திட்டு பதில் சொல்லும்.
class FridayService : Service(), TextToSpeech.OnInitListener {
    companion object { var instance: FridayService? = null }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newCachedThreadPool()
    private var tts: TextToSpeech? = null
    private var ear: AudioListener? = null
    private var wl: PowerManager.WakeLock? = null
    private val gen = AtomicInteger(0)
    @Volatile private var lastActive = 0L
    @Volatile private var lastReply = ""
    @Volatile private var lastErrShown = 0L
    @Volatile private var fbDone: (() -> Unit)? = null

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    fun isSpeaking(): Boolean = Voice.isSpeaking()
    fun inTail(): Boolean = System.currentTimeMillis() - Voice.lastEnd < 350

    // பேசிட்டிருக்கும்போது user குறுக்கிட்டார் → உடனே நிறுத்து
    fun bargeIn() {
        gen.incrementAndGet()
        Voice.interrupt()
        main.post { tts?.stop() }
        Status.state = "hearing"
    }

    override fun onCreate() {
        instance = this
        val ch = NotificationChannel("friday", "Friday", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, "friday").setContentTitle("Friday கேட்டுக்கிட்டிருக்கு")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friday:ear").apply { acquire() }
        tts = TextToSpeech(this, this)       // Gemini குரல் வேலை செய்யலைனா மட்டும் இது
        DeviceLink.startServer(this)

        Voice.onIdle = { lastActive = System.currentTimeMillis(); Status.state = "listening" }
        Voice.fallback = { text, done ->
            fbDone = done
            main.post {
                val t = tts
                if (t == null) { fbDone?.invoke(); fbDone = null } else t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "f")
            }
        }
        Status.state = "listening"
        ear = AudioListener(this) { wav -> exec.execute { onAudio(wav) } }
        ear?.start()
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (t.setLanguage(Locale("ta", "IN")) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale("en", "IN"))
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { fbDone?.invoke(); fbDone = null }
            override fun onDone(id: String?) { fbDone?.invoke(); fbDone = null }
        })
    }

    // பதிவான குரல் → Gemini (கேட்டது + பதில் ஒரே call) → வாக்கியம் வாக்கியமா பேசும்
    private fun onAudio(wav: ByteArray) {
        if (Status.state == "listening" || Status.state == "hearing") Status.state = "thinking"
        val active = System.currentTimeMillis() - lastActive < 45000
        var myGen = -1
        val reply = StringBuilder()
        val ctx = this

        fun claim() {
            if (myGen < 0) {
                myGen = gen.incrementAndGet()
                lastActive = System.currentTimeMillis()
                Voice.interrupt()
                main.post { tts?.stop() }
            }
        }

        val sink = object : Brain.Sink {
            override fun cancelled(): Boolean = myGen >= 0 && gen.get() != myGen
            override fun onHeard(t: String) { claim(); Status.heard = t; Overlay.show(ctx, t, "…") }
            override fun onSentence(s: String) {
                claim()
                if (cancelled()) return
                reply.append(s).append(' ')
                Status.reply = reply.toString().trim()
                Overlay.show(ctx, null, Status.reply)
                Voice.enqueue(ctx, s)
            }
            override fun onAction(a: JSONObject) { claim(); if (!cancelled()) main.post { Actions.run(ctx, a) } }
        }

        val ok = Brain.askAudio(this, wav, active, lastReply, sink)
        if (!ok && System.currentTimeMillis() - lastErrShown > 30000) {
            lastErrShown = System.currentTimeMillis()
            Overlay.show(this, null, "Gemini error: " + Gemini.lastError.take(120))
        }
        if (reply.isNotBlank()) lastReply = reply.toString().trim()
        if (!Voice.isSpeaking() && Status.state != "hearing") Status.state = "listening"
    }

    // மற்ற devices / agent பயன்படுத்தும் text வழி
    fun process(cmd: String): String {
        val r = Brain.ask(this, cmd)
        main.post { r.actions.forEach { Actions.run(this, it) } }
        return r.speak
    }

    fun say(t: String) {
        val text = t.ifBlank { "சரி" }
        Overlay.show(this, null, text)
        Voice.enqueue(this, text)
    }

    override fun onDestroy() {
        instance = null
        Status.state = "off"
        ear?.stop(); Overlay.remove(); Voice.interrupt()
        try { wl?.release() } catch (e: Exception) { }
        tts?.shutdown(); exec.shutdown()
    }
}
