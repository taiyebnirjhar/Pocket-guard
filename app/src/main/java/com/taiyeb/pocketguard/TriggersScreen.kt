package com.taiyeb.pocketguard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TriggersScreen(refreshKey: Int) {
    val context = LocalContext.current
    val setup = remember(refreshKey) { SetupStatus.read(context) }
    var airplane by remember { mutableStateOf(Prefs.airplaneAlarm(context)) }
    var charger by remember { mutableStateOf(Prefs.chargerAlarm(context)) }
    var earbuds by remember { mutableStateOf(Prefs.earbudsAlarm(context)) }
    var autoEarbuds by remember { mutableStateOf(Prefs.autoArmEarbuds(context)) }
    var autoTransit by remember { mutableStateOf(Prefs.autoArmTransit(context)) }
    var photos by remember { mutableStateOf(Prefs.intruderPhotos(context)) }
    var tableGuard by remember { mutableStateOf(Prefs.tableGuard(context)) }
    var sosButtons by remember { mutableStateOf(Prefs.sosButtons(context)) }
    var camera by remember(refreshKey) {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && Prefs.autoArmTransit(context)) TransitRecognition.enable(context)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    var microphone by remember(refreshKey) {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val sosKeys = remember(refreshKey) { SosSetup.keysEnabled(context) }
    var keysDisclosure by remember { mutableStateOf(false) }
    val shortcutClash = remember(refreshKey) { SosSetup.systemShortcutSet(context) }
    val recordPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        camera = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // A running guard needs the microphone in its foreground type to record from the lock screen.
        if (result.values.any { it }) GuardService.refreshForeground()
    }
    val askRecord = { recordPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    val askBluetooth = {
        if (Build.VERSION.SDK_INT >= 31 && !setup.bluetoothPermission) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    TabPage(stringResource(R.string.triggers_title), stringResource(R.string.triggers_sub)) {
        SectionLabel(stringResource(R.string.triggers_alarms))
        GlassCard {
            SettingRow(R.drawable.ic_pocket, stringResource(R.string.triggers_pocket), stringResource(R.string.triggers_pocket_sub)) {
                TagPill(stringResource(R.string.tag_always_on), Lavender)
            }
            ToggleRow(R.drawable.ic_shield, stringResource(R.string.triggers_table), stringResource(R.string.triggers_table_sub), tableGuard) {
                tableGuard = it
                Prefs.setTableGuard(context, it)
            }
            if (Prefs.SOS_AVAILABLE) {
                ToggleRow(R.drawable.ic_vibration, stringResource(R.string.triggers_sos), stringResource(R.string.triggers_sos_sub), sosButtons) {
                    sosButtons = it
                    Prefs.setSosButtons(context, it)
                    GuardService.refreshSos()
                    if (it && (!camera || !microphone)) askRecord()
                }
                if (sosButtons) {
                    if (!camera || !microphone) PermissionWarning(stringResource(R.string.permission_sos_record)) { askRecord() }
                    if (!sosKeys) PermissionWarning(stringResource(R.string.sos_keys_off)) { keysDisclosure = true }
                    if (shortcutClash) PermissionWarning(stringResource(R.string.sos_shortcut_clash)) { SosSetup.openSettings(context) }
                    Text(stringResource(R.string.sos_legal), color = Muted, fontSize = 12.sp)
                }
            }
            ToggleRow(R.drawable.ic_flight, stringResource(R.string.triggers_airplane), stringResource(R.string.triggers_airplane_sub), airplane) {
                airplane = it
                Prefs.setAirplaneAlarm(context, it)
            }
            ToggleRow(R.drawable.ic_plug, stringResource(R.string.triggers_charger), stringResource(R.string.triggers_charger_sub), charger) {
                charger = it
                Prefs.setChargerAlarm(context, it)
            }
            ToggleRow(R.drawable.ic_headset, stringResource(R.string.triggers_earbuds), stringResource(R.string.triggers_earbuds_sub), earbuds) {
                earbuds = it
                Prefs.setEarbudsAlarm(context, it)
                if (it) askBluetooth()
            }
        }

        if (keysDisclosure) {
            // Play policy: what the accessibility service sees and why, and an explicit yes,
            // before sending the user to turn it on.
            AlertDialog(
                onDismissRequest = { keysDisclosure = false },
                title = { Text(stringResource(R.string.sos_keys_disclosure_title)) },
                text = { Text(stringResource(R.string.sos_keys_disclosure)) },
                confirmButton = {
                    TextButton(onClick = {
                        keysDisclosure = false
                        SosSetup.openSettings(context)
                    }) { Text(stringResource(R.string.sos_keys_agree), color = Purple, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { keysDisclosure = false }) { Text(stringResource(R.string.action_cancel), color = Lavender) }
                },
                containerColor = CardColor,
                titleContentColor = TextPrimary,
                textContentColor = Muted,
                shape = RoundedCornerShape(28.dp),
            )
        }

        SectionLabel(stringResource(R.string.triggers_auto))
        GlassCard {
            ToggleRow(
                R.drawable.ic_headset,
                stringResource(R.string.triggers_auto_earbuds),
                stringResource(R.string.triggers_auto_earbuds_sub),
                autoEarbuds,
            ) {
                autoEarbuds = it
                Prefs.setAutoArmEarbuds(context, it)
                if (it) askBluetooth()
            }
            ToggleRow(
                R.drawable.ic_bus,
                stringResource(R.string.triggers_auto_transit),
                stringResource(R.string.triggers_auto_transit_sub),
                autoTransit,
            ) {
                autoTransit = it
                Prefs.setAutoArmTransit(context, it)
                when {
                    !it -> TransitRecognition.disable(context)
                    setup.activityPermission -> TransitRecognition.enable(context)
                    Build.VERSION.SDK_INT >= 29 -> activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    else -> Unit
                }
            }
            if (autoTransit && !setup.activityPermission && Build.VERSION.SDK_INT >= 29) {
                PermissionWarning(stringResource(R.string.permission_activity)) {
                    activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        }

        if (earbuds || autoEarbuds) {
            GlassCard {
                if (setup.bluetoothPermission) {
                    DevicePicker()
                } else {
                    PermissionWarning(stringResource(R.string.permission_bluetooth)) { askBluetooth() }
                }
            }
        }

        SectionLabel(stringResource(R.string.triggers_evidence))
        GlassCard {
            ToggleRow(R.drawable.ic_camera, stringResource(R.string.emergency_photos), stringResource(R.string.emergency_photos_sub), photos) {
                photos = it
                Prefs.setIntruderPhotos(context, it)
                if (it && !camera) cameraPermission.launch(Manifest.permission.CAMERA)
            }
            if (photos && !camera) {
                PermissionWarning(stringResource(R.string.permission_camera)) { cameraPermission.launch(Manifest.permission.CAMERA) }
            }
        }
    }
}

/** Lets the user choose which paired earbuds or watch count, so a car stereo doesn't. */
@Composable
private fun DevicePicker() {
    val context = LocalContext.current
    val devices = remember { BluetoothReceiver.pairedDevices(context) }
    var trusted by remember { mutableStateOf(Prefs.trustedDevices(context)) }
    Column {
        Text(stringResource(R.string.devices_title), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.devices_sub), color = Muted, fontSize = 13.sp)
    }
    if (devices.isEmpty()) {
        Text(stringResource(R.string.devices_none), color = Muted, fontSize = 13.sp)
    }
    devices.forEach { (name, address) ->
        val checked = address in trusted
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox) { on ->
                    trusted = if (on) trusted + address else trusted - address
                    Prefs.setTrustedDevices(context, trusted)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = Purple, uncheckedColor = Muted, checkmarkColor = TextPrimary),
            )
            Spacer(Modifier.width(8.dp))
            Text(name, color = TextPrimary, fontSize = 15.sp)
        }
    }
}
