package com.friday.ai

import android.content.Context
import java.io.InputStream
import java.net.*
import kotlin.concurrent.thread

// WiFi வழியா மற்ற devices-உடன் இணைப்பு:
//  * இந்த phone port 8765-ல கட்டளை கேக்கும் (மற்ற Friday phone/TV/PC இதுக்கு அனுப்பலாம்)
//  * send(): வேற device-ல இருக்க Friday-க்கு கட்டளை அனுப்பும்
//  * wake(): PC-ஐ Wake-on-LAN மூலம் எழுப்பும்
object DeviceLink {
    private var server: ServerSocket? = null

    fun startServer(svc: FridayService) {
        if (server != null) return
        thread {
            try {
                val s = ServerSocket(8765); server = s
                while (true) { val c = s.accept(); thread { handle(svc, c) } }
            } catch (e: Exception) { server = null }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = java.io.ByteArrayOutputStream()
        while (true) { val b = i.read(); if (b < 0) return if (sb.size() == 0) null else sb.toString("UTF-8"); if (b == 10) break; if (b != 13) sb.write(b) }
        return sb.toString("UTF-8")
    }

    private fun handle(svc: FridayService, c: Socket) = c.use {
        val i = c.getInputStream()
        readLine(i) ?: return
        var len = 0; var tok = ""
        while (true) {
            val l = readLine(i) ?: break
            if (l.isEmpty()) break
            val low = l.lowercase()
            if (low.startsWith("content-length:")) len = l.substring(15).trim().toInt()
            if (low.startsWith("x-token:")) tok = l.substring(8).trim()
        }
        val buf = ByteArray(len); var r = 0
        while (r < len) { val n = i.read(buf, r, len - r); if (n < 0) break; r += n }
        val reply = if (tok != Prefs(svc).token) "unauthorized" else svc.process(String(buf, 0, r, Charsets.UTF_8))
        val b = reply.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray())
            write(b); flush()
        }
    }

    // token: அந்த device-ல இருக்க Friday-ஓட token (தற்போது இந்த phone-ஓடதே பயன்படுத்துது; தனியா வேணும்னா "name=ip,mac,token" மாத்திக்கலாம்)
    fun send(ctx: Context, name: String, cmd: String): String {
        val ip = Prefs(ctx).devices()[name.lowercase()]?.first ?: return "device தெரியலை"
        return try {
            val c = URL("http://$ip:8765/cmd").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 60000
            c.setRequestProperty("X-Token", Prefs(ctx).token)
            c.outputStream.use { it.write(cmd.toByteArray()) }
            c.inputStream.bufferedReader().readText()
        } catch (e: Exception) { "இணைக்க முடியலை" }
    }

    fun wake(ctx: Context, name: String) {
        val mac = Prefs(ctx).devices()[name.lowercase()]?.second ?: return
        try {
            val m = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
            val pkt = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
            DatagramSocket().use { it.broadcast = true; it.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), 9)) }
        } catch (_: Exception) {}
    }
}
