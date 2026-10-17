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

/** Uses Google Play services activity recognition to offer arming when you get on a bus, train or car. */
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
        Notifications.showArmPrompt(context, R.string.notify_arm_transit)
    }
}

/**
 * After a restart or app update: re-arm if the guard was on (a thief force-restarting the phone
 * must not switch it off; with an alarm running it resumes) and restore activity recognition.
 * Direct-boot aware, so it runs before the first unlock too.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (Prefs.armed(context) && GuardState.phase.value == Phase.OFF) {
            try {
                GuardService.arm(context, auto = Prefs.autoArmed(context))
            } catch (e: Exception) {
                Notifications.showRearm(context)
            }
        }
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED && Prefs.autoArmTransit(context)) {
            TransitRecognition.enable(context)
        }
    }
}
