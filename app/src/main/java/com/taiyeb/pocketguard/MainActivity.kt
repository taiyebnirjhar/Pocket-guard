package com.taiyeb.pocketguard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ARM = "arm"
    }

    /** Bumped on resume so the setup checklist re-reads settings after the user comes back. */
    private val refreshKey = mutableIntStateOf(0)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_ARM, false) == true) GuardService.arm(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PocketGuardTheme {
                MainScreen(refreshKey = refreshKey.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey.intValue++
    }
}
