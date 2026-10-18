package com.taiyeb.pocketguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val version by History.version.collectAsState()
    val incidents = remember(version) { History.load(context) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    TabPage(
        stringResource(R.string.history_title),
        action = {
            if (incidents.isNotEmpty()) {
                CircleIconButton(R.drawable.ic_delete, stringResource(R.string.history_clear)) { confirmClear = true }
            }
        },
    ) {
        if (incidents.isEmpty()) {
            EmptyCard(R.drawable.ic_shield, stringResource(R.string.history_empty))
        }
        incidents.forEach { IncidentCard(it) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    History.clear(context)
                }) { Text(stringResource(R.string.action_delete), color = Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel), color = Lavender) }
            },
            containerColor = CardColor,
            titleContentColor = TextPrimary,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@DrawableRes
private fun Trigger.icon() = when (this) {
    Trigger.POCKET_REMOVAL -> R.drawable.ic_pocket
    Trigger.AIRPLANE_MODE -> R.drawable.ic_flight
    Trigger.CHARGER_UNPLUGGED -> R.drawable.ic_plug
    Trigger.EARBUDS_DISCONNECTED -> R.drawable.ic_headset
    Trigger.RESTARTED, Trigger.POWER_MENU -> R.drawable.ic_power
    Trigger.TABLE_MOVED -> R.drawable.ic_pocket
    Trigger.SOS -> R.drawable.ic_vibration
}

@Composable
private fun IncidentCard(incident: Incident) {
    val context = LocalContext.current
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val level = when {
        incident.escalated -> stringResource(R.string.history_level_siren)
        else -> stringResource(R.string.history_level_warning)
    }
    val ended = incident.endedAt
    val outcome = when {
        ended == null -> stringResource(R.string.history_ongoing)
        incident.flicker && incident.trigger == Trigger.TABLE_MOVED -> stringResource(R.string.history_put_back)
        incident.flicker -> stringResource(R.string.history_flicker)
        incident.ownerStopped -> stringResource(
            R.string.history_owner_stopped, ((ended - incident.startedAt) / 1000).toInt(),
        )
        else -> stringResource(R.string.history_disarmed)
    }
    val color = if (incident.escalated) Red else Amber

    GlassCard {
        SettingRow(
            incident.trigger.icon(),
            stringResource(incident.trigger.labelRes()),
            format.format(Date(incident.startedAt)),
            tint = color,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TagPill(level, color)
            Text(outcome, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        val photos = incident.photos.map { File(History.photosDir(context), it) }.filter { it.exists() }
        if (photos.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                photos.forEach { file -> Thumbnail(file) { MediaActions.open(context, file) } }
            }
        }
        val videos = incident.videos.map { File(History.videosDir(context), it) }.filter { it.exists() }
        if (videos.isNotEmpty()) {
            Text(stringResource(R.string.sos_recordings), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                videos.forEachIndexed { i, file ->
                    val label = stringResource(R.string.history_play, i + 1)
                    TextButton(onClick = { MediaActions.open(context, file) }, modifier = Modifier.semantics { contentDescription = label }) {
                        Text("▶ ${i + 1}", color = Lavender, fontSize = 14.sp)
                    }
                }
            }
        }
        val media = photos + videos
        if (media.isNotEmpty()) MediaButtons(media)
    }
}

/** Save to phone (the gallery's "Pocket Guard" albums) and Share, for an alarm's photos and videos. */
@Composable
private fun MediaButtons(files: List<File>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save: () -> Unit = {
        val app = context.applicationContext
        scope.launch {
            val saved = withContext(Dispatchers.IO) { MediaActions.saveToPhone(app, files) }
            val text = if (saved == files.size) {
                app.getString(R.string.history_saved, saved)
            } else {
                app.getString(R.string.history_save_failed)
            }
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }
    // Android 9 and older: the shared Pictures and Movies folders need the storage permission.
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else Toast.makeText(context, R.string.history_save_failed, Toast.LENGTH_LONG).show()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillAction(stringResource(R.string.history_save), {
            val granted = !MediaActions.needsPermission ||
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (granted) save() else askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        })
        PillAction(stringResource(R.string.history_share), { MediaActions.share(context, files) })
    }
}

@Composable
private fun Thumbnail(file: File, onClick: () -> Unit) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) { decodeUpright(file)?.asImageBitmap() }
    }
    bitmap?.let {
        Image(
            it,
            contentDescription = stringResource(R.string.history_photo),
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    }
}

/** Small, correctly rotated copy of a camera JPEG (cameras store rotation in EXIF, not pixels). */
private fun decodeUpright(file: File): Bitmap? = try {
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
    val rotation = ExifInterface(file.path).rotationDegrees
    if (bitmap == null || rotation == 0) {
        bitmap
    } else {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }
} catch (e: Exception) {
    null
}
