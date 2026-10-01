package com.mie.kreaworkbench.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.db.ImageRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class SaveOutcome {
    data object Already : SaveOutcome()
    data class Saved(val uri: String) : SaveOutcome()
    data class Failed(val message: String) : SaveOutcome()
}

fun albumUriExists(context: Context, uriString: String): Boolean {
    if (uriString.isBlank()) return false
    return try {
        val uri = Uri.parse(uriString)
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
            it.moveToFirst()
        } == true
    } catch (_: Exception) {
        false
    }
}

fun saveToAlbum(context: Context, file: File, displayName: String): String {
    val mime = when {
        displayName.endsWith(".jpg", true) || displayName.endsWith(".jpeg", true) -> "image/jpeg"
        displayName.endsWith(".webp", true) -> "image/webp"
        else -> "image/png"
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Comfy Workbench")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error(context.str(R.string.err_album))
    resolver.openOutputStream(uri).use { out ->
        requireNotNull(out) { context.str(R.string.err_album) }
        file.inputStream().use { it.copyTo(out) }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    return uri.toString()
}

/** 视频入相册：Movies/Comfy Workbench（MediaStore.Video）。 */
fun saveVideoToAlbum(context: Context, file: File, displayName: String): String {
    val mime = when (file.extension.lowercase()) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "gif" -> "image/gif"
        else -> "video/mp4"
    }
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Comfy Workbench")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: error(context.str(R.string.err_album))
    resolver.openOutputStream(uri).use { out ->
        requireNotNull(out) { context.str(R.string.err_album) }
        file.inputStream().use { it.copyTo(out) }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    return uri.toString()
}

fun saveImageIfNeeded(context: Context, row: ImageRow): SaveOutcome {
    if (row.savedToAlbum && albumUriExists(context, row.albumUri)) {
        return SaveOutcome.Already
    }
    val file = File(row.localPath)
    if (!file.exists()) return SaveOutcome.Failed(context.str(R.string.err_file_missing))
    return try {
        val uri = when (row.kind) {
            "video" -> saveVideoToAlbum(context, file, "krea_${row.id}.${file.extension.ifBlank { "mp4" }}")
            else -> saveToAlbum(context, file, "krea_${row.id}.png")
        }
        SaveOutcome.Saved(uri)
    } catch (e: Exception) {
        SaveOutcome.Failed(e.message ?: context.str(R.string.err_save))
    }
}

suspend fun persistDelete(app: KreaApp, row: ImageRow, alsoServer: Boolean): String? {
    withContext(Dispatchers.IO) {
        File(row.localPath).delete()
        // 视频的首帧缩略图缓存（<视频名>.jpg）一并清掉，不留孤儿
        if (row.kind == "video" && row.localPath.isNotBlank()) {
            File(row.localPath + ".jpg").delete()
        }
        app.container.db.tombstoneAndDelete(row)
    }
    app.container.engine.revision.value = app.container.engine.revision.value + 1
    if (!alsoServer) return null
    if (row.remoteFilename.isBlank()) return app.str(R.string.err_no_remote)
    return try {
        app.container.api.deleteRemoteImage(row.remoteFilename, row.remoteSubfolder)
        null
    } catch (e: Exception) {
        e.message ?: app.str(R.string.err_remote_delete)
    }
}

fun shareImage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.str(R.string.action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareVideo(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (file.extension.lowercase() == "webm") "video/webm" else "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.str(R.string.action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
