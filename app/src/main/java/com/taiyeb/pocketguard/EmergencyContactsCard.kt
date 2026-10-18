package com.taiyeb.pocketguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Who SOS alerts and what they get. Nothing is sent until the owner has read the exact message and
 * the list of people and approved them; any change asks again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmergencyContactsCard(refreshKey: Int) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val contacts = remember(version) { EmergencyContacts.list(context) }
    val approved = remember(version) { EmergencyContacts.approved(context) }
    val reachable = remember(version) { contacts.filter { EmergencyContacts.reachable(context, it) } }
    var name by remember { mutableStateOf(EmergencyContacts.ownerName(context)) }
    var addingNumber by rememberSaveable { mutableStateOf(false) }
    // A picked contact whose number can't be texted as it is: the owner fixes it before it's added.
    var draft by remember { mutableStateOf<EmergencyContact?>(null) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }

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
                    if (contacts.size < EmergencyContacts.MAX) {
                        if (EmergencyContacts.canText(contact.phone)) save(contacts + contact) else draft = contact
                    }
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
    ) {
        GuardService.refreshForeground()
        version++ // Re-reads the permissions, so "Allow location" goes once it's granted.
    }
    val hasLocation = remember(refreshKey, version) { SosAlerts.hasLocation(context) }

    SettingRow(R.drawable.ic_bell, stringResource(R.string.contacts_title), stringResource(if (BuildConfig.PLAY_STORE) R.string.contacts_sub_play else R.string.contacts_sub))
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
    // The alert's words, in English letters either way (see EmergencyContacts.SmsLanguage).
    val smsLanguage = remember(version) { EmergencyContacts.smsLanguage(context) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.contacts_sms_language), color = TextPrimary, fontSize = 14.sp)
            Text(stringResource(R.string.contacts_sms_language_why), color = Muted, fontSize = 12.sp)
        }
        listOf(
            EmergencyContacts.SmsLanguage.ENGLISH to R.string.sms_english,
            EmergencyContacts.SmsLanguage.BANGLISH to R.string.sms_banglish,
        ).forEach { (language, label) ->
            val selected = smsLanguage == language
            Text(
                stringResource(label),
                color = if (selected) Color.White else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(if (selected) Purple else Track)
                    .selectable(selected = selected, role = Role.RadioButton) {
                        EmergencyContacts.setSmsLanguage(context, language)
                        version++
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    contacts.forEachIndexed { i, contact ->
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = i },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(contact.name.ifBlank { contact.phone.ifBlank { contact.email } }, color = TextPrimary, fontSize = 15.sp)
                Text(
                    listOf(contact.phone, contact.email).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
            val removeLabel = stringResource(R.string.contacts_remove_who, contact.name.ifBlank { contact.phone.ifBlank { contact.email } })
            TextButton(
                onClick = { save(contacts.filterIndexed { j, _ -> j != i }) },
                modifier = Modifier.semantics { contentDescription = removeLabel },
            ) {
                Text(stringResource(R.string.contacts_remove), color = Lavender)
            }
        }
    }
    if (contacts.size < EmergencyContacts.MAX) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                runCatching {
                    pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }
            }) { Text(stringResource(R.string.contacts_add_pick), color = Lavender, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { addingNumber = true }) {
                Text(stringResource(R.string.contacts_add_number), color = Lavender, fontWeight = FontWeight.Bold)
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
            Text(stringResource(R.string.contacts_approved, reachable.size), color = Lavender, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (SosAlerts.sendTest(context)) Toast.makeText(context, R.string.contacts_test_sent, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.contacts_test), color = Lavender, fontWeight = FontWeight.Bold)
                }
                if (!hasLocation) {
                    TextButton(onClick = askPermissions) {
                        Text(stringResource(R.string.contacts_allow_location), color = Lavender, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            PermissionWarning(
                stringResource(
                    when {
                        name.isBlank() -> R.string.contacts_need_name
                        reachable.isEmpty() -> R.string.contacts_none_reachable
                        else -> R.string.contacts_not_approved
                    },
                ),
                if (name.isNotBlank() && reachable.isNotEmpty()) stringResource(R.string.action_review) else null,
            ) { confirming = true }
        }
    }

    draft?.let { picked ->
        ContactDialog(
            initial = picked,
            isNew = true,
            onSave = { save(contacts + it) },
            onDismiss = { draft = null },
        )
    }
    if (addingNumber) ContactDialog(
        initial = null,
        onSave = { save(contacts + it) },
        onDismiss = { addingNumber = false },
    )
    editing?.let { i ->
        ContactDialog(
            initial = contacts.getOrNull(i),
            onSave = { updated -> save(contacts.mapIndexed { j, c -> if (j == i) updated else c }) },
            onDismiss = { editing = null },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.contacts_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (BuildConfig.PLAY_STORE) R.string.contacts_confirm_play else R.string.contacts_confirm,
                        reachable.joinToString(", ") { it.name.ifBlank { it.phone.ifBlank { it.email } } },
                        EmergencyContacts.previewText(context),
                        (AlertPlan.COUNTDOWN_MS / 1000).toInt(),
                    ) + stringResource(
                        R.string.contacts_confirm_more,
                        EmergencyContacts.previewUpdateText(context),
                        EmergencyContacts.falseAlarmText(context),
                    ) + if (Backend.enabled(context)) "\n\n" + stringResource(R.string.contacts_confirm_live) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    EmergencyContacts.approve(context)
                    version++
                    // Signed in now, so an SOS doesn't wait for it (or fail on a busy network).
                    if (Backend.enabled(context)) {
                        val app = context.applicationContext
                        Thread { Backend.signIn(app) }.start()
                    }
                    // Only when something's missing: the explanation needn't come back on every approval.
                    if (!hasLocation || (!BuildConfig.PLAY_STORE && !SosAlerts.canSendSms(context))) askPermissions()
                }) { Text(stringResource(R.string.contacts_approve), color = Lavender, fontWeight = FontWeight.Bold) }
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

/** Adds a person ([initial] null) or edits one: a number, an email address, or both. */
@Composable
private fun ContactDialog(
    initial: EmergencyContact?,
    onSave: (EmergencyContact) -> Unit,
    onDismiss: () -> Unit,
    // A picked contact whose number needs fixing is still a new one.
    isNew: Boolean = initial == null,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(initial?.phone.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(initial?.email.orEmpty()) }
    val phoneOk = EmergencyContacts.canText(phone)
    val emailOk = EmergencyContacts.looksLikeEmail(email)
    // An address only counts when it can be emailed, which needs the online part.
    val canEmail = Backend.enabled(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.contacts_add_number else R.string.contacts_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.contacts_contact_name)) }, singleLine = true)
                OutlinedTextField(
                    phone,
                    { phone = it },
                    label = { Text(stringResource(R.string.contacts_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                if (canEmail) {
                    OutlinedTextField(
                        email,
                        { email = it },
                        label = { Text(stringResource(R.string.contacts_email)) },
                        singleLine = true,
                        isError = email.isNotBlank() && !emailOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (phoneOk || (canEmail && emailOk)) && (email.isBlank() || emailOk) && (phone.isBlank() || phoneOk),
                onClick = {
                    onSave(EmergencyContact(name.trim(), phone.trim(), if (canEmail) email.trim() else initial?.email.orEmpty()))
                    onDismiss()
                },
            ) {
                Text(
                    stringResource(if (isNew) R.string.contacts_add else R.string.contacts_save),
                    color = Lavender,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Lavender) } },
        containerColor = CardColor,
        titleContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}
