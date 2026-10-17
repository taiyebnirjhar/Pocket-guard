package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Table mode and the motion-based pocket rules, driven by synthetic accelerometer streams.
 * Samples and timers interleave as on a phone: each sample advances the clock first.
 */
class MotionRulesTest {

    private lateinit var host: GuardEngineTest.FakeHost
    private lateinit var engine: GuardEngine
    private var random = Random(7)
    private var noise = 0.01f
    private var stepMs = 20L
    private var bias = floatArrayOf(0f, 0f, 0f)

    @Before
    fun setUp() {
        host = GuardEngineTest.FakeHost()
        engine = GuardEngine(host, hasMotionSensor = true, hasLightSensor = true)
        host.engine = engine
        engine.arm()
        engine.onProximity(false)
    }

    /**
     * Streams [ms] of samples from [gen] (time since start, ms) with sensor noise and offset. The
     * app's own buzzes shake the sensor for the 80 ms after them, as a motor on a hard table does.
     */
    private fun stream(ms: Long, gen: (Long) -> FloatArray) {
        var elapsed = 0L
        while (elapsed < ms) {
            host.advance(stepMs)
            elapsed += stepMs
            val v = gen(elapsed)
            val n = if (host.time - host.lastBuzzAt in 0L..80L) 0.5f else noise
            engine.onAccel(
                host.time,
                v[0] + bias[0] + (random.nextGaussian() * n).toFloat(),
                v[1] + bias[1] + (random.nextGaussian() * n).toFloat(),
                v[2] + bias[2] + (random.nextGaussian() * n).toFloat(),
            )
        }
    }

    private fun still(v: FloatArray, ms: Long) = stream(ms) { v }

    private val G = 9.81f
    private val faceDown = floatArrayOf(0f, 0f, -G)
    private val faceUp = floatArrayOf(0f, 0f, G)
    private val upright = floatArrayOf(0f, G, 0f)

    private fun faceDownTilted(deg: Float): FloatArray {
        val r = deg * PI.toFloat() / 180f
        return floatArrayOf(0f, G * sin(r), -G * cos(r))
    }

    private fun uprightTurned(deg: Float): FloatArray {
        val r = deg * PI.toFloat() / 180f
        return floatArrayOf(0f, G * cos(r), G * sin(r))
    }

    /**
     * Lying face-up on a thigh that turns [deg] about the hip ([r] m away) and back over [ms]: the
     * reading includes the swing's own acceleration, as a rigid body does.
     */
    private fun pivoted(deg: Double, ms: Long, r: Double, t: Long): FloatArray {
        fun theta(s: Double) = if (s <= 0 || s >= ms / 1000.0) 0.0 else
            Math.toRadians(deg) * (1 - kotlin.math.cos(2 * PI * s / (ms / 1000.0))) / 2
        fun pos(s: Double) = doubleArrayOf(r * kotlin.math.cos(theta(s)) - r, r * kotlin.math.sin(theta(s)))
        val s = t / 1000.0
        val h = 0.001
        val a = pos(s + h)
        val b = pos(s)
        val c = pos(s - h)
        val fy = (a[0] - 2 * b[0] + c[0]) / (h * h)
        val fz = (a[1] - 2 * b[1] + c[1]) / (h * h) + G
        val th = theta(s)
        return floatArrayOf(
            0f,
            (fy * kotlin.math.cos(th) + fz * kotlin.math.sin(th)).toFloat(),
            (-fy * kotlin.math.sin(th) + fz * kotlin.math.cos(th)).toFloat(),
        )
    }

    /** Minimum-jerk acceleration for travelling [cm] in [ms] (peaks at about 5.8 d / T²). */
    private fun minJerkAccel(cm: Float, ms: Long, t: Long): Float {
        val d = cm / 100f
        val T = ms / 1000f
        val s = (t / 1000f / T).coerceIn(0f, 1f)
        return d / (T * T) * (60f * s - 180f * s * s + 120f * s * s * s)
    }

    /** Placed face-down: covered while flat, then still on a hard table until it is protected. */
    private fun placeOnTable() {
        still(faceDown, 1000)
        engine.onProximity(true)
        still(faceDown, 14_000)
        assertEquals(Phase.ON_TABLE, engine.phase)
    }

