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
    private const val STATUS_CHANNEL = "guard_status"
    private const val ALARM_CHANNEL = "guard_alarm"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Guard status", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Theft alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // The app plays its own siren.
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
        )
    }

    fun status(context: Context, phase: Phase): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(phase.title)
            .setContentText(phase.hint)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Full-screen notification is the only reliable way to show the alarm screen over the lock screen. */
    fun showAlarm(context: Context) {
        val intent = Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Theft alarm")
            .setContentText("Unlock the phone to stop the siren")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ALARM_ID, notification)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Background activity starts are often blocked; the full-screen intent covers it.
        }
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
    }
}
