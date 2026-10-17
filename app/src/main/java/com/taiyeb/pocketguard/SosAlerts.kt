package com.taiyeb.pocketguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.DateFormat
import java.util.Date

/**
 * Alerts the emergency contacts when SOS starts, following [AlertPlan]: after the countdown an SMS
 * with the location, updates as the phone moves, and "false alarm" when stopped right away.
 * Location is read only while SOS runs, through the guard's foreground service. With the online
 * part on, the SMS also carries a live location link and contacts with an address get an email
 * ([LiveShare]).
 */
class SosAlerts(private val context: Context, private val log: (String) -> Unit) {

    companion object {
        private const val TICK_MS = 250L
        private const val UPDATE_INTERVAL_MS = 30_000L
        private const val FIX_TIMEOUT_MS = 10_000L

        fun canSendSms(context: Context) = !BuildConfig.PLAY_STORE &&
            context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        fun hasLocation(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** The SMS app with the message and recipients filled in; the owner presses Send. */
        fun smsIntent(numbers: List<String>, text: String): Intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(",")))
                .putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun whatsAppIntent(text: String): Intent =
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Only called after canSendSms(): granted, and never in the Play build, which doesn't declare it.
        @SuppressLint("MissingPermission")
        private fun sendSms(context: Context, number: String, text: String) {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        }

