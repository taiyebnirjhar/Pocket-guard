package com.taiyeb.pocketguard

import kotlin.math.PI
import kotlin.math.sign
import kotlin.math.sin

/** Sounds are generated in code, so the app ships no audio files. */
object Tones {
    const val SAMPLE_RATE = 44100

    /** Double beep followed by a pause; looped while the warning is active. */
    fun beeps(): ShortArray {
        val beep = (SAMPLE_RATE * 0.15).toInt()
        val gap = (SAMPLE_RATE * 0.1).toInt()
        val pause = (SAMPLE_RATE * 0.35).toInt()
        val out = ShortArray((beep + gap) * 2 + pause)
        var i = 0
        repeat(2) {
            for (n in 0 until beep) {
                out[i++] = (sin(2 * PI * 1200 * n / SAMPLE_RATE) * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
            i += gap
        }
        return out
    }

    /**
     * The SOS sound, unlike the theft siren: 1.2 s of a shrill whistle trill (about 3 kHz,
     * warbling 25 times a second, like a referee's whistle), then 1.8 s of silence for the
     * spoken call for help. Looped every [SOS_PERIOD_MS].
     */
    const val SOS_PERIOD_MS = 3000L
    const val SOS_WHISTLE_MS = 1200L

    fun sos(): ShortArray {
        val out = ShortArray((SAMPLE_RATE * SOS_PERIOD_MS / 1000).toInt())
        val whistle = (SAMPLE_RATE * SOS_WHISTLE_MS / 1000).toInt()
        var phase = 0.0
        for (n in 0 until whistle) {
            val t = n.toDouble() / SAMPLE_RATE
            phase += 2 * PI * (3000 + 250 * sin(2 * PI * 25 * t)) / SAMPLE_RATE
            val fade = minOf(1.0, n / 400.0, (whistle - n) / 400.0)
            out[n] = (sin(phase) * 0.95 * fade * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** One second of a harsh up-and-down sweep; looped for the siren. */
    fun siren(): ShortArray {
        val length = SAMPLE_RATE
        val out = ShortArray(length)
        var phase = 0.0
        for (n in 0 until length) {
            val t = n.toDouble() / length
            val triangle = if (t < 0.5) t * 2 else (1 - t) * 2
            phase += 2 * PI * (700 + 900 * triangle) / SAMPLE_RATE
            val s = sin(phase)
            out[n] = ((0.7 * s + 0.3 * sign(s)) * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
