package com.taiyeb.pocketguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
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

        /** English and Bangla labels; a real power menu shows at least two of these (off and restart). */
        private val POWER_WORDS = listOf(
            "power off", "restart", "reboot", "shut down", "switch off", "emergency",
            "বন্ধ করুন", "রিস্টার্ট", "আবার চালু", "জরুরি",
        )

        fun lockScreen() {
            val service = instance
            if (service == null) {
                Log.w(GuardService.TAG, "can't lock: power-off protection not connected")
                return
            }
            if (Build.VERSION.SDK_INT >= 28) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
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
        Log.i(GuardService.TAG, "power-off protection connected")
    }

    // Identity checks: after a rebind the old instance can be unbound after the new one connected.
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(GuardService.TAG, "power-off protection unbound")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(GuardService.TAG, "power-off protection destroyed")
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (GuardState.phase.value == Phase.OFF) return
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (!looksLikePowerMenu(event)) {
            if (event.packageName?.toString()?.contains("systemui") == true) {
                Log.i(GuardService.TAG, "lock-screen window ignored: ${event.className}")
            }
            return
        }
        Log.i(GuardService.TAG, "power menu detected: ${event.className}")
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        GuardService.onPowerMenu()
    }

    override fun onInterrupt() {
        Log.i(GuardService.TAG, "power-off protection interrupted")
    }

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
        // One word alone (e.g. a "Restart now" update prompt) is not the power menu.
        return POWER_WORDS.count { text.contains(it) } >= 2
    }
}
