package com.taiyeb.pocketguard

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Fake shutdown: shows a "shutting down" spinner, then a black screen with the backlight at minimum,
 * so a thief thinks the phone is off while it keeps sending its location. The owner holds the
 * screen for 3 seconds to get the unlock prompt.
 */
class FakeOffActivity : ComponentActivity() {

    companion object {
        private const val SPINNER_MS = 2500L
        private const val OWNER_HOLD_MS = 3000L
    }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            val phase by GuardState.phase.collectAsState()
            var shuttingDown by remember { mutableStateOf(true) }
            LaunchedEffect(phase) {
                if (phase != Phase.STEALTH) finish()
            }
            LaunchedEffect(Unit) {
                delay(SPINNER_MS)
                shuttingDown = false
                window.attributes = window.attributes.apply { screenBrightness = 0f }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val start = SystemClock.elapsedRealtime()
                            tryAwaitRelease()
                            if (SystemClock.elapsedRealtime() - start >= OWNER_HOLD_MS) askOwnerToUnlock()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (shuttingDown) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.fake_shutting_down), color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }

    private fun askOwnerToUnlock() {
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
    }
}
