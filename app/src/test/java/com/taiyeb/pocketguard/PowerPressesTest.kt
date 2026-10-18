package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerPressesTest {

    private var calls = 0
    private val presses = PowerPresses { calls++ }

    private fun pressEvery(gapMs: Long, count: Int, from: Long = 0L): Long {
        var t = from
        repeat(count) {
            presses.screenChanged(t)
            t += gapMs
        }
        return t
    }

    @Test
    fun fiveQuickPressesCallForHelp() {
        pressEvery(300, 5)
        assertEquals(1, calls)
    }

    @Test
    fun fourChangesAreEnoughWhenAndroidMergesOne() {
        pressEvery(400, 4)
        assertEquals(1, calls)
    }

    @Test
    fun checkingTheTimeIsNot() {
        // Off, on, off again: three changes in two seconds.
        pressEvery(700, 3)
        assertEquals(0, calls)
    }

    @Test
    fun slowPressesAreNot() {
        // Five presses spread over eight seconds never put four inside the window.
        pressEvery(2000, 5)
        assertEquals(0, calls)
    }

    @Test
    fun itCountsAgainAfterACall() {
        val end = pressEvery(300, 5)
        pressEvery(300, 2, from = end)
        assertEquals(1, calls)
        pressEvery(300, 3, from = end + 600)
        assertEquals(2, calls)
    }

    @Test
    fun resetForgetsEarlierPresses() {
        pressEvery(300, 3)
        presses.reset()
        pressEvery(300, 1, from = 900)
        assertEquals(0, calls)
    }
}
