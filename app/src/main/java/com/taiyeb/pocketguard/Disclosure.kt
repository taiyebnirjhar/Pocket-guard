package com.taiyeb.pocketguard

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Asks for [permissions] the way Google Play requires for personal data: first a plain statement
 * of what is collected, why and when ([disclosure]), and only after the user taps Continue the
 * system dialog. Dismissing it is a no, never a yes. Returns the function that starts this.
 */
@Composable
fun rememberDisclosedPermission(
    @StringRes disclosure: Int,
    permissions: Array<String>,
    onResult: (granted: Boolean) -> Unit = {},
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(result.values.isNotEmpty() && result.values.all { it })
    }
    var showing by remember { mutableStateOf(false) }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(stringResource(R.string.disclose_title)) },
            text = { Text(stringResource(disclosure)) },
            confirmButton = {
                TextButton(onClick = {
                    showing = false
                    launcher.launch(permissions)
                }) { Text(stringResource(R.string.disclose_continue), color = Purple, fontWeight = FontWeight.Bold) }
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
