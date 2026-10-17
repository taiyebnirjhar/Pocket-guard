package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic accelerometer streams: rate, noise, sensor offset and timestamp jitter are all set per test. */
class Signal(seed: Long = 1, val hz: Int = 50, private val noise: Float = 0.01f, private val offset: Float = 0f) {
    private val random = Random(seed)
    private val bias = FloatArray(3) { (random.nextFloat() * 2 - 1) * offset }
    var t = 1_000_000L
        private set

    fun sample(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        x + bias[0] + (random.nextGaussian() * noise).toFloat(),
        y + bias[1] + (random.nextGaussian() * noise).toFloat(),
        z + bias[2] + (random.nextGaussian() * noise).toFloat(),
    )

    /** Calls [onSample] for [ms] of samples; [gen] gets the time since the start in ms. */
    fun run(ms: Long, onSample: (Long, FloatArray) -> Unit, gen: (Long) -> FloatArray) {
        val step = 1000L / hz
        var elapsed = 0L
        while (elapsed < ms) {
            t += step
            elapsed += step
            onSample(t, gen(elapsed))
        }
    }

    companion object {
        const val G = 9.81f

        /** Gravity for a phone tilted [deg] from face-down towards its top edge. */
        fun faceDownTilted(deg: Float): FloatArray {
            val r = deg * PI.toFloat() / 180f
            return floatArrayOf(0f, G * sin(r), -G * cos(r))
        }

        /** Upright (in a trouser pocket) turned [deg] towards face-up. */
        fun uprightTurned(deg: Float): FloatArray {
            val r = deg * PI.toFloat() / 180f
            return floatArrayOf(0f, G * cos(r), G * sin(r))
        }
    }
}

class MotionProcessorTest {

    private fun feed(m: MotionProcessor, s: Signal, ms: Long, gen: (Long) -> FloatArray): Int {
        var events = 0
        s.run(ms, { t, v -> events = events or m.onSample(t, v[0], v[1], v[2]) }) { gen(it).let { a -> s.sample(a[0], a[1], a[2]) } }
        return events
    }

    @Test
    fun stillPhoneIsAtRestWithGravityFound() {
        val m = MotionProcessor()
        val s = Signal()
        feed(m, s, 2000) { floatArrayOf(0f, 0f, -Signal.G) }
        assertTrue(m.atRest)
        assertTrue(m.gravityAngleTo(0f, 0f, -1f) < 1f)
        assertEquals(Signal.G, m.g0, 0.05f)
    }

    @Test
    fun restWorksAtEveryRate() {
        for (hz in listOf(25, 50, 100, 200)) {
            val m = MotionProcessor()
            feed(m, Signal(hz = hz), 2000) { floatArrayOf(0f, 0f, -Signal.G) }
            assertTrue("rest at $hz Hz", m.atRest)
        }
    }

    @Test
    fun noisyHandIsNotRest() {
        val m = MotionProcessor()
        feed(m, Signal(noise = 0.12f), 3000) { floatArrayOf(0f, 0f, -Signal.G) }
        assertFalse(m.atRest)
    }

    @Test
    fun joltDoesNotTiltGravity() {
        val m = MotionProcessor()
        val s = Signal()
        feed(m, s, 2000) { floatArrayOf(0f, Signal.G, 0f) }
        val events = feed(m, s, 400) { floatArrayOf(6f, 6f, 18f) }
        assertTrue(events and MotionProcessor.Event.JOLT != 0)
        assertTrue("gated gravity held", m.gravityAngleTo(0f, 1f, 0f) < 2f)
    }

    @Test
    fun rotationAloneIsNotAJolt() {
        val m = MotionProcessor()
        val s = Signal()
        feed(m, s, 2000) { Signal.uprightTurned(0f) }
        val events = feed(m, s, 600) { Signal.uprightTurned(90f * it / 600f) }
        assertEquals(0, events and MotionProcessor.Event.JOLT)
        feed(m, s, 1500) { Signal.uprightTurned(90f) }
        assertTrue("gravity follows a real turn", m.gravityAngleTo(0f, 0f, 1f) < 5f)
    }

