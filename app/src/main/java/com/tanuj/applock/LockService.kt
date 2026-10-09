package com.tanuj.applock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app comes to the foreground. If it is a locked app that
 * hasn't been unlocked in this "session", shows LockActivity on top of it.
 * The app re-locks as soon as you switch to a different app.
 */
class LockService : AccessibilityService() {

    companion object {
        /** Package the user just unlocked; cleared when they leave it. */
        @Volatile var unlockedPkg: String? = null
    }

    private lateinit var prefs: Prefs

    // Windows that can appear on top of an app without meaning "user left it"
    private val ignored = setOf(
        "com.android.systemui",
        "com.samsung.android.honeyboard",     // Samsung keyboard
        "com.google.android.inputmethod.latin",
        "android"
    )

    override fun onServiceConnected() {
        prefs = Prefs(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignored) return

        if (pkg != unlockedPkg) unlockedPkg = null   // user moved to another app → re-lock

        if (pkg in prefs.lockedApps && unlockedPkg == null) {
            startActivity(Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(LockActivity.EXTRA_PKG, pkg)
            })
        }
    }

    override fun onInterrupt() {}
}
