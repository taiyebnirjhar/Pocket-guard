package com.taiyeb.pocketguard

import android.content.Context

object Prefs {
    private fun prefs(context: Context) =
        context.getSharedPreferences("pocket_guard", Context.MODE_PRIVATE)

    fun graceSeconds(context: Context) = prefs(context).getInt("grace_seconds", 3)
    fun setGraceSeconds(context: Context, value: Int) =
        prefs(context).edit().putInt("grace_seconds", value).apply()

    /** Test mode plays the siren quietly so it can be tried at home. On by default. */
    fun testMode(context: Context) = prefs(context).getBoolean("test_mode", true)
    fun setTestMode(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("test_mode", value).apply()

    fun setArmed(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("armed", value).apply()
}
