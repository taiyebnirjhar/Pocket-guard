package com.taiyeb.pocketguard

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns raw accelerometer samples (m/s², device axes, gravity included) into what the guard
 * decides on: the direction of gravity, stillness, jolts, free falls and walking.
 *
 * Pure Kotlin with no allocation per sample, so it runs in JVM unit tests. Every filter works on
 * sample timestamps, so the results don't depend on the rate a phone delivers (25-200 Hz).
 * Axes: face-up on a table reads (0, 0, +9.81), face-down (0, 0, -9.81), upright (0, +9.81, 0).
 */
class MotionProcessor {

    companion object {
        const val G = 9.81f

        /** A push or pull: |a| this far from 1 g. Rotation alone leaves |a| unchanged. */
        const val JOLT = 2.5f

        /** |a| below this is falling. */
        const val FREE_FALL = 3f
        const val FREE_FALL_MS = 100L

        /** A hop (a fist on the table) kicks up before it falls; a push off the edge doesn't. */
        const val KICK = 5f
        const val KICK_WINDOW_MS = 60L

        /** Gravity estimate: follows slowly, and only while |a| is near 1 g, so jolts don't tilt it. */
        const val GRAVITY_TAU_MS = 400f
        const val GRAVITY_GATE = 1.5f

        /** Fast gravity, for tilt that must be seen within a few hundred ms. */
        const val FAST_TAU_MS = 150f

        /** Slow gravity: the posture the phone has been in for the last few seconds. */
        const val SLOW_TAU_MS = 2000f

        /** Stillness is judged over 1 s, in 100 ms frames. */
        const val FRAME_MS = 100L
        const val REST_FRAMES = 10
        const val REST_SIGMA_MIN = 0.04f

        /** Never above this: an idling engine (about 0.08) must not count as still. */
        const val REST_SIGMA_MAX = 0.06f
        const val REST_RANGE = 0.3f
        const val REST_RANGE_MAX = 0.5f

        /** A longer gap than this resets the stillness window: the stream stalled. */
        const val GAP_RESET_MS = 500L

        /** Walking: >= 4 impacts above this, 1.3-3.5 per second, evenly spaced. */
        const val STEP_IMPACT = 1.5f
        const val STEP_MIN_MS = 280L
        const val STEP_MAX_MS = 770L
        const val GAIT_HOLD_MS = 1500L

        /** Angle in degrees between two vectors (0 when either is zero). */
        fun angle(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
            val n1 = sqrt(x1 * x1 + y1 * y1 + z1 * z1)
            val n2 = sqrt(x2 * x2 + y2 * y2 + z2 * z2)
            if (n1 < 1e-6f || n2 < 1e-6f) return 0f
            val c = ((x1 * x2 + y1 * y2 + z1 * z2) / (n1 * n2)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(c).toDouble()).toFloat()
        }

        private fun alpha(dtMs: Long, tauMs: Float) = 1f - exp(-dtMs / tauMs)
    }

    /** Events from the latest sample, as bit flags. */
    object Event {
        const val FRAME = 1
        const val JOLT = 2
        const val FALL = 4
        const val HOP = 8
        const val REST_START = 16
        const val REST_END = 32
    }

    var hasData = false
        private set
    var lastSampleAt = 0L
        private set

    // Latest raw sample and its magnitude.
    var ax = 0f
        private set
    var ay = 0f
        private set
    var az = 0f
        private set
    var magnitude = G
        private set

    // Gated gravity estimate.
    var gx = 0f
        private set
    var gy = 0f
        private set
    var gz = 0f
        private set

    // Fast gravity (always follows).
    var fx = 0f
        private set
    var fy = 0f
        private set
    var fz = 0f
        private set

    // Slow gravity: posture.
    var sx = 0f
        private set
    var sy = 0f
        private set
    var sz = 0f
        private set

    /** |a| of the phone at rest in its current pose; per-axis sensor offsets make it differ from 9.81. */
    var g0 = G
        private set

    var atRest = false
        private set

    /** When the current stretch of stillness began (valid while [atRest]). */
    var restSince = 0L
        private set

    /** Mean acceleration over the last still second: the best gravity there is. */
    var restX = 0f
        private set
    var restY = 0f
        private set
    var restZ = 0f
        private set

    var lastJoltAt = Long.MIN_VALUE / 2
        private set

    /** Last time a step pattern was seen; walking counts until [GAIT_HOLD_MS] after it. */
    var lastGaitAt = Long.MIN_VALUE / 2
        private set

    /** Samples in the last still second (the delivered rate). */
    var windowSamples = 0
        private set

    /** Smallest one-second sigma seen this session: the sensor's own noise. */
    var noiseFloor = REST_SIGMA_MAX
        private set

    val restSigma get() = (2f * noiseFloor).coerceIn(REST_SIGMA_MIN, REST_SIGMA_MAX)

    fun walking(now: Long) = now - lastGaitAt <= GAIT_HOLD_MS
    fun walkedWithin(now: Long, ms: Long) = now - lastGaitAt <= ms

