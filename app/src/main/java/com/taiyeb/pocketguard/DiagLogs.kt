package com.taiyeb.pocketguard

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Send detection logs" (Settings, off by default): after each alarm, when the guard is turned off,
 * and on request, the [DetectionLog] lines not sent yet go to Pocket Guard's server with the phone
 * model, Android version and sensor details, so false alarms and missed thefts on real phones can
 * be studied. The log holds what the guard saw and decided, never a location, contact or message.
 */
object DiagLogs {
    /** The server takes up to 40 000 characters per upload; the oldest lines go first. */
    private const val MAX_CHARS = 39_000

    fun on(context: Context) = Prefs.sendLogs(context) && Backend.configured(context)

    /** Uploads what's new, on a background thread; [reason]: alarm, disarm, manual or label. */
    fun send(context: Context, reason: String, onDone: ((Boolean) -> Unit)? = null) {
        if (!on(context)) return
        val app = context.applicationContext
        Thread { onDone?.invoke(upload(app, reason)) }.start()
    }

    @Synchronized
    private fun upload(context: Context, reason: String): Boolean {
        val since = Prefs.diagSentAt(context)
        val entries = DetectionLog.snapshot().asReversed().filter { it.at > since }
        if (entries.isEmpty()) return true
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val text = entries.joinToString("\n") { format.format(Date(it.at)) + "  " + it.text }.takeLast(MAX_CHARS)
        val code = Backend.addDiagLog(context, reason, BuildConfig.VERSION_NAME, device(context), text)
        // Sent, or refused for good (today's cap, storage full): either way don't send these again.
        val done = code in 200..299 || code in 400..499 && code != 401 && code != 408 && code != 429
        if (done) Prefs.setDiagSentAt(context, entries.last().at)
        return code in 200..299
    }

    /** The phone and its setup: what decides how the sensors and the lock behave. */
    private fun device(context: Context): JSONObject {
        val sensors = context.getSystemService(SensorManager::class.java)
        fun sensor(type: Int) = sensors?.getDefaultSensor(type)?.let {
            JSONObject()
                .put("name", it.name)
                .put("vendor", it.vendor)
                .put("wake_up", it.isWakeUpSensor)
                .put("max_range", it.maximumRange.toDouble())
                .put("min_delay_us", it.minDelay)
        }
        val power = context.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("build", if (BuildConfig.PLAY_STORE) "play" else "full")
            .put("language", Locale.getDefault().toLanguageTag())
            .put("utc_offset_min", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
            .put("proximity", sensor(Sensor.TYPE_PROXIMITY))
            .put("light", sensor(Sensor.TYPE_LIGHT))
            .put("accelerometer", sensor(Sensor.TYPE_ACCELEROMETER))
            .put("secure_lock", context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure)
            .put("battery_unrestricted", power?.isIgnoringBatteryOptimizations(context.packageName))
            .put("key_watcher", SosAccessibilityService.connected)
            .put("theft_guard", Prefs.theftGuard(context))
            .put("table_guard", Prefs.tableGuard(context))
            .put("sos_keys", Prefs.sosButtons(context))
            .put("delay_s", Prefs.graceSeconds(context))
            .put("test_mode", Prefs.testMode(context))
    }
}
