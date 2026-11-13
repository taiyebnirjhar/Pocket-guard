package com.taiyeb.pocketguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Records video with sound from the moment SOS starts until the owner unlocks: the back camera
 * (it faces the other person when the phone is held; in a pocket the sound is what counts), in
 * one-minute pieces so whatever was recorded is saved even if the phone is taken.
 */
class SosVideo(private val context: Context, private val owner: LifecycleOwner) {

    companion object {
        const val PIECE_MS = 60_000L

        /** A silent SOS: shorter pieces, so less is lost if the phone is switched off or smashed. */
        const val SHORT_PIECE_MS = 30_000L

        /** At most this much is recorded per SOS. */
        const val MAX_RECORDING_MS = 15 * 60_000L

        /** 480p at this rate is about 12 MB a minute with the sound. */
        private const val LOW_RES_BITRATE = 1_500_000

        /** 720p at this rate is about 30 MB a minute: 15 minutes stay under half a gigabyte. */
        private const val HD_BITRATE = 4_000_000

        /** A piece starts only with this much free space left, so recording never fills the phone. */
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024

        /** Pieces that fail one after another: the camera is gone, so stop instead of looping. */
        private const val MAX_FAILED_PIECES = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var recording: Recording? = null
    private var active = false
    private var pieces = 0
    private var pieceMs = PIECE_MS
    private var failed = 0
    private lateinit var dir: File
    private lateinit var prefix: String
    private var onPiece: (File) -> Unit = {}

    /**
     * Bumped by every [start]: the last piece of a stopped recording finalizes later, and if a new
     * SOS has started by then, that old callback must not touch the new recording.
     */
    private var session = 0

    private val nextPiece = Runnable { recording?.stop() } // Finalize starts the next one.

    /**
     * [onPiece] runs on the main thread for every piece saved. [lowRes]: 480p at a modest bitrate,
     * for pieces that are uploaded while SOS runs. [pieceMs]: how long each piece is.
     */
    fun start(dir: File, prefix: String, lowRes: Boolean = false, pieceMs: Long = PIECE_MS, onPiece: (File) -> Unit) {
        if (active) return
        if (!granted(Manifest.permission.CAMERA)) {
            Log.w(GuardService.TAG, "SOS video: no camera permission")
            return
        }
        active = true
        session++
        val mySession = session
        pieces = 0
        this.pieceMs = pieceMs
        failed = 0
        this.dir = dir
        this.prefix = prefix
        this.onPiece = onPiece
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active || mySession != session) return@addListener
            val built = if (lowRes) {
                Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .setTargetVideoEncodingBitRate(LOW_RES_BITRATE)
                    .build()
            } else {
                Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .setTargetVideoEncodingBitRate(HD_BITRATE)
                    .build()
            }
            val cameras = try {
                future.get()
            } catch (e: Exception) {
                Log.w(GuardService.TAG, "SOS video: no camera provider", e)
                active = false
                return@addListener
            }
            provider = cameras
            val bound = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA).any { selector ->
                try {
                    cameras.unbindAll()
                    cameras.bindToLifecycle(owner, selector, VideoCapture.withOutput(built))
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!bound) {
                Log.w(GuardService.TAG, "SOS video: camera not available right now")
                active = false
                return@addListener
            }
            recorder = built
            record()
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(nextPiece)
        recording?.stop() // Its Finalize saves the last piece, then releases the camera.
        if (recording == null) release()
    }

    @SuppressLint("MissingPermission") // Checked just before.
    private fun record() {
        val output = recorder ?: return
        if (!active || pieces >= MAX_RECORDING_MS / pieceMs || failed >= MAX_FAILED_PIECES) {
            active = false
            release()
            return
        }
        if (dir.usableSpace < MIN_FREE_BYTES) {
            Log.w(GuardService.TAG, "SOS video: stopped, the phone is nearly full")
            active = false
            release()
            return
        }
        val file = File(dir, "${prefix}_$pieces.mp4")
        pieces++
        val mySession = session
        val piece = onPiece
        val pending = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (granted(Manifest.permission.RECORD_AUDIO)) pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> Log.i(GuardService.TAG, "SOS video recording: ${file.name}")
                is VideoRecordEvent.Finalize -> {
                    if (mySession != session) {
                        // From a recording stopped before the current one began: just keep its piece.
                        if (file.length() > 0) piece(file) else file.delete()
                        return@start
                    }
                    recording = null
                    // Ended early (an error): the next piece gets its own full minute.
                    handler.removeCallbacks(nextPiece)
                    if (event.hasError() && file.length() == 0L) failed++ else failed = 0
                    if (file.length() > 0) {
                        Log.i(GuardService.TAG, "SOS video saved: ${file.name} (${file.length() / 1024} KB)")
                        piece(file)
                    } else {
                        Log.w(GuardService.TAG, "SOS video piece empty (error ${event.error})")
                        file.delete()
                    }
                    if (active) record() else release()
                }
            }
        }
        handler.postDelayed(nextPiece, pieceMs)
    }

    private fun release() {
        handler.removeCallbacks(nextPiece)
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // Camera already released.
        }
        recorder = null
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
