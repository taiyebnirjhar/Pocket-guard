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
import android.hardware.display.DisplayManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
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
        private const val ACCEL_PERIOD_US = 20_000
        private const val ACCEL_LATENCY_US = 100_000
        private const val ACCEL_HEALTH_MS = 10_000L

        /** Warnings where the owner is likely looking at the phone start this much later. */
        private const val OWNER_SILENT_MS = 1500L

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

        /** The SOS setting, or the key watcher's state, changed: apply it to a running guard. */
        fun refreshSos() {
            instance?.updateSos()
        }

        /** Both volume keys held [SosHold.HOLD_MS] (from [source]). Main thread. */
        fun onSosHold(source: String) {
            instance?.sosHold(source)
        }

        /**
         * Permissions were just granted in the app (so it is in the foreground, where Android lets
         * a service add the camera and microphone): re-declare the service's types.
         */
        fun refreshForeground() {
            instance?.let { if (it.engine != null) it.goForeground() }
        }

        fun disarm(context: Context) {
            val service = instance
            if (service != null) {
                service.disarm()
            } else {
                Prefs.setArmed(context, false)
                Prefs.setAlarmActive(context, false)
                GuardState.phase.value = Phase.OFF
                GuardState.armedAt.value = null
            }
        }

    }

    private val handler = Handler(Looper.getMainLooper())
    private val timers = mutableMapOf<GuardEngine.Timer, Runnable>()
    private lateinit var sensorManager: SensorManager
    private lateinit var displayManager: DisplayManager
    private var lastDisplayState = Display.STATE_UNKNOWN
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
            // Picked up off the table and answered: in vibrate mode no ring told us it was a call.
            // A call already on when the alarm began (on earbuds, say) says nothing about who took it.
            if (callModeNow() && !callAtAlarmStart) {
                current.onCallAnswered()
                if (!current.phase.isAlarm) return
            }
            handler.postDelayed(this, 500)
        }
    }

    /** Screen just went off: is the phone locked now, or does the lock-delay setting leave it open? */
    private val lockCheck = Runnable {
        if (engine == null || powerManager.isInteractive) return@Runnable
        val locked = keyguardManager.isDeviceLocked
        Log.i(TAG, "lock check: locked=$locked")
        log(if (locked) "screen off and locked" else "screen off but NOT locked (lock delay or Smart Lock): the guard treats it as the owner's")
        val previous = Prefs.lockCheck(this)
        when {
            !locked -> {
                Prefs.setLockCheck(this, Prefs.LockCheck.FAILED)
                if (previous != Prefs.LockCheck.FAILED) Notifications.showLockWarning(this)
            }
            // The power button locks at once even with a lock delay; only a screen timeout shows the
            // gap. So a pass never clears a failure: tapping Fix starts the test over.
            previous != Prefs.LockCheck.FAILED -> Prefs.setLockCheck(this, Prefs.LockCheck.PASSED)
        }
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
                    log("unlocked")
                    onUnlocked(engine)
                }
                Intent.ACTION_SCREEN_ON -> log("screen on")
                Intent.ACTION_SCREEN_OFF -> {
                    log("screen off")
                    engine.onScreenOff()
                    handler.removeCallbacks(lockCheck)
                    handler.postDelayed(lockCheck, LOCK_CHECK_DELAY_MS)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    engine.onPowerEvent()
                    handler.postDelayed({ lastPlugType = plugType() }, 1000)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    engine.onPowerEvent()
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
        displayManager = getSystemService(DisplayManager::class.java)
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
        // The raw accelerometer: gravity's direction is what tells a table from a pocket, and unlike
        // fused linear acceleration it doesn't keep the gyroscope running.
        motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
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
        // SOS records sound with its video; the microphone, like the camera, must be declared now.
        var withMicrophone = withCamera
        if (Build.VERSION.SDK_INT >= 30 && Prefs.sosButtons(this) && granted(Manifest.permission.RECORD_AUDIO)) {
            withMicrophone = withMicrophone or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        for (types in listOf(withMicrophone, withCamera, base).distinct()) {
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
        GuardState.armedAt.value = SystemClock.elapsedRealtime()
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketGuard:guard").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
        handler.postDelayed(renewWakeLock, WAKE_LOCK_RENEW_MS)
        lastPlugType = plugType()
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        // 50 Hz, delivered in batches of up to 100 ms: a pull's jolt lasts a few hundred ms.
        motionSensor?.let { sensorManager.registerListener(this, it, ACCEL_PERIOD_US, ACCEL_LATENCY_US) }
        displayManager.registerDisplayListener(displayListener, handler)
        lastDisplayState = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Must be EXPORTED: with NOT_EXPORTED, Android 14 doesn't deliver USER_PRESENT (found by the e2e test),
        // so unlocking would never stop the siren. These actions are protected system broadcasts anyway.
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        log(
            "armed: proximity " + (proximitySensor?.let { if (it.isWakeUpSensor) "yes (works screen-off)" else "yes (may sleep screen-off)" } ?: "MISSING") +
                ", light " + (if (lightSensor != null) "yes" else "no") +
                ", accelerometer " + (if (motionSensor != null) "yes" else "MISSING") +
                ", table guard " + (if (Prefs.tableGuard(this)) "on" else "off") +
                ", delay ${Prefs.graceSeconds(this)} s",
        )
        updateSos()
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) setUpSpeech() }
        accelCount = 0
        lastAccelRate = -1
        handler.removeCallbacks(accelHealth)
        handler.postDelayed(accelHealth, ACCEL_HEALTH_MS)
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
        GuardState.armedAt.value = null
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
        displayManager.unregisterDisplayListener(displayListener)
        handler.removeCallbacks(accelHealth)
        sos?.stop()
        sos = null
        tts?.shutdown()
        tts = null
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
            Sensor.TYPE_ACCELEROMETER -> {
                accelCount++
                engine.onAccel(sampleTime(event), event.values[0], event.values[1], event.values[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- SOS --------------------------------------------------------------------------------------

    private var sos: SosButtons? = null
    private var tts: TextToSpeech? = null
    private var sosPhrase = ""

    /**
     * Starts or stops the SOS fallback to match the setting (armed only). With the key watcher on,
     * it sees the keys themselves and the fallback isn't needed.
     */
    fun updateSos() {
        val want = engine != null && Prefs.sosButtons(this)
        val fallback = want && !SosAccessibilityService.connected
        if (fallback && sos == null) {
            sos = SosButtons(this, ::log) { sosHold("volume steps") }.also { it.start() }
        } else if (!fallback && sos != null) {
            sos?.stop()
            sos = null
        }
        if (want != sosWanted) {
            sosWanted = want
            log(
                when {
                    !want -> "SOS off"
                    SosAccessibilityService.connected -> "SOS on: watching the volume keys"
                    else -> "SOS on: reading the volume steps (key watcher off)"
                },
            )
        }
    }

    private var sosWanted = false

    /** Both volume keys held: the SOS alarm, if it is on and the phone locked. */
    private fun sosHold(source: String) {
        val current = engine ?: return
        if (!Prefs.sosButtons(this)) return
        if (!isLocked()) {
            log("SOS hold ignored: unlocked")
            return
        }
        log("SOS: both volume keys held (${source})")
        alarm.tick()
        current.onSos()
    }

    /** The call for help is spoken on the alarm stream, in Bangla when the app shows Bangla and a voice exists. */
    private fun setUpSpeech() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        val bangla = Locale.forLanguageTag("bn-BD")
        val wantsBangla = (Language.current(this) ?: Locale.getDefault().language) == "bn"
        val useBangla = wantsBangla && engine.isLanguageAvailable(bangla) >= TextToSpeech.LANG_AVAILABLE
        engine.language = if (useBangla) bangla else Locale.US
        engine.setSpeechRate(0.9f)
        sosPhrase = if (useBangla) getString(R.string.sos_speech) else "Help! Stop it! Help!"
    }

    private fun speakSos() {
        val engine = tts ?: return
        if (sosPhrase.isEmpty()) return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        engine.speak(sosPhrase, TextToSpeech.QUEUE_FLUSH, params, "sos")
    }

    private var accelCount = 0
    private var lastAccelRate = -1

    /**
     * Every 10 s while armed: is the accelerometer still delivering? Some phones (MIUI/HyperOS
     * battery limits) stop a background app's sensors once the screen is off, and then nothing
     * that needs motion can work. Logged when the rate changes, so the log shows it.
     */
    private val accelHealth = object : Runnable {
        override fun run() {
            if (engine == null || motionSensor == null) return
            val rate = accelCount * 1000 / ACCEL_HEALTH_MS.toInt()
            accelCount = 0
            val bucket = when {
                rate == 0 -> 0
                rate < 20 -> 1
                else -> 2
            }
            val lastBucket = when {
                lastAccelRate < 0 -> -1
                lastAccelRate == 0 -> 0
                lastAccelRate < 20 -> 1
                else -> 2
            }
            if (bucket != lastBucket) {
                val screen = if (powerManager.isInteractive) "screen on" else "screen off"
                log(
                    when (bucket) {
                        0 -> "motion sensor SILENT ($screen): the phone may be restricting the app in the background"
                        1 -> "motion sensor slow: $rate samples/s ($screen)"
                        else -> "motion sensor ok: $rate samples/s ($screen)"
                    },
                )
            }
            lastAccelRate = rate
            handler.postDelayed(this, ACCEL_HEALTH_MS)
        }
    }

    /**
     * When the sample was taken, on the elapsedRealtime clock the engine's timers use. Batched
     * samples arrive late, so arrival time would squash their timing; a few phones stamp sensors
     * on another clock, and then arrival time is the better guess.
     */
    private fun sampleTime(event: SensorEvent): Long {
        val now = SystemClock.elapsedRealtime()
        val stamped = event.timestamp / 1_000_000L
        return if (stamped in now - 5000L..now + 100L) stamped else now
    }

    /** The screen turning on while the phone is out of the pocket: someone may be looking at it. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val state = displayManager.getDisplay(displayId)?.state ?: return
            if (state == Display.STATE_ON && lastDisplayState != Display.STATE_ON) engine?.onDisplayOn()
            lastDisplayState = state
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    // --- GuardEngine.Host ---

    override fun now() = SystemClock.elapsedRealtime()

    override fun isLocked() = keyguardManager.isKeyguardLocked || !powerManager.isInteractive

    private var callAtAlarmStart = false

    private fun callModeNow(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /** Calls at the ear cover and uncover the proximity sensor, which looks like a pocket. */
    override fun inCall() = Audio.callAtEar(audioManager)

    /** Read at most once a second: the engine asks often, and each read opens the prefs. */
    override fun config(): GuardEngine.Config {
        val now = SystemClock.elapsedRealtime()
        cachedConfig?.let { if (now - configReadAt < 1000L) return it }
        configReadAt = now
        return GuardEngine.Config(
            graceMs = Prefs.graceSeconds(this) * 1000L,
            airplaneAlarm = Prefs.airplaneAlarm(this),
            chargerAlarm = Prefs.chargerAlarm(this),
            earbudsAlarm = Prefs.earbudsAlarm(this),
            tableGuard = Prefs.tableGuard(this),
        ).also { cachedConfig = it }
    }

    private var cachedConfig: GuardEngine.Config? = null
    private var configReadAt = 0L

    /** Locked with a PIN, pattern or password: Smart Lock and lock delays don't count. */
    override fun credentialLocked() = keyguardManager.isDeviceLocked

    override fun screenOff() = !powerManager.isInteractive

    override fun ringing(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_RINGTONE || (Build.VERSION.SDK_INT >= 30 && mode == AudioManager.MODE_CALL_SCREENING)
    }

    override fun tableTick() = alarm.tableTick()

    override fun log(message: String) {
        Log.i(TAG, "log: $message")
        DetectionLog.add(message)
    }


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
        val evidence = engine?.evidence
        Log.i(TAG, "phase=$to from=$from trigger=$trigger" + if (to.isAlarm && evidence != null) " evidence=$evidence" else "")
        DetectionLog.add(
            when (to) {
                Phase.WAITING_FOR_POCKET -> "→ waiting for a pocket or table"
                Phase.IN_POCKET -> "→ PROTECTED (in pocket or bag)"
                Phase.ON_TABLE -> "→ PROTECTED ON THE TABLE"
                Phase.WARNING -> "→ WARNING: ${trigger ?: ""} ${evidence ?: ""}".trimEnd()
                Phase.SIREN -> "→ SIREN: ${trigger ?: ""} ${evidence ?: ""}".trimEnd()
                Phase.OFF -> "→ off"
            } + if (from.isAlarm && !to.isAlarm) " (alarm ended)" else "",
        )
        if (to.isAlarm && !from.isAlarm) {
            GuardState.unlockStopsAlarm.value = keyguardManager.isDeviceLocked
            Log.i(TAG, "alarm started, unlock stops it: ${GuardState.unlockStopsAlarm.value}")
            Prefs.setAlarmActive(this, true) // A restart during the warning comes back screaming too.
            if (!GuardState.unlockStopsAlarm.value) handler.post(lockWatch)
            callAtAlarmStart = callModeNow()
            handler.removeCallbacks(unlockPoll)
            handler.postDelayed(unlockPoll, 1000)
        }
        GuardState.phase.value = to
        GuardState.alarmTrigger.value = if (to.isAlarm) trigger else null
        when (to) {
            Phase.WARNING -> {
                // Lifted off a table or turned to look: likely the owner's own glance, so a moment
                // of silence lets them unlock first.
                alarm.startWarning(
                    silentMs = if (evidence?.ownerLikelyLooking == true) OWNER_SILENT_MS else 0,
                    onTable = trigger == Trigger.TABLE_MOVED,
                )
                trigger?.let(recorder::begin)
            }
            Phase.SIREN -> if (trigger == Trigger.SOS) {
                Prefs.setAlarmActive(this, true)
                alarm.startSos(quiet = Prefs.testMode(this)) { speakSos() }
                Notifications.showAlarm(this)
                recorder.escalate(trigger)
            } else {
                Prefs.setAlarmActive(this, true)
                // On the table it may still be put back down; its own buzzing would prevent that, so
                // a table siren buzzes only once the phone can no longer be put back.
                val putBackLeft = (engine?.tablePutBackUntil ?: 0L) - SystemClock.elapsedRealtime()
                alarm.startSiren(
                    quiet = Prefs.testMode(this),
                    vibrateAfterMs = if (trigger == Trigger.TABLE_MOVED) putBackLeft.coerceAtLeast(0L) else 0,
                )
                Notifications.showAlarm(this)
                trigger?.let(recorder::escalate)
            }
            Phase.OFF, Phase.WAITING_FOR_POCKET, Phase.IN_POCKET, Phase.ON_TABLE -> if (from.isAlarm) {
                Prefs.setAlarmActive(this, false)
                alarm.stop()
                Notifications.cancelAlarm(this)
                // Back to IN_POCKET: the pocket fabric flickered. Back to ON_TABLE: put back down.
                val flicker = to == Phase.IN_POCKET || to == Phase.ON_TABLE
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
