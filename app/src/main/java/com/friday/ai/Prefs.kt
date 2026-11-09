package com.friday.ai

import android.content.Context
import java.util.UUID

// Settings சேமிக்கிற இடம்: Google API key, model, devices, token
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("friday", Context.MODE_PRIVATE)
    var geminiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v.trim()).apply()
    // Model பேரு மாறினா இங்கே மாத்திக்கோங்க
    var model: String
        get() = sp.getString("model", "") ?: ""
        set(v) = sp.edit().putString("model", v.trim()).apply()
    var devicesRaw: String
        get() = sp.getString("devices", "") ?: ""
        set(v) = sp.edit().putString("devices", v).apply()
    // மற்ற device-கள் இந்த token வச்சுதான் இந்த phone-ஐ control பண்ணலாம்
    val token: String
        get() = sp.getString("token", null) ?: UUID.randomUUID().toString().take(8).also { sp.edit().putString("token", it).apply() }

    // "tv=192.168.1.5" அல்லது "pc=192.168.1.8,AA:BB:CC:DD:EE:FF" (ஒவ்வொரு வரி)
    fun devices(): Map<String, Pair<String, String?>> =
        devicesRaw.lines().mapNotNull { l ->
            val p = l.split("=", limit = 2)
            if (p.size < 2) return@mapNotNull null
            val v = p[1].split(",")
            p[0].trim().lowercase() to (v[0].trim() to v.getOrNull(1)?.trim())
        }.toMap()

    // வேலை செய்த model-ஐ நினைவில வச்சுக்கும்
    var workingModel: String
        get() = sp.getString("wm", "") ?: ""
        set(v) = sp.edit().putString("wm", v).apply()
    var ttsModel: String
        get() = sp.getString("ttsm", "") ?: ""
        set(v) = sp.edit().putString("ttsm", v).apply()
    // Jarvis குரல்: Charon (ஆழமான, அமைதியான). மாற்ற: Orus, Algenib, Iapetus, Alnilam
    var voice: String
        get() = sp.getString("voice", "Charon") ?: "Charon"
        set(v) = sp.edit().putString("voice", v).apply()
    var lastError: String
        get() = sp.getString("lasterr", "") ?: ""
        set(v) = sp.edit().putString("lasterr", v).apply()

    var overlayOn: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var useLive: Boolean
        get() = sp.getBoolean("uselive", true)
        set(v) = sp.edit().putBoolean("uselive", v).apply()
    // குறுக்கிடும் உணர்திறன்: 1 குறைவு, 2 நடுத்தரம், 3 அதிகம்
    var bargeIn: Int
        get() = sp.getInt("barge", 2)
        set(v) = sp.edit().putInt("barge", v).apply()
    var liveModel: String
        get() = sp.getString("livem", "") ?: ""
        set(v) = sp.edit().putString("livem", v).apply()

    // பேச்சு மொழி: tanglish (default) / english / tamil
    var lang: String
        get() = sp.getString("lang", "tanglish") ?: "tanglish"
        set(v) = sp.edit().putString("lang", v).apply()
}
