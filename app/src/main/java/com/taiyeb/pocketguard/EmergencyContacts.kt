package com.taiyeb.pocketguard

import android.content.Context
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
        "$TEXT_VERSION|${if (Backend.enabled(context)) "live" else "sms"}|${ownerName(context)}|" +
            list(context).joinToString(";") { c ->
                "${c.name}=${normalize(c.phone)}" + if (c.email.isNotBlank()) "," + c.email.trim().lowercase() else ""
            }

    fun approved(context: Context) = list(context).isNotEmpty() && ownerName(context).isNotEmpty() &&
        prefs(context).getString(KEY_APPROVED, null) == fingerprint(context)

    fun approve(context: Context) = prefs(context).edit().putString(KEY_APPROVED, fingerprint(context)).apply()

    /** The numbers to text: contacts without one get email only. */
    fun numbers(context: Context) = list(context).map { normalize(it.phone) }.filter { it.count(Char::isDigit) >= 3 }

    fun emails(context: Context) = list(context).map { it.email.trim().lowercase() }.filter { looksLikeEmail(it) }.distinct()

    fun looksLikeEmail(text: String) = Regex("^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}$").matches(text.trim())

    /** Digits and a leading +, as SMS apps want them. */
    fun normalize(phone: String) = phone.trim().let { p ->
        (if (p.startsWith("+")) "+" else "") + p.filter { it.isDigit() }
    }

    // --- The messages ----------------------------------------------------------------------------

    fun sosText(context: Context, time: String, lat: Double?, lng: Double?, accuracy: Float?, live: String? = null): String {
        val name = ownerName(context)
        val text = if (lat != null && lng != null) {
            context.getString(R.string.alert_text_sos, name, time, AlertPlan.mapLink(lat, lng), (accuracy ?: 0f).toInt())
        } else {
            context.getString(R.string.alert_text_no_location, name, time)
        }
        return if (live != null) text + "\n" + liveText(context, live) else text
    }

    fun liveText(context: Context, link: String) = context.getString(R.string.alert_text_live, link)

    fun updateText(context: Context, time: String, lat: Double, lng: Double) =
        context.getString(R.string.alert_text_update, ownerName(context), time, AlertPlan.mapLink(lat, lng))

    fun falseAlarmText(context: Context) = context.getString(R.string.alert_text_false, ownerName(context))

    /** What the approval screen shows: the alert as it would read now, without a real location. */
    fun previewText(context: Context): String {
        val text = context.getString(R.string.alert_text_sos, ownerName(context).ifEmpty { "…" }, "14:05", "https://maps.google.com/?q=…", 10)
        return if (Backend.enabled(context)) text + "\n" + liveText(context, Backend.liveLink(context, "…")) else text
    }
}
