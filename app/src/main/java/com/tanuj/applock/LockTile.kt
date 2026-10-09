package com.tanuj.applock

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile to pause / resume all locking in one tap. */
class LockTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        val prefs = Prefs(this)
        prefs.protectionPaused = !prefs.protectionPaused
        if (prefs.protectionPaused) LockService.lockAll()
        refresh()
    }

    private fun refresh() {
        val paused = Prefs(this).protectionPaused
        qsTile?.apply {
            state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = if (paused) "App Lock: Off" else "App Lock: On"
            icon = Icon.createWithResource(this@LockTile, R.drawable.ic_launcher_foreground)
            updateTile()
        }
    }
}