        /** "Send test" in Triggers: the real alert text marked as a test, to the approved people. */
        fun sendTest(context: Context): Boolean {
            val numbers = EmergencyContacts.numbers(context)
            val emails = EmergencyContacts.emails(context)
            if (numbers.isEmpty() && emails.isEmpty()) return false
            val text = context.getString(R.string.alert_text_test) + "\n" + EmergencyContacts.previewText(context)
            if (numbers.isNotEmpty()) {
                if (canSendSms(context)) {
                    numbers.forEach { runCatching { sendSms(context, it, text) } }
                } else {
                    runCatching { context.startActivity(smsIntent(numbers, text)) }
                }
            }
            if (emails.isNotEmpty() && Backend.enabled(context)) {
                val app = context.applicationContext
                val name = EmergencyContacts.ownerName(context)
                Thread { Backend.sendEmail(app, Backend.EmailKind.TEST, emails, null, name, "") }.start()
            }
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val location = LocationServices.getFusedLocationProviderClient(context)
    private var plan: AlertPlan? = null
    private var contacts = emptyList<EmergencyContact>()
    private var lastFix: Location? = null
    private var fixToken: CancellationTokenSource? = null
    private var live: LiveShare? = null

    /** The first SMS went out before the live link was ready; it follows on its own. */
    private var linkOwed = false

    /** The text for the next tap in the Play build, with the latest location. */
    var pendingText: String? = null
        private set

    /** The last alert text, sent or prepared (for "Share on WhatsApp"). */
    var lastText: String? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            val current = plan ?: return
            val now = SystemClock.elapsedRealtime()
            if (current.cancelled) return
            if (current.sentAt == null && current.countdownOver(now)) {
                sendFirst(current, now)
                return
            }
            publish(current, now)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val updates = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fix = result.lastLocation ?: return
            onFix(fix)
        }
    }

    /** SOS started. Does nothing until the owner has approved the alert and its recipients. */
    fun start() {
        if (plan != null) return
        if (!EmergencyContacts.approved(context)) {
            log("SOS alerts: off (no approved contacts)")
            return
        }
        contacts = EmergencyContacts.list(context)
        val now = SystemClock.elapsedRealtime()
        plan = AlertPlan(now)
        log("SOS alerts: ${contacts.size} contacts in ${AlertPlan.COUNTDOWN_MS / 1000} s unless cancelled")
        if (Backend.enabled(context)) {
            live = LiveShare(context, EmergencyContacts.ownerName(context), EmergencyContacts.emails(context), log)
                .also { it.start(::onLiveLink) }
        }
        startLocation()
        handler.post(tick)
    }

    /** The live page is ready: an SMS still waiting gets it; one already sent is followed up. */
    private fun onLiveLink(link: String) {
        val current = plan ?: return
        if (current.sentAt == null) return // The first SMS will carry it.
        val pending = pendingText
        if (pending != null && !canSendSms(context)) {
            pendingText = pending + "\n" + EmergencyContacts.liveText(context, link)
            lastText = pendingText
        } else if (linkOwed) {
            linkOwed = false
            send(EmergencyContacts.liveText(context, link))
        }
    }

    /** "Cancel alerts" on the alarm screen, during the countdown. */
    fun cancel() {
        val current = plan ?: return
        current.cancel()
        if (current.cancelled) {
            log("SOS alerts: cancelled before sending")
            stopLocation()
            live?.stop(falseAlarm = false)
            live = null
            GuardState.alert.value = null
        }
    }

    /** SOS ended (unlocked or stopped). */
    fun stop() {
        val current = plan ?: return
        plan = null
        handler.removeCallbacks(tick)
        stopLocation()
        GuardState.alert.value = null
        val falseAlarm = current.falseAlarmOnStop(SystemClock.elapsedRealtime())
        if (falseAlarm && canSendSms(context)) {
            log("SOS alerts: stopped right after alerting, sending false alarm")
            send(EmergencyContacts.falseAlarmText(context))
        }
        live?.stop(falseAlarm)
        live = null
        linkOwed = false
        pendingText = null
        lastText = null
    }

    /** The Play build's tap: open the SMS app with the latest alert. */
    fun openSmsApp() {
        val text = pendingText ?: return
        runCatching { context.startActivity(smsIntent(numbers(), text)) }
    }

    private fun numbers() = contacts.map { EmergencyContacts.normalize(it.phone) }.filter { it.count(Char::isDigit) >= 3 }

    private fun sendFirst(current: AlertPlan, now: Long) {
        val fix = lastFix
        val link = live?.link
        val time = timeNow()
        val text = EmergencyContacts.sosText(context, time, fix?.latitude, fix?.longitude, fix?.accuracy, link)
        current.onSent(now, fix?.latitude, fix?.longitude)
        linkOwed = live != null && link == null
        send(text)
        live?.alertSent(time)
        publish(current, now)
    }

    private fun onFix(fix: Location) {
        lastFix = fix
        val current = plan ?: return
        live?.addFix(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy else null)
        if (current.shouldUpdate(SystemClock.elapsedRealtime(), fix.latitude, fix.longitude)) {
            send(EmergencyContacts.updateText(context, timeNow(), fix.latitude, fix.longitude))
        }
    }

    private fun send(text: String) {
        val marked = if (Prefs.testMode(context)) "[TEST] $text" else text
        lastText = marked
        if (numbers().isEmpty()) return // Email-only contacts: the live share emails them.
        if (canSendSms(context)) {
            var ok = 0
            numbers().forEach { number ->
                if (runCatching { sendSms(context, number, marked) }.isSuccess) ok++
            }
            log("SOS alerts: SMS sent to $ok of ${contacts.size}")
        } else {
            pendingText = marked
            log("SOS alerts: ready to send (tap needed)")
        }
    }

    private fun publish(current: AlertPlan, now: Long) {
        GuardState.alert.value = AlertUi(
            names = contacts.joinToString(", ") { it.name },
            secondsLeft = current.secondsLeft(now),
            sent = current.sentAt != null,
            needsTap = current.sentAt != null && !canSendSms(context),
        )
    }

    @SuppressLint("MissingPermission") // Checked in hasLocation.
    private fun startLocation() {
        if (!hasLocation(context)) {
            log("SOS alerts: no location permission, alerts go without location")
            return
        }
        val token = CancellationTokenSource().also { fixToken = it }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(60_000L)
            .build()
        runCatching {
            location.getCurrentLocation(request, token.token).addOnSuccessListener { fix -> if (fix != null) onFix(fix) }
            location.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS).build(),
                updates,
                Looper.getMainLooper(),
            )
        }.onFailure { log("SOS alerts: location unavailable (${it.javaClass.simpleName})") }
    }

    private fun stopLocation() {
        fixToken?.cancel()
        fixToken = null
        runCatching { location.removeLocationUpdates(updates) }
    }

    private fun timeNow() = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
}
