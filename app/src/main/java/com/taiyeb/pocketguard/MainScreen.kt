package com.taiyeb.pocketguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Phase.color
    get() = when (this) {
        Phase.OFF -> Muted
        Phase.WAITING_FOR_POCKET, Phase.WARNING -> Amber
        Phase.IN_POCKET -> Green
        Phase.SIREN, Phase.STEALTH -> Red
    }

private val Phase.emoji
    get() = when (this) {
        Phase.OFF -> "🔓"
        Phase.WAITING_FOR_POCKET -> "👖"
        Phase.IN_POCKET -> "🛡️"
        Phase.WARNING -> "⚠️"
        Phase.SIREN -> "🚨"
        Phase.STEALTH -> "🕶️"
    }

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun MainScreen(refreshKey: Int, onOpenHistory: () -> Unit, onOpenGuide: () -> Unit) {
    val context = LocalContext.current
    val phase by GuardState.phase.collectAsState()
    val setup = remember(refreshKey, phase) { SetupStatus.read(context) }
    // Re-read on resume: test mode switches itself off after an hour.
    var testMode by remember(refreshKey) { mutableStateOf(Prefs.testMode(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(stringResource(R.string.app_name), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.main_history)) }
                TextButton(onClick = onOpenGuide) { Text(stringResource(R.string.main_guide)) }
            }
        }

        StatusCard(phase, testMode)

        val off = phase == Phase.OFF
        Button(
            onClick = {
                if (!off) {
                    GuardService.disarm(context)
                } else if (!GuardService.arm(context)) {
                    Toast.makeText(context, R.string.main_need_lock, Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (off) Green else Red),
        ) {
            Text(
                stringResource(if (off) R.string.main_arm else R.string.main_disarm),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        SettingsSection(testMode) {
            testMode = it
            Prefs.setTestMode(context, it)
        }
        TriggersSection(setup)
        EmergencySection(refreshKey)
        LanguageSection()
        SetupSection(setup)
        SensorCheck()
        Section(stringResource(R.string.test_title)) {
            listOf(
                R.string.test_1, R.string.test_2, R.string.test_3, R.string.test_4, R.string.test_5,
                R.string.test_6, R.string.test_7, R.string.test_8, R.string.test_9,
            ).forEachIndexed { i, step ->
                Text("${i + 1}. ${stringResource(step)}", color = Color.White, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(testMode: Boolean, onTestMode: (Boolean) -> Unit) {
    val context = LocalContext.current
    var grace by remember { mutableIntStateOf(Prefs.graceSeconds(context)) }
    Section(stringResource(R.string.settings_title)) {
        Text(stringResource(R.string.settings_grace, grace), color = Color.White)
        Slider(
            value = grace.toFloat(),
            onValueChange = {
                grace = it.roundToInt()
                Prefs.setGraceSeconds(context, grace)
            },
            valueRange = 1f..15f,
            steps = 13,
        )
        ToggleRow(stringResource(R.string.settings_test), stringResource(R.string.settings_test_sub), testMode, onTestMode)
    }
}

@Composable
private fun TriggersSection(setup: SetupStatus) {
    val context = LocalContext.current
    var tamper by remember { mutableStateOf(Prefs.tamperAlarm(context)) }
    var fakeShutdown by remember { mutableStateOf(Prefs.fakeShutdown(context)) }
    var charger by remember { mutableStateOf(Prefs.chargerAlarm(context)) }
    var earbuds by remember { mutableStateOf(Prefs.earbudsAlarm(context)) }
    var autoEarbuds by remember { mutableStateOf(Prefs.autoArmEarbuds(context)) }
    var autoTransit by remember { mutableStateOf(Prefs.autoArmTransit(context)) }

    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && Prefs.autoArmTransit(context)) TransitRecognition.enable(context)
    }
    val askBluetooth = {
        if (Build.VERSION.SDK_INT >= 31 && !setup.bluetoothPermission) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    Section(stringResource(R.string.triggers_title)) {
        ToggleRow(stringResource(R.string.triggers_tamper), stringResource(R.string.triggers_tamper_sub), tamper) {
            tamper = it
            Prefs.setTamperAlarm(context, it)
        }
        if (tamper) {
            ToggleRow(
                stringResource(R.string.triggers_fake_shutdown),
                stringResource(R.string.triggers_fake_shutdown_sub),
                fakeShutdown,
            ) {
                fakeShutdown = it
                Prefs.setFakeShutdown(context, it)
            }
        }
        ToggleRow(stringResource(R.string.triggers_charger), stringResource(R.string.triggers_charger_sub), charger) {
            charger = it
            Prefs.setChargerAlarm(context, it)
        }
        ToggleRow(stringResource(R.string.triggers_earbuds), stringResource(R.string.triggers_earbuds_sub), earbuds) {
            earbuds = it
            Prefs.setEarbudsAlarm(context, it)
            if (it) askBluetooth()
        }
        ToggleRow(
            stringResource(R.string.triggers_auto_earbuds),
            stringResource(R.string.triggers_auto_earbuds_sub),
            autoEarbuds,
        ) {
            autoEarbuds = it
            Prefs.setAutoArmEarbuds(context, it)
            if (it) askBluetooth()
        }
        ToggleRow(
            stringResource(R.string.triggers_auto_transit),
            stringResource(R.string.triggers_auto_transit_sub),
            autoTransit,
        ) {
            autoTransit = it
            Prefs.setAutoArmTransit(context, it)
            when {
                !it -> TransitRecognition.disable(context)
                setup.activityPermission -> TransitRecognition.enable(context)
                Build.VERSION.SDK_INT >= 29 -> activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                else -> Unit
            }
        }
        if (earbuds || autoEarbuds) {
            if (setup.bluetoothPermission) {
                DevicePicker()
            } else {
                PermissionWarning(stringResource(R.string.permission_bluetooth)) { askBluetooth() }
            }
        }
        if (autoTransit && !setup.activityPermission && Build.VERSION.SDK_INT >= 29) {
            PermissionWarning(stringResource(R.string.permission_activity)) {
                activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
    }
}

/** Lets the user choose which paired earbuds or watch count, so a car stereo doesn't. */
@Composable
private fun DevicePicker() {
    val context = LocalContext.current
    val devices = remember { BluetoothReceiver.pairedDevices(context) }
    var trusted by remember { mutableStateOf(Prefs.trustedDevices(context)) }
    Text(stringResource(R.string.devices_title), color = Color.White, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.devices_sub), color = Muted, fontSize = 13.sp)
    if (devices.isEmpty()) {
        Text(stringResource(R.string.devices_none), color = Muted, fontSize = 13.sp)
    }
    devices.forEach { (name, address) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = address in trusted,
                onCheckedChange = { checked ->
                    trusted = if (checked) trusted + address else trusted - address
                    Prefs.setTrustedDevices(context, trusted)
                },
            )
            Text(name, color = Color.White)
        }
    }
}

@Composable
private fun EmergencySection(refreshKey: Int) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf(Prefs.contactsText(context)) }
    var name by remember { mutableStateOf(Prefs.ownerName(context)) }
    var photos by remember { mutableStateOf(Prefs.intruderPhotos(context)) }
    // Re-read permissions on resume and after a request.
    var permissionTick by remember { mutableIntStateOf(0) }
    val key = refreshKey + permissionTick
    val sms = remember(key) { granted(context, Manifest.permission.SEND_SMS) }
    val location = remember(key) { Locator.hasPermission(context) }
    val camera = remember(key) { granted(context, Manifest.permission.CAMERA) }
    val background = remember(key) {
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val requestMain = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionTick++
    }
    val requestBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionTick++
    }

    Section(stringResource(R.string.emergency_title)) {
        Text(stringResource(R.string.emergency_sub), color = Muted, fontSize = 13.sp)
        OutlinedTextField(
            value = contacts,
            onValueChange = {
                contacts = it
                Prefs.setContactsText(context, it)
            },
            label = { Text(stringResource(R.string.emergency_numbers)) },
            placeholder = { Text("+8801XXXXXXXXX, +8801XXXXXXXXX") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                Prefs.setOwnerName(context, it)
            },
            label = { Text(stringResource(R.string.emergency_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(stringResource(R.string.emergency_photos), stringResource(R.string.emergency_photos_sub), photos) {
            photos = it
            Prefs.setIntruderPhotos(context, it)
        }
        SetupRow(stringResource(R.string.emergency_perm_sms), sms, fixLabel = null) {}
        SetupRow(stringResource(R.string.emergency_perm_location), location, fixLabel = null) {}
        SetupRow(stringResource(R.string.emergency_perm_camera), camera, fixLabel = null) {}
        if (!sms || !location || !camera) {
            OutlinedButton(
                onClick = {
                    requestMain.launch(
                        arrayOf(
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.CAMERA,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.emergency_allow)) }
        }
        if (location && !background && Build.VERSION.SDK_INT >= 29) {
            PermissionWarning(stringResource(R.string.emergency_background)) {
                requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        OutlinedButton(
            onClick = {
                val numbers = Prefs.contacts(context)
                val text = context.getString(
                    R.string.sms_test_message,
                    Prefs.ownerName(context).ifBlank { context.getString(R.string.sms_owner_default) },
                )
                val message = when {
                    numbers.isEmpty() -> context.getString(R.string.emergency_no_numbers)
                    !sms -> context.getString(R.string.emergency_no_sms_permission)
                    else -> context.getString(R.string.emergency_test_sent, Sms.send(context, numbers, text))
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.emergency_test)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSection() {
    if (!Language.supported) return
    val context = LocalContext.current
    val current = remember { Language.current(context) }
    Section(stringResource(R.string.language_title)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                null to stringResource(R.string.language_system),
                "en" to "English",
                "bn" to "বাংলা",
            ).forEach { (tag, label) ->
                FilterChip(
                    selected = current == tag,
                    onClick = { Language.set(context, tag) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun SetupSection(setup: SetupStatus) {
    val context = LocalContext.current
    Section(stringResource(R.string.setup_title)) {
        SetupRow(stringResource(R.string.setup_proximity), setup.proximity, fixLabel = null) {}
        SetupRow(stringResource(R.string.setup_notifications), setup.notifications) { SetupActions.notifications(context) }
        SetupRow(stringResource(R.string.setup_battery), setup.battery) { SetupActions.battery(context) }
        SetupRow(stringResource(R.string.setup_full_screen), setup.fullScreen) { SetupActions.fullScreen(context) }
        SetupRow(stringResource(R.string.setup_lock), setup.secureLock) { SetupActions.secureLock(context) }
        Text(stringResource(R.string.setup_smart_lock), color = Muted, fontSize = 13.sp)
        SetupRow(stringResource(R.string.setup_dnd), setup.doNotDisturb) { SetupActions.doNotDisturb(context) }
        SetupRow(stringResource(R.string.setup_accessibility), setup.accessibility) { SetupActions.accessibility(context) }
        if (!setup.accessibility) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.setup_restricted),
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { SetupActions.appInfo(context) }) { Text(stringResource(R.string.setup_app_info)) }
            }
        }
        SetupRow(stringResource(R.string.setup_autostart), ok = null, fixLabel = stringResource(R.string.action_open)) {
            SetupActions.autostart(context)
        }
    }
}

@Composable
private fun StatusCard(phase: Phase, testMode: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(phase.color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(phase.emoji, fontSize = 56.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(phase.titleRes()), color = phase.color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(phase.hintRes()), color = Muted, textAlign = TextAlign.Center)
            if (testMode) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.main_test_badge),
                    color = Amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Amber.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionWarning(text: String, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⚠️ $text", color = Amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onGrant) { Text(stringResource(R.string.action_allow)) }
    }
}

/** ok = null means the app can't check it, so it only offers a button. */
@Composable
private fun SetupRow(label: String, ok: Boolean?, fixLabel: String? = stringResource(R.string.action_fix), onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (ok) {
                true -> "✅"
                false -> "⚠️"
                null -> "➖"
            },
            fontSize = 18.sp,
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        if (ok != true && fixLabel != null) {
            TextButton(onClick = onFix) { Text(fixLabel) }
        }
    }
}

/** Live sensor readings, so you can see how this phone's sensors behave in a real pocket. */
@Composable
private fun SensorCheck() {
    val context = LocalContext.current
    val covered = stringResource(R.string.sensor_covered)
    val uncovered = stringResource(R.string.sensor_uncovered)
    var proximity by remember { mutableStateOf<Pair<Boolean, Float>?>(null) }
    var light by remember { mutableStateOf<Int?>(null) }
    var motion by remember { mutableFloatStateOf(0f) }
    val sensorManager = remember { context.getSystemService(SensorManager::class.java) }
    val proximitySensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_PROXIMITY -> proximity = Detection.isNear(event.sensor, event.values[0]) to event.values[0]
                    Sensor.TYPE_LIGHT -> light = event.values[0].roundToInt()
                    Sensor.TYPE_LINEAR_ACCELERATION -> motion = Detection.motion(event.values, linear = true)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        proximitySensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    Section(stringResource(R.string.sensor_title)) {
        Text(stringResource(R.string.sensor_sub), color = Muted, fontSize = 13.sp)
        SensorLine(
            R.string.sensor_proximity,
            when {
                proximitySensor == null -> stringResource(R.string.sensor_not_found)
                proximity == null -> stringResource(R.string.sensor_no_reading)
                else -> proximity!!.let { (near, raw) -> "${if (near) covered else uncovered}  ($raw)" }
            },
        )
        SensorLine(
            R.string.sensor_screen_off,
            when {
                proximitySensor == null -> "—"
                proximitySensor.isWakeUpSensor -> stringResource(R.string.sensor_wake_yes)
                else -> stringResource(R.string.sensor_wake_maybe)
            },
        )
        SensorLine(R.string.sensor_light, light?.let { "$it lux" } ?: "—")
        SensorLine(R.string.sensor_motion, "%.1f m/s²".format(motion))
    }
}

@Composable
private fun SensorLine(@StringRes label: Int, value: String) {
    Row {
        Text(stringResource(label), color = Muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp)
    }
}
