package com.tanuj.applock

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Colour palette */
object C {
    const val BG = 0xFF0F1115.toInt()
    const val SURFACE = 0xFF1A1D24.toInt()
    const val SURFACE2 = 0xFF242833.toInt()
    const val ACCENT = 0xFF5B8CFF.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0xFF9AA3B2.toInt()
    const val DANGER = 0xFFFF5C5C.toInt()
    const val OK = 0xFF34C77B.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int) = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int = 16, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

fun Context.oval(color: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

fun ripple(content: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, content)

/** Fingerprint / phone-screen-lock helpers using the platform BiometricPrompt (API 30+). */
object Bio {
    fun available(a: Activity, allowDeviceCredential: Boolean = false): Boolean {
        val auth = if (allowDeviceCredential) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG
        return a.getSystemService(BiometricManager::class.java)
            .canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun prompt(a: Activity, title: String, subtitle: String, allowDeviceCredential: Boolean, onOk: () -> Unit) {
        if (!available(a, allowDeviceCredential)) return
        val b = BiometricPrompt.Builder(a).setTitle(title).setSubtitle(subtitle)
        if (allowDeviceCredential) {
            b.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        } else {
            b.setAllowedAuthenticators(BIOMETRIC_STRONG)
            b.setNegativeButton("Use PIN", a.mainExecutor) { _, _ -> }
        }
        b.build().authenticate(CancellationSignal(), a.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onOk()
            })
    }
}

/**
 * "Forgot PIN?" flow: prove you're the phone's owner with fingerprint OR the phone's
 * own screen-lock PIN/pattern, then create a new App Lock PIN.
 */
fun Activity.resetPin(prefs: Prefs, onDone: () -> Unit) {
    if (!Bio.available(this, allowDeviceCredential = true)) {
        AlertDialog.Builder(this)
            .setTitle("Can't reset here")
            .setMessage("Your phone has no screen lock set, so App Lock can't verify it's you.\n\n" +
                    "Reset option: Settings → Apps → App Lock → Storage → Clear data. " +
                    "This removes the PIN and the locked-app list.")
            .setPositiveButton("OK", null).show()
        return
    }
    Bio.prompt(this, "Verify it's you", "Use fingerprint or your phone's screen lock", true) {
        setContentView(AuthView(this, prefs, AuthView.Mode.CREATE,
            title = "Reset PIN", subtitle = "Create a new 4-digit PIN", icon = null,
            onSuccess = {
                Toast.makeText(this, "PIN updated", Toast.LENGTH_SHORT).show()
                onDone()
            }))
    }
}

/**
 * Shows the right unlock screen for the user's chosen method.
 * Falls back to PIN when the phone has no fingerprint enrolled.
 */
fun Activity.showAuthScreen(prefs: Prefs, title: String, subtitle: String, icon: Drawable?, onSuccess: () -> Unit) {
    val method = if (Bio.available(this)) prefs.unlockMethod else Method.PIN
    when (method) {
        Method.FINGER -> {
            // Fingerprint, with the phone's own screen lock as the backup inside the prompt
            val ask = { Bio.prompt(this, "Unlock $title", "Fingerprint or phone screen lock", true, onSuccess) }
            setContentView(fingerprintScreen(title, icon, ask))
            ask()
        }
        else -> {
            val withBio = method == Method.BOTH
            val ask = { Bio.prompt(this, "Unlock $title", "Touch the fingerprint sensor", false, onSuccess) }
            setContentView(AuthView(this, prefs, AuthView.Mode.VERIFY, title, subtitle, icon,
                onSuccess = onSuccess,
                onForgot = { resetPin(prefs, onSuccess) },
                onBiometric = if (withBio) ask else null))
            if (withBio) ask()
        }
    }
}

private fun Activity.fingerprintScreen(title: String, icon: Drawable?, onTap: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(C.BG)
        setPadding(dp(24), dp(56), dp(24), dp(56))
        addView(ImageView(context).apply {
            if (icon != null) {
                setImageDrawable(icon); background = oval(C.SURFACE)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            } else setImageResource(R.mipmap.ic_launcher)
        }, LinearLayout.LayoutParams(dp(84), dp(84)))
        addView(TextView(context).apply {
            text = title; textSize = 22f; setTextColor(C.TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(18), 0, dp(6))
        })
        addView(TextView(context).apply {
            text = "Locked · use your fingerprint"; textSize = 14f; setTextColor(C.SUB); gravity = Gravity.CENTER
        })
        addView(android.view.View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            background = ripple(oval(C.SURFACE, C.ACCENT))
            setOnClickListener { onTap() }
        }, LinearLayout.LayoutParams(dp(120), dp(120)))
        addView(TextView(context).apply {
            text = "Tap to unlock"; textSize = 14f; setTextColor(C.SUB); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
