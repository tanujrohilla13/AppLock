package com.tanuj.applock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Minimal 3x3 pattern pad. Reports the connected dot sequence as a string like "0,4,8". */
class PatternView(context: Context, private val onDone: (String) -> Unit) : View(context) {

    private val dots = Array(9) { floatArrayOf(0f, 0f) }
    private val chosen = ArrayList<Int>()
    private var curX = 0f
    private var curY = 0f
    private var drawing = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ACCENT; strokeWidth = context.dpf(6); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SUB; style = Paint.Style.STROKE; strokeWidth = context.dpf(2) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val step = w / 4f
        for (i in 0..8) dots[i] = floatArrayOf(step * (1 + i % 3), step * (1 + i / 3))
    }

    private val radius get() = context.dpf(10)
    private val hitRadius get() = context.dpf(26)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { chosen.clear(); drawing = true; add(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> if (drawing) { curX = e.x; curY = e.y; add(e.x, e.y) }
            MotionEvent.ACTION_UP -> {
                drawing = false
                if (chosen.size >= 4) onDone(chosen.joinToString(","))
                else if (chosen.isNotEmpty()) { chosen.clear(); onDone("") }
                invalidate()
            }
        }
        curX = e.x; curY = e.y
        invalidate()
        return true
    }

    private fun add(x: Float, y: Float) {
        for (i in 0..8) if (i !in chosen && hypot(x - dots[i][0], y - dots[i][1]) < hitRadius) {
            chosen.add(i); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun reset() { chosen.clear(); drawing = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        for (i in 1 until chosen.size) {
            val a = dots[chosen[i - 1]]; val b = dots[chosen[i]]
            canvas.drawLine(a[0], a[1], b[0], b[1], line)
        }
        if (drawing && chosen.isNotEmpty()) {
            val a = dots[chosen.last()]; canvas.drawLine(a[0], a[1], curX, curY, line)
        }
        for (i in 0..8) {
            canvas.drawCircle(dots[i][0], dots[i][1], radius, if (i in chosen) fill else ring)
        }
    }
}
