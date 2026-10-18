package com.taiyeb.pocketguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ArmScheduleTest {

    private val dhaka = ZoneId.of("Asia/Dhaka")
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val work = ArmSchedule.Window(weekdays, LocalTime.of(8, 0), LocalTime.of(18, 0))

    // 2026-10-19 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0, zone: ZoneId = dhaka) =
        ZonedDateTime.of(LocalDateTime.of(2026, 10, day, hour, minute), zone)

    @Test
    fun insideAWindowItEndsAtItsEnd() {
        assertEquals(at(19, 18), ArmSchedule.activeEnd(listOf(work), at(19, 12)))
    }

    @Test
    fun theStartIsInsideAndTheEndIsNot() {
        assertEquals(at(19, 18), ArmSchedule.activeEnd(listOf(work), at(19, 8)))
        assertNull(ArmSchedule.activeEnd(listOf(work), at(19, 18)))
    }

    @Test
    fun outsideTheNextStartIsTheNextWeekday() {
        assertNull(ArmSchedule.activeEnd(listOf(work), at(19, 20)))
        assertEquals(at(20, 8), ArmSchedule.nextStart(listOf(work), at(19, 20)))
        // Friday evening: next is Monday.
        assertEquals(at(26, 8), ArmSchedule.nextStart(listOf(work), at(23, 19)))
    }

    @Test
    fun anOvernightWindowRunsPastMidnight() {
        val night = ArmSchedule.Window(setOf(FRIDAY), LocalTime.of(22, 0), LocalTime.of(2, 0))
        assertEquals(at(24, 2), ArmSchedule.activeEnd(listOf(night), at(23, 23)))
        // Saturday 01:00 is still Friday's window.
        assertEquals(at(24, 2), ArmSchedule.activeEnd(listOf(night), at(24, 1)))
        assertNull(ArmSchedule.activeEnd(listOf(night), at(24, 3)))
    }

    @Test
    fun equalStartAndEndIsAWholeDay() {
        val sunday = ArmSchedule.Window(setOf(SUNDAY), LocalTime.of(0, 0), LocalTime.of(0, 0))
        assertEquals(at(26, 0), ArmSchedule.activeEnd(listOf(sunday), at(25, 13)))
    }

    @Test
    fun overlappingAndTouchingWindowsAreOneStretch() {
        val morning = ArmSchedule.Window(setOf(MONDAY), LocalTime.of(8, 0), LocalTime.of(12, 0))
        val noon = ArmSchedule.Window(setOf(MONDAY), LocalTime.of(12, 0), LocalTime.of(14, 0))
        val late = ArmSchedule.Window(setOf(MONDAY), LocalTime.of(13, 0), LocalTime.of(17, 0))
        val windows = listOf(morning, noon, late)
        assertEquals(at(19, 17), ArmSchedule.activeEnd(windows, at(19, 9)))
        // The next start isn't a window that only continues the current stretch.
        assertEquals(at(26, 8), ArmSchedule.nextStart(windows, at(19, 9)))
    }

    @Test
    fun weekendOnlyScheduleSkipsTheWeek() {
        val weekend = ArmSchedule.Window(setOf(SATURDAY, SUNDAY), LocalTime.of(10, 0), LocalTime.of(20, 0))
        assertEquals(at(24, 10), ArmSchedule.nextStart(listOf(weekend), at(19, 9)))
    }

    @Test
    fun noWindowsMeansNeverArmed() {
        assertNull(ArmSchedule.activeEnd(emptyList(), at(19, 12)))
        assertNull(ArmSchedule.nextStart(emptyList(), at(19, 12)))
        val noDays = ArmSchedule.Window(emptySet(), LocalTime.of(8, 0), LocalTime.of(18, 0))
        assertNull(ArmSchedule.nextStart(listOf(noDays), at(19, 12)))
    }

    @Test
    fun aStartInsideTheSpringForwardGapMovesPastIt() {
        // Berlin, 2027-03-28: clocks go from 02:00 to 03:00, so a 02:30 start happens at 03:30.
        val berlin = ZoneId.of("Europe/Berlin")
        val early = ArmSchedule.Window(setOf(SUNDAY), LocalTime.of(2, 30), LocalTime.of(6, 0))
        val saturdayNight = ZonedDateTime.of(LocalDateTime.of(2027, 3, 27, 23, 0), berlin)
        val start = ArmSchedule.nextStart(listOf(early), saturdayNight)!!
        assertEquals(LocalDateTime.of(2027, 3, 28, 3, 30), start.toLocalDateTime())
    }
}
