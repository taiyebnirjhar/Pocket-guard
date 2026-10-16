package com.taiyeb.pocketguard

/**
 * All of Pocket Guard's decisions, with no Android types so it runs in plain JVM unit tests.
 * [GuardService] feeds it sensor readings and events, and carries out what it asks for via [Host].
 *
 * Ladder: WAITING_FOR_POCKET -> IN_POCKET -> (pulled out while locked) WARNING -> (no unlock) SIREN.
 * Airplane mode on the lock screen jumps straight to SIREN.
 * Unlocking at any point returns to WAITING_FOR_POCKET, so it re-arms by itself.
 */
class GuardEngine(
    private val host: Host,
    private val hasMotionSensor: Boolean,
    private val hasLightSensor: Boolean,
) {
    data class Config(
        val graceMs: Long,
        val airplaneAlarm: Boolean,
        val chargerAlarm: Boolean,
        val earbudsAlarm: Boolean,
    )

    enum class Timer { CONFIRM_POCKET, CHECK_REMOVAL, SIREN }

    interface Host {
        fun now(): Long
        fun isLocked(): Boolean
        fun inCall(): Boolean
        fun config(): Config
        fun schedule(timer: Timer, delayMs: Long)
        fun cancel(timer: Timer)

        /** [trigger] is the alarm's cause when entering or leaving an alarm phase, otherwise null. */
        fun onTransition(from: Phase, to: Phase, trigger: Trigger?)
        fun confirmBuzz()
        fun noticeBuzz()
    }

    companion object {
        /** Proximity must stay covered this long before the phone counts as pocketed. */
        const val POCKET_CONFIRM_MS = 1500L

        /** Proximity must stay uncovered this long, which filters out fabric shifting in the pocket. */
        const val REMOVAL_CONFIRM_MS = 800L

        /**
         * With no delay chosen the user wants the siren at once, so removal is confirmed faster;
         * a motion-only siren still stops itself if covered again within [FLICKER_RECOVERY_MS].
         */
        const val FAST_REMOVAL_CONFIRM_MS = 250L
        const val FAST_UNCOVERED_CONFIRM_MS = 500L

        /** Covered and dark again this soon after a pocket warning: it was fabric, not a hand. */
        const val FLICKER_RECOVERY_MS = 1000L

        /** Below this both pocket and outside are dark (night), so light can't confirm a removal. */
        const val DARK_LUX = 5f

        /** A "covered" reading above this can't come from inside a pocket; some sensors repeat old values. */
        const val STALE_COVERED_LUX = 50f

        /** Light and motion readings can arrive late: keep re-checking this long after uncovering. */
        const val EVIDENCE_WINDOW_MS = 2500L
        const val RECHECK_MS = 300L

        /** During a call at the ear, try confirming the pocket again this often. */
        const val CALL_RETRY_MS = 2000L

        /** Without a usable light reading, a removal needs a hand-like jolt within this window... */
        const val MOTION_WINDOW_MS = 3000L

        /** ...above this (linear acceleration; a phone pulled from a pocket easily exceeds it)... */
        const val MOTION_SPIKE = 2.5f

        /** ...and the sensor must stay uncovered this long, since pocket fabric flickers but returns. */
        const val UNCOVERED_CONFIRM_MS = 1500L
    }

    var phase = Phase.OFF
        private set
    private var trigger: Trigger? = null
    private var near: Boolean? = null
    /** Darkest light reading that arrived while the sensor was covered (null: none arrived). */
    private var coveredLux: Float? = null

    /** Latest light reading that arrived after the sensor was uncovered (null: none arrived). */
    private var uncoveredLux: Float? = null
    private var lastMotionAt: Long? = null
    private var warningAt: Long? = null

    /** The pocket warning was confirmed by fresh light, which fabric flicker can't produce. */
    private var confirmedByLight = false
    private var uncoveredAt: Long? = null

    /** No delay chosen: confirm removals faster, since the siren is meant to start at once. */
    private val fast get() = host.config().graceMs <= 0
    private val removalConfirmMs get() = if (fast) FAST_REMOVAL_CONFIRM_MS else REMOVAL_CONFIRM_MS
    private val uncoveredConfirmMs get() = if (fast) FAST_UNCOVERED_CONFIRM_MS else UNCOVERED_CONFIRM_MS

    fun arm() {
        if (phase != Phase.OFF) return
        near = null
        coveredLux = null
        uncoveredLux = null
        lastMotionAt = null
        go(Phase.WAITING_FOR_POCKET)
    }

    fun disarm() {
        if (phase == Phase.OFF) return
        cancelAll()
        go(Phase.OFF)
    }

    fun onProximity(isNear: Boolean) {
        if (isNear == near) return
        near = isNear
        uncoveredAt = if (isNear) null else host.now()
        // Many phones stop reporting light while the screen is off, so a reading only counts if it
        // arrives after this change; older values say nothing about where the phone is now.
        if (isNear) coveredLux = null else uncoveredLux = null
        host.cancel(Timer.CONFIRM_POCKET)
        host.cancel(Timer.CHECK_REMOVAL)
        when (phase) {
            Phase.WAITING_FOR_POCKET -> if (isNear) host.schedule(Timer.CONFIRM_POCKET, POCKET_CONFIRM_MS)
            Phase.IN_POCKET -> if (!isNear) host.schedule(Timer.CHECK_REMOVAL, removalConfirmMs)
            // A no-delay siren starts straight from the pocket, so it gets the same flicker recovery.
            Phase.WARNING, Phase.SIREN -> if (isNear) recoverFromFlicker()
            else -> Unit
        }
    }

    /** Re-covered within a second of a pocket warning: the pocket shifted, the phone never left. */
    private fun recoverFromFlicker() {
        val startedAt = warningAt ?: return
        if (phase == Phase.SIREN && !fast) return // That siren followed a whole grace period.
        if (trigger != Trigger.POCKET_REMOVAL || host.now() - startedAt > FLICKER_RECOVERY_MS) return
        // Bright light means it really was out; covered again now is a thief's hand or pocket.
        if (confirmedByLight) return
        cancelAll()
        go(Phase.IN_POCKET)
    }

    fun onLight(value: Float) {
        when (near) {
            true -> coveredLux = coveredLux?.let { minOf(it, value) } ?: value
            false -> uncoveredLux = value
            null -> Unit
        }
    }

    fun onMotion(strength: Float) {
        if (strength > MOTION_SPIKE) lastMotionAt = host.now()
    }

    fun onTimer(timer: Timer) {
        when (timer) {
            Timer.CONFIRM_POCKET -> confirmPocket()
            Timer.CHECK_REMOVAL -> checkRemoval()
            Timer.SIREN -> if (phase == Phase.WARNING) siren(null)
        }
    }

    fun onOwnerUnlocked() {
        if (phase == Phase.OFF) return
        cancelAll()
        if (phase != Phase.WAITING_FOR_POCKET) go(Phase.WAITING_FOR_POCKET)
        if (near == true) host.schedule(Timer.CONFIRM_POCKET, POCKET_CONFIRM_MS)
    }

    /** Restores an alarm that was running when the phone restarted or the app was killed. */
    fun resumeAlarm() {
        if (phase == Phase.OFF) return
        siren(Trigger.RESTARTED)
    }

    fun onAirplaneModeOn() {
        if (phase != Phase.OFF && host.config().airplaneAlarm && host.isLocked()) siren(Trigger.AIRPLANE_MODE)
    }

    /** Someone unplugged the phone while it was locked, e.g. charging at a café or station. */
    fun onChargerUnplugged() {
        if (host.config().chargerAlarm && host.isLocked()) warn(Trigger.CHARGER_UNPLUGGED)
    }

    /**
     * Earbuds or watch lost the phone. In the pocket that's usually the earbuds going in their case,
     * so only buzz. Out of the pocket and locked, the phone may be moving away from you.
     */
    fun onEarbudsDisconnected() {
        if (!host.config().earbudsAlarm) return
        when (phase) {
            Phase.IN_POCKET -> host.noticeBuzz()
            Phase.WAITING_FOR_POCKET -> if (host.isLocked()) warn(Trigger.EARBUDS_DISCONNECTED)
            else -> Unit
        }
    }

    private fun confirmPocket() {
        if (phase != Phase.WAITING_FOR_POCKET || near != true) return
        // In a call, or unlocked with the screen on: a pull-out now would count as the owner, so
        // "protected" would be a lie. Confirm once the call ends or the screen goes off.
        if (host.inCall() || !host.isLocked()) {
            host.schedule(Timer.CONFIRM_POCKET, CALL_RETRY_MS)
            return
        }
        go(Phase.IN_POCKET)
        host.confirmBuzz()
    }

    private fun checkRemoval() {
        if (phase != Phase.IN_POCKET || near != false) return
        // Unlocked means the owner is using it; calls cover and uncover the sensor at the ear.
        if (!host.isLocked() || host.inCall()) {
            go(Phase.WAITING_FOR_POCKET)
            return
        }
        when (val evidence = removalEvidence()) {
            is Evidence.Confirmed -> {
                confirmedByLight = evidence.byLight
                warn(Trigger.POCKET_REMOVAL)
            }
            Evidence.None -> {
                val uncoveredFor = host.now() - (uncoveredAt ?: host.now())
                if (uncoveredFor < EVIDENCE_WINDOW_MS) {
                    host.schedule(Timer.CHECK_REMOVAL, RECHECK_MS) // A late reading may still settle it.
                } else {
                    go(Phase.WAITING_FOR_POCKET)
                }
            }
            is Evidence.WaitLonger -> host.schedule(Timer.CHECK_REMOVAL, evidence.ms)
        }
    }

    private sealed interface Evidence {
        data class Confirmed(val byLight: Boolean) : Evidence
        data object None : Evidence
        data class WaitLonger(val ms: Long) : Evidence
    }

    /**
     * Light decides when there are fresh readings from both inside and outside the pocket: brighter
     * outside is a removal; lit outside yet no brighter means something still covers the phone.
     * Otherwise (dark everywhere, or the light sensor asleep with the screen off, which is common)
     * it takes a hand-like jolt plus the sensor staying uncovered for [UNCOVERED_CONFIRM_MS], since
     * fabric flickering over the sensor while walking re-covers it quickly.
     */
    private fun removalEvidence(): Evidence {
        if (!hasMotionSensor && !hasLightSensor) return Evidence.Confirmed(byLight = false)
        val now = host.now()
        val inside = coveredLux
        val outside = uncoveredLux
        if (hasLightSensor && inside != null && outside != null) {
            if (outside > maxOf(inside * 3f, inside + 10f)) return Evidence.Confirmed(byLight = true)
            // A covered sensor reading a bright room is a stale repeat, not a pocket: ignore light.
            val insideTrustworthy = inside < STALE_COVERED_LUX
            if (insideTrustworthy && outside >= DARK_LUX) return Evidence.None
        }
        val motionAt = lastMotionAt
        val jolted = motionAt != null && now - motionAt < MOTION_WINDOW_MS
        if (hasMotionSensor && !jolted) return Evidence.None
        val uncoveredFor = now - (uncoveredAt ?: now)
        return if (uncoveredFor >= uncoveredConfirmMs) {
            Evidence.Confirmed(byLight = false)
        } else {
            Evidence.WaitLonger(uncoveredConfirmMs - uncoveredFor)
        }
    }

    private fun warn(cause: Trigger) {
        if (phase != Phase.WAITING_FOR_POCKET && phase != Phase.IN_POCKET) return
        // No delay chosen: skip the warning and scream at once.
        warningAt = host.now()
        if (fast) {
            siren(cause)
            return
        }
        cancelAll()
        go(Phase.WARNING, cause)
        host.schedule(Timer.SIREN, host.config().graceMs)
    }

    private fun siren(cause: Trigger?) {
        if (phase == Phase.OFF || phase == Phase.SIREN) return
        cancelAll()
        go(Phase.SIREN, cause)
    }

    private fun go(to: Phase, cause: Trigger? = null) {
        val from = phase
        if (to.isAlarm && cause != null) trigger = cause
        val reported = if (to.isAlarm || from.isAlarm) trigger else null
        phase = to
        if (!to.isAlarm) trigger = null
        host.onTransition(from, to, reported)
    }

    private fun cancelAll() = Timer.entries.forEach(host::cancel)
}
