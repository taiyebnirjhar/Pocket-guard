package com.taiyeb.pocketguard

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap to arm. Disarming asks for unlock, so a thief can't turn it off. */
class GuardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (GuardState.phase.value == Phase.OFF) {
            try {
                GuardService.arm(this)
            } catch (e: Exception) {
                // Some Android versions refuse to start a foreground service from a tile; arm via the app.
                openApp()
            }
        } else {
            unlockAndRun { GuardService.disarm(this) }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = GuardState.phase.value != Phase.OFF
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ARM, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
