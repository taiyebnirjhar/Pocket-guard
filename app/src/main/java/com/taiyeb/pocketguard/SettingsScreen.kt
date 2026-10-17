package com.taiyeb.pocketguard

import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(refreshKey: Int, onOpenGuide: () -> Unit) {
    val context = LocalContext.current
    val setup = remember(refreshKey) { SetupStatus.read(context) }
    // Re-read on resume: test mode switches itself off after an hour.
    var testMode by remember(refreshKey) { mutableStateOf(Prefs.testMode(context)) }

    TabPage(stringResource(R.string.settings_title)) {
        SectionLabel(stringResource(R.string.settings_alarm))
        GlassCard {
            UnlockDelay()
            ToggleRow(R.drawable.ic_science, stringResource(R.string.settings_test), stringResource(R.string.settings_test_sub), testMode) {
                testMode = it
                Prefs.setTestMode(context, it)
            }
        }

        SectionLabel(stringResource(R.string.setup_title))
        SetupChecklist(setup)

        SectionLabel(stringResource(R.string.sensor_title))
        SensorCheck()

        SectionLabel(stringResource(R.string.log_title))
        DetectionLogCard()

        if (Language.supported) {
            SectionLabel(stringResource(R.string.language_title))
            LanguagePicker()
        }

        SectionLabel(stringResource(R.string.settings_help))
        GlassCard {
            SettingRow(R.drawable.ic_help, stringResource(R.string.main_guide), null, Modifier.clickable(role = Role.Button, onClick = onOpenGuide)) {
                Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Muted)
            }
        }
        HowToTest()
    }
}

@Composable
private fun UnlockDelay() {
    val context = LocalContext.current
    var grace by remember { mutableIntStateOf(Prefs.graceSeconds(context)) }
    SettingRow(
        R.drawable.ic_timer,
        stringResource(R.string.settings_delay),
        if (grace == 0) stringResource(R.string.settings_grace_none) else stringResource(R.string.settings_grace, grace),
    ) {
        TagPill(
            if (grace == 0) stringResource(R.string.home_unlock_none) else stringResource(R.string.home_seconds, grace),
            if (grace == 0) Amber else Lavender,
        )
    }
    Slider(
        value = grace.toFloat(),
        onValueChange = {
            grace = it.roundToInt()
            Prefs.setGraceSeconds(context, grace)
        },
        valueRange = 0f..15f,
        steps = 14,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Purple,
            inactiveTrackColor = Track,
            activeTickColor = Color.White.copy(alpha = 0.35f),
            inactiveTickColor = Muted.copy(alpha = 0.35f),
        ),
    )
    if (grace == 0) {
        Text(stringResource(R.string.settings_grace_none_warning), color = Amber, fontSize = 13.sp)
    }
}

@Composable
private fun SetupChecklist(setup: SetupStatus) {
    val context = LocalContext.current
    GlassCard {
        CheckRow(R.drawable.ic_pocket, stringResource(R.string.setup_proximity), setup.proximity, fix = null)
        CheckRow(R.drawable.ic_bell, stringResource(R.string.setup_notifications), setup.notifications) {
            SetupActions.notifications(context)
        }
        CheckRow(R.drawable.ic_battery, stringResource(R.string.setup_battery), setup.battery) { SetupActions.battery(context) }
        CheckRow(R.drawable.ic_shield, stringResource(R.string.setup_full_screen), setup.fullScreen) {
            SetupActions.fullScreen(context)
        }
        CheckRow(R.drawable.ic_lock, stringResource(R.string.setup_lock), setup.secureLock) { SetupActions.secureLock(context) }
        CheckRow(
            R.drawable.ic_lock,
            stringResource(R.string.setup_lock_instant),
            when (setup.lockCheck) {
                Prefs.LockCheck.PASSED -> true
                Prefs.LockCheck.FAILED -> false
                Prefs.LockCheck.UNTESTED -> null
            },
            subtitle = stringResource(
                if (setup.lockCheck == Prefs.LockCheck.UNTESTED) R.string.setup_lock_instant_untested else R.string.setup_lock_instant_hint,
            ),
            alwaysFixable = true,
        ) { SetupActions.secureLock(context) }
        CheckRow(
            R.drawable.ic_power,
            stringResource(R.string.setup_power_off_password),
            ok = null,
            fixLabel = stringResource(R.string.action_open),
        ) { SetupActions.secureLock(context) }
        CheckRow(R.drawable.ic_bell, stringResource(R.string.setup_dnd), setup.doNotDisturb) { SetupActions.doNotDisturb(context) }
        CheckRow(
            R.drawable.ic_bolt,
            stringResource(R.string.setup_autostart),
            ok = null,
            fixLabel = stringResource(R.string.action_open),
        ) { SetupActions.autostart(context) }
        Text(stringResource(R.string.setup_smart_lock), color = Muted, fontSize = 13.sp)
    }
}

