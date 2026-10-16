package com.taiyeb.pocketguard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ARM = "arm"
    }

    private enum class Screen { ONBOARDING, MAIN, HISTORY }

    /** Bumped on resume so permission and setup checks re-read settings after the user comes back. */
    private val refreshKey = mutableIntStateOf(0)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on first creation: a rotation re-delivers the same intent and would re-arm after a disarm.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_ARM, false) == true) {
            GuardService.arm(this)
        }
        if (Prefs.autoArmTransit(this)) TransitRecognition.enable(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val firstScreen = if (Prefs.onboarded(this)) Screen.MAIN else Screen.ONBOARDING
        setContent {
            PocketGuardTheme {
                var screen by rememberSaveable { mutableStateOf(firstScreen) }
                when (screen) {
                    Screen.ONBOARDING -> OnboardingScreen(onDone = {
                        Prefs.setOnboarded(this@MainActivity, true)
                        screen = Screen.MAIN
                    })
                    Screen.MAIN -> MainScreen(
                        refreshKey = refreshKey.intValue,
                        onOpenHistory = { screen = Screen.HISTORY },
                        onOpenGuide = { screen = Screen.ONBOARDING },
                    )
                    Screen.HISTORY -> {
                        BackHandler { screen = Screen.MAIN }
                        HistoryScreen(onBack = { screen = Screen.MAIN })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey.intValue++
    }
}
