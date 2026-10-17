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
        const val MAX_PIECES = 15
    }

    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var recording: Recording? = null
    private var active = false
    private var pieces = 0
    private lateinit var dir: File
    private lateinit var prefix: String
    private var onPiece: (File) -> Unit = {}

    private val nextPiece = Runnable { recording?.stop() } // Finalize starts the next one.

    /** [onPiece] runs on the main thread for every piece saved. */
    fun start(dir: File, prefix: String, onPiece: (File) -> Unit) {
        if (active) return
        if (!granted(Manifest.permission.CAMERA)) {
            Log.w(GuardService.TAG, "SOS video: no camera permission")
            return
        }
        active = true
        pieces = 0
        this.dir = dir
        this.prefix = prefix
        this.onPiece = onPiece
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active) return@addListener
            val built = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
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
        if (!active || pieces >= MAX_PIECES) {
            active = false
            release()
            return
        }
        val file = File(dir, "${prefix}_$pieces.mp4")
        pieces++
        val pending = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (granted(Manifest.permission.RECORD_AUDIO)) pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> Log.i(GuardService.TAG, "SOS video recording: ${file.name}")
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (file.length() > 0) {
                        Log.i(GuardService.TAG, "SOS video saved: ${file.name} (${file.length() / 1024} KB)")
                        onPiece(file)
                    } else {
                        Log.w(GuardService.TAG, "SOS video piece empty (error ${event.error})")
                        file.delete()
                    }
                    if (active) record() else release()
                }
            }
        }
        handler.postDelayed(nextPiece, PIECE_MS)
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