    @Test
    fun sidewaysPushIsAJolt() {
        val m = MotionProcessor()
        val s = Signal()
        feed(m, s, 2000) { floatArrayOf(0f, 0f, Signal.G) }
        val start = s.t
        // 2.6 m/s² across gravity, then braking: |a| rises only 0.34, below the |a| rule.
        var events = feed(m, s, 200) { floatArrayOf(2.6f, 0f, Signal.G) }
        events = events or feed(m, s, 200) { floatArrayOf(-2.6f, 0f, Signal.G) }
        events = events or feed(m, s, 600) { floatArrayOf(0f, 0f, Signal.G) }
        assertTrue(events and MotionProcessor.Event.JOLT != 0)
        assertTrue("dated to the push", m.lastJoltAt - start in 0L..60L)
    }

    @Test
    fun turnAndBackIsNotASidewaysJolt() {
        for (seed in 1L..5L) {
            val m = MotionProcessor()
            val s = Signal(seed = seed, offset = 0.3f, noise = 0.02f)
            feed(m, s, 2000) { Signal.uprightTurned(0f) }
            // 25 degrees and back in 400 ms: far from the old gravity, but |a| stays at 1 g.
            var events = feed(m, s, 400) { t -> Signal.uprightTurned(25f * sin(PI.toFloat() * t / 400f)) }
            events = events or feed(m, s, 1500) { Signal.uprightTurned(0f) }
            assertEquals("seed $seed", 0, events and MotionProcessor.Event.JOLT)
        }
    }

    @Test
    fun noSidewaysJoltsWhileWalking() {
        val m = MotionProcessor()
        val s = Signal(noise = 0.03f)
        // Soft steps (below the |a| rule) and a sideways sway that would pass the sideways one.
        val walk = { t: Long ->
            val phase = (t % 550) / 550f
            floatArrayOf(3f * sin(2 * PI.toFloat() * phase), Signal.G + if (phase < 0.12f) 2f else 0f, 0f)
        }
        feed(m, s, 3000, walk)
        assertTrue(m.walking(s.t))
        val events = feed(m, s, 5000) { walk(it + 3000) }
        assertEquals(0, events and MotionProcessor.Event.JOLT)
    }

    @Test
    fun pushOffTheEdgeIsAFall() {
        val m = MotionProcessor()
        val s = Signal()
        feed(m, s, 2000) { floatArrayOf(0f, 0f, -Signal.G) }
        val events = feed(m, s, 250) { floatArrayOf(0f, 0f, -0.5f) }
        assertTrue(events and MotionProcessor.Event.FALL != 0)
    }

    @Test
    fun hopWithAKickIsNotAFall() {
        val m = MotionProcessor()
        val s = Signal()
        feed(m, s, 2000) { floatArrayOf(0f, 0f, -Signal.G) }
        var events = feed(m, s, 40) { floatArrayOf(0f, 0f, -(Signal.G + 8f)) }
        events = events or feed(m, s, 140) { floatArrayOf(0f, 0f, -0.5f) }
        assertEquals(0, events and MotionProcessor.Event.FALL)
        assertTrue(events and MotionProcessor.Event.HOP != 0)
    }

    @Test
    fun walkingIsDetected() {
        val m = MotionProcessor()
        val s = Signal(noise = 0.05f)
        feed(m, s, 4000) { t ->
            val phase = (t % 550) / 550f
            val impact = if (phase < 0.12f) 3f else 0f
            floatArrayOf(0f, Signal.G + impact, 0f)
        }
        assertTrue(m.walking(s.t))
        assertFalse(m.atRest)
    }

    @Test
    fun fewBumpsAreNotWalking() {
        val m = MotionProcessor()
        val s = Signal()
        feed(m, s, 3000) { t -> floatArrayOf(0f, Signal.G + if (t in 500..540 || t in 1900..1940) 3f else 0f, 0f) }
        assertFalse(m.walking(s.t))
    }

    @Test
    fun offsetSensorStillRestsAndFindsItsOwnOneG() {
        val m = MotionProcessor()
        feed(m, Signal(offset = 0.3f, noise = 0.02f), 2500) { floatArrayOf(0f, 0f, -Signal.G) }
        assertTrue(m.atRest)
        assertTrue(kotlin.math.abs(m.g0 - Signal.G) < 0.6f)
    }
}
