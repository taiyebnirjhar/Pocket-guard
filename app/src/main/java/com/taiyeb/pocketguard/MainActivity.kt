package com.taiyeb.pocketguard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    private enum class Screen { ONBOARDING, SETUP, MAIN }

    /** Bumped on resume so permission and setup checks re-read settings after the user comes back. */
    private val refreshKey = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Expired entries and recordings past the cap go even if the phone never alarms again.
        val app = applicationContext
        Thread { History.prune(app) }.start()
        // Only on first creation: a rotation re-delivers the same intent and would re-arm after a disarm.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_ARM, false) == true) {
            if (!GuardService.arm(this)) Toast.makeText(this, R.string.main_need_lock, Toast.LENGTH_LONG).show()
        }
        if (savedInstanceState == null) handleFixLock(intent)
        if (Prefs.autoArmTransit(this)) TransitRecognition.enable(this)
        // For screenshots and shortcuts: "--es tab settings" opens that tab, "--es tab setup" the setup.
        val tabExtra = intent?.getStringExtra(EXTRA_TAB)
        val firstScreen = when {
            !Prefs.onboarded(this) -> Screen.ONBOARDING
            tabExtra.equals("setup", ignoreCase = true) -> Screen.SETUP
            else -> Screen.MAIN
        }
        val firstTab = Tab.entries.firstOrNull { it.name.equals(tabExtra, ignoreCase = true) } ?: Tab.HOME
        setContent {
            PocketGuardTheme {
                var screen by rememberSaveable { mutableStateOf(firstScreen) }
                var tab by rememberSaveable { mutableStateOf(firstTab) }
                // Setup opened by the power button arms the guard once it's done.
                var armAfterSetup by rememberSaveable { mutableStateOf(false) }
                when (screen) {
                    Screen.ONBOARDING -> {
                        // Reopened as the guide: Back returns to the app instead of leaving it.
                        BackHandler(Prefs.onboarded(this@MainActivity)) { screen = Screen.MAIN }
                        OnboardingScreen(onDone = {
                            val firstTime = !Prefs.onboarded(this@MainActivity)
                            Prefs.setOnboarded(this@MainActivity, true)
                            // First run goes straight on to the permissions, while the user is paying attention.
                            // Optional steps too (SOS buttons, recording, contacts), so nothing is left to find later.
                            screen = if (firstTime && setupSteps(this@MainActivity).any { !it.done }) Screen.SETUP else Screen.MAIN
                        })
                    }
                    Screen.SETUP -> {
                        val leave = {
                            armAfterSetup = false
                            screen = Screen.MAIN
                        }
                        BackHandler { leave() }
                        GlowBackground(Purple) {
                            SetupScreen(
                                refreshKey = refreshKey.intValue,
                                onDone = {
                                    if (armAfterSetup && !GuardService.arm(this@MainActivity)) {
                                        Toast.makeText(this@MainActivity, R.string.main_need_lock, Toast.LENGTH_LONG).show()
                                    }
                                    leave()
                                },
                                onSkip = leave,
                                onOpenContacts = {
                                    leave()
                                    tab = Tab.TRIGGERS
                                },
                            )
                        }
                    }
                    Screen.MAIN -> {
                        BackHandler(tab != Tab.HOME) { tab = Tab.HOME }
                        MainTabs(
                            tab,
                            onSelect = { tab = it },
                            onOpenGuide = { screen = Screen.ONBOARDING },
                            onOpenSetup = { arm ->
                                armAfterSetup = arm
                                screen = Screen.SETUP
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MainTabs(tab: Tab, onSelect: (Tab) -> Unit, onOpenGuide: () -> Unit, onOpenSetup: (Boolean) -> Unit) {
        val phase by GuardState.phase.collectAsState()
        GlowBackground(if (tab == Tab.HOME) phase.glow else Purple) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    refreshKey = refreshKey.intValue,
                    onOpenGuide = onOpenGuide,
                    onOpenSetup = onOpenSetup,
                    onOpenTriggers = { onSelect(Tab.TRIGGERS) },
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
        // An arming reminder tapped while the app was already open.
        if (intent.getBooleanExtra(EXTRA_ARM, false)) {
            intent.removeExtra(EXTRA_ARM)
            if (!GuardService.arm(this)) Toast.makeText(this, R.string.main_need_lock, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleFixLock(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FIX_LOCK, false) != true) return
        intent.removeExtra(EXTRA_FIX_LOCK)
        SetupActions.secureLock(this)
    }

    override fun onResume() {
        super.onResume()
        refreshKey.intValue++
        // Open now: Android grants the camera, microphone and location types again (they may have
        // been refused when the guard restarted from boot or the background).
        GuardService.refreshForeground()
    }
}
