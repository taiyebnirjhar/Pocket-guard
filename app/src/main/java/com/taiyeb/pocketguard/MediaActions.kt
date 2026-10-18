package com.taiyeb.pocketguard

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/**
 * Photos and recordings live in the app's private storage, which the gallery can't see. These
 * open one in an app the owner picks, share them, or copy them to the phone's Pictures and Movies
 * ("Pocket Guard" albums) to keep.
 */
object MediaActions {
    private const val ALBUM = "Pocket Guard"

    fun isVideo(file: File) = file.extension.equals("mp4", ignoreCase = true)
    private fun mime(file: File) = if (isVideo(file)) "video/mp4" else "image/jpeg"

    private fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Full screen in an app the owner chooses (the phone's gallery, a video player, …). */
    fun open(context: Context, file: File) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), mime(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, context.getString(R.string.history_open_with))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    /** Sends them anywhere: police, family, a cloud drive. */
    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val type = when {
            files.all { isVideo(it) } -> "video/mp4"
            files.none { isVideo(it) } -> "image/jpeg"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uri(context, it) }))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Before Android 10 saving to the shared folders needs the storage permission. */
    val needsPermission get() = Build.VERSION.SDK_INT < 29

    /**
     * Copies [files] into Pictures/Pocket Guard and Movies/Pocket Guard, where the gallery shows
     * them. Blocking (run off the main thread); returns how many were saved.
     */
    fun saveToPhone(context: Context, files: List<File>): Int = files.count { file ->
        runCatching { if (Build.VERSION.SDK_INT >= 29) saveQ(context, file) else saveLegacy(context, file) }.getOrDefault(false)
    }

    @RequiresApi(29)
    private fun saveQ(context: Context, file: File): Boolean {
        val video = isVideo(file)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(file))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + ALBUM,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File): Boolean {
        val base = Environment.getExternalStoragePublicDirectory(
            if (isVideo(file)) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        val dir = File(base, ALBUM).apply { mkdirs() }
        val copy = file.copyTo(File(dir, file.name), overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(copy.path), arrayOf(mime(file)), null)
        return true
    }
}
