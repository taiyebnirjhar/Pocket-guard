package com.taiyeb.pocketguard

import android.content.Context

object Prefs {
    private fun prefs(context: Context) =
        context.getSharedPreferences("pocket_guard", Context.MODE_PRIVATE)

    private fun get(context: Context, key: String, default: Boolean) = prefs(context).getBoolean(key, default)
    private fun put(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    fun graceSeconds(context: Context) = prefs(context).getInt("grace_seconds", 3)
    fun setGraceSeconds(context: Context, value: Int) =
        prefs(context).edit().putInt("grace_seconds", value).apply()

    /** Test mode plays the siren quietly so it can be tried at home. On by default. */
    fun testMode(context: Context) = get(context, "test_mode", true)
    fun setTestMode(context: Context, value: Boolean) = put(context, "test_mode", value)

    fun setArmed(context: Context, value: Boolean) = put(context, "armed", value)

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
}
