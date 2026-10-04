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
    // MIME 按文件真实扩展名给（bug 修复：以前 gif/webp 之外一律 image/png）
    val mime = mimeForImage(file.extension.ifBlank { "png" })
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Comfy直连")
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

/** 视频入相册：Movies/Comfy直连（MediaStore.Video）。 */
fun saveVideoToAlbum(context: Context, file: File, displayName: String): String {
    val mime = mimeForVideo(file.extension.ifBlank { "mp4" })
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Comfy直连")
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
        // 展示名带真实扩展名；gif 虽由视频节点产出，但本质是动图，按图片入 Pictures（MIME 才对）
        val ext = file.extension.ifBlank { if (row.kind == "video") "mp4" else "png" }.lowercase()
        val uri = if (row.kind == "video" && ext != "gif") {
            saveVideoToAlbum(context, file, "krea_${row.id}.$ext")
        } else {
            saveToAlbum(context, file, "krea_${row.id}.$ext")
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
        type = mimeForVideo(file.extension)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.str(R.string.action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
