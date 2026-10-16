package com.taiyeb.pocketguard

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import java.text.DateFormat
import java.util.Date

/**
 * Collects evidence while an alarm runs: history entry, SMS with location to the emergency
 * contacts (then periodic updates), and intruder photos. Main thread only.
 */
class IncidentRecorder(
    private val context: Context,
    owner: LifecycleOwner,
    /** Called once the camera is free again, so the torch strobe can start without fighting it. */
    private val onCameraFree: () -> Unit,
) {
    companion object {
        private const val LOCATION_TIMEOUT_MS = 20_000L
        private const val UPDATE_INTERVAL_MS = 5 * 60_000L
        private const val MAX_UPDATES = 6
        private const val PHOTOS = 2
    }

    private enum class Alert { FIRST, UPDATE }

    private val handler = Handler(Looper.getMainLooper())
    private val camera = IntruderCamera(context, owner)
    private var current: Incident? = null
    private var updatesLeft = 0

    private val periodicUpdate = object : Runnable {
        override fun run() {
            val incident = current ?: return
            if (updatesLeft-- <= 0) return
            locateAndAlert(incident, Alert.UPDATE)
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    /** First warning: start a history entry, but don't alert anyone yet (the owner may unlock). */
    fun begin(trigger: Trigger) {
        if (current != null) return
        val now = System.currentTimeMillis()
        current = Incident(id = now, startedAt = now, trigger = trigger).also { History.save(context, it) }
    }

    /** Siren or fake shutdown: this is now treated as a theft. */
    fun escalate(trigger: Trigger, stealth: Boolean) {
        val incident = current ?: run {
            begin(trigger)
            current!!
        }
        if (incident.escalated) return
        incident.escalated = true
        incident.stealth = stealth
        History.save(context, incident)

        locateAndAlert(incident, Alert.FIRST)
        updatesLeft = MAX_UPDATES
        handler.postDelayed(periodicUpdate, UPDATE_INTERVAL_MS)

        if (Prefs.intruderPhotos(context)) {
            camera.capture(History.photosDir(context), "i${incident.id}", PHOTOS) { files ->
                incident.photos += files.map { it.name }
                History.save(context, incident)
                onCameraFree()
            }
        } else {
            onCameraFree()
        }
    }

    fun end(ownerStopped: Boolean) {
        val incident = current ?: return
        current = null
        handler.removeCallbacks(periodicUpdate)
        incident.endedAt = System.currentTimeMillis()
        incident.ownerStopped = ownerStopped
        History.save(context, incident)
        if (ownerStopped && incident.alertsSent > 0) {
            Sms.send(context, Prefs.contacts(context), message(R.string.sms_all_clear, ownerLabel()))
        }
    }

    private fun locateAndAlert(incident: Incident, alert: Alert) {
        var done = false
        val finish: (Location?) -> Unit = { location ->
            if (!done) {
                done = true
                location?.let {
                    incident.locations += Fix(it.latitude, it.longitude, it.accuracy, it.time)
                }
                // The owner may have unlocked while we waited for a fix: don't alarm the contacts then.
                if (incident.endedAt == null) {
                    incident.alertsSent += Sms.send(context, Prefs.contacts(context), alertText(incident, alert, location))
                }
                History.save(context, incident)
            }
        }
        handler.postDelayed({ finish(null) }, LOCATION_TIMEOUT_MS)
        Locator.current(context) { finish(it) }
    }

    private fun alertText(incident: Incident, alert: Alert, location: Location?): String {
        val where = if (location != null) {
            message(
                R.string.sms_location,
                "https://maps.google.com/?q=${location.latitude},${location.longitude}",
                location.accuracy.toInt(),
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(location.time)),
            )
        } else {
            message(R.string.sms_no_location)
        }
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val body = when (alert) {
            Alert.FIRST -> message(
                R.string.sms_alert, ownerLabel(), context.getString(incident.trigger.labelRes()), where, battery,
            )
            Alert.UPDATE -> message(R.string.sms_update, ownerLabel(), where, battery)
        }
        return if (Prefs.testMode(context)) message(R.string.sms_test_prefix) + " " + body else body
    }

    private fun ownerLabel() = Prefs.ownerName(context).ifBlank { context.getString(R.string.sms_owner_default) }

    private fun message(id: Int, vararg args: Any) = context.getString(id, *args)
}
