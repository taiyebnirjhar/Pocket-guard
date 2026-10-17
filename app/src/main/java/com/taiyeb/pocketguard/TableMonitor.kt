package com.taiyeb.pocketguard

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Face-down on a table the proximity sensor stays covered, so pocket rules never see a theft:
 * this watches the motion instead. It decides when a covered phone is really lying on a hard
 * surface (not in a pocket, bag or lap), and then which movements mean someone took it.
 *
 * Any movement that could be someone taking it warns: slid, lifted, tilted, held, resting at a
 * new angle, pushed off the edge. Knocks and bumps that leave it where it was don't.
 * Pure Kotlin on top of [MotionProcessor], driven sample by sample.
 */
class TableMonitor(private val m: MotionProcessor, private val log: (String) -> Unit = {}) {

    companion object {
        /** Placement: face-down within this when the sensor gets covered. */
        const val LATCH_FLAT_DEG = 20f

        /**
         * Put down on a table, a phone is still at once; pushed into a pocket it keeps getting
         * jostled. Stillness must have begun this soon after covering (the 1 s stillness window
         * adds its own second).
         */
        const val LATCH_SETTLE_MS = 2500L

        /** Hard-surface test. */
        const val TABLE_REST_MS = 3000L
        const val TABLE_FLAT_DEG = 10f
        const val CREEP_DEG = 0.15f

        /** Rocking of the one-second means beyond this is breathing, a lap or a cushion. */
        const val BODY_DEG = 0.15f

        /** Lift-type rules. */
        const val TILT_DEG = 10f
        const val TILT_HOLD_MS = 300L
        const val TILT_GATE = 0.08f
        const val LIFT_CM = 3f
        const val LIFT_WINDOW_MS = 1000L
        const val HELD_MS = 3000L
        const val HELD_DEG = 6f
        const val POSE_DEG = 8f

        /** Uncover counts when there was a lift-like motion within this of the uncover. */
        const val UNCOVER_MOTION_MS = 1000L
        const val UNCOVER_MOTION_DEG = 3f

        /** Slides. */
        const val BURST_START = 0.15f
        const val BURST_CAP_MS = 3000L
        const val SLIDE_CM = 5f

        /**
         * A vehicle pulling away pushes one way for seconds (a hand accelerates and stops). After
         * [DRIVE_MS] of that the phone is a vehicle suspect: slow-hand rules pause, tilt, uncover
         * and fall stay armed. It never leaves table mode for it: a hand can look like a car, and
         * a silent exit would drop protection. Confirmed by road or engine vibration, or by a push
         * lasting [DRIVE_CONFIRM_MS], the pause holds until the phone has been still for
         * [PARKED_MS] (parked), and the angle it rests at then becomes the placement pose;
         * otherwise it ends [SUSPECT_MS] after it began, once the phone is still.
         */
        const val DRIVE_ACCEL = 0.2f
        const val DRIVE_MS = 2000L
        const val VEHICLE_PUSH_MS = 1500L
        const val DRIVE_CONFIRM_MS = 5000L
        const val SUSPECT_MS = 10_000L
        const val PARKED_MS = 30_000L
        const val VEHICLE_SIGMA = 0.12f
        const val VEHICLE_VIBRATION_MS = 3000L

        /**
         * An engine shakes the phone without moving it: vibration this long while it lies within
         * [VEHICLE_STEADY_DEG] of where it rested makes it a vehicle suspect before it drives off.
         * A hand can't hold it that steady. Confirming a vehicle still takes a push.
         */
        const val VEHICLE_IDLE_MS = 3000L
        const val VEHICLE_STEADY_DEG = 1f

        /** After the app's own buzz, motion is ignored this long. */
        const val OWN_VIBRATION_MS = 500L

        /** A buzz before the placement settled gives it this long after the buzz to be still. */
        const val OWN_BUZZ_SETTLE_MS = 1500L
    }

    enum class Signal { NONE, TILT, LIFT, HELD, POSE, FALL, UNCOVER, SLIDE }

    // --- Placement latch and entry ------------------------------------------------------------

    /** Set when the sensor was covered with the phone face-down and still roughly flat. */
    var latched = false
        private set
    private var latchAt = 0L
    private var latchX = 0f
    private var latchY = 0f
    private var latchZ = 0f
    private var settled = false

