package com.friday.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.sqrt

// Friday-ஓட குரல் வெளியீடு. stopNow() = நீங்க குறுக்கிட்டா உடனே நிறுத்தும்.
class Player {
    private class Item(val pcm: ByteArray, val rate: Int)
    private val q = LinkedBlockingQueue<Item>()
    private var track: AudioTrack? = null
    private var trackRate = 0
    @Volatile private var written = 0L
    @Volatile var level = 0f

    init { thread { loop() } }

    fun play(pcm: ByteArray, rate: Int = 24000) { q.put(Item(pcm, rate)) }

    private fun loop() {
        while (true) {
            val it = q.take()
            try {
                val t = ensure(it.rate)
                var off = 0
                while (off < it.pcm.size) {
                    val end = minOf(off + 4800, it.pcm.size)       // 100 ms துண்டுகள்
                    t.write(it.pcm, off, end - off)
                    var s = 0.0
                    var i = off
                    while (i + 1 < end) { val v = ((it.pcm[i + 1].toInt() shl 8) or (it.pcm[i].toInt() and 0xFF)).toShort().toInt(); s += v.toDouble() * v; i += 2 }
                    level = (sqrt(s / maxOf(1, (end - off) / 2)) / 7000.0).toFloat().coerceIn(0f, 1f)
                    written += (end - off) / 2
                    off = end
                }
            } catch (e: Exception) { }
        }
    }

    @Synchronized
    private fun ensure(rate: Int): AudioTrack {
        val cur = track
        if (cur != null && trackRate == rate) return cur
        try { cur?.release() } catch (e: Exception) { }
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play(); track = t; trackRate = rate; written = 0
        return t
    }

    fun isPlaying(): Boolean {
        if (q.isNotEmpty()) return true
        val t = track ?: return false
        return try { written > t.playbackHeadPosition + 1200 } catch (e: Exception) { false }
    }

    // உடனே நிறுத்து
    @Synchronized
    fun stopNow() {
        q.clear()
        try { track?.pause(); track?.flush(); track?.play() } catch (e: Exception) { }
        written = 0; level = 0f
    }
}
