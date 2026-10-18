package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Test

class SosHoldTest {

    /** A fake clock: tasks run when time passes their due time, like a Handler. */
    private var now = 0L
    private val due = mutableMapOf<Runnable, Long>()
    private val fired = mutableListOf<String>()
    private val hold = SosHold(
        post = { task, delayMs -> due[task] = now + delayMs },
        cancel = { task -> due.remove(task) },
    ) { fired += it }

    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = due.entries.filter { it.value <= end }.minByOrNull { it.value } ?: break
            now = next.value
            due.remove(next.key)
            next.key.run()
        }
        now = end
    }

    private fun up(down: Boolean) = hold.press(up = true, down = down)
    private fun volumeDown(down: Boolean) = hold.press(up = false, down = down)

    @Test
    fun volumeUpHeldCallsForHelpOnce() {
        up(true)
        advance(SosHold.HOLD_MS - 1)
        assertEquals(emptyList<String>(), fired)
        advance(1)
        assertEquals(listOf("volume up held"), fired)
        advance(10_000)
        assertEquals(listOf("volume up held"), fired)
    }

    @Test
    fun aVolumeChangeIsNot() {
        repeat(5) {
            up(true)
            advance(800)
            up(false)
            advance(200)
        }
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun volumeDownIsNot() {
        volumeDown(true)
        advance(10_000)
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun volumeDownTooCancelsIt() {
        up(true)
        advance(1000)
        volumeDown(true)
        advance(10_000)
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun lettingGoStartsTheCountAgain() {
        up(true)
        advance(1500)
        up(false)
        up(true)
        advance(SosHold.HOLD_MS - 1)
        assertEquals(emptyList<String>(), fired)
        advance(1)
        assertEquals(listOf("volume up held"), fired)
    }

    @Test
    fun resetForgetsHeldKeys() {
        up(true)
        advance(1000)
        hold.reset()
        advance(10_000)
        assertEquals(emptyList<String>(), fired)
    }
}
