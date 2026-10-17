package com.taiyeb.pocketguard

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper

/**
 * Sees the volume keys themselves, screen on or off, which no ordinary app can: both held
 * together for [SosHold.HOLD_MS] is the SOS call. It only watches: every key still does what it
 * always does. It is opt-in, after the disclosure in Triggers; without it there is no SOS hold.
 */
class SosAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var connected = false
            private set
    }

    private val hold = SosHold(Handler(Looper.getMainLooper())) { GuardService.onSosHold("keys") }

    override fun onServiceConnected() {
        android.util.Log.i(GuardService.TAG, "SOS key watcher connected")
        connected = true
        GuardService.refreshSos()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        if (event.repeatCount == 0) {
            android.util.Log.d(GuardService.TAG, "SOS key watcher: ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"}")
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) hold.press(up, true)
            KeyEvent.ACTION_UP -> hold.press(up, false)
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        hold.reset()
        GuardService.refreshSos()
        return super.onUnbind(intent)
    }
}

/** Both volume keys down together, held [HOLD_MS]: the SOS call. Main thread. */
class SosHold(private val handler: Handler, private val onHold: () -> Unit) {
    companion object {
        const val HOLD_MS = 3000L
    }

    private var upDown = false
    private var downDown = false
    private val fire = Runnable { if (upDown && downDown) onHold() }

    fun press(up: Boolean, down: Boolean) {
        val wasBoth = upDown && downDown
        if (up) upDown = down else downDown = down
        val both = upDown && downDown
        if (both && !wasBoth) handler.postDelayed(fire, HOLD_MS)
        if (!both) handler.removeCallbacks(fire)
    }

    fun reset() {
        upDown = false
        downDown = false
        handler.removeCallbacks(fire)
    }
}

/** What the SOS setup card shows: is the key watcher on, and does the system shortcut clash. */
object SosSetup {
    fun keysEnabled(context: android.content.Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val ours = android.content.ComponentName(context, SosAccessibilityService::class.java)
        return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == ours }
    }

    /** A target is assigned to Android's volume-keys accessibility shortcut (hold + and −). */
    fun systemShortcutSet(context: android.content.Context): Boolean {
        val target = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, "accessibility_shortcut_target_service")
        }.getOrNull()
        return !target.isNullOrBlank()
    }

    fun openSettings(context: android.content.Context) {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
