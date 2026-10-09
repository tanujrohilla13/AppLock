package com.tanuj.applock

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/** Shown on top of a locked app. */
class LockActivity : Activity() {

    companion object { const val EXTRA_PKG = "pkg" }

    private lateinit var prefs: Prefs
    private var pkg = ""
    private var label = "App"
    private var icon: Drawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        load()
        start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: pkg
        load(); start()
    }

    private fun load() {
        label = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            icon = packageManager.getApplicationIcon(ai)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) { "App" }
    }

    private fun start() {
        if (prefs.isOn(F.FAKE_CRASH, false)) showFakeCrash() else showAuth()
    }

    /** A convincing "app stopped" screen; press-and-hold the button to reveal the real unlock. */
    private fun showFakeCrash() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFFAFAFA.toInt())
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "⚠"; textSize = 46f; gravity = Gravity.CENTER; setTextColor(0xFF5F6368.toInt())
        })
        root.addView(TextView(this).apply {
            text = "$label keeps stopping"
            textSize = 19f; setTextColor(0xFF202124.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(10)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Close app"
            textSize = 15f; setTextColor(0xFF1A73E8.toInt()); gravity = Gravity.END
            setPadding(dp(20), dp(28), dp(8), dp(8))
            // quick tap closes (go home); long-press opens the real unlock
            setOnClickListener { goHome() }
            setOnLongClickListener { showAuth(); true }
        })
        root.addView(TextView(this).apply {
            text = "hold to open"; textSize = 10f; setTextColor(0x11000000); gravity = Gravity.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun showAuth() = showAuthScreen(prefs, label, "Enter to unlock", icon) { unlock() }

    private fun unlock() {
        LockService.markUnlocked(pkg)
        prefs.addLog("Unlocked $label")
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goHome()
}
