package com.taiyeb.pocketguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

        /** An older fix (the last known one from before SOS) would send helpers to the wrong place. */
        private const val MAX_FIX_AGE_MS = 2 * 60_000L

        /** A text that didn't go (no signal) is tried again, this many times in all. */
        private const val SMS_TRIES = 5
        private const val SMS_RETRY_MS = 30_000L

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
        private fun sendSms(context: Context, number: String, text: String, sent: ((Int) -> PendingIntent)? = null): Int {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(text)
            val intents = sent?.let { make -> ArrayList(parts.indices.map(make)) }
            sms.sendMultipartTextMessage(number, null, parts, intents, null)
            return parts.size
        }

        /**
         * "Send test" on the SOS tab: the real alert text marked as a test, to the approved numbers.
         * By SMS only: the email service's free plan is kept for real alerts.
         */
        fun sendTest(context: Context): Boolean {
            val numbers = EmergencyContacts.numbers(context)
            if (numbers.isEmpty()) return false
            val text = EmergencyContacts.sms(context, R.string.alert_text_test) + "\n" + EmergencyContacts.previewText(context)
            if (canSendSms(context)) {
                numbers.forEach { runCatching { sendSms(context, it, text) } }
            } else {
                runCatching { context.startActivity(smsIntent(numbers, text)) }
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
    private var alertTime = ""

    /** The first SMS went out before the live link was ready; it follows on its own. */
    private var linkOwed = false

    /** The text for the next tap in the Play build: the whole alert, with the latest location. */
    var pendingText: String? = null
        private set

    /** The whole alert as it reads now (for "Share on WhatsApp"), once it's gone out. */
    var lastText: String? = null
        private set

    // Texts waiting for the phone's word that they went: by id, with how many parts are still out.
    private class SmsJob(val number: String, val text: String, val tries: Int, var parts: Int, var failed: Boolean = false)
    private val smsAction = context.packageName + ".SOS_SMS_SENT"
    private val smsJobs = HashMap<Int, SmsJob>()
    private var smsSeq = 0

    /** Numbers an alert reached (the phone confirmed it went), and ones that never went. */
    private val delivered = HashSet<String>()
    private val undeliverable = HashSet<String>()

    /** Retries waiting for their turn, cancelled together when SOS ends. */
    private val retries = Any()

    /** The false alarm sent as SOS ended: still retried after the end, unlike the alert itself. */
    private var closingText: String? = null
    private var receiverOn = false
    private var released = false

    private val smsSent = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val id = intent.getIntExtra("id", -1)
            val job = smsJobs[id] ?: return
            if (resultCode != Activity.RESULT_OK) job.failed = true
            if (--job.parts > 0) return
            smsJobs.remove(id)
            if (!job.failed) {
                delivered += job.number
                undeliverable -= job.number
                plan?.let { publish(it, SystemClock.elapsedRealtime()) }
                return
            }
            retry(job.number, job.text, job.tries)
        }
    }

    /** A text that didn't go: again later, unless SOS has ended or it's been tried enough. */
    private fun retry(number: String, text: String, tries: Int) {
        if (tries < SMS_TRIES && !released && (plan != null || text == closingText)) {
            log("SOS alerts: an SMS didn't go (try $tries), trying again")
            handler.postAtTime(
                { sendTracked(number, text, tries + 1) },
                retries,
                SystemClock.uptimeMillis() + SMS_RETRY_MS,
            )
        } else {
            log("SOS alerts: an SMS couldn't be sent")
            if (number !in delivered) undeliverable += number
            plan?.let { publish(it, SystemClock.elapsedRealtime()) }
        }
    }

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

    /**
     * SOS started. Does nothing until the owner has approved the alert and its recipients.
     * [resume]: the same SOS coming back after a restart, which carries on where it was (a
     * cancelled alert stays cancelled; one already sent isn't sent again, only updated).
     */
    fun start(resume: Boolean = false) {
        if (plan != null) return
        if (!EmergencyContacts.approved(context)) {
            log("SOS alerts: off (no approved contacts)")
            return
        }
        val stage = if (resume) Prefs.sosAlertStage(context) else Prefs.SOS_ALERT_NONE
        if (stage == Prefs.SOS_ALERT_CANCELLED) {
            log("SOS alerts: stay cancelled after the restart")
            return
        }
        contacts = EmergencyContacts.list(context)
        lastFix = null
        delivered.clear()
        undeliverable.clear()
        handedOver = false
        val now = SystemClock.elapsedRealtime()
        val current = AlertPlan(now)
        plan = current
        val existing = if (resume) Prefs.sosIncident(context) else null
        if (stage == Prefs.SOS_ALERT_SENT) {
            // Already out before the restart: no second alert, and not a "false alarm" if stopped now.
            current.onSent(now - AlertPlan.FALSE_ALARM_MS - 1, null, null)
            alertTime = Prefs.sosAlertTime(context)
            log("SOS alerts: already sent before the restart, carrying on with updates")
        } else {
            Prefs.setSosAlertStage(context, Prefs.SOS_ALERT_COUNTING)
            if (existing == null) Prefs.setSosIncident(context, null)
            log("SOS alerts: ${contacts.size} contacts in ${AlertPlan.COUNTDOWN_MS / 1000} s unless cancelled")
        }
        if (Backend.enabled(context)) {
            val emails = if (Backend.emailOn(context)) EmergencyContacts.emails(context) else emptyList()
            live = LiveShare(context, EmergencyContacts.ownerName(context), emails, log)
                .also { it.start(::onLiveLink, existing) }
        }
        startLocation()
        handler.post(tick)
    }

    /**
     * The owner tapped Send SMS or Share on WhatsApp: she's sending the alert herself, so the
     * unlock that follows isn't the end of the SOS (see [GuardService]), and never a false alarm.
     */
    var handedOver = false
        private set

    fun handOver() {
        handedOver = true
    }

    /** The live page is ready: an SMS still waiting gets it; one already sent is followed up. */
    private fun onLiveLink(link: String) {
        val current = plan ?: return
        if (current.sentAt == null) return // The first SMS will carry it.
        refreshTexts()
        if (linkOwed && canSendSms(context)) {
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
            Prefs.setSosAlertStage(context, Prefs.SOS_ALERT_CANCELLED)
            stopLocation()
            live?.discard()
            live = null
            GuardState.alert.value = null
        }
    }

    /** SOS ended (unlocked or stopped). */
    fun stop() {
        Prefs.setSosAlertStage(context, Prefs.SOS_ALERT_NONE)
        Prefs.setSosIncident(context, null)
        val current = plan ?: return
        plan = null
        handler.removeCallbacks(tick)
        // A retry still waiting must not reach anyone after the SOS (or its false alarm) is over.
        handler.removeCallbacksAndMessages(retries)
        stopLocation()
        GuardState.alert.value = null
        val falseAlarm = current.falseAlarmOnStop(SystemClock.elapsedRealtime()) && !handedOver
        if (falseAlarm && canSendSms(context)) {
            // Only to whoever the alert reached (or is reaching right now).
            val reached = delivered + smsJobs.values.filter { !it.failed }.map { it.number }
            log("SOS alerts: stopped right after alerting, sending false alarm to ${reached.size}")
            val text = EmergencyContacts.falseAlarmText(context)
            closingText = marked(text)
            send(text, reached.toList())
        }
        live?.stop(falseAlarm)
        live = null
        lastFix = null
        linkOwed = false
        pendingText = null
        lastText = null
    }

    /** The Play build's tap (or a send that failed): open the SMS app with the latest alert. */
    fun openSmsApp() {
        val text = pendingText ?: lastText ?: return
        handOver()
        runCatching { context.startActivity(smsIntent(numbers(), text)) }
    }

    private fun numbers() = contacts.filter { EmergencyContacts.canText(it.phone) }.map { EmergencyContacts.normalize(it.phone) }

    private fun sendFirst(current: AlertPlan, now: Long) {
        val fix = lastFix
        alertTime = timeNow()
        current.onSent(now, fix?.latitude, fix?.longitude)
        Prefs.setSosAlertStage(context, Prefs.SOS_ALERT_SENT, alertTime)
        linkOwed = live != null && live?.link == null
        refreshTexts()
        if (canSendSms(context)) send(wholeText()) else log("SOS alerts: ready to send (tap needed)")
        live?.alertSent(alertTime)
        publish(current, now)
    }

    /** The whole alert with the latest location and the live link once there is one. */
    private fun wholeText(): String {
        val fix = lastFix
        return EmergencyContacts.sosText(context, alertTime, fix?.latitude, fix?.longitude, fix?.accuracy, live?.link)
    }

    private fun refreshTexts() {
        val text = marked(wholeText())
        lastText = text
        if (!canSendSms(context) && numbers().isNotEmpty()) pendingText = text
        // The Play build's alert screen shows it; a resumed one needs the latest text too.
        plan?.let { publish(it, SystemClock.elapsedRealtime()) }
    }

    private fun onFix(fix: Location) {
        val age = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
        if (age > MAX_FIX_AGE_MS) return
        lastFix = fix
        val current = plan ?: return
        live?.addFix(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy else null)
        if (current.sentAt == null) return
        // The Play build's tap always sends the whole alert, so it just takes the new location.
        refreshTexts()
        if (canSendSms(context) && current.shouldUpdate(SystemClock.elapsedRealtime(), fix.latitude, fix.longitude)) {
            send(EmergencyContacts.updateText(context, timeNow(), fix.latitude, fix.longitude))
        }
    }

    private fun marked(text: String) = if (Prefs.testMode(context)) "[TEST] $text" else text

    /** Texts everyone with a number (the full build: sent here, and retried until it goes). */
    private fun send(text: String, numbers: List<String> = numbers()) {
        if (numbers.isEmpty() || !canSendSms(context)) return // Email-only contacts: the live share emails them.
        val marked = marked(text)
        val ok = numbers.count { sendTracked(it, marked, 1) }
        log("SOS alerts: SMS sent to $ok of ${numbers.size}")
    }

    private fun sendTracked(number: String, text: String, tries: Int): Boolean {
        if (!receiverOn && !released) {
            ContextCompat.registerReceiver(context, smsSent, IntentFilter(smsAction), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
        }
        val id = ++smsSeq
        val job = SmsJob(number, text, tries, parts = 0)
        smsJobs[id] = job
        val ok = runCatching {
            job.parts = sendSms(context, number, text) { part ->
                val intent = Intent(smsAction).setPackage(context.packageName).putExtra("id", id)
                PendingIntent.getBroadcast(
                    context,
                    id * 16 + part.coerceAtMost(15),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            }
        }.isSuccess
        if (!ok) {
            // Refused outright (no SIM, no service): tried again like a text that didn't go.
            smsJobs.remove(id)
            retry(number, text, tries)
        }
        return ok
    }

    /** The service is going away: stop waiting for texts' results. */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        smsJobs.clear()
        if (receiverOn) runCatching { context.unregisterReceiver(smsSent) }
        receiverOn = false
    }

    private fun publish(current: AlertPlan, now: Long) {
        val numbers = numbers()
        GuardState.alert.value = AlertUi(
            names = contacts.filter { EmergencyContacts.reachable(context, it) }.joinToString(", ") { it.name },
            secondsLeft = current.secondsLeft(now),
            sent = current.sentAt != null,
            needsTap = current.sentAt != null && !canSendSms(context) && numbers.isNotEmpty(),
            // Every text gave up (no signal, no SIM): say so, and offer the SMS app instead.
            smsFailed = canSendSms(context) && numbers.isNotEmpty() && undeliverable.containsAll(numbers),
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

    private fun timeNow() = EmergencyContacts.smsTime(Date())
}
