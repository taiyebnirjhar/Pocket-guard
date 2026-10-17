package com.taiyeb.pocketguard

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * The SOS trigger: volume up pressed [PRESSES] times within [WINDOW_MS] while the guard runs and
 * the phone is locked (screen off, or on the lock screen).
 *
 * Android gives volume keys to no app with the screen off; it only lets them change the media
 * volume while media is playing. So while it listens, and nothing else plays, it plays silence,
 * counts the media volume going up, and puts the volume straight back: the press changes nothing
 * the owner can hear, and nothing else on the phone sees a difference. At full volume a press
 * would change nothing, so the volume is held one step below it while listening and restored
 * after. When another app plays media, the keys are that app's: presses still count, and the
 * volume is left where the owner puts it. Holding the key doesn't count: its repeats come faster
 * than any tap.
 */
class SosButtons(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onSos: () -> Unit,
) {
    companion object {
        const val PRESSES = 5
        const val WINDOW_MS = 3000L

        /** Key repeats of a held button come every ~50 ms; taps are well apart. */
        const val MIN_TAP_GAP_MS = 90L
        private const val MEDIA_CHECK_MS = 2000L
        private const val SAMPLE_RATE = 8000

        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val taps = LongArray(PRESSES)
    private var tapCount = 0
    private var lastRaiseAt = Long.MIN_VALUE / 2
    private var started = false
    private var listening = false
    private var silence: AudioTrack? = null

    /** The media volume to return to after each press (null: not holding it). */
    private var holdAt: Int? = null

    /** The owner's own media volume when listening began, if it was lowered from full. */
    private var ownerVolume: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                VOLUME_CHANGED -> onVolumeChanged(intent)
                else -> update()
            }
        }
    }

    /** Media from another app starts or stops: the keys become its, or ours again. */
    private val mediaCheck = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, MEDIA_CHECK_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Exported like the service's own receiver: Android 14 holds some system broadcasts back
        // from NOT_EXPORTED receivers. These actions are protected, so only the system sends them.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        handler.postDelayed(mediaCheck, MEDIA_CHECK_MS)
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(receiver) }
        stopListening()
        tapCount = 0
    }

    private fun locked() = !power.isInteractive || keyguard.isKeyguardLocked

    /**
     * Another app's media is playing. The app's own alarm isn't media, and its silence is the
     * only media marked as sonification (a playback list doesn't say which app plays what).
     */
    private fun otherMediaPlaying() = audio.activePlaybackConfigurations.any {
        val a = it.audioAttributes
        (a.usage == AudioAttributes.USAGE_MEDIA || a.usage == AudioAttributes.USAGE_GAME || a.usage == AudioAttributes.USAGE_UNKNOWN) &&
            a.contentType != AudioAttributes.CONTENT_TYPE_SONIFICATION
    }

    private fun update() {
        if (!started) return
        if (!locked()) {
            if (listening) log("SOS buttons off while unlocked")
            stopListening()
            return
        }
        if (!listening) {
            listening = true
            log("SOS buttons listening (volume up ×$PRESSES)")
        }
        val other = otherMediaPlaying()
        if (other && silence != null) {
            log("SOS buttons: another app is playing media, its volume is left alone")
            stopSilence()
        } else if (!other && silence == null) {
            startSilence()
        }
    }

    private fun startSilence() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now >= max) {
            ownerVolume = now
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, max - 1, 0)
        }
        holdAt = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val frames = SAMPLE_RATE / 2
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
        }.getOrNull()
        if (track == null) {
            log("SOS buttons: couldn't start the silent track")
            restoreOwnerVolume()
            return
        }
        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames, -1)
        track.play()
        silence = track
    }

    private fun stopSilence() {
        silence?.let { runCatching { it.stop() }; it.release() }
        silence = null
        holdAt = null
        restoreOwnerVolume()
    }

    private fun restoreOwnerVolume() {
        ownerVolume?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        ownerVolume = null
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        stopSilence()
    }

    private fun onVolumeChanged(intent: Intent) {
        if (!listening || intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return
        val value = intent.getIntExtra(EXTRA_VALUE, -1)
        val prev = intent.getIntExtra(EXTRA_PREV, -1)
        val hold = holdAt
        if (value > prev && (hold == null || prev == hold)) {
            log("SOS buttons: volume up")
            onRaise()
        }
        // Put it straight back: with only silence playing, the press should change nothing.
        if (hold != null && value != hold) audio.setStreamVolume(AudioManager.STREAM_MUSIC, hold, 0)
    }

    private fun onRaise() {
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastRaiseAt
        lastRaiseAt = now
        if (gap < MIN_TAP_GAP_MS) {
            tapCount = 0 // Held down: not the pattern.
            return
        }
        taps[tapCount % PRESSES] = now
        tapCount++
        if (tapCount >= PRESSES && now - taps[tapCount % PRESSES] <= WINDOW_MS) {
            tapCount = 0
            log("SOS: volume up ×$PRESSES")
            onSos()
        }
    }
}
