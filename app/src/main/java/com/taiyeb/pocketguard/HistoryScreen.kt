package com.taiyeb.pocketguard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version by History.version.collectAsState()
    val incidents = remember(version) { History.load(context) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
            Spacer(Modifier.weight(1f))
            if (incidents.isNotEmpty()) {
                TextButton(onClick = { History.clear(context) }) { Text(stringResource(R.string.history_clear), color = Red) }
            }
        }
        Text(stringResource(R.string.history_title), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (incidents.isEmpty()) {
            Text(stringResource(R.string.history_empty), color = Muted)
        }
        incidents.forEach { IncidentCard(it) }
    }
}

@Composable
private fun IncidentCard(incident: Incident) {
    val context = LocalContext.current
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val level = when {
        incident.stealth -> stringResource(R.string.history_level_stealth)
        incident.escalated -> stringResource(R.string.history_level_siren)
        else -> stringResource(R.string.history_level_warning)
    }
    val ended = incident.endedAt
    val outcome = when {
        ended == null -> stringResource(R.string.history_ongoing)
        incident.flicker -> stringResource(R.string.history_flicker)
        incident.ownerStopped -> stringResource(
            R.string.history_owner_stopped, ((ended - incident.startedAt) / 1000).toInt(),
        )
        else -> stringResource(R.string.history_disarmed)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(format.format(Date(incident.startedAt)), color = Muted, fontSize = 13.sp)
            Text(
                stringResource(incident.trigger.labelRes()),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("$level · $outcome", color = if (incident.escalated) Red else Amber, fontSize = 14.sp)
            if (incident.alertsSent > 0) {
                Text(stringResource(R.string.history_sms_sent, incident.alertsSent), color = Muted, fontSize = 13.sp)
            }
            incident.locations.lastOrNull()?.let { fix ->
                TextButton(onClick = {
                    val uri = Uri.parse("geo:${fix.lat},${fix.lng}?q=${fix.lat},${fix.lng}")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: Exception) {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${fix.lat},${fix.lng}")),
                            )
                        } catch (noBrowser: Exception) {
                            // No maps app and no browser.
                        }
                    }
                }) {
                    Text(stringResource(R.string.history_open_map, fix.accuracy.toInt()))
                }
            }
            if (incident.photos.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    incident.photos.forEach { name -> Thumbnail(File(History.photosDir(context), name)) }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) { decodeUpright(file)?.asImageBitmap() }
    }
    bitmap?.let {
        Image(
            it,
            contentDescription = stringResource(R.string.history_photo),
            modifier = Modifier.size(110.dp).clip(RoundedCornerShape(12.dp)),
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
