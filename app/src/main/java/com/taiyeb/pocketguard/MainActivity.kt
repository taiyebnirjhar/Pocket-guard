package com.taiyeb.pocketguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Home glows in the phase colour; off is a faint purple rather than grey. */
private val Phase.glow get() = if (this == Phase.OFF) Purple.copy(alpha = 0.55f) else color

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ARM = "arm"

        /** From the lock warning: start the lock test over and open the lock settings. */
        const val EXTRA_FIX_LOCK = "fix_lock"

        /** Tab to open, by name (home, triggers, history, settings). */
        const val EXTRA_TAB = "tab"
    }

    private enum class Screen { ONBOARDING, MAIN }

    /** Bumped on resume so permission and setup checks re-read settings after the user comes back. */
    private val refreshKey = mutableIntStateOf(0)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on first creation: a rotation re-delivers the same intent and would re-arm after a disarm.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_ARM, false) == true) {
            if (!GuardService.arm(this)) Toast.makeText(this, R.string.main_need_lock, Toast.LENGTH_LONG).show()
        }
        if (savedInstanceState == null) handleFixLock(intent)
        if (Prefs.autoArmTransit(this)) TransitRecognition.enable(this)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val firstScreen = if (Prefs.onboarded(this)) Screen.MAIN else Screen.ONBOARDING
        // For screenshots and shortcuts: "--es tab settings" opens that tab.
        val firstTab = Tab.entries.firstOrNull { it.name.equals(intent?.getStringExtra(EXTRA_TAB), ignoreCase = true) } ?: Tab.HOME
        setContent {
            PocketGuardTheme {
                var screen by rememberSaveable { mutableStateOf(firstScreen) }
                var tab by rememberSaveable { mutableStateOf(firstTab) }
                when (screen) {
                    Screen.ONBOARDING -> {
                        // Reopened as the guide: Back returns to the app instead of leaving it.
                        BackHandler(Prefs.onboarded(this@MainActivity)) { screen = Screen.MAIN }
                        OnboardingScreen(onDone = {
                            Prefs.setOnboarded(this@MainActivity, true)
                            screen = Screen.MAIN
                        })
                    }
                    Screen.MAIN -> {
                        BackHandler(tab != Tab.HOME) { tab = Tab.HOME }
                        MainTabs(tab, onSelect = { tab = it }, onOpenGuide = { screen = Screen.ONBOARDING })
                    }
                }
            }
        }
    }

    @Composable
    private fun MainTabs(tab: Tab, onSelect: (Tab) -> Unit, onOpenGuide: () -> Unit) {
        val phase by GuardState.phase.collectAsState()
        GlowBackground(if (tab == Tab.HOME) phase.glow else Purple) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    refreshKey = refreshKey.intValue,
                    onOpenGuide = onOpenGuide,
                    onOpenSettings = { onSelect(Tab.SETTINGS) },
                )
                Tab.TRIGGERS -> TriggersScreen(refreshKey.intValue)
                Tab.HISTORY -> HistoryScreen()
                Tab.SETTINGS -> SettingsScreen(refreshKey.intValue, onOpenGuide)
            }
            BottomNav(
                tab,
                onSelect,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleFixLock(intent)
    }

    private fun handleFixLock(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FIX_LOCK, false) != true) return
        intent.removeExtra(EXTRA_FIX_LOCK)
        SetupActions.secureLock(this)
    }

    override fun onResume() {
        super.onResume()
        refreshKey.intValue++
    }
}
