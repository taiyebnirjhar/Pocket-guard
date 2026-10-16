package com.taiyeb.pocketguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service with two jobs while the guard is armed:
 * 1. Spot the power menu opening on the lock screen, close it, and fire the siren.
 *    (No app can block power-off itself; this catches the attempt.)
 * 2. Lock the screen when the phone is pocketed unlocked.
 */
class PowerMenuWatcher : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: PowerMenuWatcher? = null

        private val POWER_WORDS = listOf("power off", "restart", "reboot", "shut down", "switch off")

        fun lockScreen() {
            if (Build.VERSION.SDK_INT >= 28) instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, PowerMenuWatcher::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (GuardState.phase.value == Phase.OFF) return
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (!looksLikePowerMenu(event)) return
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        GuardService.onPowerMenu()
    }

    override fun onInterrupt() = Unit

    /** Phone makers name the power menu differently, so match the class name or the button labels. */
    private fun looksLikePowerMenu(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString().orEmpty()
        val packageName = event.packageName?.toString().orEmpty()
        if (className.contains("GlobalActions", ignoreCase = true) ||
            packageName.contains("globalactions", ignoreCase = true)
        ) {
            return true
        }
        if (!packageName.contains("systemui", ignoreCase = true)) return false
        val text = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase()
        return POWER_WORDS.any { text.contains(it) }
    }
}
