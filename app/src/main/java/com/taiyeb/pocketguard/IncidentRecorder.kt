package com.taiyeb.pocketguard

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.lifecycle.LifecycleOwner

/**
 * Collects evidence while an alarm runs: a history entry and intruder photos, both kept on the
 * phone for when it's recovered. Main thread only.
 */
class IncidentRecorder(
    private val context: Context,
    owner: LifecycleOwner,
    /** Called once the camera is free again, so the torch strobe can start without fighting it. */
    private val onCameraFree: () -> Unit,
) {
    enum class Outcome { OWNER_STOPPED, FLICKER }

    companion object {
        private const val PHOTOS = 2
    }

    private val camera = IntruderCamera(context, owner)
    private val video = SosVideo(context, owner)
    private var current: Incident? = null
    private var sosRecording = false

    /** First warning: start a history entry (the owner may still unlock). */
    fun begin(trigger: Trigger) {
        if (current != null) return
        val now = System.currentTimeMillis()
        current = Incident(id = now, startedAt = now, trigger = trigger).also { History.save(context, it) }
    }

    /** Siren: this is now treated as a theft. */
    fun escalate(trigger: Trigger) {
        val incident = current ?: run {
            begin(trigger)
            current!!
        }
        // Already a theft siren: only a call for help on top of it still starts the recording.
        if (incident.escalated && (trigger != Trigger.SOS || sosRecording)) return
        incident.escalated = true
        History.save(context, incident)

        // Before the first unlock after a restart, app storage is locked, so there's nowhere to save.
        val storageUnlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        if (trigger == Trigger.SOS) {
            // A call for help: record what happens instead of photographing the owner.
            if (storageUnlocked) {
                sosRecording = true
                // Shared live: smaller pieces, so they upload quickly over mobile data.
                val live = LiveShare.current
                video.start(History.videosDir(context), "s${incident.id}", lowRes = live != null) { file ->
                    incident.videos += file.name
                    History.save(context, incident)
                    live?.addVideo(file)
                }
            }
        } else if (Prefs.intruderPhotos(context) && storageUnlocked) {
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
        video.stop()
        sosRecording = false
        val incident = current ?: return
        current = null
        incident.endedAt = System.currentTimeMillis()
        incident.ownerStopped = outcome == Outcome.OWNER_STOPPED
        incident.flicker = outcome == Outcome.FLICKER
        History.save(context, incident)
    }
}
