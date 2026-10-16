package com.taiyeb.pocketguard

/**
 * All of Pocket Guard's decisions, with no Android types so it runs in plain JVM unit tests.
 * [GuardService] feeds it sensor readings and events, and carries out what it asks for via [Host].
 *
 * Ladder: WAITING_FOR_POCKET -> IN_POCKET -> (pulled out while locked) WARNING -> (no unlock) SIREN.
 * Tampering jumps straight to SIREN, or to STEALTH when fake shutdown is on.
 * Unlocking at any point returns to WAITING_FOR_POCKET, so it re-arms by itself.
 */
class GuardEngine(
    private val host: Host,
    private val hasMotionSensor: Boolean,
    private val hasLightSensor: Boolean,
) {
    data class Config(
        val graceMs: Long,
        val tamperAlarm: Boolean,
        val chargerAlarm: Boolean,
        val earbudsAlarm: Boolean,
        val fakeShutdown: Boolean,
    )

    enum class Timer { CONFIRM_POCKET, AUTO_LOCK, CHECK_REMOVAL, SIREN }

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
        fun lockScreen()
    }

    companion object {
        /** Proximity must stay covered this long before the phone counts as pocketed. */
        const val POCKET_CONFIRM_MS = 1500L

        /** Extra wait before locking a phone pocketed unlocked, so a thumb over the sensor doesn't lock it. */
        const val AUTO_LOCK_DELAY_MS = 1500L

        /** Proximity must stay uncovered this long, which filters out fabric shifting in the pocket. */
        const val REMOVAL_CONFIRM_MS = 400L

        /** A removal needs movement within this window, or a jump in light. */
        const val MOTION_WINDOW_MS = 2000L
        const val MOTION_SPIKE = 1.5f
    }

    var phase = Phase.OFF
        private set
    private var trigger: Trigger? = null
    private var near: Boolean? = null
    private var lux: Float? = null
    private var pocketLux: Float? = null
    private var lastMotionAt: Long? = null

    fun arm() {
        if (phase != Phase.OFF) return
        near = null
        pocketLux = null
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
        host.cancel(Timer.CONFIRM_POCKET)
        host.cancel(Timer.CHECK_REMOVAL)
        host.cancel(Timer.AUTO_LOCK)
        when (phase) {
            Phase.WAITING_FOR_POCKET -> if (isNear) host.schedule(Timer.CONFIRM_POCKET, POCKET_CONFIRM_MS)
            Phase.IN_POCKET -> if (!isNear) host.schedule(Timer.CHECK_REMOVAL, REMOVAL_CONFIRM_MS)
            else -> Unit
        }
    }

    fun onLight(value: Float) {
        lux = value
        if (phase == Phase.IN_POCKET && near == true) {
            pocketLux = pocketLux?.let { minOf(it, value) } ?: value
        }
    }

    fun onMotion(strength: Float) {
        if (strength > MOTION_SPIKE) lastMotionAt = host.now()
    }

    fun onTimer(timer: Timer) {
        when (timer) {
            Timer.CONFIRM_POCKET -> confirmPocket()
            Timer.AUTO_LOCK -> if (phase == Phase.IN_POCKET && near == true && !host.isLocked()) host.lockScreen()
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

    fun onPowerMenu() {
        val config = host.config()
        if (phase == Phase.OFF || !config.tamperAlarm) return
        if (config.fakeShutdown) stealth(Trigger.POWER_MENU) else siren(Trigger.POWER_MENU)
    }

    fun onAirplaneModeOn() {
        if (phase != Phase.OFF && host.config().tamperAlarm && host.isLocked()) siren(Trigger.AIRPLANE_MODE)
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
        if (phase != Phase.WAITING_FOR_POCKET || near != true || host.inCall()) return
        pocketLux = lux
        go(Phase.IN_POCKET)
        host.confirmBuzz()
        if (!host.isLocked()) host.schedule(Timer.AUTO_LOCK, AUTO_LOCK_DELAY_MS)
    }

    private fun checkRemoval() {
        if (phase != Phase.IN_POCKET || near != false) return
        // Unlocked means the owner is using it; calls cover and uncover the sensor at the ear.
        if (host.isLocked() && !host.inCall() && hasRemovalEvidence()) {
            warn(Trigger.POCKET_REMOVAL)
        } else {
            go(Phase.WAITING_FOR_POCKET)
        }
    }

    /** A real removal moves the phone or exposes it to light; a sensor flicker does neither. */
    private fun hasRemovalEvidence(): Boolean {
        if (!hasMotionSensor && !hasLightSensor) return true
        val motionAt = lastMotionAt
        val moved = hasMotionSensor && motionAt != null && host.now() - motionAt < MOTION_WINDOW_MS
        val dark = pocketLux
        val current = lux
        val brighter = hasLightSensor && dark != null && current != null &&
            current > maxOf(dark * 3f, dark + 10f)
        return moved || brighter
    }

    private fun warn(cause: Trigger) {
        if (phase != Phase.WAITING_FOR_POCKET && phase != Phase.IN_POCKET) return
        cancelAll()
        go(Phase.WARNING, cause)
        host.schedule(Timer.SIREN, host.config().graceMs)
    }

    private fun siren(cause: Trigger?) {
        if (phase == Phase.OFF || phase == Phase.SIREN || phase == Phase.STEALTH) return
        cancelAll()
        go(Phase.SIREN, cause)
    }

    /** Once the siren has revealed the alarm, pretending to shut down is pointless, so only from quiet phases. */
    private fun stealth(cause: Trigger) {
        if (phase == Phase.OFF || phase == Phase.SIREN || phase == Phase.STEALTH) return
        cancelAll()
        go(Phase.STEALTH, cause)
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