    /** Stillness must have begun by this time; the app's own buzz pushes it back. */
    private var settleDeadline = 0L

    // Hard-surface test over the current stretch of stillness.
    private var testRestSince = -1L
    private var testStartAt = 0L
    private var testStartX = 0f
    private var testStartY = 0f
    private var testStartZ = 0f

    /** Per-axis extremes of the one-second means' direction: how far it swings either way. */
    private val swingLo = FloatArray(3)
    private val swingHi = FloatArray(3)

    /** Proximity going near. Starts a latch when the phone is face-down and roughly flat. */
    fun onCovered(t: Long) {
        if (!m.hasData || m.fastAngleTo(0f, 0f, -1f) > LATCH_FLAT_DEG) {
            latched = false
            if (m.hasData) log("table: not face-down when covered (%.0f° from flat)".format(m.fastAngleTo(0f, 0f, -1f)))
            return
        }
        log("table: covered face-down, waiting for it to lie still")
        latched = true
        latchAt = t
        settleDeadline = t + LATCH_SETTLE_MS
        latchX = m.fx; latchY = m.fy; latchZ = m.fz
        settled = false
        testRestSince = -1L
    }

    fun onUncovered() {
        latched = false
        testRestSince = -1L
    }

    fun clearLatch() {
        latched = false
        testRestSince = -1L
    }

    /**
     * Every sample while not alarming: the settle check. Judged as soon as stillness arrives; the
     * app's own pocket buzz restarts the stillness window, so it extends the deadline. The pose it
     * settled in becomes the placement pose: it may have been covered at an angle, or still at one
     * just before it was laid flat, so any stillness that began by the deadline counts.
     */
    fun trackSettle(t: Long) {
        if (!latched) return
        if (m.atRest && m.restSince <= settleDeadline) {
            if (!settled) log("table: lying still, testing the surface (3 s)")
            settled = true
            latchX = m.restX; latchY = m.restY; latchZ = m.restZ
        } else if (!settled && t > settleDeadline) {
            latched = false
            log("table: not still soon enough after being put down (a pocket?), not a table")
        }
    }

    /** True once a settled placement has passed the hard-surface test. Call for every sample. */
    fun readyToEnter(t: Long, locked: () -> Boolean): Boolean {
        if (!latched || !settled) return false
        if (!m.atRest) {
            testRestSince = -1L
            return false
        }
        // The pose must stay the one it was placed in.
        if (m.gravityAngleTo(latchX, latchY, latchZ) > TABLE_FLAT_DEG) {
            latched = false
            log("table: moved away from where it was put down, not a table")
            return false
        }
        if (testRestSince != m.restSince) {
            testRestSince = m.restSince
            restartTest(t)
        }
        // Body or soft surface: the tilt keeps rocking or creeping while "still". Measured on
        // one-second means, swing to swing (a start mid-swing would halve it), against a limit no
        // lower than 8x their own noise.
        val n = sqrt(m.restX * m.restX + m.restY * m.restY + m.restZ * m.restZ).coerceAtLeast(1e-3f)
        val dir = floatArrayOf(m.restX / n, m.restY / n, m.restZ / n)
        var span = 0f
        for (i in 0..2) {
            swingLo[i] = min(swingLo[i], dir[i])
            swingHi[i] = max(swingHi[i], dir[i])
            span += (swingHi[i] - swingLo[i]) * (swingHi[i] - swingLo[i])
        }
        val rockDeg = Math.toDegrees(sqrt(span).toDouble()).toFloat()
        val meanNoiseDeg = Math.toDegrees(
            (m.noiseFloor / sqrt(m.windowSamples.coerceAtLeast(1).toFloat()) / m.g0).toDouble(),
        ).toFloat()
        if (rockDeg > max(BODY_DEG, 8f * meanNoiseDeg)) {
            log("table: rocking %.2f° (a lap, cushion or body?), testing again".format(rockDeg))
            restartTest(t) // Breathing, a cushion or a lap: the test starts over.
            return false
        }
        if (t - testStartAt < TABLE_REST_MS) return false
        val creep = MotionProcessor.angle(m.restX, m.restY, m.restZ, testStartX, testStartY, testStartZ)
        if (creep > CREEP_DEG) {
            log("table: still sinking %.2f° (soft surface?), testing again".format(creep))
            restartTest(t)
            return false
        }
        if (m.gravityAngleTo(0f, 0f, -1f) > TABLE_FLAT_DEG) {
            logOnce("table: surface ok but tilted %.0f° (over 10°)".format(m.gravityAngleTo(0f, 0f, -1f)))
            return false
        }
        if (!locked()) {
            logOnce("table: surface ok, waiting for the phone to be locked")
            return false
        }
        return true
    }