    /** Angle of the fast gravity estimate away from (x, y, z). */
    fun fastAngleTo(x: Float, y: Float, z: Float) = angle(fx, fy, fz, x, y, z)
    fun gravityAngleTo(x: Float, y: Float, z: Float) = angle(gx, gy, gz, x, y, z)
    fun slowAngleTo(x: Float, y: Float, z: Float) = angle(sx, sy, sz, x, y, z)

    /** |a| minus the at-rest magnitude: positive while accelerating away from the ground. */
    val vertical get() = magnitude - g0

    // --- Stillness frames -------------------------------------------------------------------

    private var frameStart = 0L
    private var frameN = 0
    private var frameSx = 0.0
    private var frameSy = 0.0
    private var frameSz = 0.0
    private var frameSxx = 0.0
    private var frameSyy = 0.0
    private var frameSzz = 0.0
    private var frameSm = 0.0
    private val minX = FloatArray(REST_FRAMES)
    private val maxX = FloatArray(REST_FRAMES)
    private val minY = FloatArray(REST_FRAMES)
    private val maxY = FloatArray(REST_FRAMES)
    private val minZ = FloatArray(REST_FRAMES)
    private val maxZ = FloatArray(REST_FRAMES)
    private val sumN = IntArray(REST_FRAMES)
    private val sumX = DoubleArray(REST_FRAMES)
    private val sumY = DoubleArray(REST_FRAMES)
    private val sumZ = DoubleArray(REST_FRAMES)
    private val sumXX = DoubleArray(REST_FRAMES)
    private val sumYY = DoubleArray(REST_FRAMES)
    private val sumZZ = DoubleArray(REST_FRAMES)
    private val sumM = DoubleArray(REST_FRAMES)
    private var frames = 0
    private var frameIndex = 0
    private var curMinX = 0f
    private var curMaxX = 0f
    private var curMinY = 0f
    private var curMaxY = 0f
    private var curMinZ = 0f
    private var curMaxZ = 0f

    // --- Free fall and kicks ------------------------------------------------------------------

    private var fallStart = 0L
    private var falling = false
    private var fallReported = false
    private var lastKickAt = Long.MIN_VALUE / 2

    // --- Steps ---------------------------------------------------------------------------------

    private val stepTimes = LongArray(5)
    private var stepCount = 0
    private var inImpact = false
    private var impactPeak = 0f
    private var impactPeakAt = 0L

    /** Clears everything learned about the current pose (after a stall, or on re-arming). */
    fun reset() {
        hasData = false
        atRest = false
        frames = 0
        frameN = 0
        falling = false
        stepCount = 0
        inImpact = false
    }

    /** Feeds one sample. Returns the [Event] flags it caused. */
    fun onSample(t: Long, x: Float, y: Float, z: Float): Int {
        var events = 0
        val m = sqrt(x * x + y * y + z * z)
        if (!hasData || t - lastSampleAt > GAP_RESET_MS || t < lastSampleAt) {
            if (!hasData) {
                gx = x; gy = y; gz = z
                fx = x; fy = y; fz = z
                sx = x; sy = y; sz = z
                restX = x; restY = y; restZ = z
            }
            if (atRest) events = events or Event.REST_END
            atRest = false
            frames = 0
            startFrame(t, x, y, z)
            hasData = true
            falling = false
            stepCount = 0
        }
        val dt = (t - lastSampleAt).coerceIn(0L, GAP_RESET_MS)
        lastSampleAt = t
        ax = x; ay = y; az = z
        magnitude = m

        // Gravity estimates.
        if (abs(m - g0) <= GRAVITY_GATE && dt > 0) {
            val a = alpha(dt, GRAVITY_TAU_MS)
            gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz)
        }
        if (dt > 0) {
            val a = alpha(dt, FAST_TAU_MS)
            fx += a * (x - fx); fy += a * (y - fy); fz += a * (z - fz)
            val s = alpha(dt, SLOW_TAU_MS)
            sx += s * (x - sx); sy += s * (y - sy); sz += s * (z - sz)
        }

        // Jolts and kicks.
        if (abs(m - g0) >= JOLT) {
            lastJoltAt = t
            events = events or Event.JOLT
        }
        if (m - g0 >= KICK) lastKickAt = t

        // Free fall: >= 100 ms near zero g. A kick just before means a hop, not a push off an edge.
        if (m < FREE_FALL) {
            if (!falling) {
                falling = true
                fallReported = false
                fallStart = t
            } else if (!fallReported && t - fallStart >= FREE_FALL_MS) {
                fallReported = true
                val hop = fallStart - lastKickAt <= KICK_WINDOW_MS
                events = events or (if (hop) Event.HOP else Event.FALL)
            }
        } else {
            falling = false
        }

        trackSteps(t, m)

