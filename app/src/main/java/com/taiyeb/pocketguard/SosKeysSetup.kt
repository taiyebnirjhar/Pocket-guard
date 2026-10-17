package com.taiyeb.pocketguard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Turning on the SOS buttons: first what the accessibility service sees and why (Play policy:
 * an explicit yes before sending the user there), then straight to its switch. Where Android
 * restricts it (installed outside an app store), a guide with a button for each of the three
 * steps, ticked off as Android reports them done. Re-read on [refreshKey] (each return to the app).
 */
@Composable
fun rememberSosKeysSetup(refreshKey: Int): () -> Unit {
    val context = LocalContext.current
    var disclosing by rememberSaveable { mutableStateOf(false) }
    var guiding by rememberSaveable { mutableStateOf(false) }
    // 1: try the switch once (only then does App info offer the setting), 2: allow it, 3: turn on.
    var stage by rememberSaveable { mutableIntStateOf(1) }
    val restricted = remember(refreshKey) { SosSetup.restricted(context) }
    val enabled = remember(refreshKey) { SosSetup.keysEnabled(context) }
    LaunchedEffect(enabled) { if (enabled) guiding = false }

    if (disclosing) {
        AlertDialog(
            onDismissRequest = { disclosing = false },
            title = { Text(stringResource(R.string.sos_keys_disclosure_title)) },
            text = { Text(stringResource(R.string.sos_keys_disclosure)) },
            confirmButton = {
                TextButton(onClick = {
                    disclosing = false
                    if (restricted) {
                        stage = 1
                        guiding = true
                    } else {
                        SosSetup.openSettings(context)
                    }
                }) { Text(stringResource(R.string.sos_keys_agree), color = Lavender, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { disclosing = false }) { Text(stringResource(R.string.action_cancel), color = Lavender) }
            },
            containerColor = CardColor,
            titleContentColor = TextPrimary,
            textContentColor = Muted,
            shape = RoundedCornerShape(28.dp),
        )
    }
    if (guiding) {
        AlertDialog(
            onDismissRequest = { guiding = false },
            title = { Text(stringResource(R.string.sos_guide_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.sos_guide_intro))
                    GuideStep(1, stringResource(R.string.sos_guide_1), stringResource(R.string.action_open), done = stage > 1 || !restricted, next = stage == 1 && restricted) {
                        stage = 2
                        SosSetup.openSettings(context)
                    }
                    GuideStep(2, stringResource(R.string.sos_guide_2), stringResource(R.string.action_app_info), done = !restricted, next = stage == 2 && restricted) {
                        stage = 3
                        openAppSettings(context)
                    }
                    GuideStep(3, stringResource(R.string.sos_guide_3), stringResource(R.string.action_open), done = enabled, next = !restricted || stage == 3) {
                        SosSetup.openSettings(context)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { guiding = false }) { Text(stringResource(R.string.action_close), color = Lavender) }
            },
            containerColor = CardColor,
            titleContentColor = TextPrimary,
            textContentColor = Muted,
            shape = RoundedCornerShape(28.dp),
        )
    }
    return { disclosing = true }
}

@Composable
private fun GuideStep(number: Int, text: String, action: String, done: Boolean, next: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background((if (done) Green else if (next) Lavender else Muted).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
            } else {
                Text("$number", color = if (next) Lavender else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (next) TextPrimary else Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        PillAction(action, onClick, color = if (next) Lavender else Muted)
    }
}
