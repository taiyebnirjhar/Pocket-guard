package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Test

class SosTapsTest {

    private val fired = mutableListOf<String>()
    private val silent = mutableListOf<Boolean>()
    private val taps = SosTaps { gesture, quiet ->
        fired += gesture
        silent += quiet
    }

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
        assertEquals(listOf(false), silent)
    }

    private fun tapDown(times: Int, gapMs: Long, from: Long = 0L): Long {
        var t = from
        repeat(times) {
            taps.press(up = false, now = t)
            t += gapMs
        }
        return t
    }

    @Test
    fun fourQuickTapsDownCallForHelpSilently() {
        tapDown(4, 250)
        assertEquals(listOf("volume down tapped 4 times"), fired)
        assertEquals(listOf(true), silent)
    }

    @Test
    fun mixedKeysAreNot() {
        // Up, down, up, down: someone adjusting the volume, not a gesture.
        var t = 0L
        repeat(4) { i ->
            taps.press(up = i % 2 == 0, now = t)
            t += 250
        }
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun volumeUpStartsTheDownCountOver() {
        val t = tapDown(3, 250)
        taps.press(up = true, now = t)
        tapDown(1, 250, from = t + 100)
        assertEquals(emptyList<String>(), fired)
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
