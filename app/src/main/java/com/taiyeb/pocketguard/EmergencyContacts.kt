package com.taiyeb.pocketguard

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Someone to alert when SOS starts: by SMS when there's a number, by email when there's an address. */
data class EmergencyContact(val name: String, val phone: String, val email: String = "")

/**
 * The people SOS alerts and the words they get, kept on the phone. Alerts go out only once the
 * owner has seen this exact message and list and approved it (Play: no messages on the user's
 * behalf without confirming content and recipients); changing either asks again.
 */
object EmergencyContacts {
    const val MAX = 5

    private const val PREFS = "emergency_contacts"
    private const val KEY_LIST = "contacts"
    private const val KEY_NAME = "name"
    private const val KEY_APPROVED = "approved"

    /** Bump when the alert wording changes, so the owner approves the new text. */
    private const val TEXT_VERSION = 3

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<EmergencyContact> = try {
        val array = JSONArray(prefs(context).getString(KEY_LIST, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            EmergencyContact(o.getString("name"), o.optString("phone"), o.optString("email"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(context: Context, contacts: List<EmergencyContact>) {
        val array = JSONArray()
        contacts.take(MAX).forEach {
            array.put(JSONObject().put("name", it.name).put("phone", it.phone).put("email", it.email.trim()))
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** How the owner is named in alerts ("Taiyeb needs help!"). */
    fun ownerName(context: Context) = prefs(context).getString(KEY_NAME, "").orEmpty()

    fun setOwnerName(context: Context, name: String) = prefs(context).edit().putString(KEY_NAME, name.trim()).apply()

    /**
     * Everything the approval covers: the wording (and whether it carries the live link), the
     * name, and the people with their numbers and addresses.
     */
    private fun fingerprint(context: Context) =
        // The words themselves too (they follow the app's language), so a new language asks again.
        // Emails off is marked only then, so approvals made before that switch existed still hold.
        "$TEXT_VERSION|${if (Backend.enabled(context)) "live" else "sms"}" +
            (if (Backend.enabled(context) && !Backend.emailOn(context)) "-nomail" else "") +
            "|${wording(context).hashCode()}|" +
            "${ownerName(context)}|" +
            list(context).joinToString(";") { c ->
                "${c.name}=${normalize(c.phone)}" + if (c.email.isNotBlank()) "," + c.email.trim().lowercase() else ""
            }

    private fun wording(context: Context) = listOf(
        R.string.alert_text_sos, R.string.alert_text_no_location, R.string.alert_text_update,
        R.string.alert_text_false, R.string.alert_text_live,
    ).joinToString("|") { sms(context, it) }

    fun approved(context: Context) = list(context).any { reachable(context, it) } && ownerName(context).isNotEmpty() &&
        prefs(context).getString(KEY_APPROVED, null) == fingerprint(context)

    /** Approved once, but something it covers has changed since: alerts are off until approved again. */
    fun needsReapproval(context: Context) = prefs(context).getString(KEY_APPROVED, null) != null && !approved(context)

    fun approve(context: Context) {
        // Pinned first: the emails' default follows whether anything was approved yet.
        Prefs.setEmailAlerts(context, Prefs.emailAlerts(context))
        prefs(context).edit().putString(KEY_APPROVED, fingerprint(context)).apply()
    }

    /** Some list was approved at some point (even if it needs approving again now). */
    fun everApproved(context: Context) = prefs(context).getString(KEY_APPROVED, null) != null

    /** Shortest number an SMS can go to (local numbers included); shorter ones are typos. */
    const val MIN_DIGITS = 6

    fun canText(phone: String) = normalize(phone).count(Char::isDigit) >= MIN_DIGITS

    /** Someone an alert reaches: a number to text, or an address while the online part is on. */
    fun reachable(context: Context, contact: EmergencyContact) =
        canText(contact.phone) || Backend.emailOn(context) && looksLikeEmail(contact.email)

    /** The numbers to text: contacts without one get email only. */
    fun numbers(context: Context) = list(context).filter { canText(it.phone) }.map { normalize(it.phone) }

    fun emails(context: Context) = list(context).map { it.email.trim().lowercase() }.filter { looksLikeEmail(it) }.distinct()

    fun looksLikeEmail(text: String) = Regex("^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}$").matches(text.trim())

    /** Digits and a leading +, as SMS apps want them. */
    fun normalize(phone: String) = phone.trim().let { p ->
        (if (p.startsWith("+")) "+" else "") + p.filter { it.isDigit() }
    }

    // --- The messages ----------------------------------------------------------------------------

    /**
     * The SMS words: English, or Bangla in English letters (Banglish), whatever language the app
     * shows. One Bangla letter makes the whole SMS Unicode, which holds 70 characters a part
     * instead of 160: an alert with its two links would be 4 SMS instead of 2.
     */
    enum class SmsLanguage { ENGLISH, BANGLISH }

    private const val KEY_SMS_LANGUAGE = "sms_language"

    fun smsLanguage(context: Context) =
        if (prefs(context).getString(KEY_SMS_LANGUAGE, null) == "banglish") SmsLanguage.BANGLISH else SmsLanguage.ENGLISH

    fun setSmsLanguage(context: Context, language: SmsLanguage) = prefs(context).edit()
        .putString(KEY_SMS_LANGUAGE, if (language == SmsLanguage.BANGLISH) "banglish" else "en").apply()

    private val banglish = mapOf(
        R.string.alert_text_sos to R.string.alert_text_sos_banglish,
        R.string.alert_text_no_location to R.string.alert_text_no_location_banglish,
        R.string.alert_text_update to R.string.alert_text_update_banglish,
        R.string.alert_text_false to R.string.alert_text_false_banglish,
        R.string.alert_text_test to R.string.alert_text_test_banglish,
        R.string.alert_text_live to R.string.alert_text_live_banglish,
    )

    /** An SMS text, from resources read in English so numbers stay in English digits too. */
    fun sms(context: Context, id: Int, vararg args: Any): String {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val english = context.createConfigurationContext(config)
        val chosen = if (smsLanguage(context) == SmsLanguage.BANGLISH) banglish[id] ?: id else id
        // Without arguments: the template itself (the approval's fingerprint), not formatted.
        return if (args.isEmpty()) english.getString(chosen) else english.getString(chosen, *args)
    }

    /**
     * The time an SMS says, in English digits. Plain spaces only: Android 14+ puts a narrow
     * no-break space before AM/PM, which isn't in the SMS alphabet and would double the parts.
     */
    fun smsTime(time: java.util.Date = java.util.Date()): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.ENGLISH).format(time)
            .replace('\u202F', ' ').replace('\u00A0', ' ')

    fun sosText(context: Context, time: String, lat: Double?, lng: Double?, accuracy: Float?, live: String? = null): String {
        val name = ownerName(context)
        val text = if (lat != null && lng != null) {
            sms(context, R.string.alert_text_sos, name, time, AlertPlan.mapLink(lat, lng), (accuracy ?: 0f).toInt())
        } else {
            sms(context, R.string.alert_text_no_location, name, time)
        }
        return if (live != null) text + "\n" + liveText(context, live) else text
    }

    fun liveText(context: Context, link: String) = sms(context, R.string.alert_text_live, link)

    fun updateText(context: Context, time: String, lat: Double, lng: Double) =
        sms(context, R.string.alert_text_update, ownerName(context), time, AlertPlan.mapLink(lat, lng))

    fun falseAlarmText(context: Context) = sms(context, R.string.alert_text_false, ownerName(context))

    fun previewUpdateText(context: Context) =
        sms(context, R.string.alert_text_update, ownerName(context).ifEmpty { "…" }, "2:07 PM", "https://maps.google.com/?q=…")

    /** What the approval screen shows: the alert as it would read now, without a real location. */
    fun previewText(context: Context): String {
        val text = sms(context, R.string.alert_text_sos, ownerName(context).ifEmpty { "…" }, "2:05 PM", "https://maps.google.com/?q=…", 10)
        return if (Backend.enabled(context)) text + "\n" + liveText(context, Backend.liveLink(context, "…")) else text
    }
}
