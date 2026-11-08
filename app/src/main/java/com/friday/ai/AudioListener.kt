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

// எப்பவும் திறந்திருக்கும் mic (beep இல்ல, on/off இல்ல). சத்த அளவை தானா கத்துக்கிட்டு,
// நீங்க பேசி முடிச்சதும் அந்த பகுதியை மட்டும் onUtterance-க்கு அனுப்பும்.
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

    private fun loop() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) } catch (e: Exception) { }
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) } catch (e: Exception) { }
        rec.startRecording()

        val buf = ShortArray(frame)
        val pre = ArrayList<ShortArray>()          // பேச ஆரம்பிக்கும் முன் 300 ms சேர்த்து வைக்க
        val utter = ByteArrayOutputStream()
        var inSpeech = false
        var speechRun = 0
        var silence = 0
        var voiced = 0
        var frames = 0
        var floor = 300.0                           // சுற்றுப்புற சத்த அளவு (தானா கத்துக்கும்)

        while (run) {
            var n = 0
            while (n < frame && run) {
                val r = rec.read(buf, n, frame - n)
                if (r < 0) { rec.stop(); rec.release(); return }
                n += r
            }
            if (!run) break
            if (svc.isMuted()) { inSpeech = false; speechRun = 0; pre.clear(); utter.reset(); continue }   // Friday பேசும்போது தன் குரலை கேக்காம

            var sum = 0.0
            for (i in 0 until frame) sum += buf[i].toDouble() * buf[i]
            val rms = sqrt(sum / frame)
            val speech = rms > maxOf(floor * 2.6, 400.0)

            if (!inSpeech) {
                if (speech) speechRun++ else { speechRun = 0; floor = (floor * 0.98 + rms * 0.02).coerceIn(100.0, 2000.0) }
                pre.add(buf.copyOf()); if (pre.size > 15) pre.removeAt(0)
                if (speechRun >= 4) {
                    inSpeech = true; utter.reset(); silence = 0; voiced = speechRun; frames = 0
                    for (f in pre) for (s in f) { utter.write(s.toInt() and 0xFF); utter.write((s.toInt() shr 8) and 0xFF) }
                    pre.clear()
                }
            } else {
                for (i in 0 until frame) { utter.write(buf[i].toInt() and 0xFF); utter.write((buf[i].toInt() shr 8) and 0xFF) }
                frames++
                if (speech) { silence = 0; voiced++ } else silence++
                if (silence >= 50 || frames >= 750) {            // 1 வினாடி அமைதி அல்லது 15 வினாடி
                    inSpeech = false; speechRun = 0
                    if (voiced >= 20) onUtterance(wav(utter.toByteArray()))   // 0.4 வினாடிக்கு குறைவான சத்தம் புறக்கணிப்பு
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
