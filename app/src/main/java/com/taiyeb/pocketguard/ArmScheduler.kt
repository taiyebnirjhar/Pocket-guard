package com.taiyeb.pocketguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Arms and disarms the guard on the owner's schedule ([ArmSchedule]), like a Focus mode schedule:
 * on at the start of a window, off at its end. Turning it off by hand inside a window pauses it
 * for [PAUSE_MS] and then it arms again; "Skip today" keeps it off until the window ends.
 *
 * Nothing runs in between: an alarm wakes [ScheduleReceiver] at the next start, end or pause end,
 * and [apply] puts the guard where it should be. Exact when "Alarms & reminders" is allowed (the
 * GitHub build asks), otherwise within a few minutes. Alarms don't survive a restart or a clock
 * change, so those plan again too.
 */
object ArmScheduler {
    const val PAUSE_MS = 15 * 60_000L

    /** An alarm going off at a window's end while it rings: try again this much later. */
    private const val BUSY_RETRY_MS = 10 * 60_000L

    const val ACTION_CHECK = "com.taiyeb.pocketguard.SCHEDULE_CHECK"
    const val ACTION_ARM_NOW = "com.taiyeb.pocketguard.SCHEDULE_ARM_NOW"
    const val ACTION_PAUSE_MORE = "com.taiyeb.pocketguard.SCHEDULE_PAUSE_MORE"
    const val ACTION_SKIP = "com.taiyeb.pocketguard.SCHEDULE_SKIP"

    /** What Home shows: armed until, paused until, or the next start (epoch ms; null when not). */
    data class Status(val armedUntil: Long?, val pausedUntil: Long?, val nextStart: Long?)

    fun windows(context: Context) = ArmSchedule.fromJson(Prefs.scheduleJson(context))

    fun enabled(context: Context) = Prefs.scheduleOn(context) && windows(context).isNotEmpty()

    private fun now() = ZonedDateTime.now(ZoneId.systemDefault())
    private fun ZonedDateTime.ms() = toInstant().toEpochMilli()

    fun status(context: Context): Status? {
        if (!enabled(context)) return null
        val now = now()
        val windows = windows(context)
        val end = ArmSchedule.activeEnd(windows, now)?.ms()
        val paused = Prefs.schedulePausedUntil(context).takeIf { end != null && it > now.ms() }
        return Status(
            armedUntil = end?.takeIf { paused == null },
            pausedUntil = paused?.let { minOf(it, end!!) },
            nextStart = ArmSchedule.nextStart(windows, now)?.ms(),
        )
    }

    /**
     * Puts the guard where the schedule wants it and sets the next check. [mayArm] is false right
     * after the owner armed it (the service is still starting, so "off" would be stale).
     */
    fun apply(context: Context, mayArm: Boolean = true) {
        try {
            plan(context, mayArm)
        } finally {
            GuardState.scheduleVersion.value++
        }
    }

    private fun plan(context: Context, mayArm: Boolean) {
        if (!enabled(context)) {
            cancelAlarm(context)
            Prefs.setSchedulePausedUntil(context, 0)
            Prefs.setScheduleArmed(context, false)
            Notifications.cancelSchedule(context)
            return
        }
        val now = now()
        val nowMs = now.ms()
        val windows = windows(context)
        val end = ArmSchedule.activeEnd(windows, now)?.ms()
        var paused = Prefs.schedulePausedUntil(context)
        if (paused != 0L && (paused <= nowMs || end == null)) {
            paused = 0
            Prefs.setSchedulePausedUntil(context, 0)
        }

        if (end != null) {
            if (paused == 0L) {
                Notifications.cancelSchedule(context)
                if (mayArm && !Prefs.armed(context)) armBySchedule(context, end)
                setAlarm(context, end)
            } else {
                Notifications.showSchedulePaused(context, minOf(paused, end))
                setAlarm(context, minOf(paused, end))
            }
            return
        }

        // Outside every window.
        Notifications.cancelSchedule(context)
        if (Prefs.scheduleArmed(context) && Prefs.armed(context)) {
            if (Prefs.alarmActive(context)) {
                // Never end a running alarm: look again a little later.
                setAlarm(context, nowMs + BUSY_RETRY_MS)
                return
            }
            GuardService.disarm(context, byUser = false)
            Notifications.showInfo(context, R.string.notify_schedule_off)
        }
        Prefs.setScheduleArmed(context, false)
        val next = ArmSchedule.nextStart(windows, now)
        if (next != null) setAlarm(context, next.ms()) else cancelAlarm(context)
    }

