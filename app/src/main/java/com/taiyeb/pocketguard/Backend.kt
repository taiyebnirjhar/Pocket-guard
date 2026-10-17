package com.taiyeb.pocketguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pocket Guard's online part (Supabase): an anonymous account per install, incidents with their
 * live location, and the alert emails. The project and its public key are built in by CI; builds
 * without them (forks, local builds) simply have no online features.
 *
 * Every call blocks: run them off the main thread.
 */
object Backend {
    private const val PREFS = "backend"
    private const val TIMEOUT_MS = 10_000
    private const val UPLOAD_TIMEOUT_MS = 120_000

    data class Incident(val id: String, val viewerToken: String)
    data class Fix(val lat: Double, val lng: Double, val accuracy: Float?, val time: Long)

    private class Reply(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    // Device-protected, like the rest of the settings: SOS works before the first unlock.
    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The end-to-end test points a debug build at a local backend through these settings. */
    private fun url(context: Context) =
        (prefs(context).getString("url_override", null) ?: BuildConfig.SUPABASE_URL).trimEnd('/')
    private fun key(context: Context) = prefs(context).getString("key_override", null) ?: BuildConfig.SUPABASE_KEY

    fun configured(context: Context) = url(context).isNotEmpty() && key(context).isNotEmpty()

    /** Online features are on: built in, and the owner hasn't switched them off. */
    fun enabled(context: Context) = configured(context) && Prefs.liveLocation(context)

    fun liveLink(context: Context, viewerToken: String): String {
        val page = prefs(context).getString("page_override", null) ?: BuildConfig.LIVE_PAGE_URL
        return "$page#t=$viewerToken"
    }

    private fun call(context: Context, method: String, path: String, body: Any?, token: String?, prefer: String? = null): Reply {
        val conn = URL(url(context) + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("apikey", key(context))
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (prefer != null) conn.setRequestProperty("Prefer", prefer)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Reply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            Reply(-1, e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    // --- Sign-in: anonymous, made on first use and refreshed as needed -------------------------

    @Synchronized
    private fun token(context: Context): String? {
        val p = prefs(context)
        val access = p.getString("access_token", null)
        if (access != null && System.currentTimeMillis() < p.getLong("expires_at", 0) - 60_000) return access
        val refresh = p.getString("refresh_token", null)
        if (refresh != null) {
            val r = call(context, "POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh), null)
            if (r.ok) return saveSession(context, r.body)
            // Only a refresh token the server no longer knows means a new account; anything else
            // (offline, rate limited, server trouble) keeps it for the next try.
            if (r.code != 400 || !r.body.contains("refresh_token_not_found") && !r.body.contains("Invalid Refresh Token")) return null
            forgetSession(context)
        }
        val r = call(context, "POST", "/auth/v1/signup", JSONObject(), null)
        return if (r.ok) saveSession(context, r.body) else null
    }

    private fun forgetSession(context: Context) {
        prefs(context).edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }

    /** Signs in ahead of need (when contacts are approved), so an SOS doesn't wait on it. */
    fun signIn(context: Context): Boolean = configured(context) && token(context) != null

    private fun saveSession(context: Context, body: String): String? = try {
        val s = JSONObject(body)
        val access = s.getString("access_token")
        prefs(context).edit()
            .putString("access_token", access)
            .putString("refresh_token", s.getString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() + s.optLong("expires_in", 3600) * 1000)
            .apply()
        access
    } catch (e: Exception) {
        null
    }

    fun signedIn(context: Context) = prefs(context).getString("refresh_token", null) != null

    // --- Incidents ------------------------------------------------------------------------------

    fun openIncident(context: Context, ownerName: String, battery: Int?): Incident? {
        val token = token(context) ?: return null
        val body = JSONObject().put("owner_name", ownerName.take(60))
        if (battery != null) body.put("battery", battery)
        val r = call(context, "POST", "/rest/v1/incidents?select=id,viewer_token", body, token, "return=representation")
        if (!r.ok) return null
        return try {
            val row = JSONArray(r.body).getJSONObject(0)
            Incident(row.getString("id"), row.getString("viewer_token"))
        } catch (e: Exception) {
            null
        }
    }

    /** The reply's HTTP code (-1: no connection or no sign-in). */
    fun addLocations(context: Context, incidentId: String, fixes: List<Fix>): Int {
        if (fixes.isEmpty()) return 204
        val token = token(context) ?: return -1
        val rows = JSONArray()
        fixes.forEach { f ->
            rows.put(
                JSONObject().put("incident_id", incidentId).put("lat", f.lat).put("lng", f.lng)
                    .put("accuracy", f.accuracy?.toDouble() ?: JSONObject.NULL)
                    .put("recorded_at", iso(f.time)),
            )
        }
        return call(context, "POST", "/rest/v1/locations", rows, token, "return=minimal").code
    }

    fun updateIncident(context: Context, incidentId: String, status: String?, battery: Int?): Boolean {
        val token = token(context) ?: return false
        val body = JSONObject()
        if (status != null) body.put("status", status).put("ended_at", iso(System.currentTimeMillis()))
        if (battery != null) body.put("battery", battery)
        if (body.length() == 0) return true
        return call(context, "PATCH", "/rest/v1/incidents?id=eq.$incidentId", body, token, "return=minimal").ok
    }

    // --- Recordings ----------------------------------------------------------------------------

    /** Uploads one SOS video piece through a link signed for it: true when it's stored or refused for good. */
    fun uploadVideo(context: Context, incidentId: String, file: File): Boolean {
        val token = token(context) ?: return false
        val bytes = file.length()
        if (bytes <= 0) return true
        val ask = call(
            context,
            "POST",
            "/functions/v1/media",
            JSONObject().put("action", "upload").put("incident_id", incidentId).put("kind", "video")
                .put("bytes", bytes).put("content_type", "video/mp4"),
            token,
        )
        // Refused for good (bad, too big, incident closed, store full); 401 and 429 may pass later.
        if (!ask.ok) return ask.code in setOf(400, 403, 404, 413, 507)
        val (mediaId, url) = try {
            val o = JSONObject(ask.body)
            o.getLong("media_id") to o.getString("url")
        } catch (e: Exception) {
            return false
        }
        if (!put(url, file)) return false
        return call(context, "POST", "/functions/v1/media", JSONObject().put("action", "done").put("media_id", mediaId), token).ok
    }

    private fun put(url: String, file: File): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = UPLOAD_TIMEOUT_MS
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(file.length())
            conn.setRequestProperty("Content-Type", "video/mp4") // Signed into the link, with the size.
            file.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    // --- Email and account ---------------------------------------------------------------------

    enum class EmailKind(val wire: String) { SOS("sos"), FALSE_ALARM("false_alarm"), TEST("test") }

    /** Sends the alert email: true when it went out, had gone out, or was refused for good (a limit). */
    fun sendEmail(context: Context, kind: EmailKind, emails: List<String>, incidentId: String?, name: String, time: String): Boolean {
        if (emails.isEmpty()) return true
        val token = token(context) ?: return false
        val body = JSONObject()
            .put("kind", kind.wire)
            .put("emails", JSONArray(emails))
            .put("name", name)
            .put("time", time)
            .put("lang", if (context.resources.configuration.locales[0].language == "bn") "bn" else "en")
        if (incidentId != null) body.put("incident_id", incidentId)
        val r = call(context, "POST", "/functions/v1/notify", body, token)
        return r.ok || r.code in 400..499 && r.code != 401 && r.code != 408
    }

    /** Deletes the account and everything stored with it; false when the server couldn't be reached. */
    fun deleteAccount(context: Context): Boolean {
        if (!signedIn(context)) return true
        val token = token(context) ?: return false
        val r = call(context, "POST", "/functions/v1/delete-account", JSONObject(), token)
        // 401: the account no longer exists on the server.
        if (r.ok || r.code == 401) {
            forgetSession(context)
            return true
        }
        return false
    }

    private fun iso(time: Long): String =
        java.time.Instant.ofEpochMilli(time).toString()
}
