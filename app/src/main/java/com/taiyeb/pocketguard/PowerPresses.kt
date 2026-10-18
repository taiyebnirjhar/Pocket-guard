package com.taiyeb.pocketguard

/**
 * Power pressed quickly about five times: the SOS call that needs no setup and works with the
 * screen off. No app sees the power key itself, but each press turns the screen on or off, so this
 * counts those changes. Android may merge a very quick off-and-on into fewer broadcasts, so
 * [CHANGES] (four) within [WINDOW_MS] counts; checking the time or a fumble (two or three) never does.
 */
class PowerPresses(private val onSos: () -> Unit) {
    companion object {
        const val CHANGES = 4
        const val WINDOW_MS = 4000L
    }

    private val times = ArrayDeque<Long>()

    /** The screen turned on or off at [now] (elapsed realtime, ms). */
    fun screenChanged(now: Long) {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > WINDOW_MS) times.removeFirst()
        if (times.size >= CHANGES) {
            times.clear()
            onSos()
        }
    }

    fun reset() = times.clear()
}
