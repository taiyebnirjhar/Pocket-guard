package com.taiyeb.pocketguard

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Sees the volume keys themselves, which no ordinary app can: volume up tapped [SosTaps.TAPS]
 * times quickly, with the screen on, is the SOS call. It only watches: every key still does
 * what it always does. It is opt-in, after the disclosure; without it the volume-key SOS doesn't
 * work (the notification, tile, app and alarm-screen buttons still do).
 */
class SosAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var connected = false
            private set
    }

    private val taps = SosTaps { gesture, silent -> GuardService.onSosTaps(gesture, silent) }

    override fun onServiceConnected() {
        android.util.Log.i(GuardService.TAG, "SOS key watcher connected")
        connected = true
        if (GuardState.phase.value != Phase.OFF) DetectionLog.add("SOS key watcher on")
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
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // Music, a video, a call or a ringing phone: taps are someone turning it up (or silencing
            // the ringer), not a call for help. Our own warning beeps count as "music", but during a
            // theft alarm the taps must work: the owner grabbed her phone because someone is
            // harassing her (SOS takes over).
            val audio = getSystemService(android.media.AudioManager::class.java)
            val ownAlarm = GuardState.phase.value.isAlarm
            val music = !ownAlarm && audio.isMusicActive
            val mode = audio.mode
            val key = if (up) "up" else "down"
            // Each press goes in the detection log (armed only), so a phone where SOS doesn't fire
            // shows whether the keys arrive at all, and why they didn't count.
            val armed = GuardState.phase.value != Phase.OFF
            if (music || mode != android.media.AudioManager.MODE_NORMAL) {
                taps.reset()
                if (armed) DetectionLog.add("SOS keys: volume $key ignored (" + (if (music) "music or video playing" else "call or ringing, audio mode $mode") + ")")
            } else {
                val n = taps.press(up, event.eventTime)
                if (armed && n > 0) DetectionLog.add("SOS keys: volume $key ($n of ${SosTaps.TAPS})")
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        // The phone (or the user) turned Accessibility off: the volume-key SOS stops working.
        if (GuardState.phase.value != Phase.OFF) DetectionLog.add("SOS key watcher OFF: volume-key SOS won't work")
        taps.reset()
        GuardService.refreshSos()
        return super.onUnbind(intent)
    }
}

/** What the SOS setup card shows: is the key watcher on, and where to turn it on. */
object SosSetup {
    fun keysEnabled(context: android.content.Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val ours = android.content.ComponentName(context, SosAccessibilityService::class.java)
        return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == ours }
    }

    /** Our own switch in Accessibility where the phone can open it directly, else the whole list. */
    fun openSettings(context: android.content.Context) {
        val ours = android.content.ComponentName(context, SosAccessibilityService::class.java).flattenToString()
        val direct = android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, ours)
        val list = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        for (intent in listOfNotNull(direct.takeIf { android.os.Build.VERSION.SDK_INT >= 31 }, list)) {
            try {
                context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Not on this phone: try the next.
            }
        }
    }

    /**
     * Android 13+ greys out Accessibility for apps installed outside an app store until the
     * owner allows restricted settings in App info. True while that still applies (or can't be
     * told: the guide then just shows every step).
     */
    fun restricted(context: android.content.Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33 || BuildConfig.PLAY_STORE) return false
        val mode = runCatching {
            context.getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
                "android:access_restricted_settings", android.os.Process.myUid(), context.packageName,
            )
        }.getOrNull() ?: return true
        return mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED
    }
}
