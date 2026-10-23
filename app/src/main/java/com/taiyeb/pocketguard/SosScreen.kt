package com.taiyeb.pocketguard

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp

/**
 * The SOS tab: whether an SOS reaches anyone, what it does step by step (each online step with its
 * own switch), how to start it, and the emergency contacts.
 */
@Composable
fun SosScreen(refreshKey: Int) {
    val context = LocalContext.current
    // Bumped when the contacts change, so the readiness line follows at once.
    var contactsVersion by remember { mutableIntStateOf(0) }
    val online = Backend.configured(context)
    var live by remember { mutableStateOf(Prefs.liveLocation(context)) }
    var video by remember { mutableStateOf(Prefs.videoUpload(context)) }
    var email by remember { mutableStateOf(Prefs.emailAlerts(context)) }
    var sosButtons by remember { mutableStateOf(Prefs.sosButtons(context)) }
    var camera by remember(refreshKey) {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var microphone by remember(refreshKey) {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val sosKeys = remember(refreshKey) { SosSetup.keysEnabled(context) }
    val setUpKeys = rememberSosKeysSetup(refreshKey)
    val askRecord = rememberDisclosedPermission(
        R.string.disclose_sos_record, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
    ) {
        camera = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // A running guard needs the microphone in its foreground type to record from the lock screen.
        if (camera || microphone) GuardService.refreshForeground()
    }
    // Read again whenever a switch or a contact changes: the live link and email change who is reached.
    val reached = remember(refreshKey, contactsVersion, live, email) {
        if (EmergencyContacts.approved(context)) EmergencyContacts.list(context).count { EmergencyContacts.reachable(context, it) } else 0
    }
    val reapprove = remember(refreshKey, contactsVersion, live, email) { EmergencyContacts.needsReapproval(context) }

    TabPage(stringResource(R.string.sos_tab_title), stringResource(R.string.sos_sub)) {
        GlassCard {
            when {
                reapprove -> SettingRow(
                    R.drawable.ic_warning, stringResource(R.string.home_alerts_off), stringResource(R.string.home_alerts_off_sub), tint = Amber,
                )
                reached > 0 -> SettingRow(
                    R.drawable.ic_check, stringResource(R.string.sos_ready, reached), stringResource(R.string.sos_ready_sub), tint = Green,
                )
                else -> SettingRow(
                    R.drawable.ic_warning, stringResource(R.string.sos_siren_only), stringResource(R.string.sos_siren_only_sub), tint = Amber,
                )
            }
        }

        SectionLabel(stringResource(R.string.sos_will))
        GlassCard {
            SettingRow(R.drawable.ic_bell, stringResource(R.string.sos_step_siren), stringResource(R.string.sos_step_siren_sub)) {
                TagPill(stringResource(R.string.tag_always), Lavender)
            }
            SettingRow(R.drawable.ic_camera, stringResource(R.string.sos_step_record), stringResource(R.string.sos_step_record_sub)) {
                TagPill(stringResource(R.string.tag_always), Lavender)
            }
            if (!camera || !microphone) PermissionWarning(stringResource(R.string.permission_sos_record)) { askRecord() }
            SettingRow(
                R.drawable.ic_vibration,
                stringResource(R.string.sos_step_sms),
                stringResource(if (reached > 0) R.string.sos_step_sms_sub else R.string.sos_step_sms_none),
                tint = if (reached > 0) Lavender else Muted,
            ) {
                TagPill(stringResource(R.string.tag_always), Lavender)
            }
            if (online) {
                ToggleRow(R.drawable.ic_shield, stringResource(R.string.sos_step_live), stringResource(R.string.sos_step_live_sub), live) {
                    live = it
                    Prefs.setLiveLocation(context, it)
                    GuardService.refreshForeground()
                }
                // Both ride on the live link: without it there is no page to upload to and no sign-in to email from.
                ToggleRow(
                    R.drawable.ic_camera,
                    stringResource(R.string.sos_step_video),
                    stringResource(if (live) R.string.sos_step_video_sub else R.string.sos_needs_live),
                    live && video,
                ) {
                    if (!live) {
                        Toast.makeText(context, R.string.sos_needs_live, Toast.LENGTH_SHORT).show()
                        return@ToggleRow
                    }
                    video = it
                    Prefs.setVideoUpload(context, it)
                }
                ToggleRow(
                    R.drawable.ic_bell,
                    stringResource(R.string.sos_step_email),
                    stringResource(if (live) R.string.sos_step_email_sub else R.string.sos_needs_live),
                    live && email,
                ) {
                    if (!live) {
                        Toast.makeText(context, R.string.sos_needs_live, Toast.LENGTH_SHORT).show()
                        return@ToggleRow
                    }
                    email = it
                    Prefs.setEmailAlerts(context, it)
                }
            } else {
                Text(stringResource(R.string.sos_no_online), color = Muted, fontSize = 12.sp)
            }
        }

        if (Prefs.SOS_AVAILABLE) {
            SectionLabel(stringResource(R.string.sos_start_section))
            GlassCard {
                ToggleRow(R.drawable.ic_vibration, stringResource(R.string.triggers_sos), stringResource(R.string.triggers_sos_sub), sosButtons) {
                    // With the theft alarm off too there would be nothing to arm.
                    if (!it && !Prefs.theftGuard(context)) {
                        Toast.makeText(context, R.string.triggers_keep_one, Toast.LENGTH_LONG).show()
                        return@ToggleRow
                    }
                    sosButtons = it
                    Prefs.setSosButtons(context, it)
                    GuardService.refreshSos()
                    if (it && (!camera || !microphone)) askRecord() else if (it && !sosKeys) setUpKeys()
                }
                if (sosButtons && !sosKeys) {
                    PermissionWarning(stringResource(R.string.sos_keys_off), stringResource(R.string.action_turn_on)) { setUpKeys() }
                }
                Text(stringResource(R.string.sos_other_ways), color = Muted, fontSize = 12.sp)
                Text(stringResource(R.string.sos_legal), color = Muted, fontSize = 12.sp)
            }

            SectionLabel(stringResource(R.string.contacts_section))
            // Afresh when a switch changes: the live link and email change what the approval covers.
            GlassCard { key(live, email) { EmergencyContactsCard(refreshKey) { contactsVersion++ } } }
        }
    }
}
