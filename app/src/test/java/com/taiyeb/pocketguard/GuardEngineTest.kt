package com.taiyeb.pocketguard

import com.taiyeb.pocketguard.GuardEngine.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuardEngineTest {

    private class FakeHost : GuardEngine.Host {
        var time = 1_000_000L
        var locked = true
        var call = false
        var config = GuardEngine.Config(
            graceMs = 3000, airplaneAlarm = true, chargerAlarm = true, earbudsAlarm = true,
        )
        val timers = mutableMapOf<Timer, Long>()
        val transitions = mutableListOf<Triple<Phase, Phase, Trigger?>>()
        var confirmBuzzes = 0
        var noticeBuzzes = 0
        lateinit var engine: GuardEngine

        override fun now() = time
        override fun isLocked() = locked
        override fun inCall() = call
        override fun config() = config
        override fun schedule(timer: Timer, delayMs: Long) {
            timers[timer] = time + delayMs
        }
        override fun cancel(timer: Timer) {
            timers.remove(timer)
        }
        override fun onTransition(from: Phase, to: Phase, trigger: Trigger?) {
            transitions += Triple(from, to, trigger)
        }
        override fun confirmBuzz() {
            confirmBuzzes++
        }
        override fun noticeBuzz() {
            noticeBuzzes++
        }

        /** Moves the clock forward, firing due timers in order, like the real Handler would. */
        fun advance(ms: Long) {
            val end = time + ms
            while (true) {
                val next = timers.entries.filter { it.value <= end }.minByOrNull { it.value } ?: break
                timers.remove(next.key)
                time = next.value
                engine.onTimer(next.key)
            }
            time = end
        }
    }

    private lateinit var host: FakeHost
    private lateinit var engine: GuardEngine

    @Before
    fun setUp() {
        newEngine(hasMotion = true, hasLight = true)
    }

    private fun newEngine(hasMotion: Boolean, hasLight: Boolean) {
        host = FakeHost()
        engine = GuardEngine(host, hasMotion, hasLight)
        host.engine = engine
        engine.arm()
        engine.onProximity(false)
        engine.onLight(300f)
    }

    private fun pocket() {
        engine.onProximity(true)
        engine.onLight(0f)
        host.advance(GuardEngine.POCKET_CONFIRM_MS + 10)
    }

    /** A hand pulling the phone out: movement, then light and an uncovered sensor. */
    private fun pullOut() {
        engine.onMotion(8f)
        engine.onProximity(false)
        engine.onLight(300f)
        host.advance(GuardEngine.REMOVAL_CONFIRM_MS + 10)
    }

    @Test
    fun armingWaitsForPocket() {
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun coveredLongEnoughBecomesProtectedWithBuzz() {
        pocket()
        assertEquals(Phase.IN_POCKET, engine.phase)
        assertEquals(1, host.confirmBuzzes)
    }

    @Test
    fun briefCoverDoesNotCountAsPocket() {
        engine.onProximity(true)
        host.advance(500)
        engine.onProximity(false)
        host.advance(5000)
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun pullOutWhileLockedWarnsThenSirens() {
        pocket()
        pullOut()
        assertEquals(Phase.WARNING, engine.phase)
        assertEquals(Triple(Phase.IN_POCKET, Phase.WARNING, Trigger.POCKET_REMOVAL), host.transitions.last())
        host.advance(3000)
        assertEquals(Phase.SIREN, engine.phase)
        assertEquals(Trigger.POCKET_REMOVAL, host.transitions.last().third)
    }

    @Test
    fun unlockDuringWarningStopsItAndReportsCause() {
        pocket()
        pullOut()
        engine.onOwnerUnlocked()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
        assertEquals(Triple(Phase.WARNING, Phase.WAITING_FOR_POCKET, Trigger.POCKET_REMOVAL), host.transitions.last())
        host.advance(10_000)
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun unlockDuringSirenStopsIt() {
        pocket()
        pullOut()
        host.advance(3000)
        engine.onOwnerUnlocked()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun pullOutWhileUnlockedIsTheOwner() {
        pocket()
        host.locked = false
        pullOut()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
        assertFalse(host.transitions.any { it.second == Phase.WARNING })
    }

    @Test
    fun uncoveredWithoutMotionOrLightIsAFlicker() {
        pocket()
        engine.onProximity(false)
        host.advance(1000)
        assertFalse(host.transitions.any { it.second == Phase.WARNING })
    }

    @Test
    fun lightJumpAloneCountsAsRemoval() {
        pocket()
        engine.onProximity(false)
        engine.onLight(200f)
        host.advance(GuardEngine.REMOVAL_CONFIRM_MS + 10)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun oldMotionDoesNotCount() {
        pocket()
        engine.onMotion(8f)
        host.advance(GuardEngine.MOTION_WINDOW_MS + 100)
        engine.onProximity(false)
        host.advance(GuardEngine.EVIDENCE_WINDOW_MS + 500)
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun shortUncoverInsidePocketIsIgnored() {
        pocket()
        engine.onMotion(8f)
        engine.onProximity(false)
        host.advance(200)
        engine.onProximity(true)
        host.advance(5000)
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    @Test
    fun withoutMotionOrLightSensorsProximityAloneTriggers() {
        newEngine(hasMotion = false, hasLight = false)
        pocket()
        engine.onProximity(false)
        host.advance(GuardEngine.REMOVAL_CONFIRM_MS + 10)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun callsNeverCountAsPocketOrRemoval() {
        host.call = true
        pocket()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)

        host.call = false
        engine.onProximity(false)
        pocket()
        host.call = true
        pullOut()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun airplaneAlarmOffIsIgnored() {
        host.config = host.config.copy(airplaneAlarm = false)
        engine.onAirplaneModeOn()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun airplaneModeOnlyWhenLocked() {
        host.locked = false
        engine.onAirplaneModeOn()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
        host.locked = true
        engine.onAirplaneModeOn()
        assertEquals(Phase.SIREN, engine.phase)
        assertEquals(Trigger.AIRPLANE_MODE, host.transitions.last().third)
    }

    @Test
    fun chargerUnplugWarnsWhenLockedOnly() {
        host.locked = false
        engine.onChargerUnplugged()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
        host.locked = true
        engine.onChargerUnplugged()
        assertEquals(Phase.WARNING, engine.phase)
        host.advance(3000)
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun chargerAlarmOffIsIgnored() {
        host.config = host.config.copy(chargerAlarm = false)
        engine.onChargerUnplugged()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun earbudsDisconnectInPocketOnlyBuzzes() {
        pocket()
        engine.onEarbudsDisconnected()
        assertEquals(Phase.IN_POCKET, engine.phase)
        assertEquals(1, host.noticeBuzzes)
    }

    @Test
    fun earbudsDisconnectOutOfPocketWarns() {
        engine.onEarbudsDisconnected()
        assertEquals(Phase.WARNING, engine.phase)
        assertEquals(Trigger.EARBUDS_DISCONNECTED, host.transitions.last().third)
    }

    @Test
    fun earbudsAlarmOffIsIgnored() {
        host.config = host.config.copy(earbudsAlarm = false)
        engine.onEarbudsDisconnected()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun secondTriggerDuringWarningDoesNotRestartGrace() {
        engine.onChargerUnplugged()
        host.advance(2000)
        engine.onEarbudsDisconnected()
        host.advance(1000)
        assertEquals(Phase.SIREN, engine.phase)
        assertEquals(Trigger.CHARGER_UNPLUGGED, host.transitions.last().third)
    }

    @Test
    fun graceUsesConfig() {
        host.config = host.config.copy(graceMs = 5000)
        engine.onChargerUnplugged()
        host.advance(4900)
        assertEquals(Phase.WARNING, engine.phase)
        host.advance(200)
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun noDelaySirensImmediatelyWithoutWarning() {
        host.config = host.config.copy(graceMs = 0)
        pocket()
        pullOut()
        assertEquals(Phase.SIREN, engine.phase)
        assertFalse(host.transitions.any { it.second == Phase.WARNING })
        assertEquals(Trigger.POCKET_REMOVAL, host.transitions.last().third)
    }

    @Test
    fun noDelayAppliesToChargerToo() {
        host.config = host.config.copy(graceMs = 0)
        engine.onChargerUnplugged()
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun unlockReArmsWhenStillCovered() {
        pocket()
        engine.onProximity(false)
        engine.onMotion(8f)
        host.advance(GuardEngine.REMOVAL_CONFIRM_MS + 10)
        engine.onProximity(true)
        engine.onOwnerUnlocked()
        host.locked = true
        host.advance(GuardEngine.POCKET_CONFIRM_MS + 10)
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    @Test
    fun disarmCancelsEverything() {
        pocket()
        pullOut()
        engine.disarm()
        assertEquals(Phase.OFF, engine.phase)
        assertTrue(host.timers.isEmpty())
        assertEquals(Triple(Phase.WARNING, Phase.OFF, Trigger.POCKET_REMOVAL), host.transitions.last())
    }

    @Test
    fun nothingHappensWhileOff() {
        engine.disarm()
        engine.onAirplaneModeOn()
        engine.onChargerUnplugged()
        engine.onEarbudsDisconnected()
        engine.onProximity(true)
        host.advance(5000)
        assertEquals(Phase.OFF, engine.phase)
    }

    @Test
    fun darkRemovalWaitsForSustainedUncover() {
        pocket()
        engine.onMotion(8f)
        engine.onProximity(false)
        host.advance(GuardEngine.REMOVAL_CONFIRM_MS + 10)
        assertEquals(Phase.IN_POCKET, engine.phase)
        host.advance(GuardEngine.UNCOVERED_CONFIRM_MS)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun darkFlickerWithYankThatRecoversIsIgnored() {
        pocket()
        engine.onMotion(8f)
        engine.onProximity(false)
        host.advance(1200)
        engine.onProximity(true)
        host.advance(10_000)
        assertEquals(Phase.IN_POCKET, engine.phase)
        assertFalse(host.transitions.any { it.second == Phase.WARNING })
    }

    @Test
    fun walkingMotionInDarkPocketIsIgnored() {
        pocket()
        engine.onMotion(2f) // a step
        engine.onProximity(false)
        host.advance(5000)
        assertFalse(host.transitions.any { it.second == Phase.WARNING })
    }

    @Test
    fun litOutsideButNotBrighterIsNotARemoval() {
        pocket()
        engine.onMotion(8f)
        engine.onProximity(false)
        engine.onLight(7f)
        host.advance(5000)
        assertFalse(host.transitions.any { it.second == Phase.WARNING })
    }

    @Test
    fun recoveredFlickerCancelsPocketWarning() {
        pocket()
        engine.onMotion(8f)
        engine.onProximity(false) // no light reading: confirmed by motion
        host.advance(GuardEngine.UNCOVERED_CONFIRM_MS + 10)
        assertEquals(Phase.WARNING, engine.phase)
        engine.onProximity(true)
        assertEquals(Phase.IN_POCKET, engine.phase)
        host.advance(10_000)
        assertFalse(host.transitions.any { it.second == Phase.SIREN })
        assertEquals(Triple(Phase.WARNING, Phase.IN_POCKET, Trigger.POCKET_REMOVAL), host.transitions.last())
    }

    @Test
    fun lightConfirmedWarningIsNotCancelledByCoveringAgain() {
        pocket()
        pullOut() // bright light outside: really out
        engine.onProximity(true) // a thief's hand or pocket
        assertEquals(Phase.WARNING, engine.phase)
        host.advance(3000)
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun lateLightReadingStillCounts() {
        pocket()
        engine.onProximity(false)
        host.advance(GuardEngine.REMOVAL_CONFIRM_MS + 100)
        assertEquals(Phase.IN_POCKET, engine.phase)
        engine.onLight(300f) // the light sensor wakes up late
        host.advance(GuardEngine.RECHECK_MS + 10)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun pocketConfirmsOnceTheCallEnds() {
        host.call = true
        engine.onProximity(true)
        host.advance(GuardEngine.POCKET_CONFIRM_MS + 10)
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
        host.call = false
        host.advance(GuardEngine.CALL_RETRY_MS + 10)
        assertEquals(Phase.IN_POCKET, engine.phase)
    }

    @Test
    fun coveringAgainLaterDoesNotCancelWarning() {
        pocket()
        pullOut()
        host.advance(GuardEngine.FLICKER_RECOVERY_MS + 100)
        engine.onProximity(true)
        host.advance(3000)
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun coveringDoesNotCancelOtherWarnings() {
        engine.onChargerUnplugged()
        engine.onProximity(true)
        host.advance(3000)
        assertEquals(Phase.SIREN, engine.phase)
    }

    /** The bug from the field: light sensor asleep with the screen off, last reading a bright room. */
    @Test
    fun frozenLightSensorFallsBackToMotion() {
        // Arrives before pocketing (setUp reads 300 lux with the sensor uncovered), then nothing.
        engine.onProximity(true)
        host.advance(GuardEngine.POCKET_CONFIRM_MS + 10)
        assertEquals(Phase.IN_POCKET, engine.phase)
        engine.onMotion(4f)
        engine.onProximity(false)
        host.advance(GuardEngine.UNCOVERED_CONFIRM_MS + 10)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun darkInsideButNoReadingOutsideFallsBackToMotion() {
        pocket()
        engine.onMotion(4f)
        engine.onProximity(false)
        host.advance(GuardEngine.UNCOVERED_CONFIRM_MS + 10)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun sensorRepeatingStaleBrightValueWhileCoveredFallsBackToMotion() {
        engine.onProximity(true)
        engine.onLight(400f) // stale repeat of the room reading
        host.advance(GuardEngine.POCKET_CONFIRM_MS + 10)
        engine.onMotion(4f)
        engine.onProximity(false)
        engine.onLight(400f)
        host.advance(GuardEngine.UNCOVERED_CONFIRM_MS + 10)
        assertEquals(Phase.WARNING, engine.phase)
    }

    @Test
    fun frozenLightAndNoMotionIsIgnored() {
        engine.onProximity(true)
        host.advance(GuardEngine.POCKET_CONFIRM_MS + 10)
        engine.onProximity(false)
        host.advance(5000)
        assertFalse(host.transitions.any { it.second == Phase.WARNING })
    }

    @Test
    fun resumedAlarmSirensWithRestartCause() {
        engine.resumeAlarm()
        assertEquals(Phase.SIREN, engine.phase)
        assertEquals(Trigger.RESTARTED, host.transitions.last().third)
    }

    @Test
    fun nonAlarmTransitionsCarryNoTrigger() {
        pocket()
        assertEquals(Triple(Phase.WAITING_FOR_POCKET, Phase.IN_POCKET, null), host.transitions.last())
    }
}
