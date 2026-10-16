package com.taiyeb.pocketguard

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/** Per-app language (Android 13+). Older versions follow the phone's language. */
object Language {
    val supported get() = Build.VERSION.SDK_INT >= 33

    /** "en", "bn" or null for the phone's language. */
    fun current(context: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else locales[0].language
    }

    fun set(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
