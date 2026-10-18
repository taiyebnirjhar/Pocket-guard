package com.taiyeb.pocketguard

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Flashing red screen shown over the lock screen while the siren plays. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back does nothing: the only way out is unlocking the phone.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            val phase by GuardState.phase.collectAsState()
            LaunchedEffect(phase) {
                if (phase != Phase.SIREN) finish()
            }
            // Over: nothing more to show while it closes.
            if (phase == Phase.SIREN) PocketGuardTheme {
                AlarmScreen(onOwner = {
                    val keyguard = getSystemService(KeyguardManager::class.java)
                    when {
                        // Began on an unlocked phone: only the credential in the app stops it.
                        !GuardState.unlockStopsAlarm.value -> runCatching { startActivity(MainActivity.intent(this@AlarmActivity)) }
                        // An SOS started while unlocked has no lock screen to dismiss: the owner is here.
                        !keyguard.isKeyguardLocked && GuardState.alarmTrigger.value == Trigger.SOS -> GuardService.ownerStopSos()
                        else -> keyguard.requestDismissKeyguard(this@AlarmActivity, null)
                    }
                })
            }
        }
    }
}

/** The SOS alerts: a countdown with Cancel, then who was told, or the tap the Play build needs. */
@Composable
private fun AlertStatus(alert: AlertUi) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Spacer(Modifier.height(16.dp))
    when {
        alert.smsFailed -> {
            Text(stringResource(R.string.alert_sms_failed), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
            OutlinedButton(
                onClick = { GuardService.sendAlertsByHand() },
                shape = CircleShape,
                border = BorderStroke(1.dp, Color.White),
            ) { Text(stringResource(R.string.alert_send_sms), color = Color.White, fontWeight = FontWeight.Bold) }
        }
        !alert.sent -> {
            Text(
                stringResource(R.string.alert_countdown, alert.names, alert.secondsLeft),
                color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            androidx.compose.material3.TextButton(onClick = { GuardService.cancelAlerts() }) {
                Text(stringResource(R.string.alert_cancel), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        alert.needsTap -> OutlinedButton(
            onClick = { GuardService.sendAlertsByHand() },
            shape = CircleShape,
            border = BorderStroke(1.dp, Color.White),
        ) { Text(stringResource(R.string.alert_send_sms), color = Color.White, fontWeight = FontWeight.Bold) }
        else -> Text(stringResource(R.string.alert_sent, alert.names), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
    if (alert.sent) {
        androidx.compose.material3.TextButton(onClick = {
            val text = GuardService.alertText() ?: return@TextButton
            // She's sharing it herself: the unlock WhatsApp asks for doesn't end the SOS.
            GuardService.handOverAlerts()
            val whatsApp = SosAlerts.whatsAppIntent(text)
            runCatching { context.startActivity(whatsApp) }.onFailure {
                // No WhatsApp: any app that can send text.
                runCatching {
                    context.startActivity(
                        android.content.Intent.createChooser(whatsApp.setPackage(null), null)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }) { Text(stringResource(R.string.alert_whatsapp), color = Color.White) }
    }
}

@Composable
private fun AlarmScreen(onOwner: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "flash")
    val background by transition.animateColor(
        initialValue = Color(0xFFDC2626),
        targetValue = Color(0xFF450A0A),
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "background",
    )
    Column(
        // Scrolls: in Bangla, with large text or on a small phone, the owner button must stay reachable.
        modifier = Modifier.fillMaxSize().background(background).verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Once SOS, SOS until the screen closes: the trigger clears as the alarm stops, a moment
        // before this screen goes, and the theft screen (with its "I need help" button) would flash.
        val trigger by GuardState.alarmTrigger.collectAsState()
        val sosSeen = remember { booleanArrayOf(false) }
        if (trigger == Trigger.SOS) sosSeen[0] = true
        val sos = sosSeen[0]
        Text(if (sos) "🆘" else "🚨", fontSize = 96.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (sos) R.string.alarm_help else R.string.alarm_stolen),
            color = Color.White,
            fontSize = 36.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (sos) R.string.alarm_help_sub else R.string.alarm_return),
            color = Color.White,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
        val context = androidx.compose.ui.platform.LocalContext.current
        // Before the first unlock after a restart there's nowhere to save a recording.
        val canRecord = remember {
            context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.getSystemService(android.os.UserManager::class.java).isUserUnlocked
        }
        if (sos && canRecord) {
            // Said out loud on the screen too: being recorded makes people back off.
            Spacer(Modifier.height(12.dp))
            Text("● " + stringResource(R.string.alarm_help_recording), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        val alert by GuardState.alert.collectAsState()
        alert?.let { AlertStatus(it) }
        val unlockStops by GuardState.unlockStopsAlarm.collectAsState()
        if (!unlockStops) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.alarm_open_app), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        if (!sos) {
            // The owner may be the one who grabbed it, from someone harassing her: one tap turns
            // the theft alarm into SOS (contacts, location, recording). Harmless if a thief taps it.
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { GuardService.requestSos("alarm screen") },
                // A touch only: a key press (a keyboard's Enter, a remote) must never send an SOS.
                modifier = Modifier.focusProperties { canFocus = false },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB91C1C)),
                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 20.dp),
            ) {
                Text("🆘 " + stringResource(R.string.alarm_need_help), fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(if (sos) 48.dp else 20.dp))
        OutlinedButton(
            onClick = onOwner,
            shape = CircleShape,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        ) {
            Text(stringResource(R.string.alarm_owner_unlock), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
