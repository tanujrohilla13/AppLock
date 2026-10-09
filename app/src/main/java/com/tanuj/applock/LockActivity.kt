package com.tanuj.applock

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*

class LockActivity : Activity() {

    companion object { const val EXTRA_PKG = "pkg" }

    private lateinit var prefs: Prefs
    private var pkg: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        buildUi()
        showBiometric()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: pkg
        buildUi()
        showBiometric()
    }

    private fun buildUi() {
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        } catch (e: Exception) { pkg }

        val pinBox = EditText(this).apply {
            hint = "Enter PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 24f
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        fun tryPin() {
            if (prefs.checkPin(pinBox.text.toString())) unlock()
            else { pinBox.text.clear(); pinBox.error = "Wrong PIN" }
        }
        pinBox.setOnEditorActionListener { _, _, _ -> tryPin(); true }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(TextView(context).apply {
                text = "🔒  $appName is locked"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 48)
            })
            addView(pinBox)
            addView(Button(context).apply { text = "Unlock"; setOnClickListener { tryPin() } })
            addView(Button(context).apply { text = "Use fingerprint"; setOnClickListener { showBiometric() } })
        })
    }

    private fun showBiometric() {
        val bm = getSystemService(BiometricManager::class.java)
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return

        BiometricPrompt.Builder(this)
            .setTitle("Unlock app")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Use PIN", mainExecutor) { _, _ -> }
            .build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = unlock()
            })
    }

    private fun unlock() {
        LockService.unlockedPkg = pkg
        finish()
    }

    /** Back button = leave the locked app and go to the home screen. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