    private fun pocketUpright() {
        still(upright, 1000)
        engine.onProximity(true)
        still(upright, 3000)
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    private fun warnedWith(evidence: Evidence) {
        assertEquals(Phase.WARNING, engine.phase)
        assertEquals(evidence, engine.evidence)
    }

    private fun neverWarned() = assertFalse(host.transitions.any { it.second.isAlarm })

    // --- Robustness: the same decisions across sensors ---------------------------------------------

    @Test
    fun decisionsHoldAcrossRatesNoiseAndOffsets() {
        for (seed in 1..5) {
            for (hz in listOf(25, 50, 100)) {
                val label = "seed $seed at $hz Hz"
                setUp()
                random = Random(seed.toLong())
                stepMs = 1000L / hz
                noise = 0.02f
                bias = FloatArray(3) { (random.nextFloat() * 2 - 1) * 0.3f }

                still(faceDown, 1000)
                engine.onProximity(true)
                still(faceDown, 14_000)
                assertEquals(label, Phase.ON_TABLE, engine.phase)

                stream(80) { t -> val k = if (t <= 40) 1.5f else -1.5f; floatArrayOf(0f, 0f, -G - k) }
                still(faceDown, 2000)
                assertEquals("$label knock", Phase.ON_TABLE, engine.phase)

                stream(1200) { t -> floatArrayOf(minJerkAccel(20f, 1200, t), 0f, -G) }
                still(faceDown, 2000)
                assertEquals("$label slide", Phase.ON_TABLE, engine.phase)
                assertEquals("$label slide chirp", 1, host.chirps.size)

                stream(600) { t -> faceDownTilted(60f * t / 600f) }
                still(faceDownTilted(60f), 400)
                assertEquals("$label tilt", Phase.WARNING, engine.phase)
            }
        }
    }

    @Test
    fun walkingPocketStaysQuietAcrossSeeds() {
        for (seed in 1..5) {
            setUp()
            random = Random(seed.toLong())
            noise = 0.15f
            val swing = { t: Long ->
                val sw = 20f * sin(2 * PI.toFloat() * t / 1100f)
                val v = uprightTurned(sw)
                if (t % 550 < 60) floatArrayOf(v[0], v[1] + 3f, v[2]) else v
            }
            still(upright, 1000)
            engine.onProximity(true)
            stream(4000, swing)
            // Fabric flicking off the sensor, on and off, while walking for 20 s.
            var elapsed = 4000L
            repeat(10) { i ->
                engine.onProximity(i % 2 == 1)
                stream(2000) { swing(it + elapsed) }
                elapsed += 2000
            }
            assertFalse("seed $seed", host.transitions.any { it.second.isAlarm })
        }
    }

    // --- Entering table mode ------------------------------------------------------------------------

    @Test
    fun faceDownOnTableBecomesProtectedWithDoubleTick() {
        placeOnTable()
        assertEquals(1, host.tableTicks)
        assertEquals(1, host.confirmBuzzes) // The pocket buzz first, then the table's double tick.
    }

    @Test
    fun uprightInPocketNeverBecomesTable() {
        pocketUpright()
        still(upright, 15_000)
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    @Test
    fun breathingLapIsNotATable() {
        still(faceDown, 1000)
        engine.onProximity(true)
        // 0.25 Hz rocking of about 1 degree: a lap or a body, however still it is otherwise.
        stream(20_000) { t -> faceDownTilted(0.5f * sin(2 * PI.toFloat() * 0.25f * t / 1000f)) }
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    @Test
    fun unlockedPhoneOnTableWaitsForTheLock() {
        host.credentialLocked = false
        still(faceDown, 1000)
        engine.onProximity(true)
        still(faceDown, 14_000)
        assertEquals(Phase.IN_POCKET, engine.phase)
        host.credentialLocked = true
        still(faceDown, 9000)
        assertEquals(Phase.ON_TABLE, engine.phase)
    }

    @Test
    fun coveredWhileUprightThenLaidFlatIsNotTable() {
        still(upright, 1000)
        engine.onProximity(true) // Covered in a pocket while standing.
        still(upright, 3000)
        stream(1000) { t -> uprightTurned(-90f * t / 1000f) } // Sitting down, phone ends up face-down.
        still(faceDown, 15_000)
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    @Test
    fun tableModeCanBeTurnedOff() {
        host.config = host.config.copy(tableGuard = false)
        still(faceDown, 1000)
        engine.onProximity(true)
        still(faceDown, 15_000)
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    // --- Taking it off the table ----------------------------------------------------------------------

    @Test
    fun turnedOverToLookWarns() {
        placeOnTable()
        stream(600) { t -> faceDownTilted(60f * t / 600f) }
        still(faceDownTilted(60f), 400)
        warnedWith(Evidence.TABLE_TILT)
        assertEquals(Trigger.TABLE_MOVED, host.transitions.last().third)
    }

    @Test
    fun liftedLevelWarns() {
        placeOnTable()
        // Up 5 cm in 0.5 s without tilting: |a| rises then falls.
        stream(500) { t -> floatArrayOf(0f, 0f, -(G + minJerkAccel(5f, 500, t))) }
        still(faceDown, 300)
        warnedWith(Evidence.TABLE_LIFT)
    }

    @Test
    fun slowLiftHeldInHandWarns() {
        placeOnTable()
        noise = 0.15f // A hand is never as still as a table.
        stream(4000) { t -> faceDownTilted(6f * minOf(1f, t / 2000f)) }
        assertTrue(engine.phase == Phase.WARNING)
        assertTrue(engine.evidence == Evidence.TABLE_HELD || engine.evidence == Evidence.TABLE_POSE || engine.evidence == Evidence.TABLE_LIFT)
    }

    @Test
    fun pushedOffTheEdgeWarns() {
        placeOnTable()
        stream(300) { floatArrayOf(0f, 0f, -0.3f) }
        warnedWith(Evidence.TABLE_FALL)
    }

    @Test
    fun uncoveredWithALiftWarns() {
        placeOnTable()
        stream(300) { t -> faceDownTilted(8f * t / 300f) }
        engine.onProximity(false)
        still(faceDownTilted(8f), 400)
        assertEquals(Phase.WARNING, engine.phase)
        assertTrue(engine.evidence == Evidence.TABLE_UNCOVER || engine.evidence == Evidence.TABLE_TILT)
    }

    @Test
    fun proximityGlitchWithNoMotionIsIgnored() {
        placeOnTable()
        engine.onProximity(false)
        still(faceDown, 1500)
        engine.onProximity(true)
        still(faceDown, 500)
        assertEquals(Phase.ON_TABLE, engine.phase)
        neverWarned()
    }

    @Test
    fun stalledAccelerometerFailsSafeOnUncover() {
        placeOnTable()
        engine.onProximity(false)
        host.advance(2500) // No samples at all.
        warnedWith(Evidence.MOTION_UNKNOWN)
    }

    @Test
    fun slideOnlyChirps() {
        placeOnTable()
        // Slid 20 cm along the table in 1.2 s.
        stream(1200) { t -> floatArrayOf(minJerkAccel(20f, 1200, t), 0f, -G) }
        still(faceDown, 2000)
        assertEquals(Phase.ON_TABLE, engine.phase)
        assertEquals(listOf(false), host.chirps)
        neverWarned()
    }

    @Test
    fun repeatedSlidesBeepLonger() {
        placeOnTable()
        repeat(4) {
            stream(1200) { t -> floatArrayOf(minJerkAccel(20f, 1200, t), 0f, -G) }
            still(faceDown, 2000)
        }
        assertEquals(listOf(false, false, false, true), host.chirps)
    }

    @Test
    fun knockOnTheTableIsIgnored() {
        placeOnTable()
        // A knock: pushed down and back, so the phone ends where it was, at rest.
        stream(80) { t -> val k = if (t <= 40) 1.5f else -1.5f; floatArrayOf(0.8f * k / 1.5f, 0.4f * k / 1.5f, -G - k) }
        still(faceDown, 2000)
        assertEquals(Phase.ON_TABLE, engine.phase)
        neverWarned()
        assertTrue(host.chirps.isEmpty())
    }

    @Test
    fun vehiclePullingAwayLeavesTableSilently() {
        placeOnTable()
        noise = 0.08f // Road vibration.
        stream(7000) { floatArrayOf(1.2f, 0f, -G) }
        assertEquals(Phase.IN_POCKET, engine.phase)
        neverWarned()
    }

    @Test
    fun idlingThenPullingAwayLeavesTableSilently() {
        placeOnTable()
        noise = 0.08f
        still(faceDown, 1500) // Engine idling at the lights: the launch comes partway into a burst.
        stream(7000) { floatArrayOf(1.2f, 0f, -G) }
        assertEquals(Phase.IN_POCKET, engine.phase)
        neverWarned()
    }

    @Test
    fun smallTiltProppedUpStaysProtected() {
        placeOnTable()
        // One edge raised a few degrees onto something and left: no car, so it stays protected.
        stream(1000) { t -> faceDownTilted(6f * t / 1000f) }
        still(faceDownTilted(6f), 10_000)
        assertEquals(Phase.ON_TABLE, engine.phase)
    }

    @Test
    fun proppedUpPhoneIgnoresASensorGlitch() {
        placeOnTable()
        stream(1000) { t -> faceDownTilted(6f * t / 1000f) }
        still(faceDownTilted(6f), 3000)
        engine.onProximity(false) // Nothing moved it: the sensor flickered.
        still(faceDownTilted(6f), 1500)
        engine.onProximity(true)
        still(faceDownTilted(6f), 1000)
        assertEquals(Phase.ON_TABLE, engine.phase)
        neverWarned()
    }

    @Test
    fun proppedUpPhoneWithASensorOffsetStaysProtected() {
        bias = floatArrayOf(0f, 0.3f, 0f) // Along the way it gets tilted.
        placeOnTable()
        stream(1000) { t -> faceDownTilted(5f * t / 1000f) }
        still(faceDownTilted(5f), 15_000)
        assertEquals(Phase.ON_TABLE, engine.phase)
        neverWarned()
    }

    @Test
    fun proppedUpPhoneOnABusyTableIsNotHeld() {
        placeOnTable()
        stream(1500) { t -> faceDownTilted(5f * t / 1500f) }
        still(faceDownTilted(5f), 6000)
        noise = 0.05f // Writing, typing, cutlery on the table: it is never still.
        still(faceDownTilted(5f), 8000)
        assertEquals(Phase.ON_TABLE, engine.phase)
        neverWarned()
    }

    @Test
    fun carefulLiftWithAPalmOverTheSensorWarns() {
        placeOnTable()
        // Lifted 10 cm over 1.5 s, tilting 5 degrees as it goes, the sensor kept covered.
        stream(1500) { t ->
            val k = 1f + minJerkAccel(10f, 1500, t) / G
            faceDownTilted(5f * t.coerceAtMost(400L) / 400f).map { it * k }.toFloatArray()
        }
        noise = 0.05f // In the hand.
        still(faceDownTilted(5f), 500)
        stream(4000) { t -> faceDownTilted(5f).map { it * (1f + if (t % 550 < 60) 0.3f else 0f) }.toFloatArray() }
        assertTrue(host.transitions.any { it.second.isAlarm })
        assertFalse(host.transitions.any { it.first == Phase.ON_TABLE && it.second == Phase.IN_POCKET })
    }

    @Test
    fun stopAndGoDrivingStaysQuiet() {
        // Laid on the seat while parked, then city traffic: pushes of 1-2.5 m/s² for 2-4.5 s, mild
        // road vibration (below what confirms a vehicle), never a 5 s push.
        for (seed in 1L..3L) {
            setUp()
            val plan = Random(seed)
            placeOnTable()
            noise = 0.04f
            var left = 120_000L
            while (left > 0) {
                val amp = 1f + 1.5f * plan.nextFloat()
                val ms = 2000L + plan.nextInt(2500)
                val dir = plan.nextInt(4)
                stream(ms) { t ->
                    val ramp = (minOf(t, ms - t).coerceAtLeast(0L) / 400f).coerceAtMost(1f)
                    val h = amp * ramp
                    when (dir) {
                        0 -> floatArrayOf(h, 0f, -G)
                        1 -> floatArrayOf(-h, 0f, -G)
                        2 -> floatArrayOf(0f, h, -G)
                        else -> floatArrayOf(0f, -h, -G)
                    }
                }
                val gap = 2000L + plan.nextInt(6000)
                still(faceDown, gap)
                left -= ms + gap
            }
            noise = 0.01f
            assertTrue("seed $seed", host.transitions.none { it.second.isAlarm })
        }
    }

    @Test
    fun idlingCarThenDrivingOffStaysQuiet() {
        // Laid on the seat while parked; the engine starts and idles, then stop-and-go driving.
        // Many pushes escape the |a| test (a sensor offset against them), so the idle shake must
        // already have marked it a vehicle.
        val plan = Random(99)
        repeat(24) { i ->
            setUp()
            random = Random(1000L + i)
            bias = FloatArray(3) { (plan.nextFloat() * 2 - 1) * 0.3f }
            noise = 0.005f + 0.02f * plan.nextFloat()
            placeOnTable()
            noise = 0.08f + 0.08f * plan.nextFloat() // Engine vibration.
            still(faceDown, 4000L + plan.nextInt(11_000))
            var left = 60_000L
            while (left > 0 && engine.phase == Phase.ON_TABLE) {
                val amp = 0.6f + 2.4f * plan.nextFloat()
                val ms = 1500L + plan.nextInt(6000)
                val az = 2 * PI.toFloat() * plan.nextFloat()
                stream(ms) { t ->
                    val h = amp * (minOf(t, ms - t).coerceAtLeast(0L) / 500f).coerceAtMost(1f)
                    floatArrayOf(h * cos(az), h * sin(az), -G)
                }
                val gap = 1000L + plan.nextInt(8000)
                still(faceDown, gap)
                left -= ms + gap
            }
            assertTrue("drive $i", host.transitions.none { it.second.isAlarm })
        }
        noise = 0.01f
        bias = floatArrayOf(0f, 0f, 0f)
    }

    @Test
    fun vibrationAloneNeverDropsTableMode() {
        placeOnTable()
        noise = 0.1f // An idling engine, or someone shaking the table: no push, so it stays guarded.
        still(faceDown, 15_000)
        noise = 0.01f
        still(faceDown, 2000)
        assertEquals(Phase.ON_TABLE, engine.phase)
        neverWarned()
    }

    @Test
    fun placementSettlingAfterThePocketBuzzStillEnters() {
        noise = 0.12f // In the hand.
        stream(1500) { t -> faceDownTilted(3f + 2f * sin(2 * PI.toFloat() * t / 700f)) }
        engine.onProximity(true)
        // Touching down and letting go takes most of a second; the pocket buzz comes at 1.5 s.
        stream(900) { t -> faceDownTilted(1f + 2f * (1f - t / 900f) * sin(2 * PI.toFloat() * t / 300f)) }
        noise = 0.01f
        still(faceDownTilted(1f), 14_000)
        assertEquals(1, host.confirmBuzzes)
        assertEquals(Phase.ON_TABLE, engine.phase)
    }

    @Test
    fun laidDownEdgeFirstStillBecomesProtected() {
        still(faceDownTilted(15f), 1000)
        engine.onProximity(true) // Covered on the way down, one edge first.
        stream(400) { t -> faceDownTilted(15f - 15f * t / 400f) }
        still(faceDown, 14_000)
        assertEquals(Phase.ON_TABLE, engine.phase)
    }

    @Test
    fun seatedPocketIsNotATable() {
        // Sitting, screen to the thigh: flat and still like a table, but breathing rocks it 0.3 degrees.
        still(faceDownTilted(3f), 1000)
        engine.onProximity(true)
        stream(30_000) { t -> faceDownTilted(3f + 0.15f * sin(2 * PI.toFloat() * 0.2f * t / 1000f)) }
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    @Test
    fun smoothHardAccelerationIsNotATilt() {
        placeOnTable()
        // An electric car: no vibration, 2 m/s² for 6 s (fakes an 11.5 degree tilt).
        stream(6000) { floatArrayOf(2f, 0f, -G) }
        still(faceDown, 3000)
        neverWarned()
    }

    @Test
    fun liftedOntoABookAtAnAngleWarns() {
        placeOnTable()
        stream(1500) { t -> faceDownTilted(15f * t / 1500f) }
        still(faceDownTilted(15f), 3000)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun putBackAfterAGlanceRecovers() {
        host.config = host.config.copy(graceMs = 5000)
        placeOnTable()
        stream(600) { t -> faceDownTilted(70f * t / 600f) }
        still(faceDownTilted(70f), 400)
        warnedWith(Evidence.TABLE_TILT)
        stream(600) { t -> faceDownTilted(70f - 70f * t / 600f) }
        still(faceDown, 3000)
        assertEquals(Phase.ON_TABLE, engine.phase)
        assertFalse(host.transitions.any { it.second == Phase.SIREN })
    }

    @Test
    fun putBackFaceDownNeedsTheSensorCoveredAgain() {
        placeOnTable()
        stream(300) { t -> faceDownTilted(8f * t / 300f) }
        engine.onProximity(false)
        stream(600) { t -> faceDownTilted(8f + 60f * t / 600f) }
        assertEquals(Phase.WARNING, engine.phase)
        // Laid back face-down but something still keeps the sensor uncovered: not put back.
        still(faceDown, 3000)
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun putBackRecoveriesAreLimited() {
        placeOnTable()
        repeat(4) {
            stream(600) { t -> faceDownTilted(70f * t / 600f) }
            still(faceDownTilted(70f), 400)
            stream(600) { t -> faceDownTilted(70f - 70f * t / 600f) }
            still(faceDown, 3000)
        }
        assertTrue(engine.phase.isAlarm)
    }

    @Test
    fun ownerUnlockOnTableRearms() {
        placeOnTable()
        stream(600) { t -> faceDownTilted(60f * t / 600f) }
        still(faceDownTilted(60f), 400)
        engine.onOwnerUnlocked()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun pickedUpToAnswerACallIsTheOwner() {
        placeOnTable()
        host.ringing = true
        stream(600) { t -> faceDownTilted(60f * t / 600f) }
        still(faceDownTilted(60f), 400)
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
        neverWarned()
    }

    @Test
    fun answeringACallInSilentModeIsTheOwner() {
        host.config = host.config.copy(graceMs = 10_000)
        placeOnTable()
        // Nothing rang, so the pick-up warns; answering the call shows it was the owner.
        stream(600) { t -> faceDownTilted(60f * t / 600f) }
        still(faceDownTilted(60f), 400)
        warnedWith(Evidence.TABLE_TILT)
        engine.onCallAnswered()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
        still(upright, 12_000)
        assertFalse(host.transitions.any { it.second == Phase.SIREN })
    }

    @Test
    fun answeringACallDoesNotStopAPocketAlarm() {
        pocketUpright()
        engine.onProximity(false)
        stream(300) { floatArrayOf(6f, 6f, 18f) }
        still(upright, 2000)
        warnedWith(Evidence.JOLT)
        engine.onCallAnswered()
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun noDelayGivesATablePickUpAMomentFirst() {
        host.config = host.config.copy(graceMs = 0)
        placeOnTable()
        stream(600) { t -> faceDownTilted(60f * t / 600f) }
        still(faceDownTilted(60f), 400)
        assertEquals(Phase.WARNING, engine.phase)
        still(faceDownTilted(60f), GuardEngine.FAST_TABLE_WARNING_MS)
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun airplaneModeOnTheTableSirens() {
        placeOnTable()
        engine.onAirplaneModeOn()
        assertEquals(Phase.SIREN, engine.phase)
    }

    // --- Pocket: the gentle pull -------------------------------------------------------------------------

    @Test
    fun gentlePullTurnedToLookWarns() {
        pocketUpright()
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(80f * t / 800f) } // No jolt: a slow, careful pull.
        engine.onDisplayOn() // The thief presses power to look.
        still(uprightTurned(80f), 1500)
        warnedWith(Evidence.TILT_OUT)
    }

    @Test
    fun screenWokenJustBeforeItCameOutIsACue() {
        pocketUpright()
        engine.onDisplayOn() // Power pressed while drawing it out.
        still(upright, 300)
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(85f * t / 800f) }
        still(uprightTurned(85f), 1500)
        warnedWith(Evidence.TILT_OUT)
    }

    @Test
    fun gentlePullWithoutLookingKeepsWatchingThenCatchesTheLook() {
        pocketUpright()
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(80f * t / 800f) }
        still(uprightTurned(80f), 4000)
        assertEquals(Phase.IN_POCKET, engine.phase)
        neverWarned()
        // Later the thief turns it over to look at it.
        stream(600) { t -> uprightTurned(80f + 60f * t / 600f) }
        engine.onDisplayOn()
        still(uprightTurned(140f), 1000)
        warnedWith(Evidence.TILT_OUT)
    }

    @Test
    fun outOfOnePocketAndIntoAnotherWarns() {
        pocketUpright()
        engine.onProximity(false)
        stream(700) { t -> uprightTurned(90f * t / 700f) }
        still(uprightTurned(90f), 1000)
        // Into the thief's pocket, sideways.
        stream(700) { t ->
            val r = (90f * t / 700f) * PI.toFloat() / 180f
            floatArrayOf(G * sin(r), 0f, G * cos(r))
        }
        engine.onProximity(true)
        still(floatArrayOf(G, 0f, 0f), 2500)
        warnedWith(Evidence.OUT_AND_IN)
    }

    @Test
    fun slowPocketSwapStillWarns() {
        pocketUpright()
        engine.onProximity(false)
        stream(1000) { t -> uprightTurned(90f * t / 1000f) }
        still(uprightTurned(90f), 2000) // Looked at it for a while.
        stream(800) { t ->
            val r = (90f * t / 800f) * PI.toFloat() / 180f
            floatArrayOf(G * sin(r), 0f, G * cos(r))
        }
        engine.onProximity(true)
        still(floatArrayOf(G, 0f, 0f), 2500)
        warnedWith(Evidence.OUT_AND_IN)
    }

    @Test
    fun sittingDownWithAnOpenPocketIsNotTheft() {
        pocketUpright()
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(85f * t / 800f) } // One smooth turn, seat reached.
        still(uprightTurned(85f), 500)
        engine.onProximity(true)
        still(uprightTurned(85f), 3000)
        assertEquals(Phase.IN_POCKET, engine.phase)
        neverWarned()
    }

    @Test
    fun pocketSwapWhileWalkingNeedsTheScreenToo() {
        pocketUpright()
        // Walking: steps every 550 ms.
        stream(5000) { t -> floatArrayOf(0f, G + if (t % 550 < 60) 3f else 0f, 0f) }
        engine.onProximity(false)
        stream(700) { t -> uprightTurned(90f * t / 700f) }
        still(uprightTurned(90f), 1000)
        stream(700) { t ->
            val r = (90f * t / 700f) * PI.toFloat() / 180f
            floatArrayOf(G * sin(r), 0f, G * cos(r))
        }
        engine.onProximity(true)
        still(floatArrayOf(G, 0f, 0f), 2500)
        assertEquals(Phase.IN_POCKET, engine.phase)
        neverWarned()
    }

    @Test
    fun notificationOnADeskDoesNotWarn() {
        pocketUpright()
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(90f * t / 800f) }
        still(faceUp, 15_000) // Taken out and left face-up on the desk, locked.
        engine.onDisplayOn() // A message lights the screen; nobody touched it.
        still(faceUp, 2000)
        assertEquals(Phase.IN_POCKET, engine.phase)
        neverWarned()
    }

    @Test
    fun ownerPullingOutUnlockedIsIgnored() {
        pocketUpright()
        host.locked = false
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(80f * t / 800f) }
        engine.onDisplayOn()
        still(uprightTurned(80f), 1500)
        neverWarned()
    }

    @Test
    fun screenWokenByTheChargerIsNoCue() {
        pocketUpright()
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(80f * t / 800f) }
        engine.onPowerEvent()
        engine.onDisplayOn()
        still(uprightTurned(80f), 1500)
        neverWarned()
    }

    @Test
    fun turnToLookWarningDoesNotRecoverWhenCovered() {
        pocketUpright()
        engine.onProximity(false)
        stream(800) { t -> uprightTurned(80f * t / 800f) }
        engine.onDisplayOn()
        still(uprightTurned(80f), 1500)
        warnedWith(Evidence.TILT_OUT)
        engine.onProximity(true) // A hand over the sensor.
        still(uprightTurned(80f), 500)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun walkingJoltsNeedTheStepsToStop() {
        pocketUpright()
        val walk = { t: Long -> floatArrayOf(0f, G + if (t % 550 < 60) 3f else 0f, 0f) }
        stream(4000, walk)
        // Walking with fabric flicking off the sensor: steps are jolts, but it isn't a theft.
        engine.onProximity(false)
        stream(6000) { walk(it + 4000) }
        neverWarned()
        still(upright, 5000) // Stopped, sensor still uncovered: the last steps don't count.
        neverWarned()
    }

    @Test
    fun thiefHurryingOffAfterTheYankStillWarns() {
        // Yanked out of a standing owner's pocket, then hurried off at once: the getaway steps fall
        // into a rhythm with the yank, but the yank itself was made standing still.
        for (gap in 0L until 400L step 40L) {
            setUp()
            pocketUpright()
            var uncovered = false
            stream(6000) { t ->
                if (!uncovered) { uncovered = true; engine.onProximity(false) }
                val v = uprightTurned(70f * t.coerceAtMost(300L) / 300f)
                val yank = if (t < 200) 5f * sin(PI.toFloat() * t / 200f) else 0f
                val ph = if (t >= 40 + gap) (t - 40 - gap) % 400 else -1L
                val step = if (ph in 0L..119L) 3.5f * sin(PI.toFloat() * ph / 120f) else 0f
                val k = 1f + step / G
                floatArrayOf(v[0] * k, v[1] * k + yank, v[2] * k)
            }
            still(uprightTurned(70f), 8000)
            assertTrue("gap $gap", host.transitions.any { it.second.isAlarm })
        }
    }

    @Test
    fun seatedKneeDipWithAnOpenPocketIsQuiet() {
        // Sitting, the pocket gaping open, in the dark: the thigh dips 15-30 degrees about the hip and
        // back. Its swing moves |a| by 1-2 m/s², under a jolt, so it must stay quiet.
        val dips = listOf(15.0 to 800L, 15.0 to 1200L, 20.0 to 1200L, 25.0 to 1500L, 30.0 to 1500L)
        for ((deg, ms) in dips) for (seed in 1L..2L) {
            setUp()
            random = Random(seed)
            bias = floatArrayOf(0.2f, -0.3f, 0.25f)
            still(faceUp, 1000)
            engine.onProximity(true)
            still(faceUp, 15_000)
            assertEquals(Phase.IN_POCKET, engine.phase)
            engine.onProximity(false)
            stream(ms + 200) { t -> pivoted(deg, ms, 0.25, t) }
            still(faceUp, 6000)
            assertTrue("dip $deg in $ms ms, seed $seed", host.transitions.none { it.second.isAlarm })
        }
    }

    @Test
    fun joltWarningCoveredAgainAtANewAngleStillWarns() {
        pocketUpright()
        engine.onProximity(false)
        stream(300) { floatArrayOf(6f, 6f, 18f) } // Yanked out...
        stream(600) { t -> uprightTurned(100f * t / 600f) }
        still(uprightTurned(100f), 700)
        warnedWith(Evidence.JOLT)
        // ...and within a second into the thief's own pocket, at a new angle.
        stream(300) { t -> uprightTurned(100f - 40f * t / 300f) }
        engine.onProximity(true)
        still(uprightTurned(60f), 2500)
        warnedWith(Evidence.OUT_AND_IN)
    }

    @Test
    fun realJoltPullStillWarns() {
        pocketUpright()
        engine.onProximity(false)
        stream(300) { floatArrayOf(6f, 6f, 18f) }
        still(upright, 2000)
        warnedWith(Evidence.JOLT)
    }
}
