package com.taiyeb.pocketguard

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import java.text.DateFormat
import java.util.Date

/**
 * Collects evidence while an alarm runs: a history entry, SMS to the emergency contacts (the first
 * one immediately with the last known location, then fresh fixes), and intruder photos.
 * Main thread only.
 */
class IncidentRecorder(
    private val context: Context,
    owner: LifecycleOwner,
    private val sms: SmsSender,
    /** Called once the camera is free again, so the torch strobe can start without fighting it. */
    private val onCameraFree: () -> Unit,
) {
    enum class Outcome { OWNER_STOPPED, FLICKER }

    companion object {
        private const val LAST_FIX_TIMEOUT_MS = 3_000L
        private const val FRESH_FIX_TIMEOUT_MS = 30_000L
        private const val UPDATE_INTERVAL_MS = 5 * 60_000L
        private const val MAX_UPDATES = 6
        private const val PHOTOS = 2
        private const val RECENT_FIX_MS = 2 * 60_000L
    }

    private enum class Alert { FIRST, UPDATE }

    private val handler = Handler(Looper.getMainLooper())
    private val camera = IntruderCamera(context, owner)
    private var current: Incident? = null
    private var updatesLeft = 0
    private var alertsAllowed = false

    private val periodicUpdate = object : Runnable {
        override fun run() {
            val incident = current ?: return
            if (updatesLeft-- <= 0) return
            freshFix { location -> alert(incident, Alert.UPDATE, location) }
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

        alertsAllowed = Prefs.contacts(context).isNotEmpty() && Prefs.allowAlert(context)
        // Alert right away with whatever location is known: a thief may force-restart within seconds.
        lastFix { last ->
            alert(incident, Alert.FIRST, last)
            freshFix { fresh ->
                if (fresh != null && (last == null || fresh.time > last.time)) alert(incident, Alert.UPDATE, fresh)
            }
        }
        updatesLeft = MAX_UPDATES
        handler.postDelayed(periodicUpdate, UPDATE_INTERVAL_MS)

        // Photos switch on the camera privacy dot, which would give the fake shutdown away; and
        // before the first unlock after a restart, app storage is locked, so there's nowhere to save.
        val storageUnlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        if (Prefs.intruderPhotos(context) && !stealth && storageUnlocked) {
            camera.capture(History.photosDir(context), "i${incident.id}", PHOTOS) { files ->
                Log.i(GuardService.TAG, "intruder photos saved: ${files.size}")
                incident.photos += files.map { it.name }
                History.save(context, incident)
                onCameraFree()
            }
        } else {
            onCameraFree()
        }
    }

    fun end(outcome: Outcome) {
        val incident = current ?: return
        current = null
        handler.removeCallbacks(periodicUpdate)
        sms.cancel(incident.id)
        incident.endedAt = System.currentTimeMillis()
        incident.ownerStopped = outcome == Outcome.OWNER_STOPPED
        incident.flicker = outcome == Outcome.FLICKER
        History.save(context, incident)
        // Only if an alert actually reached someone; never "ignore the alert" for one that never arrived.
        if (incident.ownerStopped && incident.alertsSent > 0) {
            sms.send(incident.id, Prefs.contacts(context), message(R.string.sms_all_clear, ownerLabel())) {}
        }
    }

    /** Called when the radio may be back (airplane mode off), to resend failed alerts now. */
    fun retryAlerts() = sms.retryNow()

    private fun alert(incident: Incident, alert: Alert, location: Location?) {
        Log.i(GuardService.TAG, "alert $alert location=${location?.let { "${it.latitude},${it.longitude}" }}")
        location?.let {
            incident.locations += Fix(it.latitude, it.longitude, it.accuracy, it.time)
            History.save(context, incident)
        }
        // The owner may have unlocked while we waited for a fix: don't alarm the contacts then.
        if (incident.endedAt != null || current !== incident || !alertsAllowed) return
        sms.send(incident.id, Prefs.contacts(context), alertText(incident, alert, location)) {
            incident.alertsSent++
            History.save(context, incident)
        }
    }

    private fun lastFix(onResult: (Location?) -> Unit) = withTimeout(LAST_FIX_TIMEOUT_MS, onResult) {
        Locator.last(context, it)
    }

    private fun freshFix(onResult: (Location?) -> Unit) = withTimeout(FRESH_FIX_TIMEOUT_MS, onResult) {
        Locator.current(context, it)
    }

    /** Runs [request] and delivers its result, or null after [timeoutMs], exactly once. */
    private fun withTimeout(timeoutMs: Long, onResult: (Location?) -> Unit, request: ((Location?) -> Unit) -> Unit) {
        var done = false
        val deliver: (Location?) -> Unit = { location ->
            if (!done) {
                done = true
                onResult(location)
            }
        }
        handler.postDelayed({ deliver(null) }, timeoutMs)
        request(deliver)
    }

    private fun alertText(incident: Incident, alert: Alert, location: Location?): String {
        val where = if (location != null) {
            val age = System.currentTimeMillis() - location.time
            val time = when {
                age < RECENT_FIX_MS -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(location.time))
                age < 60 * 60_000L -> message(R.string.sms_age_minutes, (age / 60_000L).toInt())
                else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(location.time))
            }
            message(
                R.string.sms_location,
                "https://maps.google.com/?q=${location.latitude},${location.longitude}",
                location.accuracy.toInt(),
                time,
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
