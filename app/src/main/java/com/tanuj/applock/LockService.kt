package com.tanuj.applock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app comes to the foreground. If it is a locked app that
 * hasn't been unlocked in this "session", shows LockActivity on top of it.
 * Re-locks when you switch to another app or turn the screen off.
 */
class LockService : AccessibilityService() {

    companion object {
        /** pkg → time it re-locks (Long.MAX_VALUE = currently open & unlocked). */
        private val unlocked = java.util.concurrent.ConcurrentHashMap<String, Long>()
        fun markUnlocked(pkg: String) { unlocked[pkg] = Long.MAX_VALUE }
        fun lockAll() = unlocked.clear()
    }

    private var current: String? = null

    private lateinit var prefs: Prefs

    // Windows that can appear over an app without meaning "user left it"
    private val ignored = setOf(
        "com.android.systemui",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.biometrics.app.setting",
        "android"
    )

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { lockAll() }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignored) return

        val now = System.currentTimeMillis()

        // User switched apps: start the re-lock timer for the app they left
        if (pkg != current) {
            current?.let { prev ->
                if (unlocked[prev] == Long.MAX_VALUE) {
                    val delay = prefs.relockDelay
                    if (delay <= 0) unlocked.remove(prev) else unlocked[prev] = now + delay
                }
            }
            current = pkg
        }

        if (pkg !in prefs.lockedApps) return
        if (!lockingActive()) return
        val until = unlocked[pkg]
        if (until != null && (until == Long.MAX_VALUE || now < until)) {
            unlocked[pkg] = Long.MAX_VALUE        // still within grace period
            return
        }
        unlocked.remove(pkg)
        run {
            startActivity(Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(LockActivity.EXTRA_PKG, pkg)
            })
        }
    }

    /** Master pause, schedule window and trusted-wifi checks. */
    private fun lockingActive(): Boolean {
        if (prefs.protectionPaused) return false

        val start = prefs.scheduleStart; val end = prefs.scheduleEnd
        if (start >= 0 && end >= 0 && start != end) {
            val c = java.util.Calendar.getInstance()
            val mins = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
            val inWindow = if (start < end) mins in start until end else (mins >= start || mins < end)
            if (!inWindow) return false
        }

        val trusted = prefs.trustedWifi
        if (trusted.isNotEmpty() && currentWifi() == trusted) return false
        return true
    }

    private fun currentWifi(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wm.connectionInfo?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
        } catch (e: Exception) { null }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        super.onDestroy()
    }
}
