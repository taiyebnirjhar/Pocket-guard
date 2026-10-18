package com.taiyeb.pocketguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/** Takes a few silent front-camera photos of whoever is holding the phone when the siren starts. */
class IntruderCamera(private val context: Context, private val owner: LifecycleOwner) {

    companion object {
        private const val SETTLE_MS = 700L
        private const val BETWEEN_MS = 1500L
        // Slow phones (and 2-core CI emulators) can take several seconds to open the camera.
        private const val TIMEOUT_MS = 12_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Ends the capture in progress, if any (keeping what's saved). */
    private var stopCurrent: (() -> Unit)? = null

    /**
     * The camera is wanted for something else (the SOS video): stop now. Only this capture's own
     * use is released, never the other's.
     */
    fun cancel() {
        stopCurrent?.invoke()
    }

    /** [onDone] always runs exactly once, with whatever was saved (possibly nothing). */
    fun capture(dir: File, prefix: String, count: Int, onDone: (List<File>) -> Unit) {
        val saved = mutableListOf<File>()
        var finished = false
        var provider: ProcessCameraProvider? = null
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val finish = {
            if (!finished) {
                finished = true
                stopCurrent = null
                try {
                    provider?.unbind(imageCapture)
                } catch (e: Exception) {
                    // Camera already released.
                }
                onDone(saved.toList())
            }
        }
        stopCurrent = finish
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        handler.postDelayed({ finish() }, TIMEOUT_MS)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (finished) return@addListener
            try {
                val cameras = future.get()
                provider = cameras
                cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
            } catch (e: Exception) {
                finish() // No front camera, or camera use not allowed right now.
                return@addListener
            }
            shoot(imageCapture, dir, prefix, 0, count, saved, { finished }, finish)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun shoot(
        imageCapture: ImageCapture,
        dir: File,
        prefix: String,
        index: Int,
        count: Int,
        saved: MutableList<File>,
        isFinished: () -> Boolean,
        finish: () -> Unit,
    ) {
        if (index >= count) {
            finish()
            return
        }
        handler.postDelayed({
            if (isFinished()) return@postDelayed
            val file = File(dir, "${prefix}_$index.jpg")
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        saved += file
                        shoot(imageCapture, dir, prefix, index + 1, count, saved, isFinished, finish)
                    }

                    override fun onError(exception: ImageCaptureException) = finish()
                },
            )
        }, if (index == 0) SETTLE_MS else BETWEEN_MS)
    }
}