        // Stillness, in 100 ms frames over the last second.
        if (t - frameStart >= FRAME_MS) {
            events = events or closeFrame(t)
            startFrame(t, x, y, z)
        }
        addToFrame(x, y, z, m)
        return events
    }

    private fun trackSteps(t: Long, m: Float) {
        val impact = m - g0
        if (impact >= STEP_IMPACT) {
            if (!inImpact || impact > impactPeak) {
                if (!inImpact) inImpact = true
                impactPeak = impact
                impactPeakAt = t
            }
            return
        }
        if (!inImpact) return
        inImpact = false
        val last = if (stepCount > 0) stepTimes[(stepCount - 1) % stepTimes.size] else Long.MIN_VALUE / 2
        val gap = impactPeakAt - last
        if (gap < STEP_MIN_MS) return // Same impact, or a rattle.
        if (gap > STEP_MAX_MS) stepCount = 0
        stepTimes[stepCount % stepTimes.size] = impactPeakAt
        stepCount++
        if (stepCount < 4) return
        // Evenly spaced: the gaps of the last 4 steps differ by < 25%.
        var minGap = Long.MAX_VALUE
        var maxGap = 0L
        for (i in 1..3) {
            val a = stepTimes[(stepCount - i) % stepTimes.size]
            val b = stepTimes[(stepCount - i - 1) % stepTimes.size]
            val g = a - b
            minGap = min(minGap, g)
            maxGap = max(maxGap, g)
        }
        if (maxGap <= minGap * 1.25f + 1) lastGaitAt = impactPeakAt
    }

    private fun startFrame(t: Long, x: Float, y: Float, z: Float) {
        frameStart = t
        frameN = 0
        frameSx = 0.0; frameSy = 0.0; frameSz = 0.0
        frameSxx = 0.0; frameSyy = 0.0; frameSzz = 0.0
        frameSm = 0.0
        curMinX = x; curMaxX = x
        curMinY = y; curMaxY = y
        curMinZ = z; curMaxZ = z
    }

    private fun addToFrame(x: Float, y: Float, z: Float, m: Float) {
        frameN++
        frameSx += x; frameSy += y; frameSz += z
        frameSxx += x.toDouble() * x; frameSyy += y.toDouble() * y; frameSzz += z.toDouble() * z
        frameSm += m
        curMinX = min(curMinX, x); curMaxX = max(curMaxX, x)
        curMinY = min(curMinY, y); curMaxY = max(curMaxY, y)
        curMinZ = min(curMinZ, z); curMaxZ = max(curMaxZ, z)
    }

    private fun closeFrame(t: Long): Int {
        if (frameN == 0) return Event.FRAME
        val i = frameIndex
        sumN[i] = frameN
        sumX[i] = frameSx; sumY[i] = frameSy; sumZ[i] = frameSz
        sumXX[i] = frameSxx; sumYY[i] = frameSyy; sumZZ[i] = frameSzz
        sumM[i] = frameSm
        minX[i] = curMinX; maxX[i] = curMaxX
        minY[i] = curMinY; maxY[i] = curMaxY
        minZ[i] = curMinZ; maxZ[i] = curMaxZ
        frameIndex = (frameIndex + 1) % REST_FRAMES
        if (frames < REST_FRAMES) frames++

        var events = Event.FRAME
        val still = frames == REST_FRAMES && windowStill()
        if (still && !atRest) {
            atRest = true
            restSince = t
            events = events or Event.REST_START
        } else if (!still && atRest) {
            atRest = false
            events = events or Event.REST_END
        }
        return events
    }

    /** Vector sigma and range over the last second; on success also updates the rest pose and noise. */
    private fun windowStill(): Boolean {
        var n = 0
        var sX = 0.0
        var sY = 0.0
        var sZ = 0.0
        var sXX = 0.0
        var sYY = 0.0
        var sZZ = 0.0
        var sM = 0.0
        var loX = Float.MAX_VALUE
        var hiX = -Float.MAX_VALUE
        var loY = Float.MAX_VALUE
        var hiY = -Float.MAX_VALUE
        var loZ = Float.MAX_VALUE
        var hiZ = -Float.MAX_VALUE
        for (i in 0 until REST_FRAMES) {
            n += sumN[i]
            sX += sumX[i]; sY += sumY[i]; sZ += sumZ[i]
            sXX += sumXX[i]; sYY += sumYY[i]; sZZ += sumZZ[i]
            sM += sumM[i]
            loX = min(loX, minX[i]); hiX = max(hiX, maxX[i])
            loY = min(loY, minY[i]); hiY = max(hiY, maxY[i])
            loZ = min(loZ, minZ[i]); hiZ = max(hiZ, maxZ[i])
        }
        if (n < 3) return false
        val mx = sX / n
        val my = sY / n
        val mz = sZ / n
        val variance = (sXX / n - mx * mx) + (sYY / n - my * my) + (sZZ / n - mz * mz)
        val sigma = sqrt(max(0.0, variance)).toFloat()
        val range = max(hiX - loX, max(hiY - loY, hiZ - loZ))
        // The quietest second so far is the sensor's own noise (learned whether still or not).
        noiseFloor = min(noiseFloor, max(sigma, 0.002f))
        val still = sigma <= restSigma && range <= (6f * noiseFloor).coerceIn(REST_RANGE, REST_RANGE_MAX)
        if (still) {
            windowSamples = n
            restX = mx.toFloat(); restY = my.toFloat(); restZ = mz.toFloat()
            g0 = (sM / n).toFloat().coerceIn(9.0f, 10.6f)
        }
        return still
    }
}
