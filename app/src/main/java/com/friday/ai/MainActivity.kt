package com.friday.ai

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Friday-ஓட முகப்புத் திரை: உயிரோட்டமுள்ள orb, setup checklist, AI & குரல் அமைப்புகள்
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var p: Prefs
    private val refreshers = mutableListOf<() -> Unit>()
    private var permTotal = 0

    private lateinit var stateTv: TextView
    private lateinit var heardTv: TextView
    private lateinit var replyTv: TextView
    private lateinit var startBtn: TextView
    private lateinit var progTv: TextView
    private lateinit var progFill: View
    private lateinit var errTv: TextView
    private lateinit var testTv: TextView
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var devEt: EditText
    private val voiceChips = mutableListOf<Pair<String, TextView>>()

    private val BG = 0xFF05070D.toInt()
    private val CARD = 0xFF0F1626.toInt()
    private val STROKE = 0x22FFFFFF
    private val CYAN = 0xFF22D3EE.toInt()
    private val VIOLET = 0xFF8B5CF6.toInt()
    private val GREEN = 0xFF34D399.toInt()
    private val SUB = 0xFF94A3B8.toInt()
    private val MATCH = -1
    private val WRAP = -2

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun bg(color: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun ripple(base: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)
    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = bg(CARD, 22, STROKE)
        setPadding(dp(16), dp(14), dp(16), dp(14)); layoutTransition = LayoutTransition()
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- அனுமதிகளின் நிலை ----------
    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private fun accOk() = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun batteryOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun permRow(parent: LinearLayout, icon: String, title: String, desc: String, ok: () -> Boolean, act: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val ic = tv(icon, 20f, Color.WHITE).apply { gravity = Gravity.CENTER; background = bg(0x2238BDF8, 14) }
        row.addView(ic, lp(dp(44), dp(44), 0, 0, 12, 0))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, Color.WHITE, true))
        col.addView(tv(desc, 12f, SUB))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val chip = tv("", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        row.addView(chip, lp(WRAP, WRAP))
        val go = View.OnClickListener { act() }
        row.setOnClickListener(go); chip.setOnClickListener(go)
        parent.addView(row, lp(MATCH, WRAP))
        permTotal++
        refreshers.add {
            val g = ok()
            chip.text = if (g) "✓ ON" else "அனுமதி"
            chip.setTextColor(if (g) GREEN else BG)
            chip.background = if (g) bg(0x2234D399, 16) else bg(CYAN, 16)
        }
        refreshers.add { }
        permChecks.add(ok)
    }
    private val permChecks = mutableListOf<() -> Boolean>()

    private fun refresh() {
        refreshers.forEach { it() }
        val done = permChecks.count { it() }
        progTv.text = done.toString() + " / " + permChecks.size
        progFill.animate().scaleX(if (permChecks.isEmpty()) 0f else done.toFloat() / permChecks.size).setDuration(450).start()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = Prefs(this)
        if (p.model == "gemini-2.5-flash") p.model = ""      // பழைய default → இப்போ தானா தேர்வு
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(40))
            layoutTransition = LayoutTransition()
        }

        // ----- தலைப்பு + orb -----
        root.addView(tv("F R I D A Y", 22f, CYAN, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }, lp(MATCH, WRAP))
        root.addView(tv("உங்கள் personal AI", 12f, SUB).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, 0, 2, 0, 0))
        root.addView(OrbView(this), lp(MATCH, dp(280), 0, 6, 0, 0))
        stateTv = tv("", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        root.addView(stateTv, lp(MATCH, WRAP))
        heardTv = tv("", 13f, SUB).apply { gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
        root.addView(heardTv, lp(MATCH, WRAP, 8, 6, 8, 0))
        replyTv = tv("", 14f, 0xFF67E8F9.toInt()).apply { gravity = Gravity.CENTER; maxLines = 4 }
        root.addView(replyTv, lp(MATCH, WRAP, 8, 4, 8, 0))

        startBtn = tv("START FRIDAY", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.1f
            background = ripple(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, VIOLET)).apply { cornerRadius = dp(30).toFloat() })
            setOnClickListener { toggle() }
        }
        root.addView(startBtn, lp(MATCH, dp(56), 0, 18, 0, 0))

        // ----- Setup checklist -----
        val setup = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("Setup", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        progTv = tv("", 13f, CYAN, true)
        head.addView(progTv)
        setup.addView(head, lp(MATCH, WRAP))
        val track = FrameLayout(this).apply { background = bg(0x22FFFFFF, 4) }
        progFill = View(this).apply { background = bg(CYAN, 4); pivotX = 0f; scaleX = 0f }
        track.addView(progFill, FrameLayout.LayoutParams(MATCH, MATCH))
        setup.addView(track, lp(MATCH, dp(4), 0, 8, 0, 6))

        permRow(setup, "🎙", "Mic · Phone · Contacts", "குரல் கேக்க, call பண்ண", { micOk() }) {
            val l = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(l.toTypedArray(), 1)
        }
        permRow(setup, "✋", "Accessibility", "மற்ற ஆப்ல tap / type / scroll", { accOk() }) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        permRow(setup, "🪟", "Screen text", "எந்த ஆப் மேலயும் பதிலை text-ஆ காட்ட", { overlayOk() }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        permRow(setup, "🔋", "Battery", "Background-ல நிக்காம இருக்க", { batteryOk() }) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        root.addView(setup, lp(MATCH, WRAP, 0, 18, 0, 0))

        // ----- AI & குரல் -----
        val ai = card()
        ai.addView(tv("AI", 17f, Color.WHITE, true))
        keyEt = EditText(this).apply {
            setText(p.geminiKey); hint = "Google API key (AIza…)"; setHintTextColor(0x66FFFFFF); setTextColor(Color.WHITE); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = bg(0x14FFFFFF, 14); setPadding(dp(14), dp(12), dp(14), dp(12)); setSingleLine()
        }
        ai.addView(keyEt, lp(MATCH, WRAP, 0, 10, 0, 0))
        val testBtn = tv("இணைப்பை சோதி", 14f, CYAN, true).apply {
            gravity = Gravity.CENTER; background = ripple(bg(0x1A22D3EE, 14, 0x4422D3EE)); setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener { testGemini() }
        }
        ai.addView(testBtn, lp(MATCH, WRAP, 0, 10, 0, 0))
        testTv = tv("", 12f, SUB)
        ai.addView(testTv, lp(MATCH, WRAP, 2, 6, 2, 0))
        errTv = tv("", 11f, 0xFFF87171.toInt())
        ai.addView(errTv, lp(MATCH, WRAP, 2, 4, 2, 0))

        ai.addView(tv("குரல்", 14f, Color.WHITE, true), lp(WRAP, WRAP, 0, 14, 0, 6))
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in listOf("Charon", "Orus", "Algenib", "Iapetus", "Alnilam")) {
            val ch = tv(v, 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)) }
            ch.setOnClickListener { p.voice = v; styleChips() }
            voiceChips.add(Pair(v, ch))
            chipRow.addView(ch, lp(WRAP, WRAP, 0, 0, 8, 0))
        }
        ai.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        styleChips()

        val sw = Switch(this).apply {
            text = "Screen text காட்டு"; setTextColor(Color.WHITE); textSize = 14f; isChecked = p.overlayOn
            setOnCheckedChangeListener { _, on -> p.overlayOn = on; Overlay.setEnabled(this@MainActivity, on) }
        }
        ai.addView(sw, lp(MATCH, WRAP, 0, 14, 0, 0))
        root.addView(ai, lp(MATCH, WRAP, 0, 14, 0, 0))

        // ----- Advanced (மடக்கலாம்) -----
        val adv = card()
        val advBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val advHead = tv("Advanced  ▾", 15f, SUB, true).apply {
            setOnClickListener { advBody.visibility = if (advBody.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        adv.addView(advHead)
        fun field(v: String, h: String, lines: Int) = EditText(this).apply {
            setText(v); hint = h; setHintTextColor(0x66FFFFFF); setTextColor(Color.WHITE); textSize = 13f
            background = bg(0x14FFFFFF, 14); setPadding(dp(14), dp(10), dp(14), dp(10)); minLines = lines
        }
        advBody.addView(tv("Model (காலியா விடுங்க, தானா தேர்வாகும்)", 12f, SUB), lp(WRAP, WRAP, 0, 10, 0, 4))
        modelEt = field(p.model, "auto", 1); advBody.addView(modelEt)
        advBody.addView(tv("மற்ற devices (name=ip அல்லது name=ip,MAC)", 12f, SUB), lp(WRAP, WRAP, 0, 10, 0, 4))
        devEt = field(p.devicesRaw, "tv=192.168.1.5", 3); advBody.addView(devEt)
        advBody.addView(tv("Device token: " + p.token, 12f, SUB), lp(WRAP, WRAP, 0, 10, 0, 0))
        adv.addView(advBody)
        root.addView(adv, lp(MATCH, WRAP, 0, 14, 0, 0))

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); isVerticalScrollBarEnabled = false; addView(root) })
    }

    private fun styleChips() {
        for ((name, ch) in voiceChips) {
            val sel = name == p.voice
            ch.setTextColor(if (sel) BG else Color.WHITE)
            ch.background = if (sel) bg(CYAN, 16) else bg(0x18FFFFFF, 16, STROKE)
        }
    }

    private fun saveAll() {
        p.geminiKey = keyEt.text.toString()
        p.model = modelEt.text.toString()
        p.devicesRaw = devEt.text.toString()
    }

    private fun testGemini() {
        saveAll()
        testTv.setTextColor(SUB); testTv.text = "சோதிக்கிறேன்…"
        thread {
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Reply with the single word: OK")))))
            val r = Gemini.call(p, body)
            ui.post {
                if (r != null) { testTv.setTextColor(GREEN); testTv.text = "✓ வேலை செய்யுது  ·  " + p.workingModel }
                else { testTv.setTextColor(0xFFF87171.toInt()); testTv.text = "✗ " + Gemini.lastError.take(200) }
            }
        }
    }

    private fun toggle() {
        if (FridayService.instance != null) { stopService(Intent(this, FridayService::class.java)); return }
        saveAll()
        if (p.geminiKey.isBlank()) { toast("முதல்ல Google API key போடுங்க"); keyEt.requestFocus(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { toast("Mic அனுமதி கொடுங்க"); return }
        startForegroundService(Intent(this, FridayService::class.java))
    }

    private val tick = object : Runnable {
        override fun run() {
            val on = FridayService.instance != null
            stateTv.text = when (if (on) Status.state else "off") {
                "listening" -> "கேட்டுக்கிட்டிருக்கேன்"
                "hearing" -> "கேக்கிறேன்…"
                "thinking" -> "யோசிக்கிறேன்…"
                "speaking" -> "பேசுறேன்"
                else -> "ஓய்வில்"
            }
            heardTv.text = if (on && Status.heard.isNotBlank()) "“" + Status.heard + "”" else ""
            replyTv.text = if (on) Status.reply else ""
            startBtn.text = if (on) "STOP" else "START FRIDAY"
            errTv.text = if (p.lastError.isBlank()) "" else "கடைசி error: " + p.lastError
            ui.postDelayed(this, 150)
        }
    }

    override fun onResume() { super.onResume(); refresh(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick); saveAll() }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res); refresh()
    }
}
