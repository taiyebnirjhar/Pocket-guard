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

    /** Streams [ms] of samples from [gen] (time since start, ms) with sensor noise and offset. */
    private fun stream(ms: Long, gen: (Long) -> FloatArray) {
        var elapsed = 0L
        while (elapsed < ms) {
            host.advance(stepMs)
            elapsed += stepMs
            val v = gen(elapsed)
            engine.onAccel(
                host.time,
                v[0] + bias[0] + (random.nextGaussian() * noise).toFloat(),
                v[1] + bias[1] + (random.nextGaussian() * noise).toFloat(),
                v[2] + bias[2] + (random.nextGaussian() * noise).toFloat(),
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
        stream(4000) { floatArrayOf(1.2f, 0f, -G) }
        assertEquals(Phase.IN_POCKET, engine.phase)
        neverWarned()
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
