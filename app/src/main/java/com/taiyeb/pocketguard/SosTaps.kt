package com.taiyeb.pocketguard

/**
 * The SOS key gestures: volume up tapped [TAPS] times within [WINDOW_MS] is the loud SOS, volume
 * down tapped the same way the silent one. One button, quick, found by feel, and used by nothing
 * else on the phone. The service leaves taps out while music, a video or a call is playing (when
 * people change the volume to hear better); a press of the other key starts the count over, and
 * holding a key counts once. Main thread.
 */
class SosTaps(private val onTaps: (gesture: String, silent: Boolean) -> Unit) {
    companion object {
        const val TAPS = 4
        const val WINDOW_MS = 2000L
    }

    private val times = ArrayDeque<Long>()

    /** Which key the taps in [times] are on. */
    private var countingUp = true

    /** A key went down (not a repeat from holding it) at [now] (ms, any steady clock). */
    fun press(up: Boolean, now: Long) {
        if (up != countingUp) {
            times.clear()
            countingUp = up
        }
        times.addLast(now)
        while (now - times.first() > WINDOW_MS) times.removeFirst()
        if (times.size >= TAPS) {
            times.clear()
            onTaps("volume ${if (up) "up" else "down"} tapped $TAPS times", !up)
        }
    }

    fun reset() = times.clear()
}
