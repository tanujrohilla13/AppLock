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
 * Full-screen unlock / create screen. Supports numeric PIN (optionally shuffled)
 * and a 3x3 pattern. Used for unlocking apps, opening App Lock and changing the code.
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
    /** CREATE only: true to create a pattern instead of a PIN. VERIFY uses the saved type. */
    createPattern: Boolean = false,
    /** Called after each wrong attempt with the running wrong-count (for intruder capture). */
    private val onWrong: ((Int) -> Unit)? = null,
) : LinearLayout(act) {

    enum class Mode { VERIFY, CREATE }

    private val usePattern = if (mode == Mode.VERIFY) prefs.isPattern else createPattern
    private val entered = StringBuilder()
    private var firstSecret: String? = null
    private val dots = LinearLayout(act)
    private val msg = TextView(act)
    private val subtitleView = TextView(act)
    private val handler = Handler(Looper.getMainLooper())
    private var patternView: PatternView? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(C.BG)
        setPadding(act.dp(24), act.dp(56), act.dp(24), act.dp(28))

        addView(ImageView(act).apply {
            if (icon != null) {
                setImageDrawable(icon)
                setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
                background = act.oval(C.SURFACE)
            } else setImageResource(R.mipmap.ic_launcher)
        }, LayoutParams(act.dp(84), act.dp(84)))

        addView(TextView(act).apply {
            text = title; textSize = 22f; setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER; setPadding(0, act.dp(18), 0, act.dp(6))
        })
        subtitleView.apply { text = subtitle; textSize = 14f; setTextColor(C.SUB); gravity = Gravity.CENTER }
        addView(subtitleView)

        if (!usePattern) {
            dots.gravity = Gravity.CENTER
            addView(dots, LayoutParams(WRAP_CONTENT, act.dp(20)).apply { topMargin = act.dp(36) })
        }
        msg.apply { textSize = 13f; setTextColor(C.DANGER); gravity = Gravity.CENTER }
        addView(msg, LayoutParams(MATCH_PARENT, act.dp(40)).apply { topMargin = act.dp(8) })

        addView(View(act), LayoutParams(1, 0, 1f))

        if (usePattern) {
            patternView = PatternView(act) { seq -> onPattern(seq) }
            addView(patternView, LayoutParams(act.dp(300), act.dp(300)))
        } else {
            addView(buildKeypad())
        }

        if (onForgot != null) addView(TextView(act).apply {
            text = "Forgot ${if (usePattern) "pattern" else "code"}?"
            setTextColor(C.ACCENT); textSize = 15f
            setPadding(act.dp(16), act.dp(18), act.dp(16), act.dp(4))
            setOnClickListener { onForgot.invoke() }
        })

        renderDots()
        updateLockout()
    }

    // ---------- numeric keypad ----------
    private fun buildKeypad(): View {
        val grid = LinearLayout(act).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        var digits = (1..9).map { it.toString() }
        if (prefs.isOn(F.SHUFFLE, false) && mode == Mode.VERIFY) digits = digits.shuffled()
        val extra = if (prefs.isOn(F.SHUFFLE, false) && mode == Mode.VERIFY) "0" else "0"
        val keys = digits.toMutableList()
        // last row: bio/empty, 0, delete
        val rows = listOf(
            keys.subList(0, 3).toList(), keys.subList(3, 6).toList(), keys.subList(6, 9).toList(),
            listOf(if (onBiometric != null) "bio" else "", extra, "del")
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
            setOnClickListener { if (entered.isNotEmpty()) { entered.deleteCharAt(entered.length - 1); renderDots() } }
            setOnLongClickListener { entered.clear(); renderDots(); true }
        }
        else -> TextView(act).apply {
            text = k; textSize = 28f; setTextColor(C.TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            background = ripple(act.oval(C.SURFACE))
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onDigit(k) }
        }
    }

    private val dotCount get() = when {
        mode == Mode.CREATE -> maxOf(4, entered.length)
        prefs.secretLength > 0 -> prefs.secretLength
        else -> maxOf(4, entered.length)
    }

    private fun renderDots() {
        if (usePattern) return
        dots.removeAllViews()
        repeat(dotCount) { i ->
            dots.addView(View(act).apply {
                background = if (i < entered.length) act.oval(C.ACCENT) else act.oval(C.BG, C.SUB)
            }, LayoutParams(act.dp(14), act.dp(14)).apply { setMargins(act.dp(9), 0, act.dp(9), 0) })
        }
    }

    // ---------- input handling ----------
    private fun onDigit(d: String) {
        if (isLockedOut() || entered.length >= 8) return
        entered.append(d)
        if (msg.text.isNotEmpty() && !isLockedOut()) msg.text = ""
        renderDots()
        when (mode) {
            Mode.VERIFY -> {
                val len = prefs.secretLength
                if (len > 0 && entered.length == len) handler.postDelayed({ verify() }, 80)
                else if (len == 0 && prefs.checkSecret(entered.toString())) success()
                else if (len == 0 && entered.length == 8) verify()
            }
            Mode.CREATE -> if (entered.length == 4) handler.postDelayed({ create(entered.toString()) }, 150)
        }
    }

    private fun onPattern(seq: String) {
        if (isLockedOut()) { patternView?.reset(); return }
        if (seq.isEmpty()) { fail("Connect at least 4 dots"); patternView?.reset(); return }
        when (mode) {
            Mode.VERIFY -> { entered.clear(); entered.append(seq); verify() }
            Mode.CREATE -> create(seq)
        }
        patternView?.reset()
    }

    private fun verify() {
        if (prefs.checkSecret(entered.toString())) { success(); return }
        val fails = prefs.failCount + 1
        onWrong?.invoke(fails)
        if (fails >= 5) { prefs.failCount = 0; prefs.lockUntil = System.currentTimeMillis() + 30_000 }
        else prefs.failCount = fails
        fail(if (fails >= 5) "" else "Wrong · ${5 - fails} left")
        updateLockout()
    }

    private fun create(secret: String) {
        when {
            firstSecret == null -> {
                firstSecret = secret; entered.clear()
                subtitleView.text = "Confirm to continue"; renderDots()
            }
            firstSecret == secret -> { prefs.setSecret(secret, usePattern); onSuccess() }
            else -> {
                firstSecret = null; entered.clear()
                subtitleView.text = if (usePattern) "Draw a pattern" else "Create a 4-digit PIN"
                fail("Didn't match – try again"); renderDots()
            }
        }
    }

    private fun success() { prefs.failCount = 0; onSuccess() }

    private fun fail(text: String) {
        msg.text = text; entered.clear(); renderDots()
        performHapticFeedback(HapticFeedbackConstants.REJECT)
        ObjectAnimator.ofFloat(if (usePattern) patternView else dots, "translationX",
            0f, 24f, -24f, 16f, -16f, 6f, 0f).setDuration(380).start()
    }

    private fun isLockedOut() = System.currentTimeMillis() < prefs.lockUntil

    private fun updateLockout() {
        val left = prefs.lockUntil - System.currentTimeMillis()
        if (left > 0) {
            msg.text = "Too many attempts. Wait ${(left + 999) / 1000}s"
            handler.postDelayed({ updateLockout() }, 1000)
        } else if (msg.text.startsWith("Too many")) msg.text = ""
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
