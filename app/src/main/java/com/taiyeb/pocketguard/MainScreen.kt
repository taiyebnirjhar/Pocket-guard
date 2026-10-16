package com.taiyeb.pocketguard

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Phase.color
    get() = when (this) {
        Phase.OFF -> Muted
        Phase.WAITING_FOR_POCKET, Phase.WARNING -> Amber
        Phase.IN_POCKET -> Green
        Phase.SIREN -> Red
    }

private val Phase.emoji
    get() = when (this) {
        Phase.OFF -> "🔓"
        Phase.WAITING_FOR_POCKET -> "👖"
        Phase.IN_POCKET -> "🛡️"
        Phase.WARNING -> "⚠️"
        Phase.SIREN -> "🚨"
    }

@Composable
fun MainScreen(refreshKey: Int) {
    val context = LocalContext.current
    val phase by GuardState.phase.collectAsState()
    val setup = remember(refreshKey, phase) { SetupStatus.read(context) }
    var grace by remember { mutableIntStateOf(Prefs.graceSeconds(context)) }
    var testMode by remember { mutableStateOf(Prefs.testMode(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pocket Guard", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        StatusCard(phase, testMode)

        val off = phase == Phase.OFF
        Button(
            onClick = { if (off) GuardService.arm(context) else GuardService.disarm(context) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (off) Green else Red),
        ) {
            Text(if (off) "ARM" else "DISARM", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Section("Settings") {
            Text("Time to unlock before the siren: $grace s", color = Color.White)
            Slider(
                value = grace.toFloat(),
                onValueChange = {
                    grace = it.roundToInt()
                    Prefs.setGraceSeconds(context, grace)
                },
                valueRange = 1f..5f,
                steps = 3,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Test mode", color = Color.White)
                    Text("Quiet siren, for trying it at home", color = Muted, fontSize = 13.sp)
                }
                Switch(
                    checked = testMode,
                    onCheckedChange = {
                        testMode = it
                        Prefs.setTestMode(context, it)
                    },
                )
            }
        }

        Section("Setup checklist") {
            SetupRow("Proximity sensor", setup.proximity, fixLabel = null) {}
            SetupRow("Notifications allowed", setup.notifications) { SetupActions.notifications(context) }
            SetupRow("Not killed by battery saver", setup.battery) { SetupActions.battery(context) }
            SetupRow("Alarm can show on lock screen", setup.fullScreen) { SetupActions.fullScreen(context) }
            SetupRow("Screen lock (PIN / fingerprint) set", setup.secureLock) { SetupActions.secureLock(context) }
            SetupRow(
                "Autostart (Xiaomi, Realme, Oppo, Vivo…)",
                ok = null,
                fixLabel = "Open",
            ) { SetupActions.autostart(context) }
        }

        SensorCheck()

        Section("How to test") {
            listOf(
                "Keep Test mode on. Tap ARM.",
                "Put the phone in your pocket. Wait for a short buzz: it's protected.",
                "Pull it out without unlocking. You'll hear beeps (in your earbuds if connected).",
                "Wait the unlock time: the quiet siren starts and the torch flashes.",
                "Unlock with your fingerprint: everything stops and it re-arms by itself.",
                "Try the volume buttons during the siren: they shouldn't silence it.",
            ).forEachIndexed { i, step ->
                Text("${i + 1}. $step", color = Color.White, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
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
            Text(phase.title, color = phase.color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(phase.hint, color = Muted, textAlign = TextAlign.Center)
            if (testMode) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "TEST MODE · quiet siren",
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

/** ok = null means the app can't check it, so it only offers a button. */
@Composable
private fun SetupRow(label: String, ok: Boolean?, fixLabel: String? = "Fix", onFix: () -> Unit) {
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
    var proximity by remember { mutableStateOf("no reading yet") }
    var light by remember { mutableStateOf("—") }
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
                    Sensor.TYPE_PROXIMITY -> {
                        val near = Detection.isNear(event.sensor, event.values[0])
                        proximity = (if (near) "COVERED" else "uncovered") + "  (${event.values[0]})"
                    }
                    Sensor.TYPE_LIGHT -> light = "${event.values[0].roundToInt()} lux"
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

    Section("Sensor check") {
        Text("Cover the top of the screen with your hand to test.", color = Muted, fontSize = 13.sp)
        SensorLine("Proximity", if (proximitySensor == null) "not found" else proximity)
        SensorLine(
            "Works with screen off",
            when {
                proximitySensor == null -> "—"
                proximitySensor.isWakeUpSensor -> "yes (wake-up sensor)"
                else -> "maybe (test in pocket)"
            },
        )
        SensorLine("Light", light)
        SensorLine("Motion", "%.1f m/s²".format(motion))
    }
}

@Composable
private fun SensorLine(label: String, value: String) {
    Row {
        Text(label, color = Muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp)
    }
}
