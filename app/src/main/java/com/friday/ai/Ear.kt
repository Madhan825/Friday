package com.friday.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

object Audio {
    fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(pcm.size + 36); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1.toShort()); h.putShort(1.toShort())
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2.toShort()); h.putShort(16.toShort())
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}

// எப்பவும் திறந்திருக்கும் mic. beep இல்ல. சத்த அளவை கத்துக்கிட்டு, நீங்க பேசுறதை மட்டும் துண்டா அனுப்பும்.
// Friday பேசிக்கிட்டிருக்கும்போதும் கேக்கும்; அவ குரலை விட தெளிவா நீங்க பேசினா bargeIn() அழைக்கும் (குறுக்கீடு).
class Ear(private val cb: Callback, private val sens: () -> Int) {
    interface Callback {
        fun isSpeaking(): Boolean
        fun bargeIn()
        fun speechStart(pre: ByteArray)
        fun speechFrame(f: ByteArray)
        fun speechEnd(voicedFrames: Int)
        fun level(l: Float)
    }

    @Volatile private var run = false
    private val rate = 16000
    private val frame = 320                  // 20 ms

    fun start() { run = true; thread { outer() } }
    fun stop() { run = false }

    private fun outer() {
        while (run) {
            try { loop() } catch (e: Exception) { }
            Thread.sleep(1000)               // வேற ஆப் mic எடுத்திருந்தா திரும்ப முயற்சிக்கும்
        }
    }

    private fun bytes(list: List<ShortArray>): ByteArray {
        val b = ByteBuffer.allocate(list.sumOf { it.size } * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in list) for (s in f) b.putShort(s)
        return b.array()
    }

    private fun loop() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) } catch (e: Exception) { }
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) } catch (e: Exception) { }
        rec.startRecording()

        val buf = ShortArray(frame)
        val pre = ArrayList<ShortArray>()
        var inSpeech = false
        var speechRun = 0
        var silence = 0
        var voiced = 0
        var frames = 0
        var floor = 300.0                    // சுற்றுப்புற சத்தம் (தானா கத்துக்கும்)
        var echo = 0.0                       // Friday குரல் mic-ல திரும்ப கேக்கும் அளவு

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
            cb.level((rms / 6000.0).toFloat().coerceIn(0f, 1f))
            val speakingNow = cb.isSpeaking()

            if (!inSpeech) {
                val base = when (sens()) { 1 -> 2200.0; 3 -> 1100.0; else -> 1600.0 }
                val thr = if (speakingNow) maxOf(floor * 3.0, echo * 1.6, base) else maxOf(floor * 2.6, 400.0)
                val loud = rms > thr
                if (loud) speechRun++ else {
                    speechRun = 0
                    if (!speakingNow) floor = (floor * 0.98 + rms * 0.02).coerceIn(100.0, 2000.0)
                }
                if (speakingNow && speechRun < 3) echo = maxOf(echo * 0.995, rms) else if (!speakingNow) echo *= 0.9
                pre.add(buf.copyOf()); if (pre.size > 20) pre.removeAt(0)
                val need = if (speakingNow) 10 else 6
                if (speechRun >= need) {
                    if (speakingNow) cb.bargeIn()
                    inSpeech = true; silence = 0; voiced = speechRun; frames = 0
                    cb.speechStart(bytes(pre)); pre.clear()
                }
            } else {
                cb.speechFrame(bytes(listOf(buf)))
                frames++
                if (rms > maxOf(floor * 2.2, 350.0)) { silence = 0; voiced++ } else silence++
                if (silence >= 30 || frames >= 900) {         // 0.6 வினாடி அமைதி = பேசி முடிச்சாச்சு
                    inSpeech = false; speechRun = 0
                    cb.speechEnd(voiced)
                }
            }
        }
        rec.stop(); rec.release()
    }
}
