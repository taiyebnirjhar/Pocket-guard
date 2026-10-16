package com.taiyeb.pocketguard

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

class AlarmPlayer(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val handler = Handler(Looper.getMainLooper())

    private var track: AudioTrack? = null
    private var savedMusicVolume: Int? = null
    private var savedAlarmVolume: Int? = null
    private var targetAlarmVolume = 0
    private var torchId: String? = null
    private var torchOn = false

    /** Puts the alarm volume back every 200 ms, so the volume buttons can't silence the siren. */
    private val volumeLock = object : Runnable {
        override fun run() {
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != targetAlarmVolume) {
                setVolume(AudioManager.STREAM_ALARM, targetAlarmVolume)
            }
            handler.postDelayed(this, 200)
        }
    }

    private val torchBlink = object : Runnable {
        override fun run() {
            val id = torchId ?: return
            torchOn = !torchOn
            try {
                camera.setTorchMode(id, torchOn)
            } catch (e: Exception) {
                return
            }
            handler.postDelayed(this, 250)
        }
    }

    /** Short buzz confirming the phone is now protected. */
    fun tick() = vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))

    /** Stage 1: beeps on the media stream (which goes to the earbuds when connected) plus vibration. */
    fun startWarning() {
        stop()
        if (headphonesConnected()) {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6f).roundToInt()
            if (current < target) {
                savedMusicVolume = current
                setVolume(AudioManager.STREAM_MUSIC, target)
            }
        }
        play(Tones.beeps(), AudioAttributes.USAGE_MEDIA)
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150), 0))
    }

    /** Stage 2: siren on the alarm stream (plays through silent mode), torch strobe, strong vibration. */
    fun startSiren(quiet: Boolean) {
        stop()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        targetAlarmVolume = if (quiet) maxOf(1, (max * 0.3f).roundToInt()) else max
        handler.post(volumeLock)
        play(Tones.siren(), AudioAttributes.USAGE_ALARM)
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 200), 0))
        torchId = findTorch()
        handler.post(torchBlink)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        track?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // Never started.
            }
            it.release()
        }
        track = null
        vibrator.cancel()
        torchId?.let {
            try {
                camera.setTorchMode(it, false)
            } catch (e: Exception) {
                // Camera busy or gone.
            }
        }
        torchOn = false
        savedMusicVolume?.let { setVolume(AudioManager.STREAM_MUSIC, it) }
        savedMusicVolume = null
        savedAlarmVolume?.let { setVolume(AudioManager.STREAM_ALARM, it) }
        savedAlarmVolume = null
    }

    private fun play(samples: ShortArray, usage: Int) {
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Tones.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        try {
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            newTrack.write(samples, 0, samples.size)
            newTrack.setLoopPoints(0, samples.size, -1)
            newTrack.play()
            track = newTrack
        } catch (e: Exception) {
            // Audio unavailable; vibration and torch still run.
        }
    }

    private fun headphonesConnected(): Boolean {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    private fun findTorch(): String? = try {
        camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        null
    }

    private fun setVolume(stream: Int, volume: Int) {
        try {
            audio.setStreamVolume(stream, volume, 0)
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse volume changes on some devices.
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }
}
