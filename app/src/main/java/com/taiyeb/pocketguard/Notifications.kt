package com.taiyeb.pocketguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object Notifications {
    const val STATUS_ID = 1
    private const val ALARM_ID = 2
    private const val INFO_ID = 3
    private const val LOCK_WARNING_ID = 4
    private const val RESTORE_ID = 6
    private const val SCHEDULE_ID = 8
    private const val ARM_PROMPT_ID = 9
    private const val STATUS_CHANNEL = "guard_status"

    // Problems the owner must hear about (the guard stopped, the lock is slow, protection is
    // reduced): an ordinary notification with sound, unlike the silent status.
    private const val PROBLEMS_CHANNEL = "guard_problems"
    private const val ALARM_CHANNEL = "guard_alarm"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // The app plays its own siren.
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(PROBLEMS_CHANNEL, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.deleteNotificationChannel("guard_stealth") // Fake shutdown, removed feature.
    }

    fun status(context: Context, phase: Phase): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(phase.titleRes(!Prefs.theftGuard(context))))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        builder.setContentText(context.getString(phase.hintRes(!Prefs.theftGuard(context))))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // One tap from the lock screen, no unlock needed: the Play-safe way to call for help.
        if (phase != Phase.OFF && !phase.isAlarm) {
            val sos = PendingIntent.getService(
                context, 7, GuardService.sosIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(R.drawable.ic_warning, context.getString(R.string.action_sos), sos)
        }
        return builder.build()
    }

    /**
     * Earbuds connected or a ride detected: ask instead of arming by itself, since arming on a
     * guess is what set off alarms nobody expected. One tap arms it.
     */
    fun showArmPrompt(context: Context, text: Int) {
        val intent = MainActivity.intent(context, arm = true)
        // Its own id: an "Pocket Guard turned off" note posted next must not replace the way back.
        notifyInfo(context, text, PendingIntent.getActivity(
            context, 5, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ), ARM_PROMPT_ID, channel = STATUS_CHANNEL)
    }

    /**
     * After a restart Android lets a service started in the background record and locate only
     * once the app has been opened: until then intruder photos, SOS video and location are off.
     */
    fun showRestore(context: Context) {
        notifyInfo(context, R.string.notify_restore, PendingIntent.getActivity(
            context, 6, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ), RESTORE_ID)
    }

    fun cancelRestore(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RESTORE_ID)
    }

    /** Android stopped the guard (e.g. background restart refused): one tap arms it again. */
    fun showRearm(context: Context) {
        val intent = MainActivity.intent(context, arm = true)
        notifyInfo(context, R.string.notify_rearm, PendingIntent.getActivity(
            context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
    }

    /** The phone didn't lock when the screen went off: a pocketed phone would be open to a thief. */
    fun showLockWarning(context: Context) {
        // Through the app, so the test starts over along with opening the lock settings.
        val fix = MainActivity.intent(context, fixLock = true)
        notifyInfo(
            context,
            R.string.notify_lock_not_instant,
            PendingIntent.getActivity(context, 4, fix, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            LOCK_WARNING_ID,
        )
    }

    fun cancelLockWarning(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(LOCK_WARNING_ID)
    }

    fun showInfo(context: Context, text: Int) {
        notifyInfo(context, text, PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
    }

    private fun notifyInfo(
        context: Context,
        text: Int,
        tap: PendingIntent,
        id: Int = INFO_ID,
        channel: String = PROBLEMS_CHANNEL,
    ) {
        createChannels(context)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun openApp(context: Context, code: Int) = PendingIntent.getActivity(
        context, code, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAction(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, ScheduleActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun time(context: Context, at: Long): String = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))

    /** The schedule armed the guard. */
    fun showScheduleArmed(context: Context, until: Long) {
        createChannels(context)
        val text = context.getString(R.string.notify_schedule_armed, time(context, until))
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter((until - System.currentTimeMillis()).coerceAtLeast(60_000L))
            .setContentIntent(openApp(context, 20))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(SCHEDULE_ID, notification)
    }

    /**
     * Paused inside a window: when it arms again, with Arm now, +15 min and Skip today. The last
     * two keep the guard off, so they ask for the unlock first (not a thief's tap); Android 11 and
     * older can't, so there they're only on Home. [skipped]: off for the rest of the window.
     */
    fun showSchedulePaused(context: Context, until: Long, skipped: Boolean) {
        createChannels(context)
        val text = context.getString(
            if (skipped) R.string.notify_schedule_skipped else R.string.notify_schedule_paused,
            time(context, until),
        )
        fun action(label: Int, action: String, code: Int, unlock: Boolean) =
            NotificationCompat.Action.Builder(0, context.getString(label), scheduleAction(context, action, code))
                .setAuthenticationRequired(unlock)
                .build()
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, 21))
            .addAction(action(R.string.schedule_arm_now, ArmScheduler.ACTION_ARM_NOW, 22, unlock = false))
        if (Build.VERSION.SDK_INT >= 31 && !skipped) {
            builder.addAction(action(R.string.schedule_pause_more, ArmScheduler.ACTION_PAUSE_MORE, 23, unlock = true))
            builder.addAction(action(R.string.schedule_skip, ArmScheduler.ACTION_SKIP, 24, unlock = true))
        }
        val notification = builder.build()
        context.getSystemService(NotificationManager::class.java).notify(SCHEDULE_ID, notification)
    }

    fun cancelSchedule(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(SCHEDULE_ID)
    }

    /** Shows the alarm screen over the lock screen; a full-screen notification is the reliable path. */
    fun showAlarm(context: Context) {
        val activity = AlarmActivity::class.java
        val intent = Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
        val sos = GuardState.alarmTrigger.value == Trigger.SOS
        builder.setContentTitle(context.getString(if (sos) R.string.sos_title else R.string.phase_siren))
            .setContentText(context.getString(if (sos) R.string.alarm_help_sub else R.string.phase_siren_hint))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        context.getSystemService(NotificationManager::class.java).notify(ALARM_ID, builder.build())
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Background start blocked; the full-screen intent covers it.
        }
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
    }
}
