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
    fun setSosButtons(context: Context, value: Boolean) = put(context, "sos_buttons", value)

    /** Guard a phone lying face-down on a table, not only one in a pocket. */
    fun tableGuard(context: Context) = get(context, "table_guard", true)
    fun setTableGuard(context: Context, value: Boolean) = put(context, "table_guard", value)
    fun setIntruderPhotos(context: Context, value: Boolean) = put(context, "intruder_photos", value)

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
