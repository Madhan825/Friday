package com.friday.ai

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

// உயிரோட்டமுள்ள "orb": கேக்கும்போது நீலம், உங்க குரலுக்கு பச்சை, யோசிக்கும்போது ஊதா சுழற்சி, பேசும்போது cyan அலை.
class OrbView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ev = ArgbEvaluator()
    private var t = 0f
    private var amp = 0f
    private var cur = 0xFF475569.toInt()

    private fun al(col: Int, a: Int): Int = (col and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val st = if (FridayService.instance != null) Status.state else "off"
        val target = when (st) {
            "hearing" -> 0.35f + Status.level * 0.9f
            "speaking" -> 0.35f + 0.25f * Math.abs(Math.sin((t * 7).toDouble())).toFloat()
            "thinking" -> 0.12f
            "listening" -> 0.08f + Status.level * 0.6f
            else -> 0f
        }
        amp += (target - amp) * 0.15f
        t += 0.016f
        val goal = when (st) {
            "hearing" -> 0xFF34D399
            "thinking" -> 0xFFA78BFA
            "speaking" -> 0xFF22D3EE
            "listening" -> 0xFF38BDF8
            else -> 0xFF475569
        }.toInt()
        cur = ev.evaluate(0.08f, cur, goal) as Int

        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.22f

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * d
        for (i in 0..3) {
            val rr = r * (1.35f + i * 0.32f) + r * amp * (0.35f + i * 0.25f) + r * 0.04f * Math.sin((t * 2 + i).toDouble()).toFloat()
            paint.color = al(cur, 110 - i * 26)
            c.drawCircle(cx, cy, rr, paint)
        }
        if (st == "thinking") {
            paint.strokeWidth = 3f * d
            paint.color = al(cur, 230)
            val box = RectF(cx - r * 1.55f, cy - r * 1.55f, cx + r * 1.55f, cy + r * 1.55f)
            c.drawArc(box, t * 220f, 90f, false, paint)
            c.drawArc(box, t * 220f + 180f, 90f, false, paint)
        }
        paint.style = Paint.Style.FILL
        val gr = r * (1.15f + amp * 0.5f) * 1.6f
        paint.shader = RadialGradient(cx, cy, gr, intArrayOf(al(cur, 235), al(cur, 110), al(cur, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gr, paint)
        paint.shader = null
        paint.color = al(0xFFFFFFFF.toInt(), 235)
        c.drawCircle(cx, cy, r * 0.38f * (1f + amp * 0.4f), paint)
        postInvalidateOnAnimation()
    }
}
