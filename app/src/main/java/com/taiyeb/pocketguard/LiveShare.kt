package com.taiyeb.pocketguard

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One SOS online: opens an incident (its link goes into the SMS), uploads the location every
 * [UPLOAD_EVERY_MS] while SOS runs, emails the contacts who have an address, uploads each video
 * piece as it's saved, and closes it when SOS ends. Works offline-first: fixes and pieces wait in
 * a queue, and each step retries for as long as SOS runs; closing the incident and pieces still
 * waiting when SOS ends keep going for a while after.
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
        private const val MAX_RETRY_MS = 30_000L
        private const val MAX_QUEUED = 300
        private const val VIDEO_RETRY_MS = 20_000L
        private const val VIDEO_TRIES = 8

        /** The last piece is saved just after SOS stops: keep waiting for it this long. */
        private const val LAST_PIECE_MS = 20_000L

        /** After SOS ends, give up on what still hasn't gone up after this long. */
        private const val FINISH_WITHIN_MS = 30 * 60_000L

        /** The share an SOS recording hands its pieces to (it may still finish after SOS ends). */
        @Volatile
        var current: LiveShare? = null
            private set

        private fun backoff(attempt: Int) = (RETRY_MS * attempt).coerceAtMost(MAX_RETRY_MS)
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()

    // Videos go up on their own thread, so a slow upload never holds back the location.
    private val uploader = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Backend.Fix>()

    // A test SOS doesn't send contacts a real alert email.
    private val test = Prefs.testMode(context)

    @Volatile private var incident: Backend.Incident? = null
    @Volatile private var stopped = false
    private val videos = LinkedBlockingDeque<Pair<File, Int>>() // Piece and tries so far.

    // One upload chain at a time, so pieces go up in order and retries don't pile up.
    private val uploading = AtomicBoolean(false)
    private var stoppedAt = 0L

    // Worker thread only.
    private var sosEmailWanted = false
    private var sosEmailSent = false
    private var emailTime = ""
    private var closed = false
    private var falseAlarmEmailOwed = false

    /** The live page link, once the incident is open. */
    val link: String? get() = incident?.let { Backend.liveLink(context, it.viewerToken) }

    /**
     * Opens the incident; [onLink] runs on the main thread when the link is ready. [existing]: the
     * incident this SOS opened before a restart, carried on (its link already went out).
     */
    fun start(onLink: (String) -> Unit, existing: Backend.Incident? = null) {
        current = this
        if (existing != null) {
            incident = existing
            log("Live location: sharing again after a restart")
            main.post { if (!stopped) onLink(Backend.liveLink(context, existing.viewerToken)) }
            startVideoChain()
        } else {
            worker.execute { open(1, onLink) }
        }
        worker.scheduleWithFixedDelay(::upload, UPLOAD_EVERY_MS, UPLOAD_EVERY_MS, TimeUnit.MILLISECONDS)
    }

    /** Cancelled during the countdown: nothing more leaves the phone, the incident just ends. */
    fun discard() {
        synchronized(queue) { queue.clear() }
        stop(falseAlarm = false)
    }

    private fun open(attempt: Int, onLink: (String) -> Unit) {
        if (stopped) return
        val opened = Backend.openIncident(context, ownerName, battery())
        if (opened == null) {
            log("Live location: couldn't reach the server (try $attempt)")
            later(backoff(attempt)) { open(attempt + 1, onLink) }
            return
        }
        incident = opened
        Prefs.setSosIncident(context, opened)
        log("Live location: sharing")
        val live = Backend.liveLink(context, opened.viewerToken)
        main.post { if (!stopped) onLink(live) }
        upload()
        if (sosEmailWanted) sendSosEmail()
        startVideoChain()
    }

    fun addFix(lat: Double, lng: Double, accuracy: Float?) {
        synchronized(queue) {
            queue.add(Backend.Fix(lat, lng, accuracy, System.currentTimeMillis()))
            while (queue.size > MAX_QUEUED) queue.removeAt(0)
        }
    }

    /** A video piece was saved (main thread); it goes up after the locations. */
    fun addVideo(file: File) {
        videos.addLast(file to 0)
        startVideoChain()
    }

    private fun startVideoChain(delayMs: Long = 0) {
        if (incident == null || !uploading.compareAndSet(false, true)) return
        if (!onUploader(delayMs) { uploadVideos() }) uploading.set(false)
    }

    /** Uploader thread only: the one running chain. */
    private fun uploadVideos() {
        val id = incident?.id
        while (id != null) {
            val (file, tries) = videos.pollFirst() ?: break
            if (Backend.uploadVideo(context, id, file)) {
                log("Live location: video uploaded (${file.length() / 1024} KB)")
            } else if (tries + 1 < VIDEO_TRIES) {
                videos.addFirst(file to tries + 1)
                uploading.set(false)
                startVideoChain(VIDEO_RETRY_MS)
                return
            } else {
                log("Live location: gave up uploading ${file.name}")
            }
        }
        uploading.set(false)
        // A piece added just as the chain finished.
        if (videos.isNotEmpty()) startVideoChain()
    }

    private fun onUploader(delayMs: Long, task: () -> Unit): Boolean = try {
        uploader.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
        true
    } catch (e: RejectedExecutionException) {
        false // Finished: what's left stays on the phone.
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
        if (sosEmailSent || emails.isEmpty() || stopped) return
        val id = incident?.id ?: return
        val sent = if (test) {
            Backend.sendEmail(context, Backend.EmailKind.TEST, emails, null, ownerName, "")
        } else {
            Backend.sendEmail(context, Backend.EmailKind.SOS, emails, id, ownerName, emailTime)
        }
        if (sent) {
            sosEmailSent = true
            log("Live location: email sent to ${emails.size}")
        } else {
            later(backoff(attempt)) { sendSosEmail(attempt + 1) }
        }
    }

    private fun upload() {
        val id = incident?.id ?: return
        val batch = synchronized(queue) { ArrayList(queue) }
        if (batch.isEmpty()) return
        val code = Backend.addLocations(context, id, batch)
        val ok = code in 200..299
        // Refused for good (a limit, a closed incident): keeping the batch would block every later fix.
        val refused = code in 400..499 && code != 401 && code != 408 && code != 429
        if (ok || refused) {
            // The ones sent, not the first few: the queue may have been trimmed meanwhile.
            synchronized(queue) { queue.removeAll(batch.toSet()) }
        }
        log(if (ok) "Live location: ${batch.size} points uploaded" else "Live location: upload failed ($code)")
    }

    /** SOS ended. [falseAlarm]: stopped right after the alert, so the emailed contacts hear it too. */
    fun stop(falseAlarm: Boolean) {
        if (stopped) return
        stopped = true
        stoppedAt = System.currentTimeMillis()
        if (current === this) current = null
        worker.execute {
            upload()
            falseAlarmEmailOwed = falseAlarm && sosEmailSent && !test
            close(falseAlarm, 1)
            finishWhenDone()
        }
    }

    /** Worker thread: marks the incident ended (so the page says so), then the false alarm email. */
    private fun close(falseAlarm: Boolean, attempt: Int) {
        val id = incident?.id
        if (id == null) {
            closed = true // Never opened: nothing to close.
            return
        }
        if (!closed) {
            closed = Backend.updateIncident(context, id, if (falseAlarm) "false_alarm" else "ended", battery())
        }
        if (closed && falseAlarmEmailOwed) {
            falseAlarmEmailOwed = !Backend.sendEmail(context, Backend.EmailKind.FALSE_ALARM, emails, id, ownerName, "")
        }
        if (!closed || falseAlarmEmailOwed) {
            later(backoff(attempt)) { close(falseAlarm, attempt + 1) }
        } else {
            log("Live location: stopped")
        }
    }

    /** Shuts the worker down once everything is up (or it's been too long). */
    private fun finishWhenDone() {
        val now = System.currentTimeMillis()
        val waiting = !closed || falseAlarmEmailOwed ||
            (videos.isNotEmpty() || uploading.get()) && incident != null
        if ((waiting || now < stoppedAt + LAST_PIECE_MS) && now < stoppedAt + FINISH_WITHIN_MS) {
            later(5_000L) { finishWhenDone() }
            return
        }
        uploader.shutdown()
        worker.shutdown()
    }

    /** A retry; dropped once the worker is shutting down. */
    private fun later(delayMs: Long, task: () -> Unit) {
        try {
            worker.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Finished meanwhile.
        }
    }

    private fun battery(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
}
