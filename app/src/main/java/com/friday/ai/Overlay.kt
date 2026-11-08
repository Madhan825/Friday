package com.friday.ai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

// எந்த ஆப் மேலயும், திரையோட மேல் ஓரத்துல, Friday பேசுறதை transparent text-ஆ காட்டும்.
// தொட்டா மறையும். குரல்ல "text hide பண்ணு" சொன்னாலும் மறையும்.
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var box: LinearLayout? = null
    private var userTv: TextView? = null
    private var replyTv: TextView? = null
    private val hideRun = Runnable { box?.visibility = View.GONE }

    fun show(ctx: Context, user: String?, reply: String?) {
        main.post {
            try {
                if (!Prefs(ctx).overlayOn || !Settings.canDrawOverlays(ctx)) return@post
                ensure(ctx)
                if (user != null) { userTv?.text = user; userTv?.visibility = View.VISIBLE }
                if (reply != null) replyTv?.text = reply
                box?.visibility = View.VISIBLE
                main.removeCallbacks(hideRun)
                main.postDelayed(hideRun, 6000L + (reply?.length ?: 0) * 70L)
            } catch (e: Exception) { }
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs(ctx).overlayOn = on
        if (!on) main.post { remove() }
    }

    fun remove() {
        try { box?.let { (it.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (e: Exception) { }
        box = null; userTv = null; replyTv = null
    }

    private fun ensure(ctx: Context) {
        if (box != null) return
        val d = ctx.resources.displayMetrics.density
        val bg = GradientDrawable().apply { setColor(0x55000000); cornerRadius = 22 * d }   // பாதி transparent
        val u = TextView(ctx).apply { textSize = 12f; setTextColor(0xCCB0E0FF.toInt()); visibility = View.GONE }
        val r = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE); setShadowLayer(4f, 0f, 1f, Color.BLACK) }
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = bg
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            addView(u); addView(r)
            setOnClickListener { visibility = View.GONE }     // தொட்டா மறையும்
        }
        val sb = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusH = if (sb > 0) ctx.resources.getDimensionPixelSize(sb) else (24 * d).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = statusH + (4 * d).toInt(); horizontalMargin = 0.03f }
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(b, lp)
        box = b; userTv = u; replyTv = r
    }
}
