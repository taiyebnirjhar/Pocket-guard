package com.taiyeb.pocketguard

import android.app.NotificationManager
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
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

class AlarmPlayer(private val context: Context, private val log: (String) -> Unit = {}) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var savedInterruptionFilter: Int? = null

    private var track: AudioTrack? = null
    private var savedMusicVolume: Int? = null
    private var savedAlarmVolume: Int? = null
    private var targetAlarmVolume = 0
    private var volumeFought = false
    private var torchId: String? = null
    private var torchOn = false

    /** Puts the alarm volume back every 200 ms, so the volume buttons can't silence the siren. */
    private val volumeLock = object : Runnable {
        override fun run() {
            val now = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            if (now != targetAlarmVolume) {
                setVolume(AudioManager.STREAM_ALARM, targetAlarmVolume)
                if (!volumeFought) {
                    volumeFought = true
                    log("alarm volume lowered to $now (a button or the system), set back to $targetAlarmVolume")
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    private val torchBlink = object : Runnable {
        override fun run() {
            val id = torchId ?: return
            try {
                camera.setTorchMode(id, !torchOn)
                torchOn = !torchOn
                handler.postDelayed(this, 250)
            } catch (e: Exception) {
                // Camera still closing after the intruder photo, or busy: keep trying.
                handler.postDelayed(this, 500)
            }
        }
    }

    /** Short buzz confirming the phone is now protected. */
    fun tick() = vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))

    /** Double buzz for "something changed" (auto-armed, earbuds disconnected). */
    fun notice() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 120, 80), -1))

    /** Two quick buzzes: now guarding it on the table. */
    fun tableTick() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))

    /**
     * Stage 1: beeps on the media stream (which goes to the earbuds when connected) plus vibration.
     * After [silentMs] of silence: when the person holding it is likely looking at it, the owner
     * gets a moment to unlock without a sound. [onTable]: beeps on the alarm stream (heard through
     * silent mode) and no vibration, which would keep a phone put back down from ever being still.
     */
    fun startWarning(silentMs: Long = 0, onTable: Boolean = false) {
        stop()
        if (silentMs > 0) {
            handler.postDelayed({ beginWarning(onTable) }, silentMs)
        } else {
            beginWarning(onTable)
        }
    }

    private fun beginWarning(onTable: Boolean) {
        if (onTable) {
            play(Tones.beeps(), AudioAttributes.USAGE_ALARM)
            return
        }
        if (Audio.headsetConnected(audio)) {
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

    /** Stage 2: siren on the alarm stream (plays through silent mode) and strong vibration. */
    /** [vibrateAfterMs]: a table siren holds its vibration while the phone may still be put back. */
    fun startSiren(quiet: Boolean, vibrateAfterMs: Long = 0) {
        stop()
        overrideDoNotDisturb()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        saveAlarmVolume()
        targetAlarmVolume = if (quiet) maxOf(1, (max * 0.3f).roundToInt()) else max
        logVolume(quiet, max)
        handler.post(volumeLock)
        play(Tones.siren(), AudioAttributes.USAGE_ALARM)
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 600, 200), 0)
        if (vibrateAfterMs > 0) handler.postDelayed({ vibrate(pattern) }, vibrateAfterMs) else vibrate(pattern)
    }

    /**
     * The SOS alarm, which sounds unlike the theft siren: a whistle trill and, in its pauses,
     * [speak] (a spoken call for help), on the alarm stream at full volume through silent mode.
     */
    fun startSos(quiet: Boolean, speak: () -> Unit) {
        stop()
        overrideDoNotDisturb()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        saveAlarmVolume()
        targetAlarmVolume = if (quiet) maxOf(1, (max * 0.3f).roundToInt()) else max
        logVolume(quiet, max)
        handler.post(volumeLock)
        play(Tones.sos(), AudioAttributes.USAGE_ALARM)
        handler.post(object : Runnable {
            override fun run() {
                handler.postDelayed({ speak() }, Tones.SOS_WHISTLE_MS + 50)
                handler.postDelayed(this, Tones.SOS_PERIOD_MS)
            }
        })
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200), 0))
    }

    /** Torch strobe. Started separately because the intruder photo needs the camera first. */
    fun startTorch() {
        if (torchId != null) return
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
        torchId = null
        torchOn = false
        savedMusicVolume?.let { setVolume(AudioManager.STREAM_MUSIC, it) }
        savedMusicVolume = null
        (savedAlarmVolume ?: Prefs.savedAlarmVolume(context))?.let { setVolume(AudioManager.STREAM_ALARM, it) }
        savedAlarmVolume = null
        Prefs.setSavedAlarmVolume(context, null)
        savedInterruptionFilter?.let {
            try {
                notifications.setInterruptionFilter(it)
            } catch (e: SecurityException) {
                // Access revoked meanwhile.
            }
        }
        savedInterruptionFilter = null
    }

    private fun logVolume(quiet: Boolean, max: Int) {
        volumeFought = false
        log("alarm volume $targetAlarmVolume of $max" + if (quiet) " (test mode: quiet on purpose)" else "")
    }

    /** The volume to go back to: the one from before any siren, even one cut off by a restart. */
    private fun saveAlarmVolume() {
        val earlier = Prefs.savedAlarmVolume(context)
        savedAlarmVolume = earlier ?: audio.getStreamVolume(AudioManager.STREAM_ALARM).also {
            Prefs.setSavedAlarmVolume(context, it)
        }
    }

    /**
     * "Total silence" Do Not Disturb mutes even alarms; lift it while the siren plays, if allowed.
     * From Android 15 an app can only switch its own Do Not Disturb mode, not the user's, and
     * "restoring" the user's filter would switch on a silent mode of the app's own: so not there.
     */
    private fun overrideDoNotDisturb() {
        if (Build.VERSION.SDK_INT >= 35) return
        if (!notifications.isNotificationPolicyAccessGranted) return
        val filter = notifications.currentInterruptionFilter
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) return
        try {
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            savedInterruptionFilter = filter
        } catch (e: SecurityException) {
            // No access.
        }
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

    /** Alarm usage, because Android 13+ may drop vibrations from apps that aren't on screen. */
    private fun vibrate(effect: VibrationEffect) {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect)
        }
    }
}

object Audio {
    fun headsetConnected(audio: AudioManager): Boolean {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    /** True only when call audio comes out of the earpiece, i.e. the phone is held at the ear. */
    fun callAtEar(audio: AudioManager): Boolean {
        // Not ringing: an accomplice calling the phone must not pause the guard.
        val mode = audio.mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        if (!inCall) return false
        if (Build.VERSION.SDK_INT >= 31) {
            return audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        @Suppress("DEPRECATION")
        val speaker = audio.isSpeakerphoneOn
        return !headsetConnected(audio) && !speaker
    }
}
