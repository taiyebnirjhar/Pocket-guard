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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            PocketGuardTheme {
                AlarmScreen(onOwner = {
                    getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this@AlarmActivity, null)
                })
            }
        }
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
        modifier = Modifier.fillMaxSize().background(background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🚨", fontSize = 96.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.alarm_stolen),
            color = Color.White,
            fontSize = 36.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.alarm_return),
            color = Color.White,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
        val unlockStops by GuardState.unlockStopsAlarm.collectAsState()
        if (!unlockStops) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.alarm_open_app), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(48.dp))
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
