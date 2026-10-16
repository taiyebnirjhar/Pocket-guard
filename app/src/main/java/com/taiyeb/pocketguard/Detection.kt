package com.taiyeb.pocketguard

import android.hardware.Sensor
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

object Detection {
    /** Most proximity sensors are binary (0 = covered, max range = uncovered); some report cm. */
    fun isNear(sensor: Sensor, value: Float): Boolean {
        val range = sensor.maximumRange
        val threshold = if (range > 0f) min(range, 5f) else 5f
        return value < threshold
    }

    /** Movement strength in m/s², with gravity removed when the sensor still includes it. */
    fun motion(values: FloatArray, linear: Boolean): Float {
        val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
        return if (linear) magnitude else abs(magnitude - SensorManager.GRAVITY_EARTH)
    }
}
