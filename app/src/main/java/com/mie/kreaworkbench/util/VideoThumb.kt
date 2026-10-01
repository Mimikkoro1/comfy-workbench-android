package com.mie.kreaworkbench.util

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 视频缩略图/时长（round6）：首帧取一次后缓存成 `<视频路径>.jpg`，画廊和任务卡直接读文件。
 */
object VideoThumb {

    /** 取首帧缓存文件；失败返回 null（UI 显示占位图标）。 */
    fun thumbFile(videoPath: String): File? {
        val video = File(videoPath)
        if (!video.isFile) return null
        val thumb = File(video.parentFile, video.name + ".jpg")
        if (thumb.isFile && thumb.length() > 0 && thumb.lastModified() >= video.lastModified()) return thumb
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                thumb.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                thumb
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
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