    private fun armBySchedule(context: Context, until: Long) {
        val armed = try {
            GuardService.arm(context, byUser = false)
        } catch (e: Exception) {
            // Android refused to start it from the background (no battery exemption, no exact alarm).
            Log.w(GuardService.TAG, "Schedule couldn't arm the guard", e)
            Notifications.showArmPrompt(context, R.string.notify_schedule_tap)
            return
        }
        // No screen lock: nothing to arm (Home already asks for one), so say nothing on every check.
        if (armed) {
            Prefs.setScheduleArmed(context, true)
            Notifications.showScheduleArmed(context, until)
        }
    }

    // --- The owner's actions ------------------------------------------------------------------------

    /** Turned off by hand (Home, tile): inside a window that's a pause, not the end of the schedule. */
    fun onUserDisarmed(context: Context) {
        if (!enabled(context)) return
        if (ArmSchedule.activeEnd(windows(context), now()) != null) {
            Prefs.setSchedulePausedUntil(context, System.currentTimeMillis() + PAUSE_MS)
        }
        apply(context)
    }

    /** Armed by hand: any pause is over; inside a window the schedule turns it off at the end. */
    fun onUserArmed(context: Context) {
        if (!enabled(context)) return
        Prefs.setSchedulePausedUntil(context, 0)
        Prefs.setScheduleArmed(context, ArmSchedule.activeEnd(windows(context), now()) != null)
        apply(context, mayArm = false)
    }

    fun armNow(context: Context) {
        Prefs.setSchedulePausedUntil(context, 0)
        apply(context)
    }

    /** Another [PAUSE_MS], counted from the later of now and the current pause end. */
    fun pauseMore(context: Context) {
        val from = maxOf(System.currentTimeMillis(), Prefs.schedulePausedUntil(context))
        if (Prefs.armed(context)) GuardService.disarm(context, byUser = false)
        Prefs.setSchedulePausedUntil(context, from + PAUSE_MS)
        apply(context)
    }

    /** Off for the rest of the current window. */
    fun skipToday(context: Context) {
        val end = ArmSchedule.activeEnd(windows(context), now()) ?: return apply(context)
        if (Prefs.armed(context)) GuardService.disarm(context, byUser = false)
        Prefs.setSchedulePausedUntil(context, end.ms())
        apply(context)
    }

    // --- The alarm ----------------------------------------------------------------------------------

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 30, Intent(context, ScheduleActionReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Exact (and allowed to start the guard from the background) when the phone permits it. */
    fun exact(context: Context) =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setAlarm(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        try {
            if (exact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            }
            Log.i(GuardService.TAG, "Schedule: next check ${Instant.ofEpochMilli(at)} (exact=${exact(context)})")
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }
}

/**
 * The schedule's own alarm and notification buttons. Not exported: no other app can reach it, so
 * none can pause or skip the guard. Direct-boot aware, like the guard.
 */
class ScheduleActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ArmScheduler.ACTION_ARM_NOW -> ArmScheduler.armNow(context)
            ArmScheduler.ACTION_PAUSE_MORE -> ArmScheduler.pauseMore(context)
            ArmScheduler.ACTION_SKIP -> ArmScheduler.skipToday(context)
            else -> ArmScheduler.apply(context)
        }
    }
}

/**
 * The clock or time zone changed (an alarm set for 08:00 must follow a move to another zone), or
 * exact alarms were allowed. Exported to receive those system broadcasts, so it only ever plans
 * again from the saved schedule, whatever it's sent: harmless if another app calls it.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ArmScheduler.apply(context)
    }
}
