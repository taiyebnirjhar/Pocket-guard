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
 * The SOS fallback when the key watcher ([SosAccessibilityService]) is off: both volume keys
 * held together, read from the volume steps they make while the phone is locked (screen off, or
 * on the lock screen). Pressing both gives one step from the first key, then the second key's
 * auto-repeat: a steady run of steps the other way. That run lasting [SosHold.HOLD_MS] from the
 * first step is the hold; one key held alone, or taps, are not.
 *
 * Android gives volume keys to no app with the screen off; it only lets them change the media
 * volume while media is playing. So while it listens, and nothing else plays, it plays silence,
 * reads the media volume's steps, and puts the volume straight back: the press changes nothing
 * the owner can hear, and nothing else on the phone sees a difference. At full volume a press
 * would change nothing, so the volume is held one step below it while listening and restored
 * after (likewise one step above silent, so a step down shows). When another app plays media,
 * the keys are that app's: steps still count, and the volume is left where the owner puts it.
 */
class SosButtons(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onSos: () -> Unit,
) {
    companion object {
        /** The second key starts within this of the first. */
        const val CHORD_MS = 500L

        /** Longest pause in a held key's steps: the repeat starts ~0.5 s after the press. */
        const val REPEAT_GAP_MS = 800L
        private const val MEDIA_CHECK_MS = 2000L
        private const val SAMPLE_RATE = 8000

        /**
         * The volume is read this often. Android's volume-changed broadcast arrives late and
         * merged (four presses came as one "8 -> 9" on the emulator), so it can't time a hold.
         */
        const val POLL_MS = 50L
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    /** The run of steps in progress: when its first opposite step came, and its direction. */
    private var firstStepAt = Long.MIN_VALUE / 2
    private var firstUp = false
    private var runUp: Boolean? = null
    private var lastStepAt = Long.MIN_VALUE / 2
    private var fired = false
    private var started = false
    private var listening = false
    private var silence: AudioTrack? = null

    /** The media volume to return to after each press (null: not holding it). */
    private var holdAt: Int? = null

    /** The owner's own media volume when listening began, if it was lowered from full. */
    private var ownerVolume: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = update()
    }

    /** The last volume seen while another app's media owns it (null: holding it ourselves). */
    private var seen: Int? = null

    private val poll = object : Runnable {
        override fun run() {
            if (!listening) return
            readSteps()
            handler.postDelayed(this, POLL_MS)
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
        firstStepAt = Long.MIN_VALUE / 2
        lastStepAt = Long.MIN_VALUE / 2
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
            log("SOS buttons listening (volume + and − held)")
            handler.post(poll)
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
        if (now >= max || now <= 0) {
            ownerVolume = now
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, now.coerceIn(1, max - 1), 0)
        }
        holdAt = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        seen = null
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
        seen = null
        restoreOwnerVolume()
    }

    private fun restoreOwnerVolume() {
        ownerVolume?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        ownerVolume = null
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(poll)
        stopSilence()
    }

    /** Steps since the last read: away from the held volume, or from the last one seen. */
    private fun readSteps() {
        val value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val hold = holdAt
        val from = hold ?: seen ?: value
        if (hold == null) seen = value
        if (value == from) return
        // Put it straight back: with only silence playing, the press should change nothing.
        if (hold != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, hold, 0)
        repeat(kotlin.math.abs(value - from)) { onStep(value > from) }
    }

    private fun onStep(up: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastStepAt
        lastStepAt = now
        if (gap > REPEAT_GAP_MS) {
            // A fresh press: the first key of a possible hold.
            firstStepAt = now
            firstUp = up
            runUp = null
            fired = false
            return
        }
        if (runUp == null) {
            // The second key must start soon after the first, the other way.
            if (up == firstUp || now - firstStepAt > CHORD_MS) {
                firstStepAt = now
                firstUp = up
                return
            }
            runUp = up
        } else if (up != runUp) {
            runUp = null // Not a steady hold.
            firstStepAt = now
            firstUp = up
            return
        }
        if (!fired && now - firstStepAt >= SosHold.HOLD_MS) {
            fired = true
            onSos()
        }
    }
}
