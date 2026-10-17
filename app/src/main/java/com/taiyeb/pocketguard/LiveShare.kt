package com.taiyeb.pocketguard

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One SOS online: opens an incident (its link goes into the SMS), uploads the location every
 * [UPLOAD_EVERY_MS] while SOS runs, emails the contacts who have an address, and closes it when
 * SOS ends. Works offline-first: fixes wait in a queue, and each step retries until SOS ends.
 */
class LiveShare(
    private val context: Context,
    private val ownerName: String,
    private val emails: List<String>,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val UPLOAD_EVERY_MS = 15_000L
        private const val RETRY_MS = 5_000L
        private const val MAX_QUEUED = 300
        private const val MAX_TRIES = 5
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Backend.Fix>()

    @Volatile private var incident: Backend.Incident? = null
    @Volatile private var stopped = false
    private var sosEmailWanted = false
    private var sosEmailSent = false
    private var emailTime = ""

    /** The live page link, once the incident is open. */
    val link: String? get() = incident?.let { Backend.liveLink(context, it.viewerToken) }

    /** Opens the incident; [onLink] runs on the main thread when the link is ready. */
    fun start(onLink: (String) -> Unit) {
        worker.execute { open(1, onLink) }
        worker.scheduleWithFixedDelay(::upload, UPLOAD_EVERY_MS, UPLOAD_EVERY_MS, TimeUnit.MILLISECONDS)
    }

    private fun open(attempt: Int, onLink: (String) -> Unit) {
        if (stopped) return
        val opened = Backend.openIncident(context, ownerName, battery())
        if (opened == null) {
            log("Live location: couldn't reach the server (try $attempt)")
            if (attempt < MAX_TRIES) later(RETRY_MS * attempt) { open(attempt + 1, onLink) }
            return
        }
        incident = opened
        log("Live location: sharing")
        val live = Backend.liveLink(context, opened.viewerToken)
        main.post { if (!stopped) onLink(live) }
        upload()
        if (sosEmailWanted) sendSosEmail()
    }

    fun addFix(lat: Double, lng: Double, accuracy: Float?) {
        synchronized(queue) {
            queue.add(Backend.Fix(lat, lng, accuracy, System.currentTimeMillis()))
            while (queue.size > MAX_QUEUED) queue.removeAt(0)
        }
    }

    /** The alert went out: email the contacts too (once the incident is open). */
    fun alertSent(time: String) {
        if (stopped) return
        worker.execute {
            emailTime = time
            sosEmailWanted = true
            if (incident != null) sendSosEmail()
        }
    }

    private fun sendSosEmail(attempt: Int = 1) {
        if (sosEmailSent || emails.isEmpty()) return
        val id = incident?.id ?: return
        if (Backend.sendEmail(context, Backend.EmailKind.SOS, emails, id, ownerName, emailTime)) {
            sosEmailSent = true
            log("Live location: email sent to ${emails.size}")
        } else if (attempt < MAX_TRIES && !stopped) {
            later(RETRY_MS * attempt) { sendSosEmail(attempt + 1) }
        }
    }

    private fun upload() {
        val id = incident?.id ?: return
        val batch = synchronized(queue) { ArrayList(queue) }
        if (batch.isEmpty()) return
        if (Backend.addLocations(context, id, batch)) {
            synchronized(queue) { queue.subList(0, batch.size.coerceAtMost(queue.size)).clear() }
            log("Live location: ${batch.size} points uploaded")
        }
    }

    /** SOS ended. [falseAlarm]: stopped right after the alert, so the emailed contacts hear it too. */
    fun stop(falseAlarm: Boolean) {
        if (stopped) return
        stopped = true
        worker.execute {
            upload()
            val id = incident?.id
            if (id != null) {
                Backend.updateIncident(context, id, if (falseAlarm) "false_alarm" else "ended", battery())
                if (falseAlarm && sosEmailSent) {
                    Backend.sendEmail(context, Backend.EmailKind.FALSE_ALARM, emails, id, ownerName, "")
                }
                log("Live location: stopped")
            }
        }
        worker.shutdown()
    }

    /** A retry; dropped once SOS has ended and the worker is shutting down. */
    private fun later(delayMs: Long, task: () -> Unit) {
        try {
            worker.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Stopped meanwhile.
        }
    }

    private fun battery(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
}
