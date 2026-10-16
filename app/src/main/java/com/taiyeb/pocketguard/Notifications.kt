package com.taiyeb.pocketguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    const val STATUS_ID = 1
    private const val ALARM_ID = 2
    private const val INFO_ID = 3
    private const val STATUS_CHANNEL = "guard_status"
    private const val ALARM_CHANNEL = "guard_alarm"
    private const val STEALTH_CHANNEL = "guard_stealth"

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
            NotificationChannel(
                STEALTH_CHANNEL, context.getString(R.string.channel_stealth), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun status(context: Context, phase: Phase): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(phase.titleRes()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (phase == Phase.STEALTH) {
            // Nothing on the lock screen may hint that the "shut down" phone is still running.
            builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        } else {
            builder.setContentText(context.getString(phase.hintRes()))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        return builder.build()
    }

    /** Android stopped the guard (e.g. background restart refused): one tap arms it again. */
    fun showRearm(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ARM, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        notifyInfo(context, R.string.notify_rearm, PendingIntent.getActivity(
            context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
    }

    fun showInfo(context: Context, text: Int) {
        notifyInfo(context, text, PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
    }

    private fun notifyInfo(context: Context, text: Int, tap: PendingIntent) {
        createChannels(context)
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(INFO_ID, notification)
    }

    /**
     * Shows [activity] over the lock screen. A full-screen notification is the reliable path; the
     * direct start also works when the accessibility service is bound (it exempts the app from
     * Android's background activity start limits).
     */
    fun showFullScreen(context: Context, activity: Class<*>, stealth: Boolean) {
        val intent = Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, if (stealth) STEALTH_CHANNEL else ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
        if (stealth) {
            builder.setContentTitle(context.getString(R.string.phase_stealth))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        } else {
            builder.setContentTitle(context.getString(R.string.phase_siren))
                .setContentText(context.getString(R.string.phase_siren_hint))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
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
