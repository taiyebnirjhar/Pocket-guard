package com.taiyeb.pocketguard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/** Uses Google Play services activity recognition to arm when you get on a bus, train or car. */
object TransitRecognition {

    @SuppressLint("MissingPermission") // Checked by the caller; SecurityException is caught.
    fun enable(context: Context) {
        val request = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            ),
        )
        try {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(request, pendingIntent(context))
        } catch (e: SecurityException) {
            // Physical activity permission not granted.
        }
    }

    @SuppressLint("MissingPermission")
    fun disable(context: Context) {
        try {
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context))
        } catch (e: SecurityException) {
            // Nothing registered.
        }
    }

    /** Play services adds the result as extras, so the intent must be mutable. */
    private fun pendingIntent(context: Context): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context, 0, Intent(context, TransitReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }
}

class TransitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val boarded = result.transitionEvents.any {
            it.activityType == DetectedActivity.IN_VEHICLE &&
                it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        }
        if (!boarded || !Prefs.autoArmTransit(context) || GuardState.phase.value != Phase.OFF) return
        try {
            GuardService.arm(context, auto = true)
        } catch (e: Exception) {
            // Background start refused (battery optimization still on).
        }
    }
}

/** Activity recognition registrations are lost on reboot and app update; restore them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.autoArmTransit(context)) TransitRecognition.enable(context)
    }
}
