package com.friday.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

// எப்பவும் திறந்திருக்கும் mic. Friday பேசிட்டிருக்கும்போதும் கேக்கும் (echo cancel + அதிக அளவு இருந்தா மட்டும்
// குறுக்கீடா எடுக்கும்). நீங்க பேசி முடிச்சதும் அந்தப் பகுதியை மட்டும் onUtterance-க்கு அனுப்பும்.
class AudioListener(private val svc: FridayService, private val onUtterance: (ByteArray) -> Unit) {
    @Volatile private var run = false
    private val rate = 16000
    private val frame = 320                 // 20 ms

    fun start() { run = true; thread { outer() } }
    fun stop() { run = false }

    private fun outer() {
        while (run) {
            try { loop() } catch (e: Exception) { }
            Thread.sleep(1000)              // வேற ஆப் mic எடுத்திருந்தா திரும்ப முயற்சிக்கும்
        }
    }

    private fun open(source: Int): AudioRecord {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate * 2))
    }

    private fun loop() {
        // VOICE_COMMUNICATION: phone-ஓட echo cancel பயன்படும் (Friday குரல் mic-ல திரும்ப கேக்காம)
        var rec = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) } catch (e: Exception) { }
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) } catch (e: Exception) { }
        rec.startRecording()

        val buf = ShortArray(frame)
        val pre = ArrayList<ShortArray>()
        val utter = ByteArrayOutputStream()
        var inSpeech = false
        var speechRun = 0
        var silence = 0
        var voiced = 0
        var frames = 0
        var floor = 300.0

        while (run) {
            var n = 0
            while (n < frame && run) {
                val r = rec.read(buf, n, frame - n)
                if (r < 0) { rec.stop(); rec.release(); return }
                n += r
            }
            if (!run) break

            var sum = 0.0
            for (i in 0 until frame) sum += buf[i].toDouble() * buf[i]
            val rms = sqrt(sum / frame)
            Status.level = (rms / 4000.0).coerceIn(0.0, 1.0).toFloat()

            val speakingNow = svc.isSpeaking()
            if (!speakingNow && svc.inTail() && !inSpeech) { pre.clear(); speechRun = 0; continue }   // பேசி முடிஞ்ச 0.35 வினாடி புறக்கணிப்பு

            // Friday பேசும்போது: அதிக அளவு + நீண்ட நேரம் இருந்தா மட்டுமே குறுக்கீடு
            val thr = if (speakingNow) maxOf(floor * 3.5, 1500.0) else maxOf(floor * 2.6, 400.0)
            val need = if (speakingNow) 12 else 4
            val speech = rms > thr

            if (!inSpeech) {
                if (speech) speechRun++ else {
                    speechRun = 0
                    if (!speakingNow) floor = (floor * 0.98 + rms * 0.02).coerceIn(100.0, 2000.0)
                }
                pre.add(buf.copyOf()); if (pre.size > 15) pre.removeAt(0)
                if (speechRun >= need) {
                    if (speakingNow) svc.bargeIn()
                    inSpeech = true; utter.reset(); silence = 0; voiced = speechRun; frames = 0
                    for (f in pre) for (s in f) { utter.write(s.toInt() and 0xFF); utter.write((s.toInt() shr 8) and 0xFF) }
                    pre.clear()
                    Status.state = "hearing"
                }
            } else {
                for (i in 0 until frame) { utter.write(buf[i].toInt() and 0xFF); utter.write((buf[i].toInt() shr 8) and 0xFF) }
                frames++
                if (speech || (svc.isSpeaking() && rms > floor * 2.0)) { silence = 0; voiced++ } else silence++
                if (silence >= 35 || frames >= 750) {            // 0.7 வினாடி அமைதி (அல்லது 15 வினாடி)
                    inSpeech = false; speechRun = 0
                    if (voiced >= 15) onUtterance(wav(utter.toByteArray()))
                    else if (Status.state == "hearing") Status.state = "listening"
                    utter.reset()
                }
            }
        }
        rec.stop(); rec.release()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(pcm.size + 36); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1.toShort()); h.putShort(1.toShort())
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2.toShort()); h.putShort(16.toShort())
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
