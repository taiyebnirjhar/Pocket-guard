package com.taiyeb.pocketguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Who SOS alerts and what they get. Nothing is sent until the owner has read the exact message and
 * the list of people and approved them; any change asks again.
 */
@Composable
fun EmergencyContactsCard() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val contacts = remember(version) { EmergencyContacts.list(context) }
    val approved = remember(version) { EmergencyContacts.approved(context) }
    var name by remember { mutableStateOf(EmergencyContacts.ownerName(context)) }
    var addingNumber by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    val save = { list: List<EmergencyContact> ->
        EmergencyContacts.save(context, list)
        version++
    }
    // The system picker hands back one phone number and grants reading just that: no contacts permission.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val contact = EmergencyContact(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty())
                    if (contact.phone.isNotBlank() && contacts.size < EmergencyContacts.MAX) save(contacts + contact)
                }
            }
        }
    }
    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!BuildConfig.PLAY_STORE) add(Manifest.permission.SEND_SMS)
    }.toTypedArray()
    val askPermissions = rememberDisclosedPermission(
        if (BuildConfig.PLAY_STORE) R.string.disclose_location else R.string.disclose_location_sms,
        permissions,
    ) { GuardService.refreshForeground() }

    SettingRow(R.drawable.ic_bell, stringResource(R.string.contacts_title), stringResource(R.string.contacts_sub))
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            EmergencyContacts.setOwnerName(context, it)
            version++
        },
        label = { Text(stringResource(R.string.contacts_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    contacts.forEachIndexed { i, contact ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(contact.name.ifBlank { contact.phone }, color = TextPrimary, fontSize = 15.sp)
                Text(contact.phone, color = Muted, fontSize = 13.sp)
            }
            TextButton(onClick = { save(contacts.filterIndexed { j, _ -> j != i }) }) {
                Text(stringResource(R.string.contacts_remove), color = Lavender)
            }
        }
    }
    if (contacts.size < EmergencyContacts.MAX) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                runCatching {
                    pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }
            }) { Text(stringResource(R.string.contacts_add_pick), color = Purple, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { addingNumber = true }) {
                Text(stringResource(R.string.contacts_add_number), color = Purple, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (contacts.isNotEmpty()) {
        Text(stringResource(R.string.contacts_preview), color = Muted, fontSize = 13.sp)
        Text(
            EmergencyContacts.previewText(context),
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Lavender.copy(alpha = 0.10f))
                .padding(12.dp),
        )
        if (approved) {
            Text(stringResource(R.string.contacts_approved, contacts.size), color = Lavender, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { SosAlerts.sendTest(context) }) {
                    Text(stringResource(R.string.contacts_test), color = Purple, fontWeight = FontWeight.Bold)
                }
                if (!SosAlerts.hasLocation(context)) {
                    TextButton(onClick = askPermissions) {
                        Text(stringResource(R.string.contacts_allow_location), color = Purple, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            PermissionWarning(
                stringResource(if (name.isBlank()) R.string.contacts_need_name else R.string.contacts_not_approved),
            ) { if (name.isNotBlank()) confirming = true }
        }
    }

    if (addingNumber) AddNumberDialog(
        onAdd = { save(contacts + it) },
        onDismiss = { addingNumber = false },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.contacts_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.contacts_confirm,
                        contacts.joinToString(", ") { it.name.ifBlank { it.phone } },
                        EmergencyContacts.previewText(context),
                        (AlertPlan.COUNTDOWN_MS / 1000).toInt(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    EmergencyContacts.approve(context)
                    version++
                    askPermissions()
                }) { Text(stringResource(R.string.contacts_approve), color = Purple, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel), color = Lavender) }
            },
            containerColor = CardColor,
            titleContentColor = TextPrimary,
            textContentColor = Muted,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
private fun AddNumberDialog(onAdd: (EmergencyContact) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_add_number)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.contacts_contact_name)) }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.contacts_number)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = EmergencyContacts.normalize(phone).count { it.isDigit() } >= 6,
                onClick = {
                    onAdd(EmergencyContact(name.trim(), phone.trim()))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.contacts_add), color = Purple, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Lavender) } },
        containerColor = CardColor,
        titleContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}
