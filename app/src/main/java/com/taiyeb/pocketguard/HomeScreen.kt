package com.taiyeb.pocketguard

import android.app.Activity
import android.app.KeyguardManager
import android.os.SystemClock
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(refreshKey: Int, onOpenGuide: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val phase by GuardState.phase.collectAsState()
    val armedAt by GuardState.armedAt.collectAsState()
    val unlockStops by GuardState.unlockStopsAlarm.collectAsState()
    val historyVersion by History.version.collectAsState()
    val setup = remember(refreshKey, phase) { SetupStatus.read(context) }
    // Re-read on resume: test mode switches itself off after an hour.
    val testMode = remember(refreshKey, phase) { Prefs.testMode(context) }
    val grace = remember(refreshKey) { Prefs.graceSeconds(context) }
    val lastAlarm = remember(historyVersion) { History.load(context).maxOfOrNull { it.startedAt } }
    val confirmTitle = stringResource(R.string.home_confirm_disarm)

    val confirmOwner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) GuardService.disarm(context)
    }
    val onPower: () -> Unit = {
        when {
            phase == Phase.OFF -> if (!GuardService.arm(context)) {
                Toast.makeText(context, R.string.main_need_lock, Toast.LENGTH_LONG).show()
            }
            // The phone wasn't locked when this alarm began, so whoever holds it now has to prove
            // they're the owner before the alarm stops.
            phase.isAlarm && !unlockStops -> {
                @Suppress("DEPRECATION")
                val confirm = context.getSystemService(KeyguardManager::class.java)
                    .createConfirmDeviceCredentialIntent(confirmTitle, null)
                if (confirm != null) confirmOwner.launch(confirm) else GuardService.disarm(context)
            }
            else -> GuardService.disarm(context)
        }
    }

    HomePage {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(phase)
            if (testMode) {
                Spacer(Modifier.width(8.dp))
                TagPill(stringResource(R.string.settings_test), Amber)
            }
            Spacer(Modifier.weight(1f))
            CircleIconButton(R.drawable.ic_help, stringResource(R.string.main_guide), onOpenGuide)
        }

        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            ParticleRing(phase, Modifier.fillMaxSize())
            PowerButton(
                phase,
                contentDescription = stringResource(if (phase == Phase.OFF) R.string.main_arm else R.string.main_disarm),
                onClick = onPower,
            )
        }

        Text(
            stringResource(phase.titleRes()),
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(phase.hintRes()),
            color = Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        val since = armedAt
        if (since != null && phase != Phase.OFF) {
            Spacer(Modifier.height(14.dp))
            ArmedFor(since)
        }
        if (phase.isAlarm && !unlockStops) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.alarm_open_app), color = Amber, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                R.drawable.ic_timer,
                stringResource(R.string.home_unlock_time),
                if (grace == 0) stringResource(R.string.home_unlock_none) else stringResource(R.string.home_seconds, grace),
                Modifier.weight(1f),
            )
            StatCard(
                R.drawable.ic_history,
                stringResource(R.string.home_last_alarm),
                lastAlarm?.let { relativeTime(it) } ?: stringResource(R.string.home_last_alarm_none),
                Modifier.weight(1f),
            )
        }
        val left = setup.stepsLeft
        if (left > 0) {
            Spacer(Modifier.height(12.dp))
            GlassCard(onClick = onOpenSettings) {
                SettingRow(
                    R.drawable.ic_warning,
                    stringResource(R.string.home_setup_title),
                    stringResource(R.string.home_setup_left, left),
                    tint = Amber,
                ) {
                    Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Muted)
                }
            }
        }
    }
}

/** Big round power button: dark when off, a purple (or alarm-coloured) gradient when armed. */
@Composable
private fun PowerButton(phase: Phase, contentDescription: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")
    val top by animateColorAsState(
        when (phase) {
            Phase.OFF -> CardColor
            Phase.WARNING, Phase.SIREN -> phase.color
            else -> Lavender
        },
        tween(500),
        label = "powerTop",
    )
    val bottom by animateColorAsState(
        when (phase) {
            Phase.OFF -> Color(0xFF1A1A1C)
            Phase.WARNING, Phase.SIREN -> phase.color.copy(alpha = 0.75f)
            else -> Purple
        },
        tween(500),
        label = "powerBottom",
    )
    Box(
        Modifier
            .size(132.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(1.dp, Color.White.copy(alpha = if (phase == Phase.OFF) 0.08f else 0.28f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_power),
            contentDescription = null,
            tint = if (phase == Phase.OFF) Lavender else Color.White,
            modifier = Modifier.size(52.dp),
        )
    }
}

/** "Armed for 12:34", ticking every second. */
@Composable
private fun ArmedFor(since: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val seconds = ((now - since) / 1000).coerceAtLeast(0)
    val clock = if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(CardColor)
            .border(1.dp, CardBorder, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = Lavender, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.home_armed_for, clock), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Setup steps that still stand between the user and a guard that works when it matters. */
val SetupStatus.stepsLeft
    get() = listOf(notifications, battery, fullScreen, secureLock, lockCheck == Prefs.LockCheck.PASSED).count { !it }
