package com.tanuj.applock

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle

/** Shown on top of a locked app. */
class LockActivity : Activity() {

    companion object { const val EXTRA_PKG = "pkg" }

    private lateinit var prefs: Prefs
    private var pkg = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: pkg
        show()
    }

    private fun show() {
        var icon: Drawable? = null
        val label = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            icon = packageManager.getApplicationIcon(ai)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) { "App" }

        showAuthScreen(prefs, label, "Enter your PIN to unlock", icon) { unlock() }
    }

    private fun unlock() {
        LockService.markUnlocked(pkg)
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    /** Back = leave the locked app and go home. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
