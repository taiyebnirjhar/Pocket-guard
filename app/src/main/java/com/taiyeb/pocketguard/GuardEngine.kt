package com.taiyeb.pocketguard

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * All of Pocket Guard's decisions, with no Android types so it runs in plain JVM unit tests.
 * [GuardService] feeds it sensor readings and events, and carries out what it asks for via [Host].
 *
 * Ladder: WAITING_FOR_POCKET -> IN_POCKET -> (pulled out while locked) WARNING -> (no unlock) SIREN.
 * Face-down on a table: IN_POCKET -> ON_TABLE once it lies still on a hard surface; lifting, tilting
 * or pushing it off warns. Airplane mode on the lock screen jumps straight to SIREN.
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

    enum class Timer { CONFIRM_POCKET, CHECK_REMOVAL, SIREN, DECIDE, TABLE_CHECK }

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

        /** An incoming call is ringing. */
        fun ringing(): Boolean = false

        /** Two short buzzes: table protection is on. */
        fun tableTick() = Unit

        /** A short beep (or a 3 s burst after repeated slides): someone moved it on the table. */
        fun tableChirp(burst: Boolean) = Unit
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

        /** ...above this (a phone pulled from a pocket easily exceeds it)... */
        const val MOTION_SPIKE = 2.5f

        /** ...and the sensor must stay uncovered this long, since pocket fabric flickers but returns. */
        const val UNCOVERED_CONFIRM_MS = 1500L

        /** While walking, every step is a jolt: then it takes longer uncovered, after the steps stop. */
        const val GAIT_UNCOVERED_MS = 3000L
        const val GAIT_RECENT_MS = 2000L

        /** A jolt this soon after the uncover, while standing still, is the pull itself. */
        const val PULL_JOLT_MS = 1000L

        /** Turned to look: this far from the pocket posture, with the screen woken as it turned. */
        const val TILT_OUT_DEG = 45f
        const val TILT_OUT_HOLD_MS = 500L
        const val TILT_OUT_UNCOVERED_MS = 1200L
        const val DISPLAY_CUE_MS = 3000L
        const val ROTATION_DEG = 20f

        /** The screen waking within this of a charger event is the charger's doing. */
        const val POWER_EXCLUSION_MS = 2000L

        /** Out of one pocket and into another: a big turn while out, and a new angle once covered. */
        const val OUT_AND_IN_MIN_OUT_MS = 1000L
        const val OUT_AND_IN_PEAK_DEG = 60f
        const val OUT_AND_IN_PEAK_HOLD_MS = 300L
        const val OUT_AND_IN_FINAL_DEG = 30f
        const val OUT_AND_IN_DECIDE_MS = 2000L

        /** Out of the pocket and resting: take the new posture as the reference after this long. */
        const val WATCH_REBASE_MS = 10_000L

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

        /** Four slides in this window give a beep burst instead of a single beep. */
        const val SLIDE_BURST_WINDOW_MS = 120_000L
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
    private val table = TableMonitor(motion)

    /** No delay chosen: confirm removals faster, since the siren is meant to start at once. */
    private val fast get() = host.config().graceMs <= 0
    private val removalConfirmMs get() = if (fast) FAST_REMOVAL_CONFIRM_MS else REMOVAL_CONFIRM_MS
    private val uncoveredConfirmMs get() = if (fast) FAST_UNCOVERED_CONFIRM_MS else UNCOVERED_CONFIRM_MS
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
        uncoveredAt = if (isNear) null else now
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
        // So does a turn to look or a pocket swap: only jolt-confirmed warnings recover.
        if (evidence != Evidence.JOLT && evidence != Evidence.PROXIMITY_ONLY) return
        cancelAll()
        go(Phase.IN_POCKET)
        // Turned far while it was out: maybe the thief's own pocket, so decide as for any re-cover.
        if (hasMotionData && (peakHeld || motion.gravityAngleTo(preX, preY, preZ) >= OUT_AND_IN_FINAL_DEG)) {
            deciding = true
            decideCue = cueInEpisode
            decideFromWatch = false
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
        if (!hasMotionSensor) return
        val events = motion.onSample(t, x, y, z)
        if (events and MotionProcessor.Event.JOLT != 0) {
            lastMotionAt = t
            if (!motion.walking(t)) {
                quietJoltAt = t
                if (episodeActive && pullJoltAt == null && t - episodeStart <= PULL_JOLT_MS) pullJoltAt = t
            }
        }
        if (!phase.isAlarm) table.trackSettle(t)
        when (phase) {
            Phase.IN_POCKET -> if (near == true && !episodeActive && table.latched && host.config().tableGuard &&
                table.readyToEnter(t) { host.credentialLocked() }
            ) {
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
        }
    }

    fun onOwnerUnlocked() {
        if (phase == Phase.OFF) return
        cancelAll()
        resetEpisode()
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
            Phase.IN_POCKET, Phase.ON_TABLE -> host.noticeBuzz()
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
        resetEpisode()
        go(Phase.IN_POCKET)
        host.confirmBuzz()
        table.ownVibration(host.now(), 100)
    }

    private fun checkRemoval() {
        if (phase != Phase.IN_POCKET || near != false) return
        // Unlocked means the owner is using it; calls cover and uncover the sensor at the ear.
        if (!host.isLocked() || host.inCall()) {
            resetEpisode()
            go(Phase.WAITING_FOR_POCKET)
            return
        }
        when (val check = removalEvidence()) {
            is RemovalCheck.Confirmed -> {
                confirmedByLight = check.byLight
                warn(Trigger.POCKET_REMOVAL, check.evidence)
            }
            RemovalCheck.None -> {
                val uncoveredFor = host.now() - (uncoveredAt ?: host.now())
                if (uncoveredFor < evidenceWindowMs()) {
                    host.schedule(Timer.CHECK_REMOVAL, RECHECK_MS) // A late reading may still settle it.
                } else {
                    // No proof yet, but it may be in someone's hand: keep watching how it turns,
                    // whether the screen wakes and where it goes, instead of giving up.
                    watching = true
                }
            }
            is RemovalCheck.WaitLonger -> host.schedule(Timer.CHECK_REMOVAL, check.ms)
        }
    }

    private fun evidenceWindowMs() =
        if (hasMotionData && motion.walkedWithin(host.now(), GAIT_RECENT_MS)) GAIT_UNCOVERED_MS + 1000 else EVIDENCE_WINDOW_MS

    private sealed interface RemovalCheck {
        data class Confirmed(val byLight: Boolean, val evidence: Evidence) : RemovalCheck
        data object None : RemovalCheck
        data class WaitLonger(val ms: Long) : RemovalCheck
    }

    /**
     * Light decides when there are fresh readings from both inside and outside the pocket: brighter
     * outside is a removal; lit outside yet no brighter means something still covers the phone.
     * Otherwise (dark everywhere, or the light sensor asleep with the screen off, which is common)
     * it takes a hand-like jolt plus the sensor staying uncovered for [UNCOVERED_CONFIRM_MS], since
     * fabric flickering over the sensor while walking re-covers it quickly. While walking every
     * step is a jolt, so then it takes [GAIT_UNCOVERED_MS] uncovered, after the steps stop.
     */
    private fun removalEvidence(): RemovalCheck {
        if (!hasMotionSensor && !hasLightSensor) return RemovalCheck.Confirmed(false, Evidence.PROXIMITY_ONLY)
        val now = host.now()
        val inside = coveredLux
        val outside = uncoveredLux
        if (hasLightSensor && inside != null && outside != null) {
            if (outside > maxOf(inside * 3f, inside + 10f)) return RemovalCheck.Confirmed(true, Evidence.LIGHT)
            // A covered sensor reading a bright room is a stale repeat, not a pocket: ignore light.
            val insideTrustworthy = inside < STALE_COVERED_LUX
            if (insideTrustworthy && outside >= DARK_LUX) return RemovalCheck.None
        }
        val motionAt = lastMotionAt
        // Steps are jolts too: only one after the walking stopped counts. The pull itself, made
        // while standing still, still counts when steps follow at once (a thief hurrying off):
        // once they stop, or once the phone has been turned into a hand.
        val pull = pullJoltAt
        val jolted = (motionAt != null && now - motionAt < MOTION_WINDOW_MS &&
            (!hasMotionData || motionAt > motion.lastGaitAt + MotionProcessor.STEP_MAX_MS)) ||
            (hasMotionData && pull != null && (!motion.walkedWithin(now, GAIT_RECENT_MS) || peakHeld))
        if (hasMotionSensor && !jolted) return RemovalCheck.None
        var since = uncoveredAt ?: now
        var needed = uncoveredConfirmMs
        if (hasMotionData && motion.walkedWithin(now, GAIT_RECENT_MS)) {
            if (motion.walking(now)) return RemovalCheck.WaitLonger(RECHECK_MS)
            needed = maxOf(needed, GAIT_UNCOVERED_MS)
            since = maxOf(since, motion.lastGaitAt)
        }
        val uncoveredFor = now - since
        return if (uncoveredFor >= needed) {
            RemovalCheck.Confirmed(false, Evidence.JOLT)
        } else {
            RemovalCheck.WaitLonger(needed - uncoveredFor)
        }
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
    private var watching = false
    private var preX = 0f
    private var preY = 0f
    private var preZ = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var pathDeg = 0f
    private var peakSince = -1L
    private var peakHeld = false
    private var tiltOutSince = -1L
    private var cueInEpisode = false

    /** The latest jolt while not walking, and the one that came with this uncover (the pull). */
    private var quietJoltAt = Long.MIN_VALUE / 2
    private var pullJoltAt: Long? = null
    private var rebaseSince = -1L
    private var rebaseX = 0f
    private var rebaseY = 0f
    private var rebaseZ = 0f

    /** The pocket posture was replaced by where it came to rest: an old episode, needs a cue. */
    private var rebased = false
    private val coverTimes = LongArray(LOOSE_CYCLES)
    private var coverCount = 0

    // Captured at the re-cover for the out-and-in decision.
    private var deciding = false
    private var decideCue = false
    private var decideFromWatch = false

    private fun resetEpisode() {
        episodeActive = false
        watching = false
        deciding = false
        host.cancel(Timer.DECIDE)
    }

    private fun startEpisode(now: Long) {
        episodeActive = true
        episodeStart = now
        watching = false
        cueInEpisode = now - displayOnAt in 0L..500L
        pullJoltAt = if (now - quietJoltAt in 0L..500L) quietJoltAt else null
        // Woken just before it came out: the display cue for the turn that follows.
        if (cueInEpisode) displayCueAt = now
        pathDeg = 0f
        peakSince = -1L
        peakHeld = false
        tiltOutSince = -1L
        rebaseSince = -1L
        rebased = false
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

        // Turned to look: far from the pocket posture and the screen woke as it turned.
        if (theta >= TILT_OUT_DEG) {
            if (tiltOutSince < 0) tiltOutSince = t
        } else {
            tiltOutSince = -1L
        }
        val uncoveredFor = t - (uncoveredAt ?: t)
        if (cue && tiltOutSince >= 0 && t - tiltOutSince >= TILT_OUT_HOLD_MS &&
            uncoveredFor >= TILT_OUT_UNCOVERED_MS && host.isLocked() && !host.inCall()
        ) {
            warn(Trigger.POCKET_REMOVAL, Evidence.TILT_OUT)
            return
        }

        // Out and resting somewhere (a desk, a hand): that becomes the posture to compare with.
        if (watching) {
            if (rebaseSince < 0 || motion.slowAngleTo(rebaseX, rebaseY, rebaseZ) > 10f) {
                rebaseSince = t
                rebaseX = motion.sx; rebaseY = motion.sy; rebaseZ = motion.sz
            } else if (t - rebaseSince >= WATCH_REBASE_MS) {
                preX = rebaseX; preY = rebaseY; preZ = rebaseZ
                rebaseSince = t
                rebased = true
                peakHeld = false
                pathDeg = 0f
            }
        }
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
        // Out a long time, or resting somewhere since: the pocket posture says little any more.
        val fromWatch = rebased || outMs > WATCH_REBASE_MS
        episodeActive = false
        watching = false
        if (!hasMotionData || outMs < OUT_AND_IN_MIN_OUT_MS || !peakHeld) return
        deciding = true
        decideCue = cueInEpisode
        decideFromWatch = fromWatch
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
        if ((decideFromWatch || !quietUpright) && !decideCue) return
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
    private val slideTimes = LongArray(4)
    private var slideCount = 0
    private var tableCheckStage = 0

    private fun enterTable(t: Long) {
        cancelAll()
        go(Phase.ON_TABLE)
        table.start(t)
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
            TableMonitor.Signal.SLIDE -> {
                slideTimes[slideCount % slideTimes.size] = t
                slideCount++
                val oldest = slideTimes[slideCount % slideTimes.size]
                val burst = slideCount >= slideTimes.size && t - oldest <= SLIDE_BURST_WINDOW_MS
                host.tableChirp(burst)
            }
            TableMonitor.Signal.DRIVE_OFF -> {
                // A car pulling away, not a hand: back to pocket rules, silently.
                table.clearLatch()
                go(Phase.IN_POCKET)
            }
            TableMonitor.Signal.TILT -> tableAlarm(t, Evidence.TABLE_TILT)
            TableMonitor.Signal.LIFT -> tableAlarm(t, Evidence.TABLE_LIFT)
            TableMonitor.Signal.HELD -> tableAlarm(t, Evidence.TABLE_HELD)
            TableMonitor.Signal.POSE -> tableAlarm(t, Evidence.TABLE_POSE)
            TableMonitor.Signal.FALL -> tableAlarm(t, Evidence.TABLE_FALL)
        }
    }

    private fun tableAlarm(t: Long, why: Evidence) {
        if (phase != Phase.ON_TABLE) return
        // Picked up to answer a call: the owner's, as in the pocket where calls are ignored.
        if (host.ringing() || host.inCall()) {
            table.clearLatch()
            cancelAll()
            go(Phase.WAITING_FOR_POCKET)
            return
        }
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
        if (!to.isAlarm && from.isAlarm) evidence = null
    }

    private fun cancelAll() = Timer.entries.forEach(host::cancel)
}
