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
    private const val ALERT_WINDOW_MS = 60 * 60_000L
    private const val MAX_ALERTS_PER_WINDOW = 3

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

    /** Caps theft alerts at 3 an hour so false alarms can't spam the contacts. Records the alert if allowed. */
    fun allowAlert(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val recent = prefs(context).getString("alert_times", "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < ALERT_WINDOW_MS }
        if (recent.size >= MAX_ALERTS_PER_WINDOW) return false
        prefs(context).edit().putString("alert_times", (recent + now).joinToString(",")).apply()
        return true
    }

    /** Siren the moment the power menu or airplane mode is used while the phone is locked. */
    fun tamperAlarm(context: Context) = get(context, "tamper_alarm", true)
    fun setTamperAlarm(context: Context, value: Boolean) = put(context, "tamper_alarm", value)

    fun chargerAlarm(context: Context) = get(context, "charger_alarm", true)
    fun setChargerAlarm(context: Context, value: Boolean) = put(context, "charger_alarm", value)

    fun earbudsAlarm(context: Context) = get(context, "earbuds_alarm", false)
    fun setEarbudsAlarm(context: Context, value: Boolean) = put(context, "earbuds_alarm", value)

    fun autoArmEarbuds(context: Context) = get(context, "auto_arm_earbuds", false)
    fun setAutoArmEarbuds(context: Context, value: Boolean) = put(context, "auto_arm_earbuds", value)

    fun autoArmTransit(context: Context) = get(context, "auto_arm_transit", false)
    fun setAutoArmTransit(context: Context, value: Boolean) = put(context, "auto_arm_transit", value)

    fun fakeShutdown(context: Context) = get(context, "fake_shutdown", false)
    fun setFakeShutdown(context: Context, value: Boolean) = put(context, "fake_shutdown", value)

    fun intruderPhotos(context: Context) = get(context, "intruder_photos", true)
    fun setIntruderPhotos(context: Context, value: Boolean) = put(context, "intruder_photos", value)

    fun onboarded(context: Context) = get(context, "onboarded", false)
    fun setOnboarded(context: Context, value: Boolean) = put(context, "onboarded", value)

    /** Raw text as typed; [contacts] parses it. */
    fun contactsText(context: Context) = prefs(context).getString("contacts", "").orEmpty()
    fun setContactsText(context: Context, value: String) =
        prefs(context).edit().putString("contacts", value).apply()

    /** Up to 3 numbers, separated by commas, semicolons or new lines. */
    fun contacts(context: Context): List<String> = contactsText(context)
        .split(',', ';', '\n')
        .map { number -> number.filter { it.isDigit() || it == '+' } }
        .filter { it.length >= 3 }
        .distinct()
        .take(3)

    fun ownerName(context: Context) = prefs(context).getString("owner_name", "").orEmpty()
    fun setOwnerName(context: Context, value: String) =
        prefs(context).edit().putString("owner_name", value).apply()

    /** Bluetooth addresses that count for earbud triggers. Empty means any earbuds, headphones or watch. */
    fun trustedDevices(context: Context): Set<String> =
        prefs(context).getStringSet("trusted_devices", emptySet()).orEmpty().toSet()
    fun setTrustedDevices(context: Context, value: Set<String>) =
        prefs(context).edit().putStringSet("trusted_devices", value).apply()
}
