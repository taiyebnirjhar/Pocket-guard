package com.taiyeb.pocketguard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Asks for [permissions] the way Google Play requires for personal data: first a plain statement
 * of what is collected, why and when ([disclosure]), and only after the user taps Continue the
 * system dialog. Dismissing it is a no, never a yes. Returns the function that starts this.
 *
 * Once Android stops asking (denied twice), Continue opens the app's settings page instead,
 * where the permission can still be turned on.
 */
@Composable
fun rememberDisclosedPermission(
    @StringRes disclosure: Int,
    permissions: Array<String>,
    onResult: (granted: Boolean) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    var blocked by rememberSaveable { mutableStateOf(false) }
    var launchedAt by remember { mutableStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val activity = context.findActivity()
        val nothingGranted = result.isNotEmpty() && result.values.none { it }
        blocked = nothingGranted && activity != null &&
            result.keys.none { activity.shouldShowRequestPermissionRationale(it) }
        // Answered at once with no dialog shown: Android had already stopped asking.
        if (blocked && SystemClock.elapsedRealtime() - launchedAt < 400) openAppSettings(context)
        onResult(result.values.isNotEmpty() && result.values.all { it })
    }
    var showing by rememberSaveable { mutableStateOf(false) }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(stringResource(R.string.disclose_title)) },
            text = { Text(stringResource(disclosure)) },
            confirmButton = {
                TextButton(onClick = {
                    showing = false
                    if (blocked) {
                        openAppSettings(context)
                    } else {
                        launchedAt = SystemClock.elapsedRealtime()
                        launcher.launch(permissions)
                    }
                }) { Text(stringResource(R.string.disclose_continue), color = Lavender, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showing = false }) { Text(stringResource(R.string.disclose_not_now), color = Lavender) }
            },
            containerColor = CardColor,
            titleContentColor = TextPrimary,
            textContentColor = Muted,
            shape = RoundedCornerShape(28.dp),
        )
    }
    return { showing = true }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
