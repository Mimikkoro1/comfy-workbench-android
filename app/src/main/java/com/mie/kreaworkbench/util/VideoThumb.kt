package com.mie.kreaworkbench.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 视频缩略图/时长（round6）：首帧取一次后缓存成 `<视频路径>.jpg`，画廊和任务卡直接读文件。
 */
object VideoThumb {

    /** 首帧缓存文件路径（不管在不在）；删除视频行时用它清缓存，不触发解码。 */
    fun thumbPath(videoPath: String): File? {
        if (videoPath.isBlank()) return null
        val video = File(videoPath)
        return File(video.parentFile, video.name + ".jpg")
    }

    /** 取首帧缓存文件；失败返回 null（UI 显示占位图标）。 */
    fun thumbFile(videoPath: String): File? {
        val video = File(videoPath)
        if (!video.isFile) return null
        val thumb = File(video.parentFile, video.name + ".jpg")
        if (thumb.isFile && thumb.length() > 0 && thumb.lastModified() >= video.lastModified()) return thumb
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            // round18：以前一律 getFrameAtTime 解全分辨率首帧（4K 视频 ≈33MB ARGB），
            // 画廊一次性组合多个视频格时很容易 OOM，而且 OOM 是 Error 不是 Exception，
            // 原来的 catch (_: Exception) 接不住 → 整个进程闪退。
            // API 27+ 直接按目标尺寸解码；再兜一层 scaleDown + Throwable。
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_MAX_EDGE, THUMB_MAX_EDGE)
            } else {
                r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (frame != null) {
                val scaled = scaleDown(frame, THUMB_MAX_EDGE)
                thumb.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                if (scaled !== frame) scaled.recycle()
                thumb
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** 时长毫秒；取不到返回 0。 */
    fun durationMs(videoPath: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(videoPath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    /** "1:23" 样式的时长文案；0 返回空串。 */
    fun durationLabel(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}

/** 参考视频的元信息（round17）：任何一项取不到都给空值，不抛异常。 */
data class VideoProbe(
    val name: String?,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frame: Bitmap?,
)

private const val PROBE_FRAME_MAX_EDGE = 640

/** 画廊/任务卡视频首帧缩略图的长边上限（round18）。 */
private const val THUMB_MAX_EDGE = 512

/** 从 content URI 读视频元信息与首帧（系统选择器来源）。size 取不到为 -1。 */
fun probeUri(ctx: Context, uri: Uri): VideoProbe {
    var name: String? = null
    var size = -1L
    try {
        ctx.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) -1L else c.getLong(1)
            }
        }
    } catch (_: Exception) {
    }
    val r = MediaMetadataRetriever()
    var durationMs = 0L
    var width = 0
    var height = 0
    var frame: Bitmap? = null
    try {
        r.setDataSource(ctx, uri)
        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            val t = width
            width = height
            height = t
        }
        frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it) }
    } catch (_: Exception) {
    } finally {
        try {
            r.release()
        } catch (_: Exception) {
        }
    }
    return VideoProbe(name, size, durationMs, width, height, frame)
}

/** 从文件路径读视频元信息与首帧（图库来源）。文件不存在返回空 probe。 */
fun probeFile(path: String): VideoProbe {
    val f = File(path)
    if (!f.isFile) return VideoProbe(f.name, -1L, 0, 0, 0, null)
    val r = MediaMetadataRetriever()
    var durationMs = 0L
    var width = 0
    var height = 0
    var frame: Bitmap? = null
    try {
        r.setDataSource(path)
        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            val t = width
            width = height
            height = t
        }
        frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it) }
    } catch (_: Exception) {
    } finally {
        try {
            r.release()
        } catch (_: Exception) {
        }
    }
    return VideoProbe(f.name, f.length(), durationMs, width, height, frame)
}

/** 首帧缩到长边 ≤ 640 再存；已经够小就原样返回。 */
private fun scaleDown(b: Bitmap, maxEdge: Int = PROBE_FRAME_MAX_EDGE): Bitmap {
    val long = maxOf(b.width, b.height)
    if (long <= 0 || long <= maxEdge) return b
    val scale = maxEdge.toFloat() / long
    return Bitmap.createScaledBitmap(
        b,
        (b.width * scale).toInt().coerceAtLeast(1),
        (b.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}
