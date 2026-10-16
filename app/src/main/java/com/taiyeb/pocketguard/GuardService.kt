package com.taiyeb.pocketguard

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
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
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.math.max

/**
 * Watches the sensors while armed and runs the escalation ladder:
 * WAITING_FOR_POCKET -> IN_POCKET -> (pulled out while locked) WARNING -> (no unlock in time) SIREN.
 * Tampering (power menu, airplane mode) jumps straight to SIREN.
 * Unlocking the phone at any point stops the alarm and re-arms automatically.
 */
class GuardService : Service(), SensorEventListener {

    companion object {
        private const val ACTION_ARM = "com.taiyeb.pocketguard.ARM"
        private const val EXTRA_AUTO = "auto"

        /** Proximity must stay covered this long before we treat the phone as pocketed. */
        private const val POCKET_CONFIRM_MS = 1500L
        /** Extra wait before auto-locking a phone pocketed unlocked, so a thumb over the sensor doesn't lock it. */
        private const val AUTO_LOCK_DELAY_MS = 1500L
        /** Proximity must stay uncovered this long, which filters out fabric shifting in the pocket. */
        private const val REMOVAL_CONFIRM_MS = 400L
        /** A removal needs movement within this window (or a jump in light). */
        private const val MOTION_WINDOW_MS = 2000L
        private const val MOTION_SPIKE = 1.5f

        @Volatile
        private var instance: GuardService? = null

        /** [auto] is true when a trigger (earbuds, transit) armed it rather than the user. */
        fun arm(context: Context, auto: Boolean = false) {
            val intent = Intent(context, GuardService::class.java)
                .setAction(ACTION_ARM)
                .putExtra(EXTRA_AUTO, auto)
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

        /** Called by [PowerMenuWatcher] when the power menu opens on the lock screen. */
        fun onPowerMenu() {
            instance?.let { if (Prefs.tamperAlarm(it)) it.sirenNow() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var audioManager: AudioManager
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

    private val isLocked get() = keyguardManager.isKeyguardLocked || !powerManager.isInteractive

    /** Calls (phone or VoIP) cover and uncover the proximity sensor at the ear, which looks like a pocket. */
    private val inCall
        get() = audioManager.mode.let {
            it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION ||
                it == AudioManager.MODE_RINGTONE
        }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> onOwnerUnlocked()
                Intent.ACTION_POWER_DISCONNECTED -> onChargerUnplugged()
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> onAirplaneModeChanged()
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                    )
                    if (device != null && BluetoothReceiver.isListeningDevice(device)) onEarbudsDisconnected()
                }
            }
        }
    }

    private val confirmInPocket = Runnable {
        if (phase == Phase.WAITING_FOR_POCKET && near == true && !inCall) {
            pocketLux = lux
            setPhase(Phase.IN_POCKET)
            alarm.tick()
            if (!isLocked) handler.postDelayed(lockIfStillPocketed, AUTO_LOCK_DELAY_MS)
        }
    }

    /** A phone pocketed unlocked can't tell owner from thief, so lock it (needs the accessibility service). */
    private val lockIfStillPocketed = Runnable {
        if (phase == Phase.IN_POCKET && near == true && !isLocked) PowerMenuWatcher.lockScreen()
    }

    private val checkRemoval = Runnable {
        if (phase != Phase.IN_POCKET || near != false) return@Runnable
        if (!isLocked || inCall || !hasRemovalEvidence()) {
            setPhase(Phase.WAITING_FOR_POCKET)
        } else {
            startWarning()
        }
    }

    private val startSiren = Runnable {
        if (phase == Phase.WARNING) sirenNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorManager = getSystemService(SensorManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
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
        val wasMonitoring = monitoring
        arm()
        if (!wasMonitoring && intent?.getBooleanExtra(EXTRA_AUTO, false) == true) alarm.notice()
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
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
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
            unregisterReceiver(eventReceiver)
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
        handler.removeCallbacks(lockIfStillPocketed)
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

    private val alarmActive get() = phase == Phase.WARNING || phase == Phase.SIREN

    private fun startWarning() {
        if (!monitoring || alarmActive) return
        handler.removeCallbacks(lockIfStillPocketed)
        setPhase(Phase.WARNING)
        alarm.startWarning()
        handler.postDelayed(startSiren, Prefs.graceSeconds(this) * 1000L)
    }

    private fun sirenNow() {
        if (!monitoring || phase == Phase.SIREN) return
        handler.removeCallbacks(startSiren)
        handler.removeCallbacks(lockIfStillPocketed)
        setPhase(Phase.SIREN)
        alarm.startSiren(quiet = Prefs.testMode(this))
        Notifications.showAlarm(this)
    }

    private fun onOwnerUnlocked() {
        if (!monitoring) return
        handler.removeCallbacks(startSiren)
        handler.removeCallbacks(checkRemoval)
        handler.removeCallbacks(confirmInPocket)
        handler.removeCallbacks(lockIfStillPocketed)
        alarm.stop()
        Notifications.cancelAlarm(this)
        setPhase(Phase.WAITING_FOR_POCKET)
        if (near == true) handler.postDelayed(confirmInPocket, POCKET_CONFIRM_MS)
    }

    /** Someone unplugged the phone while it was locked, e.g. charging at a café or station. */
    private fun onChargerUnplugged() {
        if (Prefs.chargerAlarm(this) && isLocked) startWarning()
    }

    private fun onAirplaneModeChanged() {
        val airplaneOn = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        if (airplaneOn && Prefs.tamperAlarm(this) && keyguardManager.isKeyguardLocked) sirenNow()
    }

    /**
     * Earbuds or watch dropped out of Bluetooth range. In the pocket it's usually just the earbuds
     * going in their case or dying, so only buzz. Out of the pocket and locked, the phone may be
     * moving away from you.
     */
    private fun onEarbudsDisconnected() {
        if (!monitoring || !Prefs.earbudsAlarm(this)) return
        when (phase) {
            Phase.IN_POCKET -> alarm.notice()
            Phase.WAITING_FOR_POCKET -> if (isLocked) startWarning()
            else -> Unit
        }
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
