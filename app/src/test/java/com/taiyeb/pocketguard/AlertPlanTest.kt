package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPlanTest {

    private val start = 1_000_000L

    @Test
    fun firstAlertWaitsForTheCountdown() {
        val plan = AlertPlan(start)
        assertFalse(plan.countdownOver(start + AlertPlan.COUNTDOWN_MS - 1))
        assertTrue(plan.countdownOver(start + AlertPlan.COUNTDOWN_MS))
        assertEquals(5, plan.secondsLeft(start))
        assertEquals(1, plan.secondsLeft(start + 4_500))
        assertEquals(0, plan.secondsLeft(start + 6_000))
    }

    @Test
    fun cancelBeforeSendingStopsEverything() {
        val plan = AlertPlan(start)
        plan.cancel()
        assertTrue(plan.cancelled)
        assertFalse(plan.shouldUpdate(start + 600_000, 23.8, 90.4))
        assertFalse(plan.falseAlarmOnStop(start + 6_000))
    }

    @Test
    fun cancelAfterSendingDoesNothing() {
        val plan = AlertPlan(start)
        plan.onSent(start + 5_000, 23.8, 90.4)
        plan.cancel()
        assertFalse(plan.cancelled)
    }

    @Test
    fun stoppedSoonAfterTheAlertSendsFalseAlarm() {
        val plan = AlertPlan(start)
        plan.onSent(start + 5_000, 23.8, 90.4)
        assertTrue(plan.falseAlarmOnStop(start + 5_000 + AlertPlan.FALSE_ALARM_MS))
        assertFalse(plan.falseAlarmOnStop(start + 5_000 + AlertPlan.FALSE_ALARM_MS + 1))
    }

    @Test
    fun stoppedBeforeAnyAlertSendsNothing() {
        assertFalse(AlertPlan(start).falseAlarmOnStop(start + 3_000))
    }

    @Test
    fun firstFixAfterAnAlertWithoutLocationGoesOutAtOnce() {
        val plan = AlertPlan(start)
        plan.onSent(start + 5_000, null, null)
        assertTrue(plan.shouldUpdate(start + 9_000, 23.8, 90.4))
    }

    @Test
    fun updatesNeedDistanceAndTimeAndStopAtTheLimit() {
        val plan = AlertPlan(start)
        var t = start + 5_000
        plan.onSent(t, 23.8000, 90.4000)
        // Moved far, but too soon.
        assertFalse(plan.shouldUpdate(t + 60_000, 23.8100, 90.4000))
        // Long enough, but only 50 m.
        assertFalse(plan.shouldUpdate(t + AlertPlan.FOLLOW_UP_MIN_MS, 23.80045, 90.4000))
        var lat = 23.8
        repeat(AlertPlan.MAX_FOLLOW_UPS) {
            t += AlertPlan.FOLLOW_UP_MIN_MS
            lat += 0.01 // About 1.1 km north.
            assertTrue(plan.shouldUpdate(t, lat, 90.4))
        }
        t += AlertPlan.FOLLOW_UP_MIN_MS
        assertFalse(plan.shouldUpdate(t, lat + 0.01, 90.4))
    }

    @Test
    fun distanceIsRightToAFewMetres() {
        // 0.01 degree of latitude is about 1112 m.
        assertEquals(1112.0, AlertPlan.distanceMeters(23.8, 90.4, 23.81, 90.4), 5.0)
        assertEquals(0.0, AlertPlan.distanceMeters(23.8, 90.4, 23.8, 90.4), 0.001)
    }

    @Test
    fun mapLinkUsesDotsWhateverTheLocale() {
        assertEquals("https://maps.google.com/?q=23.810300,90.412500", AlertPlan.mapLink(23.8103, 90.4125))
    }
}