/**
 * One checklist item. [ok] = null means the app can't check it, so it only offers a button.
 * [alwaysFixable] keeps the button once it passes, for a check that can be re-run.
 */
@Composable
private fun CheckRow(
    @DrawableRes icon: Int,
    label: String,
    ok: Boolean?,
    subtitle: String? = null,
    fixLabel: String = stringResource(R.string.action_fix),
    alwaysFixable: Boolean = false,
    fix: (() -> Unit)?,
) {
    val tint = when (ok) {
        true -> Green
        false -> Amber
        null -> Lavender
    }
    SettingRow(icon, label, subtitle, tint = tint) {
        when {
            fix != null && (ok != true || alwaysFixable) -> PillAction(fixLabel, fix, color = if (ok == false) Amber else Lavender)
            ok == true -> StatusIcon(R.drawable.ic_check, Green)
            ok == false -> StatusIcon(R.drawable.ic_warning, Amber)
        }
    }
}

@Composable
private fun StatusIcon(@DrawableRes icon: Int, color: Color) {
    Box(Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val current = remember { Language.current(context) }
    GlassCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                null to stringResource(R.string.language_system),
                "en" to "English",
                "bn" to "বাংলা",
            ).forEach { (tag, label) ->
                val selected = current == tag
                Text(
                    label,
                    color = if (selected) Color.White else Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) Purple else Track)
                        .clickable(role = Role.RadioButton) { Language.set(context, tag) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun HowToTest() {
    GlassCard {
        Text(stringResource(R.string.test_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        listOfNotNull(
            R.string.test_1, R.string.test_2, R.string.test_3, R.string.test_4, R.string.test_5,
            R.string.test_6, R.string.test_7, R.string.test_8, R.string.test_9,
            R.string.test_10.takeIf { Prefs.SOS_AVAILABLE },
        ).forEachIndexed { i, step -> TestStep(i + 1, step) }
    }
}

@Composable
private fun TestStep(number: Int, @StringRes text: Int) {
    Row {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(Purple.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = Lavender, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}

/** What the guard saw and decided, newest first, with Copy for sending it in after a test. */
@Composable
private fun DetectionLogCard() {
    val context = LocalContext.current
    val version by DetectionLog.version.collectAsState()
    val entries = remember(version) { DetectionLog.snapshot().take(80) }
    val copied = stringResource(R.string.log_copied)
    GlassCard {
        Text(stringResource(R.string.log_sub), color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillAction(stringResource(R.string.log_copy), onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Pocket Guard log", DetectionLog.asText()))
                android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
            })
            PillAction(stringResource(R.string.log_clear), onClick = { DetectionLog.clear() }, color = Muted)
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.log_empty), color = Muted, fontSize = 13.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { entry ->
                    Row {
                        Text(DetectionLog.time(entry), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(10.dp))
                        Text(entry.text, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
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
                    Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> motion = Detection.motion(
                        event.values, linear = event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION,
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        proximitySensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        // Same fallback as the guard: phones without linear acceleration use the raw accelerometer.
        (
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            )?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    val near = proximity?.first == true
    GlassCard {
        Text(stringResource(R.string.sensor_sub), color = Muted, fontSize = 13.sp)
        SensorLine(
            R.drawable.ic_pocket,
            R.string.sensor_proximity,
            when {
                proximitySensor == null -> stringResource(R.string.sensor_not_found)
                proximity == null -> stringResource(R.string.sensor_no_reading)
                else -> proximity!!.let { (isNear, raw) -> "${if (isNear) covered else uncovered}  ($raw)" }
            },
            highlight = near,
        )
        SensorLine(
            R.drawable.ic_moon,
            R.string.sensor_screen_off,
            when {
                proximitySensor == null -> "—"
                proximitySensor.isWakeUpSensor -> stringResource(R.string.sensor_wake_yes)
                else -> stringResource(R.string.sensor_wake_maybe)
            },
        )
        SensorLine(R.drawable.ic_sun, R.string.sensor_light, light?.let { "$it lux" } ?: "—")
        SensorLine(R.drawable.ic_vibration, R.string.sensor_motion, "%.1f m/s²".format(motion))
    }
}

@Composable
private fun SensorLine(@DrawableRes icon: Int, @StringRes label: Int, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, if (highlight) Purple else Muted, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), color = Muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = if (highlight) Lavender else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
