package com.taiyeb.pocketguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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

/** One thing the phone must allow. [required] ones decide whether the alarm can work at all. */
data class SetupStep(
    val id: String,
    @DrawableRes val icon: Int,
    val title: Int,
    val why: Int,
    val required: Boolean,
    val done: Boolean,
)

/** Brands that stop background apps from restarting unless "autostart" is allowed. */
private val autostartBrands = setOf(
    "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "huawei", "honor",
    "infinix", "tecno", "itel",
)

fun needsAutostart() = Build.MANUFACTURER.lowercase() in autostartBrands ||
    Build.BRAND.lowercase() in autostartBrands

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** Every step that applies to this phone, in the order the guide asks for them. */
fun setupSteps(context: Context, status: SetupStatus = SetupStatus.read(context)): List<SetupStep> = buildList {
    add(SetupStep("lock", R.drawable.ic_lock, R.string.ready_lock, R.string.ready_lock_why, true, status.secureLock))
    add(
        SetupStep(
            "lock_delay", R.drawable.ic_timer, R.string.ready_lock_delay, R.string.ready_lock_delay_why, true,
            status.lockCheck == Prefs.LockCheck.PASSED ||
                (status.lockCheck == Prefs.LockCheck.UNTESTED && Prefs.lockDelayOpened(context)),
        ),
    )
    add(SetupStep("notifications", R.drawable.ic_bell, R.string.ready_notifications, R.string.ready_notifications_why, true, status.notifications))
    add(SetupStep("battery", R.drawable.ic_battery, R.string.ready_battery, R.string.ready_battery_why, true, status.battery))
    if (Build.VERSION.SDK_INT >= 34) {
        add(SetupStep("full_screen", R.drawable.ic_shield, R.string.ready_full_screen, R.string.ready_full_screen_why, true, status.fullScreen))
    }
    if (needsAutostart()) {
        add(SetupStep("autostart", R.drawable.ic_bolt, R.string.ready_autostart, R.string.ready_autostart_why, true, Prefs.autostartOpened(context)))
    }
    if (Prefs.intruderPhotos(context)) {
        add(
            SetupStep(
                "camera", R.drawable.ic_camera, R.string.ready_camera, R.string.ready_camera_why, false,
                granted(context, Manifest.permission.CAMERA),
            ),
        )
    }
    // SOS: asked here too, so the first run covers everything; each is optional.
    if (Prefs.sosButtons(context)) {
        add(SetupStep("sos_keys", R.drawable.ic_vibration, R.string.ready_sos_keys, R.string.ready_sos_keys_why, false, SosSetup.keysEnabled(context)))
        add(
            SetupStep(
                "sos_record", R.drawable.ic_camera, R.string.ready_sos_record, R.string.ready_sos_record_why, false,
                granted(context, Manifest.permission.CAMERA) && granted(context, Manifest.permission.RECORD_AUDIO),
            ),
        )
    }
    if (Prefs.SOS_AVAILABLE) {
        add(SetupStep("contacts", R.drawable.ic_bell, R.string.ready_contacts, R.string.ready_contacts_why, false, EmergencyContacts.approved(context)))
    }
    // From Android 15 apps can't lift the user's Do Not Disturb, so the access would do nothing.
    if (Build.VERSION.SDK_INT < 35) {
        add(SetupStep("dnd", R.drawable.ic_moon, R.string.ready_dnd, R.string.ready_dnd_why, false, status.doNotDisturb))
    }
}

/** Required steps still missing; the guard can't be relied on until this is zero. */
fun missingRequired(context: Context) = setupSteps(context).count { it.required && !it.done }

/**
 * "Get it ready": walks through every permission and setting the alarm depends on, one tap each,
 * ticking them off as they're granted. Shown after the guide, and whenever arming finds a gap.
 */
@Composable
fun SetupScreen(refreshKey: Int, onDone: () -> Unit, onSkip: () -> Unit, onOpenContacts: () -> Unit) {
    val context = LocalContext.current
    // Bumped after an in-app permission dialog, which doesn't pause the activity.
    var localKey by remember { mutableIntStateOf(0) }
    val steps = remember(refreshKey, localKey) { setupSteps(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) SetupActions.notifications(context) // Denied before: only the settings screen can change it.
        localKey++
    }
    val askCamera = rememberDisclosedPermission(R.string.disclose_camera, arrayOf(Manifest.permission.CAMERA)) { localKey++ }
    val askRecord = rememberDisclosedPermission(
        R.string.disclose_sos_record, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
    ) {
        localKey++
        // A running guard needs the microphone in its foreground type to record from the lock screen.
        GuardService.refreshForeground()
    }
    val setUpKeys = rememberSosKeysSetup(refreshKey)

    val act: (SetupStep) -> Unit = { step ->
        when (step.id) {
            "lock" -> SetupActions.secureLock(context)
            "lock_delay" -> {
                Prefs.setLockDelayOpened(context)
                SetupActions.secureLock(context)
            }
            "notifications" -> if (Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SetupActions.notifications(context)
            }
            "battery" -> SetupActions.battery(context)
            "full_screen" -> SetupActions.fullScreen(context)
            "autostart" -> {
                Prefs.setAutostartOpened(context)
                SetupActions.autostart(context)
            }
            "camera" -> askCamera()
            "dnd" -> SetupActions.doNotDisturb(context)
            "sos_keys" -> setUpKeys()
            "sos_record" -> askRecord()
            "contacts" -> onOpenContacts()
        }
    }

    val required = steps.filter { it.required }
    val requiredDone = required.count { it.done }
    val ready = requiredDone == required.size
    // The first step still to do gets the big button, so the user never has to hunt for it.
    val next = steps.firstOrNull { it.required && !it.done } ?: steps.firstOrNull { !it.done }

    TabPage(
        stringResource(R.string.ready_title),
        stringResource(R.string.ready_progress, requiredDone, required.size),
    ) {
        GlassCard {
            steps.forEach { step -> StepRow(step, isNext = step == next) { act(step) } }
        }
        Text(stringResource(R.string.ready_note), color = Muted, fontSize = 13.sp)
        if (next != null) {
            PrimaryButton(stringResource(R.string.ready_do, stringResource(next.title)), onClick = { act(next) })
        } else {
            PrimaryButton(stringResource(R.string.ready_done), onClick = onDone)
        }
        // Only optional steps left: finishing is fine. Required ones missing: allow putting it off.
        if (next != null) {
            TextButton(onClick = if (ready) onDone else onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(if (ready) R.string.ready_done else R.string.ready_later),
                    color = if (ready) Lavender else Muted,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: SetupStep, isNext: Boolean, onClick: () -> Unit) {
    val tint = when {
        step.done -> Green
        step.required -> if (isNext) Lavender else Amber
        else -> Muted
    }
    SettingRow(
        step.icon,
        stringResource(step.title),
        stringResource(step.why),
        tint = tint,
    ) {
        if (step.done) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Green.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                PillAction(stringResource(R.string.action_allow), onClick, color = if (step.required) Lavender else Muted)
                if (!step.required) {
                    Text(stringResource(R.string.ready_optional), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
