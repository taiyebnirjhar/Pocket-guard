package com.taiyeb.pocketguard

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * Settings live in device-protected storage so they can be read before the first unlock after a
 * restart: a thief force-restarting the phone must not switch the guard off.
 */
object Prefs {
    private const val NAME = "pocket_guard"
    private const val TEST_MODE_MS = 60 * 60_000L

    @Volatile
    private var migrated = false

    private fun prefs(context: Context): SharedPreferences {
        val storage = context.createDeviceProtectedStorageContext()
        if (!migrated && context.getSystemService(UserManager::class.java).isUserUnlocked) {
            migrated = true
            try {
                storage.moveSharedPreferencesFrom(context, NAME) // From older versions.
            } catch (e: Exception) {
                // Nothing to move.
            }
        }
        return storage.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    private fun get(context: Context, key: String, default: Boolean) = prefs(context).getBoolean(key, default)
    private fun put(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    fun graceSeconds(context: Context) = prefs(context).getInt("grace_seconds", 5)
    fun setGraceSeconds(context: Context, value: Int) =
        prefs(context).edit().putInt("grace_seconds", value).apply()

    /**
     * Test mode plays the siren quietly and marks SMS as [TEST]. It switches itself off after an
     * hour so a forgotten test mode can't weaken a real theft alarm.
     */
    fun testMode(context: Context) = get(context, "test_mode", false) &&
        System.currentTimeMillis() < prefs(context).getLong("test_mode_until", 0)
    fun setTestMode(context: Context, value: Boolean) = prefs(context).edit()
        .putBoolean("test_mode", value)
        .putLong("test_mode_until", if (value) System.currentTimeMillis() + TEST_MODE_MS else 0)
        .apply()

    fun armed(context: Context) = get(context, "armed", false)

    /** Armed by earbuds or transit, so it turns itself off after a few hours (kept across restarts). */
    fun autoArmed(context: Context) = get(context, "auto_armed", false)
    fun setAutoArmed(context: Context, value: Boolean) = put(context, "auto_armed", value)
    fun setArmed(context: Context, value: Boolean) = put(context, "armed", value)

    /** A siren or fake shutdown is running; restored if the phone restarts or the app is killed. */
    fun alarmActive(context: Context) = get(context, "alarm_active", false)
    fun setAlarmActive(context: Context, value: Boolean) = put(context, "alarm_active", value)

    /** The running alarm is SOS: after a restart it comes back as SOS, alerts and all. */
    fun alarmSos(context: Context) = get(context, "alarm_sos", false)
    fun setAlarmSos(context: Context, value: Boolean) = put(context, "alarm_sos", value)

    /**
     * The alarm volume from before the siren raised it, kept until it's restored: a siren cut off
     * by a restart must not leave the maximum as the volume to go back to.
     */
    fun savedAlarmVolume(context: Context) = prefs(context).getInt("saved_alarm_volume", -1).takeIf { it >= 0 }
    fun setSavedAlarmVolume(context: Context, value: Int?) =
        prefs(context).edit().putInt("saved_alarm_volume", value ?: -1).apply()

    /** When an auto-armed session ends by itself (wall clock, so it survives a restart). */
    fun autoArmUntil(context: Context) = prefs(context).getLong("auto_arm_until", 0L)
    fun setAutoArmUntil(context: Context, value: Long) = prefs(context).edit().putLong("auto_arm_until", value).apply()

    // --- Schedule (see ArmScheduler) --------------------------------------------------------------

    fun scheduleOn(context: Context) = get(context, "schedule_on", false)
    fun setScheduleOn(context: Context, value: Boolean) = put(context, "schedule_on", value)
    fun scheduleJson(context: Context): String? = prefs(context).getString("schedule", null)
    fun setScheduleJson(context: Context, value: String) = prefs(context).edit().putString("schedule", value).apply()

    /** The schedule is paused (turned off by hand in a window) until this time; 0 when not. */
    fun schedulePausedUntil(context: Context) = prefs(context).getLong("schedule_paused_until", 0L)
    fun setSchedulePausedUntil(context: Context, value: Long) =
        prefs(context).edit().putLong("schedule_paused_until", value).apply()

    /** The guard is on because of the schedule (or was armed by hand inside a window), so it ends with it. */
    fun scheduleArmed(context: Context) = get(context, "schedule_armed", false)
    fun setScheduleArmed(context: Context, value: Boolean) = put(context, "schedule_armed", value)

    /** Siren the moment airplane mode is turned on while the phone is locked. */
    fun airplaneAlarm(context: Context) = get(context, "tamper_alarm", true)
    fun setAirplaneAlarm(context: Context, value: Boolean) = put(context, "tamper_alarm", value)

    enum class LockCheck { UNTESTED, PASSED, FAILED }

    /**
     * Result of the last screen-off test: did the phone lock immediately? Without that, a phone
     * pocketed with the screen just off is still unlocked, and a thief would look like the owner.
     */
    fun lockCheck(context: Context): LockCheck =
        LockCheck.entries.firstOrNull { it.name == prefs(context).getString("lock_check", null) } ?: LockCheck.UNTESTED
    fun setLockCheck(context: Context, value: LockCheck) =
        prefs(context).edit().putString("lock_check", value.name).apply()

    fun chargerAlarm(context: Context) = get(context, "charger_alarm", true)
    fun setChargerAlarm(context: Context, value: Boolean) = put(context, "charger_alarm", value)

    fun earbudsAlarm(context: Context) = get(context, "earbuds_alarm", false)
    fun setEarbudsAlarm(context: Context, value: Boolean) = put(context, "earbuds_alarm", value)

    fun autoArmEarbuds(context: Context) = get(context, "auto_arm_earbuds", false)
    fun setAutoArmEarbuds(context: Context, value: Boolean) = put(context, "auto_arm_earbuds", value)

    fun autoArmTransit(context: Context) = get(context, "auto_arm_transit", false)
    fun setAutoArmTransit(context: Context, value: Boolean) = put(context, "auto_arm_transit", value)

    fun intruderPhotos(context: Context) = get(context, "intruder_photos", true)

    /** SOS (both volume keys held, with recording). A switch for holding it back from a release. */
    const val SOS_AVAILABLE = true

    /** SOS: both volume keys held 3 s sounds a call for help and records, while armed and locked. */
    fun sosButtons(context: Context) = SOS_AVAILABLE && get(context, "sos_buttons", true)

    /** Power pressed about five times quickly calls for help (no setup; works with the screen off). */
    fun sosPowerPresses(context: Context) = SOS_AVAILABLE && get(context, "sos_power_presses", true)
    fun setSosPowerPresses(context: Context, value: Boolean) = put(context, "sos_power_presses", value)
    fun setSosButtons(context: Context, value: Boolean) = put(context, "sos_buttons", value)

    /** Guard a phone lying face-down on a table, not only one in a pocket. */
    fun tableGuard(context: Context) = get(context, "table_guard", true)
    fun setTableGuard(context: Context, value: Boolean) = put(context, "table_guard", value)
    fun setIntruderPhotos(context: Context, value: Boolean) = put(context, "intruder_photos", value)

    /** SOS shares a live location link (and emails) through Pocket Guard's server. */
    fun liveLocation(context: Context) = get(context, "live_location", true)
    fun setLiveLocation(context: Context, value: Boolean) = put(context, "live_location", value)

    fun onboarded(context: Context) = get(context, "onboarded", false)

    /** Steps the app can't check by itself, marked done once the user opened their settings. */
    fun autostartOpened(context: Context) = get(context, "autostart_opened", false)
    fun setAutostartOpened(context: Context) = put(context, "autostart_opened", true)
    fun lockDelayOpened(context: Context) = get(context, "lock_delay_opened", false)
    fun setLockDelayOpened(context: Context) = put(context, "lock_delay_opened", true)
    fun setOnboarded(context: Context, value: Boolean) = put(context, "onboarded", value)

    /** Bluetooth addresses that count for earbud triggers. Empty means any earbuds, headphones or watch. */
    fun trustedDevices(context: Context): Set<String> =
        prefs(context).getStringSet("trusted_devices", emptySet()).orEmpty().toSet()
    fun setTrustedDevices(context: Context, value: Set<String>) =
        prefs(context).edit().putStringSet("trusted_devices", value).apply()
}
