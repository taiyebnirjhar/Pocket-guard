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
            graceMs = 3000, tamperAlarm = true, chargerAlarm = true, earbudsAlarm = true, fakeShutdown = false,
        )
        val timers = mutableMapOf<Timer, Long>()
        val transitions = mutableListOf<Triple<Phase, Phase, Trigger?>>()
        var confirmBuzzes = 0
        var noticeBuzzes = 0
        var screenLocks = 0
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
        override fun lockScreen() {
            screenLocks++
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
        host.advance(GuardEngine.REMOVAL_CONFIRM_MS + 10)
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
    fun pocketedUnlockedGetsLockedAfterDelay() {
        host.locked = false
        pocket()
        assertEquals(0, host.screenLocks)
        host.advance(GuardEngine.AUTO_LOCK_DELAY_MS + 10)
        assertEquals(1, host.screenLocks)
    }

    @Test
    fun pocketedLockedIsNotLockedAgain() {
        pocket()
        host.advance(5000)
        assertEquals(0, host.screenLocks)
    }

    @Test
    fun powerMenuSirensImmediately() {
        pocket()
        engine.onPowerMenu()
        assertEquals(Phase.SIREN, engine.phase)
        assertEquals(Trigger.POWER_MENU, host.transitions.last().third)
    }

    @Test
    fun powerMenuWithFakeShutdownGoesStealth() {
        host.config = host.config.copy(fakeShutdown = true)
        engine.onPowerMenu()
        assertEquals(Phase.STEALTH, engine.phase)
        engine.onOwnerUnlocked()
        assertEquals(Phase.WAITING_FOR_POCKET, engine.phase)
    }

    @Test
    fun fakeShutdownDoesNotReplaceARunningSiren() {
        host.config = host.config.copy(fakeShutdown = true)
        pocket()
        pullOut()
        host.advance(3000)
        engine.onPowerMenu()
        assertEquals(Phase.SIREN, engine.phase)
    }

    @Test
    fun powerMenuDuringWarningEscalates() {
        pocket()
        pullOut()
        engine.onPowerMenu()
        assertEquals(Phase.SIREN, engine.phase)
        assertTrue(host.timers.isEmpty())
    }

    @Test
    fun tamperAlarmOffIgnoresPowerMenuAndAirplane() {
        host.config = host.config.copy(tamperAlarm = false)
        engine.onPowerMenu()
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
        engine.onPowerMenu()
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
        host.advance(GuardEngine.DARK_CONFIRM_MS)
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
        engine.onMotion(3f) // a step
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
        pullOut()
        assertEquals(Phase.WARNING, engine.phase)
        engine.onProximity(true)
        assertEquals(Phase.IN_POCKET, engine.phase)
        host.advance(10_000)
        assertFalse(host.transitions.any { it.second == Phase.SIREN })
        assertEquals(Triple(Phase.WARNING, Phase.IN_POCKET, Trigger.POCKET_REMOVAL), host.transitions.last())
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
