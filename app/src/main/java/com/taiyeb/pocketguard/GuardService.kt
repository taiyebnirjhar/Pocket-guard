package com.taiyeb.pocketguard

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Watches the sensors while armed and runs the escalation ladder:
 * WAITING_FOR_POCKET -> IN_POCKET -> (pulled out while locked) WARNING -> (no unlock in time) SIREN.
 * Unlocking the phone at any point stops the alarm and re-arms automatically.
 */
class GuardService : Service(), SensorEventListener {

    companion object {
        private const val ACTION_ARM = "com.taiyeb.pocketguard.ARM"

        /** Proximity must stay covered this long before we treat the phone as pocketed. */
        private const val POCKET_CONFIRM_MS = 1500L
        /** Proximity must stay uncovered this long, which filters out fabric shifting in the pocket. */
        private const val REMOVAL_CONFIRM_MS = 400L
        /** A removal needs movement within this window (or a jump in light). */
        private const val MOTION_WINDOW_MS = 2000L
        private const val MOTION_SPIKE = 1.5f

        @Volatile
        private var instance: GuardService? = null

        fun arm(context: Context) {
            val intent = Intent(context, GuardService::class.java).setAction(ACTION_ARM)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disarm(context: Context) {
            val service = instance
            if (service != null) {
                service.disarm()
            } else {
                Prefs.setArmed(context, false)
                GuardState.phase.value = Phase.OFF
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var alarm: AlarmPlayer

    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var motionSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitoring = false

    private var near: Boolean? = null
    private var lux: Float? = null
    private var pocketLux: Float? = null
    private var lastMotionAt = 0L

    private val phase get() = GuardState.phase.value

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onOwnerUnlocked()
    }

    private val confirmInPocket = Runnable {
        if (phase == Phase.WAITING_FOR_POCKET && near == true) {
            pocketLux = lux
            setPhase(Phase.IN_POCKET)
            alarm.tick()
        }
    }

    private val checkRemoval = Runnable {
        if (phase != Phase.IN_POCKET || near != false) return@Runnable
        val ownerIsUsingPhone = powerManager.isInteractive && !keyguardManager.isKeyguardLocked
        if (ownerIsUsingPhone || !hasRemovalEvidence()) {
            setPhase(Phase.WAITING_FOR_POCKET)
        } else {
            startWarning()
        }
    }

    private val startSiren = Runnable {
        if (phase == Phase.WARNING) {
            setPhase(Phase.SIREN)
            alarm.startSiren(quiet = Prefs.testMode(this@GuardService))
            Notifications.showAlarm(this@GuardService)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorManager = getSystemService(SensorManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        alarm = AlarmPlayer(this)
        Notifications.createChannels(this)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val shown = if (phase == Phase.OFF) Phase.WAITING_FOR_POCKET else phase
        ServiceCompat.startForeground(this, Notifications.STATUS_ID, Notifications.status(this, shown), type)
        // Also reached with a null intent when the system restarts a killed service: re-arm.
        arm()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        GuardState.phase.value = Phase.OFF
        refreshTile()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun arm() {
        Prefs.setArmed(this, true)
        if (monitoring) return
        monitoring = true
        near = null
        lastMotionAt = 0L
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketGuard:guard")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        motionSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
        )
        setPhase(Phase.WAITING_FOR_POCKET)
    }

    fun disarm() {
        stopMonitoring()
        Prefs.setArmed(this, false)
        GuardState.phase.value = Phase.OFF
        refreshTile()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        handler.removeCallbacksAndMessages(null)
        alarm.stop()
        Notifications.cancelAlarm(this)
        sensorManager.unregisterListener(this)
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val isNear = Detection.isNear(event.sensor, event.values[0])
                if (isNear != near) {
                    near = isNear
                    onProximityChanged(isNear)
                }
            }
            Sensor.TYPE_LIGHT -> {
                val value = event.values[0]
                lux = value
                if (phase == Phase.IN_POCKET && near == true) {
                    pocketLux = pocketLux?.let { minOf(it, value) } ?: value
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val linear = event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION
                if (Detection.motion(event.values, linear) > MOTION_SPIKE) {
                    lastMotionAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onProximityChanged(isNear: Boolean) {
        handler.removeCallbacks(confirmInPocket)
        handler.removeCallbacks(checkRemoval)
        when (phase) {
            Phase.WAITING_FOR_POCKET -> if (isNear) handler.postDelayed(confirmInPocket, POCKET_CONFIRM_MS)
            Phase.IN_POCKET -> if (!isNear) handler.postDelayed(checkRemoval, REMOVAL_CONFIRM_MS)
            else -> Unit
        }
    }

    /** A real removal moves the phone or exposes it to light; a sensor flicker does neither. */
    private fun hasRemovalEvidence(): Boolean {
        if (motionSensor == null && lightSensor == null) return true
        val moved = motionSensor != null &&
            SystemClock.elapsedRealtime() - lastMotionAt < MOTION_WINDOW_MS
        val dark = pocketLux
        val current = lux
        val brighter = dark != null && current != null && current > max(dark * 3f, dark + 10f)
        return moved || brighter
    }

    private fun startWarning() {
        setPhase(Phase.WARNING)
        alarm.startWarning()
        handler.postDelayed(startSiren, Prefs.graceSeconds(this) * 1000L)
    }

    private fun onOwnerUnlocked() {
        if (!monitoring) return
        handler.removeCallbacks(startSiren)
        handler.removeCallbacks(checkRemoval)
        handler.removeCallbacks(confirmInPocket)
        alarm.stop()
        Notifications.cancelAlarm(this)
        setPhase(Phase.WAITING_FOR_POCKET)
        if (near == true) handler.postDelayed(confirmInPocket, POCKET_CONFIRM_MS)
    }

    private fun setPhase(newPhase: Phase) {
        GuardState.phase.value = newPhase
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.STATUS_ID, Notifications.status(this, newPhase))
        refreshTile()
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, GuardTileService::class.java))
        } catch (e: Exception) {
            // Tile not added or not supported on this device.
        }
    }
}
