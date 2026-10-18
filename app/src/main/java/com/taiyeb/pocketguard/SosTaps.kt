package com.taiyeb.pocketguard

/**
 * The SOS key gesture: volume up tapped [TAPS] times within [WINDOW_MS]. One button, quick, found
 * by feel, and used by nothing else on the phone. The service leaves taps out while music, a video
 * or a call is playing (when people tap volume up to hear better); volume down starts the count
 * over, and holding volume up counts once. Main thread.
 */
class SosTaps(private val onTaps: (gesture: String) -> Unit) {
    companion object {
        const val TAPS = 4
        const val WINDOW_MS = 2000L
    }

    private val times = ArrayDeque<Long>()

    /** A key went down (not a repeat from holding it) at [now] (ms, any steady clock). */
    fun press(up: Boolean, now: Long) {
        if (!up) {
            times.clear()
            return
        }
        times.addLast(now)
        while (now - times.first() > WINDOW_MS) times.removeFirst()
        if (times.size >= TAPS) {
            times.clear()
            onTaps("volume up tapped $TAPS times")
        }
    }

    fun reset() = times.clear()
}
