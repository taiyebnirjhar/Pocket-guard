package com.taiyeb.pocketguard

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService

/**
 * Foreground service that runs while armed. Feeds sensors and system events into [GuardEngine],
 * which makes every decision, and carries out the side effects it asks for.
 * A LifecycleService so the intruder camera can bind to it.
 */
class GuardService : LifecycleService(), SensorEventListener, GuardEngine.Host {

    companion object {
        const val TAG = "PocketGuard"
        private const val ACTION_ARM = "com.taiyeb.pocketguard.ARM"
        private const val EXTRA_AUTO = "auto"

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
            instance?.engine?.onPowerMenu()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timers = mutableMapOf<GuardEngine.Timer, Runnable>()
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var audioManager: AudioManager
    private lateinit var alarm: AlarmPlayer
    private lateinit var recorder: IncidentRecorder

    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var motionSensor: Sensor? = null
    private var engine: GuardEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val engine = engine ?: return
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> engine.onOwnerUnlocked()
                Intent.ACTION_POWER_DISCONNECTED -> engine.onChargerUnplugged()
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                    if (on) engine.onAirplaneModeOn()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                    )
                    if (device != null && BluetoothReceiver.counts(context, device)) engine.onEarbudsDisconnected()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorManager = getSystemService(SensorManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        alarm = AlarmPlayer(this)
        recorder = IncidentRecorder(this, this) {
            if (engine?.phase == Phase.SIREN) alarm.startTorch()
        }
        Notifications.createChannels(this)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground()
        // Also reached with a null intent when the system restarts a killed service: re-arm.
        val wasArmed = engine != null
        startMonitoring()
        if (!wasArmed && intent?.getBooleanExtra(EXTRA_AUTO, false) == true) alarm.notice()
        return START_STICKY
    }

    /**
     * Location and camera types let the service fetch a fix and take intruder photos while the
     * screen is off. Android only grants them when the permission is held and, on 14+, when the
     * service starts from the foreground, so fall back to the plain type if refused.
     */
    private fun goForeground() {
        val notification = Notifications.status(this, if (GuardState.phase.value == Phase.OFF) Phase.WAITING_FOR_POCKET else GuardState.phase.value)
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        var types = base
        if (Build.VERSION.SDK_INT >= 30) {
            if (Locator.hasPermission(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(this, Notifications.STATUS_ID, notification, types)
        } catch (e: Exception) {
            if (types == base) throw e
            Log.w(TAG, "Foreground with location/camera refused, using base type", e)
            ServiceCompat.startForeground(this, Notifications.STATUS_ID, notification, base)
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        GuardState.phase.value = Phase.OFF
        refreshTile()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startMonitoring() {
        Prefs.setArmed(this, true)
        if (engine != null) return
        val newEngine = GuardEngine(this, motionSensor != null, lightSensor != null)
        engine = newEngine
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
        newEngine.arm()
    }

    fun disarm() {
        engine?.disarm()
        stopMonitoring()
        Prefs.setArmed(this, false)
        GuardState.phase.value = Phase.OFF
        refreshTile()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopMonitoring() {
        val current = engine ?: return
        if (current.phase != Phase.OFF) current.disarm()
        engine = null
        timers.values.forEach { handler.removeCallbacks(it) }
        timers.clear()
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

    // --- Sensors ---

    override fun onSensorChanged(event: SensorEvent) {
        val engine = engine ?: return
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> engine.onProximity(Detection.isNear(event.sensor, event.values[0]))
            Sensor.TYPE_LIGHT -> engine.onLight(event.values[0])
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> engine.onMotion(
                Detection.motion(event.values, linear = event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION),
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- GuardEngine.Host ---

    override fun now() = SystemClock.elapsedRealtime()

    override fun isLocked() = keyguardManager.isKeyguardLocked || !powerManager.isInteractive

    /** Calls (phone or VoIP) cover and uncover the proximity sensor at the ear, which looks like a pocket. */
    override fun inCall() = audioManager.mode.let {
        it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION || it == AudioManager.MODE_RINGTONE
    }

    override fun config() = GuardEngine.Config(
        graceMs = Prefs.graceSeconds(this) * 1000L,
        tamperAlarm = Prefs.tamperAlarm(this),
        chargerAlarm = Prefs.chargerAlarm(this),
        earbudsAlarm = Prefs.earbudsAlarm(this),
        fakeShutdown = Prefs.fakeShutdown(this),
    )

    override fun schedule(timer: GuardEngine.Timer, delayMs: Long) {
        cancel(timer)
        val runnable = Runnable {
            timers.remove(timer)
            engine?.onTimer(timer)
        }
        timers[timer] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel(timer: GuardEngine.Timer) {
        timers.remove(timer)?.let { handler.removeCallbacks(it) }
    }

    override fun onTransition(from: Phase, to: Phase, trigger: Trigger?) {
        Log.i(TAG, "phase=$to from=$from trigger=$trigger")
        GuardState.phase.value = to
        when (to) {
            Phase.WARNING -> {
                alarm.startWarning()
                trigger?.let(recorder::begin)
            }
            Phase.SIREN -> {
                alarm.startSiren(quiet = Prefs.testMode(this))
                Notifications.showFullScreen(this, AlarmActivity::class.java, stealth = false)
                trigger?.let { recorder.escalate(it, stealth = false) }
            }
            Phase.STEALTH -> {
                alarm.stop()
                Notifications.showFullScreen(this, FakeOffActivity::class.java, stealth = true)
                trigger?.let { recorder.escalate(it, stealth = true) }
            }
            Phase.OFF, Phase.WAITING_FOR_POCKET, Phase.IN_POCKET -> if (from.isAlarm) {
                alarm.stop()
                Notifications.cancelAlarm(this)
                recorder.end(ownerStopped = to != Phase.OFF)
            }
        }
        if (to != Phase.OFF) {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.STATUS_ID, Notifications.status(this, to))
        }
        refreshTile()
    }

    override fun confirmBuzz() = alarm.tick()

    override fun noticeBuzz() = alarm.notice()

    override fun lockScreen() = PowerMenuWatcher.lockScreen()

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, GuardTileService::class.java))
        } catch (e: Exception) {
            // Tile not added or not supported on this device.
        }
    }
}
