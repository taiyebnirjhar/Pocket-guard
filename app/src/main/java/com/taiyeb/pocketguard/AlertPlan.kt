package com.taiyeb.pocketguard

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * When the SOS alerts to emergency contacts go out, in plain Kotlin so it runs in unit tests.
 * The first alert waits [COUNTDOWN_MS] (the alarm and recording start at once): Google Play
 * requires that the user can confirm messages sent for them, and the countdown with Cancel also
 * stops a mistaken SOS from reaching anyone. Then: an update when the phone has moved
 * [FOLLOW_UP_METERS], no more often than [FOLLOW_UP_MIN_MS], [MAX_FOLLOW_UPS] times; and "false
 * alarm" if SOS is stopped within [FALSE_ALARM_MS] of the first alert.
 */
class AlertPlan(private val startedAt: Long) {

    companion object {
        const val COUNTDOWN_MS = 5_000L
        const val FALSE_ALARM_MS = 15_000L
        const val FOLLOW_UP_MIN_MS = 5 * 60_000L
        const val FOLLOW_UP_METERS = 200.0
        const val MAX_FOLLOW_UPS = 3

        /** Great-circle distance in metres. */
        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        /** A link any phone opens in a map. */
        fun mapLink(lat: Double, lng: Double) = "https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.ROOT, lat, lng)
    }

    var cancelled = false
        private set

    /** When the first alert went out (null: not yet). */
    var sentAt: Long? = null
        private set

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastAt = 0L
    private var followUps = 0

    /** Whole seconds left before the first alert, for the alarm screen. */
    fun secondsLeft(now: Long) = ((startedAt + COUNTDOWN_MS - now + 999) / 1000).coerceAtLeast(0L).toInt()

    fun countdownOver(now: Long) = now - startedAt >= COUNTDOWN_MS

    fun cancel() {
        if (sentAt == null) cancelled = true
    }

    /** The first alert went out, with the location it carried (null: none known yet). */
    fun onSent(now: Long, lat: Double?, lng: Double?) {
        sentAt = now
        lastAt = now
        lastLat = lat
        lastLng = lng
    }

    /**
     * A new location fix. True when it should go out as an update: the first known location after
     * an alert that had none, or a move far enough, not too often, and not too many times.
     */
    fun shouldUpdate(now: Long, lat: Double, lng: Double): Boolean {
        if (sentAt == null || cancelled || followUps >= MAX_FOLLOW_UPS) return false
        val fromLat = lastLat
        val fromLng = lastLng
        val due = if (fromLat == null || fromLng == null) {
            true
        } else {
            now - lastAt >= FOLLOW_UP_MIN_MS && distanceMeters(fromLat, fromLng, lat, lng) >= FOLLOW_UP_METERS
        }
        if (!due) return false
        followUps++
        lastAt = now
        lastLat = lat
        lastLng = lng
        return true
    }

    /** SOS stopped: true when contacts were alerted moments ago and should hear it was a mistake. */
    fun falseAlarmOnStop(now: Long): Boolean {
        val sent = sentAt ?: return false
        return now - sent <= FALSE_ALARM_MS
    }
}
