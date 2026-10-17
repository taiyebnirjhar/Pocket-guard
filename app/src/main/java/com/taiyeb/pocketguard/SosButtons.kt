package com.taiyeb.pocketguard

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * The SOS trigger: volume up pressed [PRESSES] times within [WINDOW_MS], screen on or off, while
 * the guard runs. Only an app holding the active media session hears the volume keys with the
 * screen off, so it keeps one, and gets out of the way of everything else: each press still
 * changes the volume as usual, it steps aside while another app plays media (that app then owns
 * the keys, and SOS can't hear them), and play/pause from earbuds is passed on, so music resumes.
 * Holding the key doesn't count: its repeats come faster than any tap.
 */
class SosButtons(
    private val context: Context,
    private val log: (String) -> Unit,
    /** The app's own warning or siren is sounding: that's not another app's media. */
    private val ownSound: () -> Boolean,
    private val onSos: () -> Unit,
) {
    companion object {
        const val PRESSES = 5
        const val WINDOW_MS = 3000L

        /** Key repeats of a held button come every ~50 ms; taps are well apart. */
        const val MIN_TAP_GAP_MS = 90L
        private const val MEDIA_CHECK_MS = 2000L
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private val taps = LongArray(PRESSES)
    private var tapCount = 0
    private var lastAdjustAt = Long.MIN_VALUE / 2
    private var steppedAside = false

    private val volume = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
        override fun onAdjustVolume(direction: Int) {
            if (direction != 0) log("SOS buttons: volume ${if (direction > 0) "up" else "down"}")
            // Volume works as always: pass the press on to whatever stream it would have moved.
            if (direction != 0) {
                val flags = if (power.isInteractive) AudioManager.FLAG_SHOW_UI else 0
                audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, flags)
            }
            if (direction == AudioManager.ADJUST_RAISE) onRaise()
        }
    }

    /** Media from another app wants the keys: step aside until it stops. */
    private val mediaCheck = object : Runnable {
        override fun run() {
            val busy = audio.isMusicActive && !ownSound()
            if (busy && !steppedAside) {
                steppedAside = true
                session?.isActive = false
                log("SOS buttons paused: another app is playing media")
            } else if (!busy && steppedAside) {
                steppedAside = false
                session?.isActive = true
                log("SOS buttons listening again")
            }
            handler.postDelayed(this, MEDIA_CHECK_MS)
        }
    }

    fun start() {
        if (session != null) return
        val s = MediaSession(context, "PocketGuardSos")
        s.setPlaybackToRemote(volume)
        s.setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 0f).setActions(0).build(),
        )
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                // Earbud play/pause came to us only because our session is the active one: hand it
                // back so the music app gets it.
                @Suppress("DEPRECATION")
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                stepAsideFor(event)
                return true
            }
        })
        steppedAside = audio.isMusicActive && !ownSound()
        s.isActive = !steppedAside
        session = s
        handler.postDelayed(mediaCheck, MEDIA_CHECK_MS)
        log(if (steppedAside) "SOS buttons paused: another app is playing media" else "SOS buttons listening (volume up ×$PRESSES)")
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        tapCount = 0
    }

    private fun stepAsideFor(event: KeyEvent) {
        val s = session ?: return
        // Inactive now, so this and the key's release reach the app that played last.
        s.isActive = false
        steppedAside = true
        audio.dispatchMediaKeyEvent(event)
    }

    private fun onRaise() {
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastAdjustAt
        lastAdjustAt = now
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