    private var lastOnce = ""

    /** Conditions checked on every sample: log each one only when it first comes up. */
    private fun logOnce(message: String) {
        if (message == lastOnce) return
        lastOnce = message
        log(message)
    }

    private fun restartTest(t: Long) {
        testStartAt = t
        testStartX = m.restX; testStartY = m.restY; testStartZ = m.restZ
        val n = sqrt(m.restX * m.restX + m.restY * m.restY + m.restZ * m.restZ).coerceAtLeast(1e-3f)
        swingLo[0] = m.restX / n; swingLo[1] = m.restY / n; swingLo[2] = m.restZ / n
        swingLo.copyInto(swingHi)
    }

    // --- On the table ---------------------------------------------------------------------------

    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    private var refG0 = MotionProcessor.G

    /** The latest resting reading: movement starts from here (the phone may rest a little tilted). */
    private var baseX = 0f
    private var baseY = 0f
    private var baseZ = 0f
    private var baseG0 = MotionProcessor.G
    private var ignoreUntil = 0L

    private var burstActive = false
    private var burstPushed = false
    private var burstStart = 0L
    private val burst = Integrator()
    private var liftDone = false
    private var tiltSince = -1L
    private var lastLiftLikeAt = Long.MIN_VALUE / 2
    private var poseCheckedFor = -1L

    // One-way push tracking (vehicles), on every sample.
    private var driveSince = -1L
    private var driveX = 0f
    private var driveY = 0f
    private var driveZ = 0f
    private var driveMeasured = 0f
    private var driveExpected = 0f
    private var driveH = 0f
    private var driveLowSince = -1L
    private var suspectSince = -1L
    private var vibrationSince = -1L
    private var shakeSince = -1L

    /** The suspect state has seen a push, not just vibration: vibration may then confirm it. */
    private var suspectPushed = false

    /** A vehicle is confirmed: the slow-hand rules stay paused until it has been parked a while. */
    private var inVehicle = false

    /** A vehicle is suspected (or confirmed): the slow-hand rules pause while it lasts. */
    val vehicleSuspect get() = suspectSince >= 0

    /** Enters table watch: the reference pose is where it lies now. */
    fun start(t: Long) {
        refX = m.restX; refY = m.restY; refZ = m.restZ
        baseX = refX; baseY = refY; baseZ = refZ
        refG0 = m.g0
        baseG0 = m.g0
        burstActive = false
        tiltSince = -1L
        driveSince = -1L
        suspectSince = -1L
        vibrationSince = -1L
        shakeSince = -1L
        suspectPushed = false
        inVehicle = false
        poseCheckedFor = m.restSince
        ignoreUntil = t + OWN_VIBRATION_MS
    }

    /** The app is about to vibrate the phone itself; don't mistake that for a hand. */
    fun ownVibration(t: Long, durationMs: Long) {
        ignoreUntil = max(ignoreUntil, t + durationMs + OWN_VIBRATION_MS)
        // A stillness window (up to 1.2 s at 25 Hz) has to pass again after the buzz.
        if (latched && !settled) settleDeadline = max(settleDeadline, t + durationMs + OWN_BUZZ_SETTLE_MS)
    }

    /** Tilt of the phone now away from where it lay. */
    fun tiltDeg() = m.fastAngleTo(refX, refY, refZ)

    /** Was there a lift-like motion just now: tilt, a lift, or leaving stillness? */
    fun recentLiftLike(t: Long) = t - lastLiftLikeAt <= UNCOVER_MOTION_MS

