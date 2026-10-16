package com.taiyeb.pocketguard

import android.annotation.SuppressLint
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
                if (GuardService.arm(this)) {
                    showOn(true) // The service updates the state shortly; show it right away.
                } else {
                    openApp() // No secure lock screen: the app explains.
                }
            } catch (e: Exception) {
                // Some Android versions refuse to start a foreground service from a tile; arm via the app.
                openApp()
            }
        } else {
            unlockAndRun {
                GuardService.disarm(this)
                showOn(false)
            }
        }
    }

    private fun refresh() = showOn(GuardState.phase.value != Phase.OFF)

    private fun showOn(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent overload only runs below API 34.
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
