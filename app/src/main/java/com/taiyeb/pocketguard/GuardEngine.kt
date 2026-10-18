package com.taiyeb.pocketguard

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * All of Pocket Guard's decisions, with no Android types so it runs in plain JVM unit tests.
 * [GuardService] feeds it sensor readings and events, and carries out what it asks for via [Host].
 *
 * Ladder: WAITING_FOR_POCKET -> IN_POCKET -> (uncovered while locked) WARNING -> (no unlock) SIREN.
 * Locking it is the signal: covered, it is in the pocket half a second later; lying still
 * uncovered with the screen black, it goes straight to ON_TABLE.
 * Face-down on a table: IN_POCKET -> ON_TABLE once it lies still on a hard surface; sliding,
 * lifting, tilting or pushing it off warns. Being armed is the user's choice, so once armed it
 * warns on any sign of being taken and lets the owner's unlock stop it. Airplane mode on the lock screen jumps straight to SIREN.
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
        val tableGuard: Boolean = true,
    )

    enum class Timer { CONFIRM_POCKET, CHECK_REMOVAL, SIREN, DECIDE, TABLE_CHECK, TABLE_WATCH }

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

        /** Locked with a PIN, pattern or password right now (not just the keyguard showing). */
        fun credentialLocked(): Boolean = isLocked()

        /** The screen is off (black): the owner pressed power or let it time out. */
        fun screenOff(): Boolean = false

        /** An incoming call is ringing. */
        fun ringing(): Boolean = false

        /** Two short buzzes: table protection is on. */
        fun tableTick() = Unit

        /** One line for the detection log: what was seen and decided, for field tests. */
        fun log(message: String) = Unit

    }

    companion object {
        /**
         * Locked and covered this long: in the pocket and protected. Short, so protection starts
         * as the phone goes in; it only filters a finger brushing the sensor.
         */
        const val POCKET_CONFIRM_MS = 500L

        /**
         * Armed and locked, the sensor uncovered this long is a removal: no further proof is asked
         * for, since a careful thief gives none. It filters out fabric shifting in the pocket; with
         * no delay chosen the siren is meant to start at once, so it's shorter. Either way a
         * warning stops itself if covered again within [FLICKER_RECOVERY_MS].
         */
        const val REMOVAL_CONFIRM_MS = 800L
        const val FAST_REMOVAL_CONFIRM_MS = 250L


        /** Covered and dark again this soon after a pocket warning: it was fabric, not a hand. */
        const val FLICKER_RECOVERY_MS = 1000L

        /** During a call at the ear, try confirming the pocket again this often. */
        const val CALL_RETRY_MS = 2000L

        /** A jolt this recent at the removal is noted as how it came out (for the history). */
        const val MOTION_WINDOW_MS = 3000L
        const val MOTION_SPIKE = 2.5f

        /** The screen woken as it is turned or taken out: someone is looking at it. */
        const val DISPLAY_CUE_MS = 3000L
        const val ROTATION_DEG = 20f

        /** The screen waking within this of a charger event is the charger's doing. */
        const val POWER_EXCLUSION_MS = 2000L

        /** Out of one pocket and into another: a big turn while out, and a new angle once covered. */
        /** A jolt-confirmed warning recovers only if the phone turned less than this while out. */
        const val FLICKER_MAX_PATH_DEG = 45f
        const val FLICKER_MAX_TURN_DEG = 15f

        /** Uncovered and still: lying flat within this of face-up or face-down counts as a table. */
        const val FLAT_ENTRY_DEG = 15f

        /** On the table, motion this old means the accelerometer stopped. */
        const val TABLE_WATCH_MS = 2000L
        const val TABLE_MOTION_STALE_MS = 3000L

        // Below the removal filter (250 ms with no delay), so a swap too quick to warn is still judged.
        const val OUT_AND_IN_MIN_OUT_MS = 200L
        const val OUT_AND_IN_PEAK_DEG = 60f
        const val OUT_AND_IN_PEAK_HOLD_MS = 300L
        const val OUT_AND_IN_FINAL_DEG = 30f
        const val OUT_AND_IN_DECIDE_MS = 2000L

        /** Loose pocket: this many cover cycles in 30 s. */
        const val LOOSE_CYCLES = 3

        /** A face-down phone can't uncover itself: an uncover needs lift-like motion too. */
        const val TABLE_UNCOVER_MS = 300L
        const val TABLE_UNCOVER_SECOND_MS = 700L

        /** Put back down and still again: a glance, not a theft. */
        const val PUT_BACK_REST_MS = 1500L
        const val PUT_BACK_FLAT_DEG = 10f
        const val PUT_BACK_LIMIT = 3
        const val PUT_BACK_PERIOD_MS = 10 * 60_000L
        const val PUT_BACK_FROM_SIREN_MS = 10_000L

        /** No-delay mode still gives a table pick-up this long, so the owner's own grab can unlock. */
        const val FAST_TABLE_WARNING_MS = 1500L

    }

    var phase = Phase.OFF
        private set

    /** A table alarm can still be put back until then (on the [Host.now] clock). */
    val tablePutBackUntil get() = tableTriggerAt + PUT_BACK_FROM_SIREN_MS

    /** What confirmed the current alarm (null when none, or for tamper triggers). */
    var evidence: Evidence? = null
        private set

    private var trigger: Trigger? = null
    private var near: Boolean? = null
    private var coveredAt = 0L
    /** Darkest light reading that arrived while the sensor was covered (null: none arrived). */
    private var coveredLux: Float? = null

    /** Latest light reading that arrived after the sensor was uncovered (null: none arrived). */
    private var uncoveredLux: Float? = null
    private var lastMotionAt: Long? = null
    private var warningAt: Long? = null

    /** The pocket warning was confirmed by fresh light, which fabric flicker can't produce. */
    private var confirmedByLight = false
    private var uncoveredAt: Long? = null

    private val motion = MotionProcessor()
    private val table = TableMonitor(motion) { host.log(it) }

    /** No delay chosen: confirm removals faster, since the siren is meant to start at once. */
    private val fast get() = host.config().graceMs <= 0
    private val removalConfirmMs get() = if (fast) FAST_REMOVAL_CONFIRM_MS else REMOVAL_CONFIRM_MS
    private val hasMotionData get() = hasMotionSensor && motion.hasData

    fun arm() {
        if (phase != Phase.OFF) return
        near = null
        coveredLux = null
        uncoveredLux = null
        lastMotionAt = null
        resetEpisode()
        table.clearLatch()
        go(Phase.WAITING_FOR_POCKET)
    }

    fun disarm() {
        if (phase == Phase.OFF) return
        cancelAll()
        go(Phase.OFF)
    }

    fun onProximity(isNear: Boolean) {
        if (isNear == near) return
        val wasNear = near
        near = isNear
        val now = host.now()
        host.log(
            (if (isNear) "covered" else "uncovered") + " ($phase" +
                (if (hasMotionData) ", %.0f° from face-down".format(motion.gravityAngleTo(0f, 0f, -1f)) else ", no motion data") + ")",
        )
        uncoveredAt = if (isNear) null else now
        if (isNear) coveredAt = now
        // Many phones stop reporting light while the screen is off, so a reading only counts if it
        // arrives after this change; older values say nothing about where the phone is now.
        if (isNear) coveredLux = null else uncoveredLux = null
        host.cancel(Timer.CONFIRM_POCKET)
        host.cancel(Timer.CHECK_REMOVAL)
        if (phase == Phase.ON_TABLE) {
            onTableProximity(isNear)
            return
        }
        if (hasMotionData && !phase.isAlarm) {
            if (isNear) {
                table.onCovered(now)
                if (table.latched) tableTicked = false
            } else {
                table.onUncovered()
            }
        }
        when (phase) {
            Phase.WAITING_FOR_POCKET -> if (isNear) host.schedule(Timer.CONFIRM_POCKET, POCKET_CONFIRM_MS)
            Phase.IN_POCKET -> if (isNear) {
                if (wasNear == false) onRecovered(now)
            } else {
                startEpisode(now)
                host.schedule(Timer.CHECK_REMOVAL, removalConfirmMs)
            }
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
        // So does a pocket swap: only warnings confirmed by the uncover alone, or a jolt, recover.
        if (evidence != Evidence.UNCOVERED && evidence != Evidence.JOLT && evidence != Evidence.PROXIMITY_ONLY) return
        // A jolt is fabric flicker only if the phone barely turned: a hand pulling it out and
        // cupping the sensor or pocketing it swings it about on the way, even if it ends up at
        // the same angle.
        if (evidence == Evidence.JOLT && hasMotionData &&
            (turnedDeg > FLICKER_MAX_PATH_DEG || motion.gravityAngleTo(preX, preY, preZ) > FLICKER_MAX_TURN_DEG)
        ) {
            return
        }
        cancelAll()
        go(Phase.IN_POCKET)
        // Turned far while it was out: maybe the thief's own pocket, so decide as for any re-cover.
        if (hasMotionData && (peakHeld || motion.gravityAngleTo(preX, preY, preZ) >= OUT_AND_IN_FINAL_DEG)) {
            deciding = true
            decideCue = cueInEpisode
            host.schedule(Timer.DECIDE, OUT_AND_IN_DECIDE_MS)
        }
    }

    fun onLight(value: Float) {
        when (near) {
            true -> coveredLux = coveredLux?.let { minOf(it, value) } ?: value
            false -> uncoveredLux = value
            null -> Unit
        }
    }

    /** A jolt measured elsewhere (tests, or phones without a raw accelerometer stream). */
    fun onMotion(strength: Float) {
        if (strength > MOTION_SPIKE) lastMotionAt = host.now()
    }

    /** One raw accelerometer sample, m/s² with gravity, timestamped on the [Host.now] clock. */
    fun onAccel(t: Long, x: Float, y: Float, z: Float) {
        // One broken sample would poison the filters for good.
        if (!hasMotionSensor || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val events = motion.onSample(t, x, y, z)
        if (events and MotionProcessor.Event.JOLT != 0) lastMotionAt = t
        if (!phase.isAlarm) table.trackSettle(t)
        when (phase) {
            Phase.IN_POCKET -> if (near == true && !episodeActive && table.latched && host.config().tableGuard &&
                table.readyToEnter(t) { host.isLocked() }
            ) {
                enterTable(t)
            }
            // Locked, screen black and lying still and flat, uncovered (face-up, or face-down
            // with a sensor that reads far): protected now. Not at a tilt, as on a lap or upright
            // in a loose pocket.
            Phase.WAITING_FOR_POCKET -> if (near != true && motion.atRest && lyingFlat() && host.config().tableGuard &&
                host.screenOff() && host.isLocked() && !host.inCall()
            ) {
                host.log("locked, screen off and lying still: table protection on")
                enterTable(t)
            }
            Phase.ON_TABLE -> onTableMotion(t, table.onSample(t, events))
            Phase.WARNING, Phase.SIREN -> if (trigger == Trigger.TABLE_MOVED) tryPutBack(t)
            else -> Unit
        }
        if (events and MotionProcessor.Event.FRAME != 0) onFrame(t)
    }

    /** The screen turned on (any reason: a press, lift-to-wake, a notification). */
    fun onDisplayOn() {
        val now = host.now()
        if (host.ringing() || now - powerEventAt < POWER_EXCLUSION_MS) return
        displayOnAt = now
        val uncovered = uncoveredAt
        // Woken right as it came out, or just after being turned: someone is looking at it.
        val atUncover = uncovered != null && now - uncovered in -500L..1500L
        val afterTurn = now - lastRotationAt <= 1500L
        if (atUncover || afterTurn) displayCueAt = now
    }

    /**
     * A call was answered (the audio mode switched to a call). Picked up off the table to answer
     * it, even in silent mode where nothing rang: the owner's, as for a ringing call.
     */
    fun onCallAnswered() {
        if (trigger != Trigger.TABLE_MOVED || !phase.isAlarm) return
        if (phase == Phase.SIREN && host.now() - tableTriggerAt > PUT_BACK_FROM_SIREN_MS) return
        table.clearLatch()
        cancelAll()
        resetEpisode()
        go(Phase.WAITING_FOR_POCKET)
        protectAgainWhenCovered()
    }

    /** Back to waiting while already covered (put back face-down): no new cover will come. */
    private fun protectAgainWhenCovered() {
        if (near == true) host.schedule(Timer.CONFIRM_POCKET, POCKET_CONFIRM_MS)
    }

    /**
     * The screen went black (power pressed or timed out): that is the owner locking it, so a
     * covered phone is protected at once instead of on the next retry.
     */
    fun onScreenOff() {
        if (phase != Phase.WAITING_FOR_POCKET || near != true) return
        host.cancel(Timer.CONFIRM_POCKET)
        host.schedule(Timer.CONFIRM_POCKET, (coveredAt + POCKET_CONFIRM_MS - host.now()).coerceAtLeast(0L))
    }

    /** Charger plugged or unplugged: those wake the screen by themselves. */
    fun onPowerEvent() {
        powerEventAt = host.now()
    }

    fun onTimer(timer: Timer) {
        when (timer) {
            Timer.CONFIRM_POCKET -> confirmPocket()
            Timer.CHECK_REMOVAL -> checkRemoval()
            Timer.SIREN -> if (phase == Phase.WARNING) siren(null)
            Timer.DECIDE -> decideOutAndIn()
            Timer.TABLE_CHECK -> checkTableUncover()
            Timer.TABLE_WATCH -> watchTableMotion()
        }
    }

    fun onOwnerUnlocked() {
        if (phase == Phase.OFF) return
        cancelAll()
        resetEpisode()
        if (phase != Phase.WAITING_FOR_POCKET) go(Phase.WAITING_FOR_POCKET)
        if (near == true) host.schedule(Timer.CONFIRM_POCKET, POCKET_CONFIRM_MS)
    }

    /**
     * The owner called for help (the SOS buttons): the SOS alarm at once, from any armed state,
     * with no warning first. Unlocking stops it, as for any alarm.
     */
    fun onSos() {
        if (phase == Phase.OFF || (phase == Phase.SIREN && trigger == Trigger.SOS)) return
        siren(Trigger.SOS)
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
            Phase.IN_POCKET, Phase.ON_TABLE -> {
                host.noticeBuzz()
                // Its own double buzz must not read as the phone being slid or tilted on the table.
                table.ownVibration(host.now(), 300)
            }
            Phase.WAITING_FOR_POCKET -> if (host.isLocked()) warn(Trigger.EARBUDS_DISCONNECTED)
            else -> Unit
        }
    }

    private fun confirmPocket() {
        if (phase != Phase.WAITING_FOR_POCKET || near != true) return
        // In a call, or unlocked with the screen on: a pull-out now would count as the owner, so
        // "protected" would be a lie. Confirm once the call ends or the screen goes off.
        if (host.inCall() || !host.isLocked()) {
            val why = if (host.inCall()) "in a call" else "screen on or unlocked"
            if (why != waitingWhy) host.log("covered, but $why: not protecting yet (press power to lock)")
            waitingWhy = why
            host.schedule(Timer.CONFIRM_POCKET, CALL_RETRY_MS)
            return
        }
        waitingWhy = null
        resetEpisode()
        go(Phase.IN_POCKET)
        host.confirmBuzz()
        table.ownVibration(host.now(), 100)
    }

    private fun checkRemoval() {
        if (phase != Phase.IN_POCKET || near != false) return
        // Unlocked means the owner is using it; calls cover and uncover the sensor at the ear.
        if (!host.isLocked() || host.inCall()) {
            host.log("uncovered while unlocked or in a call: the owner's")
            resetEpisode()
            go(Phase.WAITING_FOR_POCKET)
            return
        }
        warn(Trigger.POCKET_REMOVAL, removalEvidence())
    }

    /**
     * Armed, locked and uncovered past the flicker filter: that is a removal. Light and motion
     * only say how it came out (for the history), and fresh bright light also stops the warning
     * from being taken for fabric flicker.
     */
    private fun removalEvidence(): Evidence {
        if (!hasMotionSensor && !hasLightSensor) return Evidence.PROXIMITY_ONLY
        val inside = coveredLux
        val outside = uncoveredLux
        confirmedByLight = hasLightSensor && inside != null && outside != null && outside > maxOf(inside * 3f, inside + 10f)
        if (confirmedByLight) return Evidence.LIGHT
        val motionAt = lastMotionAt
        return if (motionAt != null && host.now() - motionAt < MOTION_WINDOW_MS) Evidence.JOLT else Evidence.UNCOVERED
    }

    // --- Pocket: how the phone turns while it's out ----------------------------------------------

    /** Slow gravity per 100 ms frame for the last second: the posture before an uncover. */
    private val postureRing = FloatArray(30)
    private var postureFrames = 0

    /** Gated gravity per frame for the last second: how fast it is turning. */
    private val rotationRing = FloatArray(30)
    private var lastRotationAt = Long.MIN_VALUE / 2
    private var displayOnAt = Long.MIN_VALUE / 2
    private var displayCueAt = Long.MIN_VALUE / 2
    private var powerEventAt = Long.MIN_VALUE / 2

    /** Uncovered in the pocket: the episode that ends in a warning, a re-cover or a watch. */
    private var episodeActive = false
    private var episodeStart = 0L
    private var preX = 0f
    private var preY = 0f
    private var preZ = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var pathDeg = 0f

    // The same path on gated gravity (samples near 1 g only), so a jolt's own push doesn't count
    // as turning: how much the phone really swung while out.
    private var turnedDeg = 0f
    private var lastGX = 0f
    private var lastGY = 0f
    private var lastGZ = 0f
    private var peakSince = -1L
    private var peakHeld = false
    private var cueInEpisode = false
    private val coverTimes = LongArray(LOOSE_CYCLES)
    private var coverCount = 0

    // Captured at the re-cover for the out-and-in decision.
    private var deciding = false
    private var decideCue = false

    private fun resetEpisode() {
        episodeActive = false
        deciding = false
        host.cancel(Timer.DECIDE)
    }

    private fun startEpisode(now: Long) {
        episodeActive = true
        episodeStart = now
        cueInEpisode = now - displayOnAt in 0L..500L
        // Woken just before it came out: the display cue for the turn that follows.
        if (cueInEpisode) displayCueAt = now
        pathDeg = 0f
        turnedDeg = 0f
        lastGX = motion.gx; lastGY = motion.gy; lastGZ = motion.gz
        peakSince = -1L
        peakHeld = false
        deciding = false
        host.cancel(Timer.DECIDE)
        // The pocket posture: slow gravity from half a second before the uncover.
        if (postureFrames >= 6) {
            val i = ((postureFrames - 6) % 10) * 3
            preX = postureRing[i]; preY = postureRing[i + 1]; preZ = postureRing[i + 2]
        } else {
            preX = motion.sx; preY = motion.sy; preZ = motion.sz
        }
        lastX = motion.fx; lastY = motion.fy; lastZ = motion.fz
    }

    /** Every 100 ms of samples. */
    private fun onFrame(t: Long) {
        val i = (postureFrames % 10) * 3
        if (postureFrames >= 10) {
            val turned = MotionProcessor.angle(
                rotationRing[i], rotationRing[i + 1], rotationRing[i + 2], motion.gx, motion.gy, motion.gz,
            )
            if (turned >= ROTATION_DEG) lastRotationAt = t
        }
        rotationRing[i] = motion.gx; rotationRing[i + 1] = motion.gy; rotationRing[i + 2] = motion.gz
        postureRing[i] = motion.sx; postureRing[i + 1] = motion.sy; postureRing[i + 2] = motion.sz
        postureFrames++
        // The path the phone turned through, from the uncover until the out-and-in decision.
        if (episodeActive || deciding || (phase.isAlarm && trigger == Trigger.POCKET_REMOVAL)) {
            pathDeg += MotionProcessor.angle(lastX, lastY, lastZ, motion.fx, motion.fy, motion.fz)
            lastX = motion.fx; lastY = motion.fy; lastZ = motion.fz
            turnedDeg += MotionProcessor.angle(lastGX, lastGY, lastGZ, motion.gx, motion.gy, motion.gz)
            lastGX = motion.gx; lastGY = motion.gy; lastGZ = motion.gz
        }
        if (phase != Phase.IN_POCKET || !episodeActive || near != false) return

        val theta = motion.gravityAngleTo(preX, preY, preZ)

        if (theta >= OUT_AND_IN_PEAK_DEG) {
            if (peakSince < 0) peakSince = t
            if (t - peakSince >= OUT_AND_IN_PEAK_HOLD_MS) peakHeld = true
        } else {
            peakSince = -1L
        }

        val cue = t - displayCueAt <= DISPLAY_CUE_MS || lightCue()
        if (cue) cueInEpisode = true
    }

    private fun lightCue(): Boolean {
        val inside = coveredLux ?: return false
        val outside = uncoveredLux ?: return false
        return hasLightSensor && outside > maxOf(inside * 3f, inside + 10f)
    }

    /** Covered again while the episode was open: maybe it went into another pocket. */
    private fun onRecovered(now: Long) {
        coverTimes[coverCount % LOOSE_CYCLES] = now
        coverCount++
        if (!episodeActive) return
        val outMs = now - episodeStart
        episodeActive = false
        if (!hasMotionData || outMs < OUT_AND_IN_MIN_OUT_MS) return
        // Held at a big angle while out, or (too quick for the posture filter to hold one) put
        // back in at a new angle: the decision then weighs the turn and the cues.
        if (!peakHeld && motion.gravityAngleTo(preX, preY, preZ) < OUT_AND_IN_FINAL_DEG) return
        deciding = true
        decideCue = cueInEpisode
        host.schedule(Timer.DECIDE, OUT_AND_IN_DECIDE_MS)
    }

    private fun decideOutAndIn() {
        if (!deciding) return
        deciding = false
        if (phase != Phase.IN_POCKET || near != true) return
        val now = host.now()
        val net = motion.gravityAngleTo(preX, preY, preZ)
        if (net < OUT_AND_IN_FINAL_DEG) return
        // Sitting down or bending turns the phone one way in one go; a hand takes it out, turns
        // it about and puts it away, so its path is much longer than where it ends up.
        val postureEvent = pathDeg <= 1.5f * net
        if (postureEvent && !decideCue) return
        val n = sqrt(preX * preX + preY * preY + preZ * preZ).coerceAtLeast(1e-3f)
        val upright = abs(preY / n) >= 0.7f
        val oldest = coverTimes[coverCount % LOOSE_CYCLES]
        val loose = coverCount >= LOOSE_CYCLES && now - oldest <= 30_000L
        val quietUpright = upright && !loose && !motion.walkedWithin(now, 10_000L)
        if (!quietUpright && !decideCue) return
        if (!host.isLocked() || host.inCall()) return
        warn(Trigger.POCKET_REMOVAL, Evidence.OUT_AND_IN)
    }

    // --- Table --------------------------------------------------------------------------------------

    private var tableTicked = false
    private var proxTrusted = true
    private var glitches = 0
    private var tableTriggerAt = 0L
    private val putBackTimes = LongArray(PUT_BACK_LIMIT)
    private var putBackCount = 0
    private var tableCheckStage = 0

    private var waitingWhy: String? = null

    private fun enterTable(t: Long) {
        cancelAll()
        go(Phase.ON_TABLE)
        table.start(t)
        host.schedule(Timer.TABLE_WATCH, TABLE_WATCH_MS)
        proxTrusted = true
        glitches = 0
        // Two buzzes the first time for each placement: "it knows it's on the table".
        if (!tableTicked) {
            tableTicked = true
            host.tableTick()
            table.ownVibration(t, 200)
        }
    }

    private fun onTableProximity(isNear: Boolean) {
        host.cancel(Timer.TABLE_CHECK)
        if (isNear || !proxTrusted || !tableFaceDown()) return
        tableCheckStage = 1
        host.schedule(Timer.TABLE_CHECK, TABLE_UNCOVER_MS)
    }

    private fun tableFaceDown() = motion.gravityAngleTo(0f, 0f, -1f) <= 45f

    private fun lyingFlat() =
        motion.gravityAngleTo(0f, 0f, 1f) <= FLAT_ENTRY_DEG || motion.gravityAngleTo(0f, 0f, -1f) <= FLAT_ENTRY_DEG

    /**
     * On the table every motion rule needs the accelerometer. Some phones stop it with the screen
     * off: then a covered phone falls back to pocket protection (an uncover still warns), and
     * an uncovered one can't be watched, which the log says.
     */
    private fun watchTableMotion() {
        if (phase != Phase.ON_TABLE) return
        val now = host.now()
        if (motion.hasData && now - motion.lastSampleAt <= TABLE_MOTION_STALE_MS) {
            host.schedule(Timer.TABLE_WATCH, TABLE_WATCH_MS)
            return
        }
        if (near == true) {
            host.log("motion sensor stopped on the table: protecting as in a pocket")
            cancelAll()
            resetEpisode()
            go(Phase.IN_POCKET)
        } else {
            host.log("motion sensor stopped on the table: a lift can't be seen")
            host.schedule(Timer.TABLE_WATCH, TABLE_WATCH_MS)
        }
    }

    /** Uncovered on the table: real only with lift-like motion; a stalled sensor fails safe. */
    private fun checkTableUncover() {
        if (phase != Phase.ON_TABLE || near != false) return
        val now = host.now()
        if (!motion.hasData || now - motion.lastSampleAt > 800L) {
            tableAlarm(now, Evidence.MOTION_UNKNOWN)
            return
        }
        if (table.recentLiftLike(now)) {
            tableAlarm(now, Evidence.TABLE_UNCOVER)
            return
        }
        if (tableCheckStage == 1) {
            tableCheckStage = 2
            host.schedule(Timer.TABLE_CHECK, TABLE_UNCOVER_SECOND_MS)
            return
        }
        // The sensor flickered with nothing moving the phone. Twice, and it isn't trusted here.
        glitches++
        if (glitches >= 2) proxTrusted = false
    }

    private fun onTableMotion(t: Long, signal: TableMonitor.Signal) {
        when (signal) {
            TableMonitor.Signal.NONE, TableMonitor.Signal.UNCOVER -> Unit
            TableMonitor.Signal.SLIDE -> tableAlarm(t, Evidence.TABLE_SLIDE)
            TableMonitor.Signal.TILT -> tableAlarm(t, Evidence.TABLE_TILT)
            TableMonitor.Signal.LIFT -> tableAlarm(t, Evidence.TABLE_LIFT)
            TableMonitor.Signal.HELD -> tableAlarm(t, Evidence.TABLE_HELD)
            TableMonitor.Signal.POSE -> tableAlarm(t, Evidence.TABLE_POSE)
            TableMonitor.Signal.FALL -> tableAlarm(t, Evidence.TABLE_FALL)
        }
    }

    private fun tableAlarm(t: Long, why: Evidence) {
        if (phase != Phase.ON_TABLE) return
        // Already in a call (answered before it moved): the owner's. A ringing phone that's
        // picked up still warns, since an accomplice can ring it: answering stops the warning
        // (onCallAnswered), as it shows the owner has it.
        if (host.inCall()) {
            table.clearLatch()
            cancelAll()
            go(Phase.WAITING_FOR_POCKET)
            protectAgainWhenCovered()
            return
        }
        // The ring's own vibration can shake it along the table: that alone isn't a pick-up.
        if (host.ringing() && (why == Evidence.TABLE_SLIDE || why == Evidence.TABLE_HELD)) return
        tableTriggerAt = t
        warn(Trigger.TABLE_MOVED, why)
    }

    /** Put back down flat and still after a table warning: someone glanced at it and let it be. */
    private fun tryPutBack(t: Long) {
        if (!motion.atRest || t - motion.restSince < PUT_BACK_REST_MS || motion.restSince <= tableTriggerAt) return
        if (phase == Phase.SIREN && t - tableTriggerAt > PUT_BACK_FROM_SIREN_MS) return
        val faceDown = motion.gravityAngleTo(0f, 0f, -1f) <= PUT_BACK_FLAT_DEG
        val faceUp = motion.gravityAngleTo(0f, 0f, 1f) <= PUT_BACK_FLAT_DEG
        if (!faceDown && !faceUp) return
        if (faceDown && proxTrusted && near == false) return
        val oldest = putBackTimes[putBackCount % PUT_BACK_LIMIT]
        if (putBackCount >= PUT_BACK_LIMIT && t - oldest <= PUT_BACK_PERIOD_MS) return
        putBackTimes[putBackCount % PUT_BACK_LIMIT] = t
        putBackCount++
        cancelAll()
        go(Phase.ON_TABLE)
        table.start(t)
        host.schedule(Timer.TABLE_WATCH, TABLE_WATCH_MS)
    }

    // --- Alarms ---------------------------------------------------------------------------------------

    private fun warn(cause: Trigger, why: Evidence? = null) {
        if (phase != Phase.WAITING_FOR_POCKET && !phase.isProtected) return
        warningAt = host.now()
        evidence = why
        resetEpisode()
        if (fast) {
            // No delay chosen: scream at once. A table pick-up still gets a moment for the
            // owner's own grab-and-unlock, since a face-down phone gives no other sign of who it is.
            if (cause == Trigger.TABLE_MOVED) {
                cancelAll()
                go(Phase.WARNING, cause)
                host.schedule(Timer.SIREN, FAST_TABLE_WARNING_MS)
                return
            }
            siren(cause)
            return
        }
        cancelAll()
        go(Phase.WARNING, cause)
        host.schedule(Timer.SIREN, host.config().graceMs)
    }

    private fun siren(cause: Trigger?) {
        if (phase == Phase.OFF) return
        // A call for help still goes through over a theft siren (its alerts and recording start).
        if (phase == Phase.SIREN && (cause != Trigger.SOS || trigger == Trigger.SOS)) return
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
        if (!to.isAlarm && from.isAlarm) evidence = null
    }

    private fun cancelAll() = Timer.entries.forEach(host::cancel)
}
