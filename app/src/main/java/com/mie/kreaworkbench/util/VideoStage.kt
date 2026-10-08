package com.mie.kreaworkbench.util

import com.mie.kreaworkbench.data.db.ImageRow
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * 视频暂存 / 选择辅助（round17）。只能 import java.io / kotlin（不碰 android.*），
 * 这样 videoExtFor / stageVideoFile / pickableVideos 能在纯 JVM 单测里跑。
 */

/** 视频大小上限：超过直接拒绝（1 GiB）。 */
const val VIDEO_MAX_BYTES: Long = 1024L * 1024 * 1024

/** 大小提示线：达到后上传界面给「较大可能较慢」提醒（200 MiB）。 */
const val VIDEO_WARN_BYTES: Long = 200L * 1024 * 1024

private val VIDEO_EXTS = setOf("mp4", "webm", "mov", "mkv", "gif", "avi")

/**
 * 暂存副本的扩展名：文件名里的合法扩展名优先，否则按 MIME 推，都没有用 mp4。
 */
fun videoExtFor(name: String?, mime: String?): String {
    val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.US)
    if (!ext.isNullOrBlank() && ext in VIDEO_EXTS) return ext
    return when (mime?.substringBefore(';')?.trim()?.lowercase(Locale.US)) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "image/gif" -> "gif"
        "video/x-msvideo" -> "avi"
        else -> "mp4"
    }
}

/**
 * 把视频源复制成暂存副本 `<dir>/<jobId>_video.<ext>`。图库 / 系统选择器两种来源统一走这里：
 * local_video 只允许指向副本，绝不能指向图库原文件（引擎上传成功 / 取消时会删除它）。
 * 源不存在抛 FileNotFoundException（调用方报「视频不存在」）；目标目录不存在会自动创建。
 */
fun stageVideoFile(src: File, dir: File, jobId: String, ext: String = "mp4"): File {
    if (!src.isFile) throw FileNotFoundException(src.absolutePath)
    dir.mkdirs()
    val out = File(dir, "${jobId}_video.$ext")
    src.copyTo(out, overwrite = true)
    return out
}

/**
 * 图库视频网格的数据源过滤（纯函数）：只要 localPath 非空的 video 行，保持原有顺序
 * （新的在前）。文件是否真的存在由调用方（VM）再查，不进纯函数。
 */
fun pickableVideos(rows: List<ImageRow>): List<ImageRow> =
    rows.filter { it.kind == "video" && it.localPath.isNotBlank() }

/** 人类可读的大小文案：1024 进制，>=1 单位留一位小数（整数不带）。空 / 负数返回空串。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val gb = bytes / (1024.0 * 1024 * 1024)
    val mb = bytes / (1024.0 * 1024)
    val kb = bytes / 1024.0
    return when {
        gb >= 1.0 -> unitText(gb) + " GB"
        mb >= 1.0 -> unitText(mb) + " MB"
        kb >= 1.0 -> unitText(kb) + " KB"
        else -> "${bytes}B"
    }
}

private fun unitText(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
