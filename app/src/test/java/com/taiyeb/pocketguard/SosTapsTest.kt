package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Test

class SosTapsTest {

    private val fired = mutableListOf<String>()
    private val taps = SosTaps { fired += it }

    private fun tapUp(times: Int, gapMs: Long, from: Long = 0L): Long {
        var t = from
        repeat(times) {
            taps.press(up = true, now = t)
            t += gapMs
        }
        return t
    }

    @Test
    fun fourQuickTapsCallForHelpOnce() {
        tapUp(4, 250)
        assertEquals(listOf("volume up tapped 4 times"), fired)
    }

    @Test
    fun threeTapsAreNot() {
        tapUp(3, 250)
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun slowTapsAreNot() {
        // Four taps spread over three seconds: never four inside the window.
        tapUp(4, 1000)
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun volumeDownStartsTheCountOver() {
        val t = tapUp(3, 250)
        taps.press(up = false, now = t)
        tapUp(1, 250, from = t + 100)
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun itCountsAgainAfterACall() {
        val t = tapUp(4, 250)
        assertEquals(1, fired.size)
        tapUp(3, 250, from = t)
        assertEquals(1, fired.size)
        tapUp(1, 250, from = t + 750)
        assertEquals(2, fired.size)
    }

    @Test
    fun resetForgetsEarlierTaps() {
        tapUp(3, 250)
        taps.reset()
        tapUp(1, 250, from = 750)
        assertEquals(emptyList<String>(), fired)
    }
}
