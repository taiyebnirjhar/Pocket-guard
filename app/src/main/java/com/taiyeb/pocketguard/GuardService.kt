package com.taiyeb.pocketguard

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
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
import android.os.BatteryManager
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
 * A LifecycleService so the intruder camera can bind to it. Direct-boot aware, so it re-arms
 * (and resumes a running alarm) even before the first unlock after a forced restart.
 * Also tests, at every screen-off, that the phone locks immediately (a setup requirement).
 */
class GuardService : LifecycleService(), SensorEventListener, GuardEngine.Host {

    companion object {
        const val TAG = "PocketGuard"
        private const val ACTION_ARM = "com.taiyeb.pocketguard.ARM"
        private const val EXTRA_AUTO = "auto"
        private const val WAKE_LOCK_MS = 2 * 60 * 60_000L
        private const val WAKE_LOCK_RENEW_MS = 60 * 60_000L
        private const val AUTO_DISARM_MS = 4 * 60 * 60_000L
        private const val CHARGER_DEBOUNCE_MS = 2_000L
        private const val LOCK_CHECK_DELAY_MS = 1_500L

        @Volatile
        private var instance: GuardService? = null

        /** Without a PIN, pattern or password anyone can "unlock", so the guard can't tell owner from thief. */
        fun canArm(context: Context) = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

        /**
         * [auto] is true when a trigger (earbuds, transit, restart) armed it rather than the user.
         * Returns false when the phone has no secure lock screen.
         */
        fun arm(context: Context, auto: Boolean = false): Boolean {
            if (!canArm(context)) return false
            val intent = Intent(context, GuardService::class.java)
                .setAction(ACTION_ARM)
                .putExtra(EXTRA_AUTO, auto)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun disarm(context: Context) {
            val service = instance
            if (service != null) {
                service.disarm()
            } else {
                Prefs.setArmed(context, false)
                Prefs.setAlarmActive(context, false)
                GuardState.phase.value = Phase.OFF
            }
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
    private var lastPlugType = 0

    private val renewWakeLock = object : Runnable {
        override fun run() {
            wakeLock?.acquire(WAKE_LOCK_MS)
            handler.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    /** Auto-armed sessions (earbuds, transit) end by themselves so a forgotten guard can't fire at night. */
    private val autoDisarm = object : Runnable {
        override fun run() {
            if (engine?.phase?.isAlarm == true) {
                handler.postDelayed(this, 10 * 60_000L)
                return
            }
            Notifications.showInfo(this@GuardService, R.string.notify_auto_off)
            disarm()
        }
    }

    /**
     * An alarm that began before the phone locked (screen-lock delay) can't trust an unlock yet. As
     * soon as the phone is properly locked, a later unlock does prove the owner again.
     */
    private val lockWatch = object : Runnable {
        override fun run() {
            if (engine?.phase?.isAlarm != true) return
            if (keyguardManager.isDeviceLocked) {
                GuardState.unlockStopsAlarm.value = true
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Backup for USER_PRESENT, which some devices (and the emulator) occasionally don't deliver:
     * PIN-locked when the alarm began, now unlocked with the screen on, so the owner is back.
     */
    private val unlockPoll = object : Runnable {
        override fun run() {
            val current = engine ?: return
            if (!current.phase.isAlarm) return
            val unlocked = powerManager.isInteractive && !keyguardManager.isKeyguardLocked
            if (unlocked && GuardState.unlockStopsAlarm.value) {
                Log.i(TAG, "unlock detected without USER_PRESENT")
                onUnlocked(current)
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Screen just went off: is the phone locked now, or does the lock-delay setting leave it open? */
    private val lockCheck = Runnable {
        if (engine == null || powerManager.isInteractive) return@Runnable
        val locked = keyguardManager.isDeviceLocked
        Log.i(TAG, "lock check: locked=$locked")
        Prefs.setLockCheck(this, if (locked) Prefs.LockCheck.PASSED else Prefs.LockCheck.FAILED)
        if (locked) Notifications.cancelLockWarning(this) else Notifications.showLockWarning(this)
    }

    private val chargerCheck = Runnable {
        // Still unplugged after the debounce: a wobble on a dock or car mount reconnects within it.
        if (plugType() == 0) engine?.onChargerUnplugged()
    }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val engine = engine ?: return
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "user present")
                    onUnlocked(engine)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(lockCheck)
                    handler.postDelayed(lockCheck, LOCK_CHECK_DELAY_MS)
                }
                Intent.ACTION_POWER_CONNECTED -> handler.postDelayed({ lastPlugType = plugType() }, 1000)
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Lifting a phone off a wireless pad is the owner picking it up, not a theft.
                    if (lastPlugType != BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                        handler.removeCallbacks(chargerCheck)
                        handler.postDelayed(chargerCheck, CHARGER_DEBOUNCE_MS)
                    }
                    lastPlugType = 0
                }
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
        if (!goForeground()) {
            // Android refused (e.g. a background restart without the battery exemption): ask the user.
            Notifications.showRearm(this)
            stopMonitoring()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canArm(this)) {
            Notifications.showInfo(this, R.string.notify_need_lock)
            disarm()
            return START_NOT_STICKY
        }
        // Also reached with a null intent when the system restarts a killed service: re-arm.
        val wasArmed = engine != null
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        startMonitoring()
        if (!wasArmed) Prefs.setAutoArmed(this, auto)
        if (!wasArmed && auto) {
            alarm.notice()
            handler.postDelayed(autoDisarm, AUTO_DISARM_MS)
        } else if (!auto) {
            Prefs.setAutoArmed(this, false)
            handler.removeCallbacks(autoDisarm) // The user armed it: keep it on until they disarm.
        }
        return START_STICKY
    }

    /**
     * The camera type lets the service take intruder photos while the screen is off. Android only
     * grants it with the permission held and, on 14+, when started from the foreground (never from
     * boot on 15+), so fall back to the plain type when refused.
     */
    private fun goForeground(): Boolean {
        val shown = if (GuardState.phase.value == Phase.OFF) Phase.WAITING_FOR_POCKET else GuardState.phase.value
        val notification = Notifications.status(this, shown)
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        var withCamera = base
        if (Build.VERSION.SDK_INT >= 30 && granted(Manifest.permission.CAMERA)) {
            withCamera = withCamera or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        for (types in listOf(withCamera, base).distinct()) {
            try {
                ServiceCompat.startForeground(this, Notifications.STATUS_ID, notification, types)
                return true
            } catch (e: Exception) {
                // Android 15 refuses the camera type from boot: drop to the plain type.
                val notAllowed = Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException
                Log.w(TAG, "Foreground service types $types refused (notAllowed=$notAllowed)", e)
                if (types == base) return false
            }
        }
        return false
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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketGuard:guard").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
        handler.postDelayed(renewWakeLock, WAKE_LOCK_RENEW_MS)
        lastPlugType = plugType()
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        // ~60 ms: the jolt of a phone pulled from a pocket lasts only a few hundred ms (NORMAL is 200 ms).
        motionSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Must be EXPORTED: with NOT_EXPORTED, Android 14 doesn't deliver USER_PRESENT (found by the e2e test),
        // so unlocking would never stop the siren. These actions are protected system broadcasts anyway.
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        newEngine.arm()
        // The phone restarted or the app was killed mid-alarm: carry on screaming.
        if (Prefs.alarmActive(this)) newEngine.resumeAlarm()
    }

    fun disarm() {
        engine?.disarm()
        stopMonitoring()
        Prefs.setArmed(this, false)
        Prefs.setAlarmActive(this, false)
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
        handler.removeCallbacks(renewWakeLock)
        handler.removeCallbacks(autoDisarm)
        handler.removeCallbacks(chargerCheck)
        handler.removeCallbacks(lockCheck)
        handler.removeCallbacks(lockWatch)
        handler.removeCallbacks(unlockPoll)
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

    /** USER_PRESENT means the owner only if the phone was PIN-locked when the alarm began. */
    private fun onUnlocked(engine: GuardEngine) {
        if (engine.phase.isAlarm && !GuardState.unlockStopsAlarm.value) {
            Log.i(TAG, "Unlock ignored: alarm began while the phone wasn't PIN-locked")
            return
        }
        engine.onOwnerUnlocked()
    }

    private fun plugType(): Int {
        // Null receiver: just reads the sticky battery broadcast (a system broadcast, no flag needed).
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
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

    /** Calls at the ear cover and uncover the proximity sensor, which looks like a pocket. */
    override fun inCall() = Audio.callAtEar(audioManager)

    override fun config() = GuardEngine.Config(
        graceMs = Prefs.graceSeconds(this) * 1000L,
        airplaneAlarm = Prefs.airplaneAlarm(this),
        chargerAlarm = Prefs.chargerAlarm(this),
        earbudsAlarm = Prefs.earbudsAlarm(this),
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
        if (to.isAlarm && !from.isAlarm) {
            GuardState.unlockStopsAlarm.value = keyguardManager.isDeviceLocked
            Log.i(TAG, "alarm started, unlock stops it: ${GuardState.unlockStopsAlarm.value}")
            Prefs.setAlarmActive(this, true) // A restart during the warning comes back screaming too.
            if (!GuardState.unlockStopsAlarm.value) handler.post(lockWatch)
            handler.removeCallbacks(unlockPoll)
            handler.postDelayed(unlockPoll, 1000)
        }
        GuardState.phase.value = to
        when (to) {
            Phase.WARNING -> {
                alarm.startWarning()
                trigger?.let(recorder::begin)
            }
            Phase.SIREN -> {
                Prefs.setAlarmActive(this, true)
                alarm.startSiren(quiet = Prefs.testMode(this))
                Notifications.showAlarm(this)
                trigger?.let(recorder::escalate)
            }
            Phase.OFF, Phase.WAITING_FOR_POCKET, Phase.IN_POCKET -> if (from.isAlarm) {
                Prefs.setAlarmActive(this, false)
                alarm.stop()
                Notifications.cancelAlarm(this)
                // Warning to IN_POCKET only happens when the pocket fabric flickered over the sensor.
                val flicker = from == Phase.WARNING && to == Phase.IN_POCKET
                recorder.end(if (flicker) IncidentRecorder.Outcome.FLICKER else IncidentRecorder.Outcome.OWNER_STOPPED)
                GuardState.unlockStopsAlarm.value = true
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

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, GuardTileService::class.java))
        } catch (e: Exception) {
            // Tile not added or not supported on this device.
        }
    }
}
