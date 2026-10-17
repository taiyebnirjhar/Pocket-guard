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

/**
 * One SOS online: opens an incident (its link goes into the SMS), uploads the location every
 * [UPLOAD_EVERY_MS] while SOS runs, emails the contacts who have an address, uploads each video
 * piece as it's saved, and closes it when SOS ends. Works offline-first: fixes and pieces wait in
 * a queue, and each step retries; pieces still waiting when SOS ends keep going for a while.
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
        private const val VIDEO_RETRY_MS = 20_000L
        private const val VIDEO_TRIES = 8

        /** The last piece is saved just after SOS stops: keep waiting for it this long. */
        private const val LAST_PIECE_MS = 20_000L

        /** After SOS ends, give up on pieces that still haven't gone up after this long. */
        private const val FINISH_WITHIN_MS = 30 * 60_000L

        /** The share an SOS recording hands its pieces to (it may still finish after SOS ends). */
        @Volatile
        var current: LiveShare? = null
            private set
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()

    // Videos go up on their own thread, so a slow upload never holds back the location.
    private val uploader = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Backend.Fix>()

    @Volatile private var incident: Backend.Incident? = null
    @Volatile private var stopped = false
    private val videos = LinkedBlockingDeque<Pair<File, Int>>() // Piece and tries so far.
    @Volatile private var uploading = false
    private var stoppedAt = 0L
    private var sosEmailWanted = false
    private var sosEmailSent = false
    private var emailTime = ""

    /** The live page link, once the incident is open. */
    val link: String? get() = incident?.let { Backend.liveLink(context, it.viewerToken) }

    /** Opens the incident; [onLink] runs on the main thread when the link is ready. */
    fun start(onLink: (String) -> Unit) {
        current = this
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
        onUploader { uploadVideos() }
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
        onUploader { uploadVideos() }
    }

    /** Uploader thread only. */
    private fun uploadVideos() {
        val id = incident?.id ?: return
        uploading = true
        try {
            while (true) {
                val (file, tries) = videos.pollFirst() ?: break
                if (Backend.uploadVideo(context, id, file)) {
                    log("Live location: video uploaded (${file.length() / 1024} KB)")
                } else if (tries + 1 < VIDEO_TRIES) {
                    videos.addFirst(file to tries + 1)
                    onUploader(VIDEO_RETRY_MS) { uploadVideos() }
                    return
                } else {
                    log("Live location: gave up uploading ${file.name}")
                }
            }
        } finally {
            uploading = false
        }
    }

    private fun onUploader(delayMs: Long = 0, task: () -> Unit) {
        try {
            uploader.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Finished: what's left stays on the phone.
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
        stoppedAt = System.currentTimeMillis()
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
            finishWhenDone()
        }
    }

    /** Shuts the worker down once the last pieces are up (or it's been too long). */
    private fun finishWhenDone() {
        val now = System.currentTimeMillis()
        val waiting = (videos.isNotEmpty() || uploading) && incident != null
        if ((waiting || now < stoppedAt + LAST_PIECE_MS) && now < stoppedAt + FINISH_WITHIN_MS) {
            later(5_000L) { finishWhenDone() }
            return
        }
        if (current === this) current = null
        uploader.shutdown()
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