    /** Call for every sample while on the table. Returns what the movement amounts to. */
    fun onSample(t: Long, events: Int): Signal {
        if (t < ignoreUntil) {
            burstActive = false
            tiltSince = -1L
            return Signal.NONE
        }
        // Falling off the edge, without the upward kick of a hop.
        if (events and MotionProcessor.Event.FALL != 0) return Signal.FALL

        val tilt = tiltDeg()
        if (m.fastAngleTo(baseX, baseY, baseZ) >= UNCOVER_MOTION_DEG) lastLiftLikeAt = t

        // Turned over or tilted up: fast gravity far from the resting pose, with |a| near 1 g so
        // it is a real rotation rather than acceleration faking one. Faking a tilt θ takes a push
        // that raises |a| by g (1 / cos θ - 1); sensor offsets shift |a| far less, so the gate is
        // half of that (and at least 0.08, a small real tilt with a sensor offset).
        // Against |a| where it was placed: a smooth push holds it "still", and the 1 g learned
        // then would include the push.
        if (tilt >= TILT_DEG && abs(m.magnitude - refG0) <= max(TILT_GATE, 0.5f * fakeTiltExcess(tilt))) {
            if (tiltSince < 0) tiltSince = t
            if (t - tiltSince >= TILT_HOLD_MS) return Signal.TILT
        } else {
            tiltSince = -1L // Not tilted, or |a| says it's a push: the 300 ms start over.
        }

        // Vehicles: a steady one-way push, then road or engine vibration.
        val pushing = trackDrive(t)
        // Only a push of [DRIVE_MS] or more lets vibration confirm a vehicle, and only vibration
        // after it counts: shaking before a grab says nothing about the grab.
        if (pushing && vehicleSuspect && t - driveSince >= DRIVE_MS && !suspectPushed) {
            suspectPushed = true
            vibrationSince = -1L
        }
        if (events and MotionProcessor.Event.FRAME != 0) {
            val shaking = m.lastSigma >= VEHICLE_SIGMA
            if (shaking && m.fastAngleTo(baseX, baseY, baseZ) < VEHICLE_STEADY_DEG && !m.walkedWithin(t, 3000L)) {
                if (shakeSince < 0) shakeSince = t
                if (t - shakeSince >= VEHICLE_IDLE_MS && !vehicleSuspect) {
                    suspectSince = t
                    suspectPushed = false
                }
            } else {
                shakeSince = -1L
            }
            // Walking shakes it too, and is no vehicle.
            if (vehicleSuspect && suspectPushed && shaking && !m.walkedWithin(t, 3000L)) {
                if (vibrationSince < 0) vibrationSince = t
            } else {
                vibrationSince = -1L
            }
        }
        if (vehicleSuspect) {
            if ((suspectPushed && vibrationSince >= 0 && t - vibrationSince >= VEHICLE_VIBRATION_MS) ||
                (pushing && t - driveSince >= DRIVE_CONFIRM_MS)
            ) {
                inVehicle = true
            }
            // Full rules again once still: a car between pushes keeps it moving. After a drive it
            // was parked somewhere new, so the angle it rests at now is where it was put.
            val ended = !pushing && m.atRest && if (inVehicle) {
                t - m.restSince >= PARKED_MS
            } else {
                t - suspectSince >= SUSPECT_MS
            }
            if (ended) {
                if (inVehicle) {
                    refX = m.restX; refY = m.restY; refZ = m.restZ
                    refG0 = m.g0
                    poseCheckedFor = m.restSince
                } else {
                    poseCheckedFor = -1L
                }
                suspectSince = -1L
                inVehicle = false
                vibrationSince = -1L
                shakeSince = -1L
            }
        }
        val slowRulesPaused = pushing || vehicleSuspect

        // Resting again, but at a new angle: not lying on that table any more.
        if (m.atRest && poseCheckedFor != m.restSince && !slowRulesPaused) {
            poseCheckedFor = m.restSince
            val moved = MotionProcessor.angle(m.restX, m.restY, m.restZ, refX, refY, refZ)
            if (moved >= POSE_DEG) {
                // A steady push (a car pulling away) fakes a tilt, but raises |a| above 1 g by
                // g (1 / cos θ - 1); a real tilt leaves it at 1 g.
                val excess = m.g0 - refG0
                if (excess >= 0.05f && excess >= 0.8f * fakeTiltExcess(moved)) {
                    suspectSince = t
                    suspectPushed = true
                    return Signal.NONE
                }
                return Signal.POSE
            }
            // Rocked a little and settled: follow it, within limits.
            if (moved < 3f) {
                refX = m.restX; refY = m.restY; refZ = m.restZ
            }
        }

        if (!burstActive) {
            if (m.atRest) {
                baseX = m.restX; baseY = m.restY; baseZ = m.restZ
                baseG0 = m.g0
            }
            if (deviationFromBase() >= BURST_START) {
                burstActive = true
                burstStart = t
                burstPushed = false
                liftDone = false
                burst.start(baseX, baseY, baseZ, m)
            }
            return Signal.NONE
        }

        burst.step(m)
        // A vehicle pushes for seconds and moves it too, but that's no hand sliding it (a hand's
        // slide pushes one way for well under a second, then brakes).
        if (vehicleSuspect || (driveSince >= 0 && t - driveSince >= VEHICLE_PUSH_MS)) burstPushed = true
        val elapsed = t - burstStart
        if (burst.up * 100f >= 1f) lastLiftLikeAt = t

        // Lifted: acceleration along gravity, integrated twice, rises >= 3 cm within a second. A
        // push pauses it only while |a| rose about as much as a push explains; a lift raises it more.
        // A car speeding up a slope rises too, by tan(slope) of its push: with the phone flat in the
        // car that is no more than the tilt it rests at, so a lift must rise more than that.
        val nb = sqrt(baseX * baseX + baseY * baseY + baseZ * baseZ).coerceAtLeast(1e-3f)
        val cb = (abs(baseZ) / nb).coerceAtLeast(0.5f)
        val slopeRise = (sqrt(1f - cb * cb) / cb + 0.05f) * driveH
        val liftPaused = vehicleSuspect || (pushing && (driveMeasured <= 2f * driveExpected + 0.5f ||
            driveMeasured - driveExpected < slopeRise))
        if (!liftPaused && !liftDone && elapsed <= LIFT_WINDOW_MS && burst.up * 100f >= LIFT_CM) {
            liftDone = true
            return Signal.LIFT
        }
        // Held in a hand: never still again, and tilted both from where it last lay and from where
        // it was placed (a prop taken away drops it back flat).
        val heldTilt = minOf(m.fastAngleTo(baseX, baseY, baseZ), tilt)
        if (!slowRulesPaused && elapsed >= HELD_MS && heldTilt >= HELD_DEG && !m.atRest) {
            burstActive = false
            return Signal.HELD
        }

        // The burst ends when it is still again (or at the cap): a slide if it travelled far.
        if (m.atRest && m.restSince > burstStart) {
            burstActive = false
            val d = burst.correctedHorizontalCm()
            // Slid or rocked and settled level: follow it. Tilted a few degrees: keep measuring
            // from where it was placed, so small tilts can't add up unseen.
            if (MotionProcessor.angle(m.restX, m.restY, m.restZ, refX, refY, refZ) < 3f) {
                refX = m.restX; refY = m.restY; refZ = m.restZ
            }
            return if (d >= SLIDE_CM && !slowRulesPaused && !burstPushed) Signal.SLIDE else Signal.NONE
        }
        // Never still again within the cap: vibration or handling, not a measurable slide (drift
        // can't be corrected without a still end). A hand holding it is T-HELD's job.
        if (elapsed >= BURST_CAP_MS) burstActive = false
        return Signal.NONE
    }

