package com.friday.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*

// எளிய setup திரை: API key, devices, permissions, Start/Stop
class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Prefs(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 24, 0, 4) }.also { col.addView(it) }
        fun edit(v: String, h: String, lines: Int = 1) = EditText(this).apply { setText(v); hint = h; setLines(lines) }.also { col.addView(it) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { col.addView(it) }

        label("Friday AI"); label("Google (Gemini) API key")
        val key = edit(p.geminiKey, "AIza...")
        label("Model (மாத்தணும்னா)"); val model = edit(p.model, "gemini-2.5-flash")
        label("மற்ற devices (ஒரு வரிக்கு ஒன்னு: name=ip அல்லது name=ip,MAC)")
        val dev = edit(p.devicesRaw, "tv=192.168.1.5\npc=192.168.1.8,AA:BB:CC:DD:EE:FF", 4)
        label("இந்த phone-ஓட device token: ${p.token}")

        btn("Save") { p.geminiKey = key.text.toString(); p.model = model.text.toString(); p.devicesRaw = dev.text.toString(); toast("Saved") }
        btn("1) Permissions கொடு") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        btn("2) Accessibility ON பண்ணு (tap/type control)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("3) Battery restriction நீக்கு") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        btn("START Friday") { p.geminiKey = key.text.toString(); p.model = model.text.toString(); p.devicesRaw = dev.text.toString()
            startForegroundService(Intent(this, FridayService::class.java)); toast("Friday ஆரம்பம்! 'Friday' ன்னு கூப்பிடுங்க") }
        btn("STOP") { stopService(Intent(this, FridayService::class.java)) }

        setContentView(ScrollView(this).apply { addView(col, ViewGroup.LayoutParams(-1, -2)) })
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
