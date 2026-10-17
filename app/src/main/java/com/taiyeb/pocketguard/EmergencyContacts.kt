package com.taiyeb.pocketguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Someone to alert when SOS starts. */
data class EmergencyContact(val name: String, val phone: String)

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
    private const val TEXT_VERSION = 1

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<EmergencyContact> = try {
        val array = JSONArray(prefs(context).getString(KEY_LIST, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            EmergencyContact(o.getString("name"), o.getString("phone"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(context: Context, contacts: List<EmergencyContact>) {
        val array = JSONArray()
        contacts.take(MAX).forEach { array.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** How the owner is named in alerts ("Taiyeb needs help!"). */
    fun ownerName(context: Context) = prefs(context).getString(KEY_NAME, "").orEmpty()

    fun setOwnerName(context: Context, name: String) = prefs(context).edit().putString(KEY_NAME, name.trim()).apply()

    /** Everything the approval covers: the people, the name and the wording. */
    private fun fingerprint(context: Context) =
        "$TEXT_VERSION|${ownerName(context)}|" + list(context).joinToString(";") { "${it.name}=${normalize(it.phone)}" }

    fun approved(context: Context) = list(context).isNotEmpty() && ownerName(context).isNotEmpty() &&
        prefs(context).getString(KEY_APPROVED, null) == fingerprint(context)

    fun approve(context: Context) = prefs(context).edit().putString(KEY_APPROVED, fingerprint(context)).apply()

    /** Digits and a leading +, as SMS apps want them. */
    fun normalize(phone: String) = phone.trim().let { p ->
        (if (p.startsWith("+")) "+" else "") + p.filter { it.isDigit() }
    }

    // --- The messages ----------------------------------------------------------------------------

    fun sosText(context: Context, time: String, lat: Double?, lng: Double?, accuracy: Float?): String {
        val name = ownerName(context)
        return if (lat != null && lng != null) {
            context.getString(R.string.alert_text_sos, name, time, AlertPlan.mapLink(lat, lng), (accuracy ?: 0f).toInt())
        } else {
            context.getString(R.string.alert_text_no_location, name, time)
        }
    }

    fun updateText(context: Context, time: String, lat: Double, lng: Double) =
        context.getString(R.string.alert_text_update, ownerName(context), time, AlertPlan.mapLink(lat, lng))

    fun falseAlarmText(context: Context) = context.getString(R.string.alert_text_false, ownerName(context))

    /** What the approval screen shows: the alert as it would read now, without a real location. */
    fun previewText(context: Context) =
        context.getString(R.string.alert_text_sos, ownerName(context).ifEmpty { "…" }, "14:05", "https://maps.google.com/?q=…", 10)
}
