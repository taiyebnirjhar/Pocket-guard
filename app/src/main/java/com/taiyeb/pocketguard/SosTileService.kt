package com.taiyeb.pocketguard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: SOS in one tap from the lock screen, no unlock (swipe down, tap). It works
 * while the guard is armed; otherwise it opens the app to arm first.
 */
class SosTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val armed = GuardState.phase.value != Phase.OFF
        tile.state = if (armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.action_sos)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(if (armed) R.string.tile_sos_ready else R.string.tile_off)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!GuardService.requestSos("tile")) openApp()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent overload only runs below API 34.
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
