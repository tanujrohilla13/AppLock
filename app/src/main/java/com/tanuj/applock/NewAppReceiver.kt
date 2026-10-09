package com.tanuj.applock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** When a new app is installed, offer (via a notification) to lock it. */
class NewAppReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val prefs = Prefs(ctx)
        if (!prefs.isOn(F.NEW_APP, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == ctx.packageName || pkg in prefs.lockedApps) return

        val label = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("newapp", "New apps", NotificationManager.IMPORTANCE_DEFAULT))

        val lockIntent = PendingIntent.getBroadcast(ctx, pkg.hashCode(),
            Intent(ctx, LockNowReceiver::class.java).setPackage(ctx.packageName).putExtra("pkg", pkg),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = android.app.Notification.Builder(ctx, "newapp")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Lock $label?")
            .setContentText("Tap to protect this newly installed app with App Lock")
            .addAction(android.app.Notification.Action.Builder(null, "Lock it", lockIntent).build())
            .setAutoCancel(true)
            .build()
        nm.notify(pkg.hashCode(), n)
    }
}

/** Handles the "Lock it" action from the new-app notification. */
class LockNowReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        val prefs = Prefs(ctx)
        prefs.lockedApps = prefs.lockedApps + pkg
        ctx.getSystemService(NotificationManager::class.java).cancel(pkg.hashCode())
    }
}
