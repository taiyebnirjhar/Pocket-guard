package com.taiyeb.pocketguard

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * When the guard arms by itself: weekly windows like "weekdays 08:00–18:00", in the phone's own
 * time zone. Plain Kotlin (no Android), so it runs in unit tests.
 *
 * A window belongs to the day it starts on; one that ends at or before its start runs past
 * midnight ("Fri 22:00–02:00" ends on Saturday), and one whose start and end are equal lasts the
 * whole day. Windows that overlap or touch count as one stretch.
 */
object ArmSchedule {
    const val MAX_WINDOWS = 5

    data class Window(val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime) {
        val overnight get() = !end.isAfter(start)
    }

    private data class Span(val start: ZonedDateTime, val end: ZonedDateTime)

    /** Every occurrence starting from the day before [now] to a week after, in time order. */
    private fun spans(windows: List<Window>, now: ZonedDateTime): List<Span> {
        val zone = now.zone
        val today = now.toLocalDate()
        return (-1L..8L).flatMap { offset ->
            val day: LocalDate = today.plusDays(offset)
            windows.filter { day.dayOfWeek in it.days }.map { w ->
                // ZonedDateTime.of moves a time that doesn't exist (clocks forward) to just after the gap.
                val start = ZonedDateTime.of(day, w.start, zone)
                val endDay = if (w.overnight) day.plusDays(1) else day
                Span(start, ZonedDateTime.of(endDay, w.end, zone))
            }
        }.filter { it.end.isAfter(it.start) }.sortedBy { it.start }
    }

    /** When the stretch of windows covering [now] ends, or null when none covers it. */
    fun activeEnd(windows: List<Window>, now: ZonedDateTime): ZonedDateTime? {
        val all = spans(windows, now)
        var end: ZonedDateTime = all.filter { !it.start.isAfter(now) && it.end.isAfter(now) }
            .maxOfOrNull { it.end } ?: return null
        // Joined to whatever starts before (or as) it ends.
        while (true) {
            val longer = all.filter { !it.start.isAfter(end) && it.end.isAfter(end) }.maxOfOrNull { it.end } ?: return end
            end = longer
        }
    }

    /**
     * When the window [now] is in ends, not counting windows joined to it: "Skip today" in an
     * every-day 00:00–00:00 schedule skips today, not the whole week the days chain into.
     */
    fun occurrenceEnd(windows: List<Window>, now: ZonedDateTime): ZonedDateTime? =
        spans(windows, now).filter { !it.start.isAfter(now) && it.end.isAfter(now) }.maxOfOrNull { it.end }

    /** The first time after [now] a stretch begins (not counting the one [now] is in), or null. */
    fun nextStart(windows: List<Window>, now: ZonedDateTime): ZonedDateTime? {
        val coveredUntil = activeEnd(windows, now)
        return spans(windows, now).map { it.start }
            .filter { it.isAfter(now) && (coveredUntil == null || it.isAfter(coveredUntil)) }
            .minOrNull()
    }

    // --- Stored as JSON: [{"days":[1,…,7],"start":"08:00","end":"18:00"}] (ISO day numbers) --------

    fun toJson(windows: List<Window>): String = JSONArray().apply {
        windows.forEach { w ->
            put(
                JSONObject()
                    .put("days", JSONArray(w.days.map { it.value }.sorted()))
                    .put("start", w.start.toString())
                    .put("end", w.end.toString()),
            )
        }
    }.toString()

    fun fromJson(json: String?): List<Window> = try {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val days = o.getJSONArray("days")
            Window(
                (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                LocalTime.parse(o.getString("start")),
                LocalTime.parse(o.getString("end")),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
