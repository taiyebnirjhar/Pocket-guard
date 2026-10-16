package com.taiyeb.pocketguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Everything the phone must allow for the guard to work when it matters. */
data class SetupStatus(
    val notifications: Boolean,
    val battery: Boolean,
    val fullScreen: Boolean,
    val secureLock: Boolean,
    val proximity: Boolean,
    val lockCheck: Prefs.LockCheck,
    val doNotDisturb: Boolean,
    val bluetoothPermission: Boolean,
    val activityPermission: Boolean,
) {
    companion object {
        fun read(context: Context): SetupStatus {
            val power = context.getSystemService(PowerManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            return SetupStatus(
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                battery = power.isIgnoringBatteryOptimizations(context.packageName),
                fullScreen = Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent(),
                secureLock = context.getSystemService(KeyguardManager::class.java).isDeviceSecure,
                proximity = context.getSystemService(SensorManager::class.java)
                    .getDefaultSensor(Sensor.TYPE_PROXIMITY) != null,
                lockCheck = Prefs.lockCheck(context),
                doNotDisturb = notifications.isNotificationPolicyAccessGranted,
                bluetoothPermission = Build.VERSION.SDK_INT < 31 ||
                    granted(context, Manifest.permission.BLUETOOTH_CONNECT),
                activityPermission = Build.VERSION.SDK_INT < 29 ||
                    granted(context, Manifest.permission.ACTIVITY_RECOGNITION),
            )
        }

        private fun granted(context: Context, permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

object SetupActions {
    fun notifications(context: Context) {
        open(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    @SuppressLint("BatteryLife")
    fun battery(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
        if (!open(context, direct)) open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun fullScreen(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)))
        }
    }

    /** Lets the siren lift "Total silence" Do Not Disturb while it plays. */
    fun doNotDisturb(context: Context) {
        open(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun secureLock(context: Context) {
        open(context, Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    /** Brands that kill background apps hide the "autostart" switch in their own settings screens. */
    private val autostartScreens = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
    )

    fun autostart(context: Context) {
        for (screen in autostartScreens) {
            if (open(context, Intent().setComponent(screen))) return
        }
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
    }

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
