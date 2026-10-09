package com.tanuj.applock

import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen PIN pad used for unlocking, creating and resetting the PIN.
 * VERIFY: checks against the saved PIN.  CREATE: enter + confirm a new 4-digit PIN.
 */
class AuthView(
    private val act: Activity,
    private val prefs: Prefs,
    private val mode: Mode,
    title: String,
    subtitle: String,
    icon: Drawable?,
    private val onSuccess: () -> Unit,
    private val onForgot: (() -> Unit)? = null,
    private val onBiometric: (() -> Unit)? = null,
) : LinearLayout(act) {

    enum class Mode { VERIFY, CREATE }

    private val entered = StringBuilder()
    private var firstPin: String? = null
    private val dots = LinearLayout(act)
    private val msg = TextView(act)
    private val subtitleView = TextView(act)
    private val handler = Handler(Looper.getMainLooper())

    private val dotCount: Int
        get() = when {
            mode == Mode.CREATE -> 4
            prefs.pinLength > 0 -> prefs.pinLength
            else -> maxOf(4, entered.length)
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(C.BG)
        setPadding(act.dp(24), act.dp(56), act.dp(24), act.dp(28))

        // Icon in a soft circle
        val iconView = ImageView(act).apply {
            if (icon != null) {
                setImageDrawable(icon)
                setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
                background = act.oval(C.SURFACE)
            } else setImageResource(R.mipmap.ic_launcher)
        }
        addView(iconView, LayoutParams(act.dp(84), act.dp(84)))

        addView(TextView(act).apply {
            text = title
            textSize = 22f
            setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, act.dp(18), 0, act.dp(6))
        })
        subtitleView.apply {
            text = subtitle
            textSize = 14f
            setTextColor(C.SUB)
            gravity = Gravity.CENTER
        }
        addView(subtitleView)

        dots.gravity = Gravity.CENTER
        addView(dots, LayoutParams(WRAP_CONTENT, act.dp(20)).apply { topMargin = act.dp(36) })

        msg.apply { textSize = 13f; setTextColor(C.DANGER); gravity = Gravity.CENTER }
        addView(msg, LayoutParams(MATCH_PARENT, act.dp(40)).apply { topMargin = act.dp(8) })

        addView(View(act), LayoutParams(1, 0, 1f)) // spacer pushes keypad down
        addView(buildKeypad())

        if (onForgot != null) {
            addView(TextView(act).apply {
                text = "Forgot PIN?"
                setTextColor(C.ACCENT)
                textSize = 15f
                setPadding(act.dp(16), act.dp(18), act.dp(16), act.dp(4))
                setOnClickListener { onForgot.invoke() }
            })
        }
        renderDots()
        updateLockout()
    }

    // ---------- keypad ----------
    private fun buildKeypad(): View {
        val grid = LinearLayout(act).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        val rows = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"),
            listOf(if (onBiometric != null) "bio" else "", "0", "del")
        )
        for (r in rows) {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER }
            for (k in r) row.addView(key(k), LayoutParams(act.dp(74), act.dp(74)).apply {
                setMargins(act.dp(14), act.dp(7), act.dp(14), act.dp(7))
            })
            grid.addView(row)
        }
        return grid
    }

    private fun key(k: String): View = when (k) {
        "" -> View(act)
        "bio" -> ImageView(act).apply {
            setImageResource(R.drawable.ic_fingerprint)
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(act.oval(C.BG))
            setOnClickListener { onBiometric?.invoke() }
        }
        "del" -> TextView(act).apply {
            text = "⌫"; textSize = 24f; setTextColor(C.SUB); gravity = Gravity.CENTER
            background = ripple(act.oval(C.BG))
            setOnClickListener {
                if (entered.isNotEmpty()) { entered.deleteCharAt(entered.length - 1); renderDots() }
            }
            setOnLongClickListener { entered.clear(); renderDots(); true }
        }
        else -> TextView(act).apply {
            text = k; textSize = 28f; setTextColor(C.TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            background = ripple(act.oval(C.SURFACE))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onDigit(k)
            }
        }
    }

    private fun renderDots() {
        dots.removeAllViews()
        repeat(dotCount) { i ->
            val filled = i < entered.length
            dots.addView(View(act).apply {
                background = if (filled) act.oval(C.ACCENT) else act.oval(C.BG, C.SUB)
            }, LayoutParams(act.dp(14), act.dp(14)).apply { setMargins(act.dp(9), 0, act.dp(9), 0) })
        }
    }

    // ---------- logic ----------
    private fun onDigit(d: String) {
        if (isLockedOut() || entered.length >= 8) return
        entered.append(d)
        if (msg.text.isNotEmpty() && !isLockedOut()) msg.text = ""
        renderDots()

        when (mode) {
            Mode.VERIFY -> {
                val len = prefs.pinLength
                if (len > 0) {
                    if (entered.length == len) handler.postDelayed({ verify() }, 80)
                } else if (entered.length >= 4) {           // PIN from v1: length unknown
                    if (prefs.checkPin(entered.toString())) success()
                    else if (entered.length == 8) verify()
                }
            }
            Mode.CREATE -> if (entered.length == 4) handler.postDelayed({ create() }, 150)
        }
    }

    private fun verify() {
        if (prefs.checkPin(entered.toString())) { success(); return }
        val fails = prefs.failCount + 1
        if (fails >= 5) {
            prefs.failCount = 0
            prefs.lockUntil = System.currentTimeMillis() + 30_000
        } else prefs.failCount = fails
        fail(if (fails >= 5) "" else "Wrong PIN · ${5 - fails} attempts left")
        updateLockout()
    }

    private fun create() {
        val p = entered.toString()
        when {
            firstPin == null -> {
                firstPin = p; entered.clear()
                subtitleView.text = "Confirm your PIN"
                renderDots()
            }
            firstPin == p -> { prefs.setPin(p); onSuccess() }
            else -> {
                firstPin = null
                subtitleView.text = "Create a 4-digit PIN"
                fail("PINs didn't match – try again")
            }
        }
    }

    private fun success() {
        prefs.failCount = 0
        onSuccess()
    }

    private fun fail(text: String) {
        msg.text = text
        entered.clear()
        renderDots()
        performHapticFeedback(HapticFeedbackConstants.REJECT)
        ObjectAnimator.ofFloat(dots, "translationX", 0f, 24f, -24f, 16f, -16f, 6f, 0f)
            .setDuration(380).start()
    }

    private fun isLockedOut() = System.currentTimeMillis() < prefs.lockUntil

    private fun updateLockout() {
        val left = prefs.lockUntil - System.currentTimeMillis()
        if (left > 0) {
            msg.text = "Too many attempts. Try again in ${(left + 999) / 1000}s"
            handler.postDelayed({ updateLockout() }, 1000)
        } else if (msg.text.startsWith("Too many")) msg.text = ""
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