    /**
     * Follows a one-way horizontal push across samples and bursts. Without a gyroscope a steady
     * push looks just like a slow tilt, except that acceleration adds to gravity: |a| rises above
     * 1 g by about h² / 2g, while a real tilt leaves it at 1 g. True while such a push is on.
     */
    private fun trackDrive(t: Long): Boolean {
        // From the latest resting reading: a phone propped at a small angle is not a push, however
        // its sensor offset shifts |a|.
        val n = sqrt(baseX * baseX + baseY * baseY + baseZ * baseZ).coerceAtLeast(1e-3f)
        val ux = baseX / n
        val uy = baseY / n
        val uz = baseZ / n
        val ex = m.ax - baseX
        val ey = m.ay - baseY
        val ez = m.az - baseZ
        val along = ex * ux + ey * uy + ez * uz
        val hx = ex - along * ux
        val hy = ey - along * uy
        val hz = ez - along * uz
        val h = sqrt(hx * hx + hy * hy + hz * hz)
        if (h >= DRIVE_ACCEL) {
            driveLowSince = -1L
            val sameWay = driveSince >= 0 && MotionProcessor.angle(hx, hy, hz, driveX, driveY, driveZ) <= 30f
            if (!sameWay) {
                driveSince = t
                driveX = hx; driveY = hy; driveZ = hz
                driveMeasured = 0f
                driveExpected = 0f
                driveH = 0f
            } else {
                driveMeasured += m.magnitude - baseG0
                driveExpected += h * h / (2f * baseG0)
                driveH += h
                // A push only adds to |a|. Once that excess is gone (a tilt, or a lift and its stop)
                // it was no push: start over, so a later step can't revive it.
                if (t - driveSince >= 500L && driveMeasured < 0.25f * driveExpected) driveSince = -1L
            }
        } else if (driveSince >= 0 && h < DRIVE_ACCEL / 2) {
            if (driveLowSince < 0) driveLowSince = t
            if (t - driveLowSince >= 200L) driveSince = -1L
        }
        val pushing = driveSince >= 0 && t - driveSince >= 500L && driveExpected > 0f &&
            driveMeasured >= 0.5f * driveExpected
        if (pushing && t - driveSince >= DRIVE_MS && suspectSince < 0) {
            suspectSince = t
            suspectPushed = true
        }
        return pushing
    }

    /** How much a sideways push faking a tilt of [deg] would raise |a| above 1 g. */
    private fun fakeTiltExcess(deg: Float) =
        refG0 * (1f / cos(Math.toRadians(deg.toDouble())).toFloat() - 1f)

    /** How far the current sample is from the latest resting reading. */
    private fun deviationFromBase(): Float {
        val dx = m.ax - baseX
        val dy = m.ay - baseY
        val dz = m.az - baseZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Double integration over a short burst. Vertical uses acceleration along the resting gravity
     * direction (a sideways push can't fake it, and a tilt only lowers it); horizontal uses the
     * part across it. Errors grow with time squared, so bursts are kept to a few seconds and
     * corrected for the phone being still at both ends.
     */
    class Integrator {
        private var bx = 0f
        private var by = 0f
        private var bz = 0f
        private var ux = 0f
        private var uy = 0f
        private var uz = 0f
        private var last = 0L
        private var startT = 0L
        private var restAlong = MotionProcessor.G

        var vUp = 0f
            private set
        var up = 0f
            private set
        private var vx = 0f
        private var vy = 0f
        private var vz = 0f
        private var px = 0f
        private var py = 0f
        private var pz = 0f

        fun start(restX: Float, restY: Float, restZ: Float, m: MotionProcessor) {
            bx = restX; by = restY; bz = restZ
            val n = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-3f)
            ux = bx / n; uy = by / n; uz = bz / n
            restAlong = n
            last = m.lastSampleAt
            startT = last
            vUp = 0f; up = 0f
            vx = 0f; vy = 0f; vz = 0f
            px = 0f; py = 0f; pz = 0f
        }

        fun step(m: MotionProcessor) {
            val dt = ((m.lastSampleAt - last).coerceIn(0L, 100L)) / 1000f
            last = m.lastSampleAt
            if (dt <= 0f) return
            val ex = m.ax - bx
            val ey = m.ay - by
            val ez = m.az - bz
            val along = ex * ux + ey * uy + ez * uz
            // The rest reading points up (it is the push of the table), so "along" > 0 is upward.
            vUp += along * dt
            up += vUp * dt
            val hx = ex - along * ux
            val hy = ey - along * uy
            val hz = ez - along * uz
            vx += hx * dt; vy += hy * dt; vz += hz * dt
            px += vx * dt; py += vy * dt; pz += vz * dt
        }

        /** Removes the drift a non-zero end speed shows (the phone is still at both ends). */
        fun correctedHorizontalCm(): Float {
            val span = (last - startT) / 1000f
            val cx = px - vx * span / 2f
            val cy = py - vy * span / 2f
            val cz = pz - vz * span / 2f
            return sqrt(cx * cx + cy * cy + cz * cz) * 100f
        }
    }
}
