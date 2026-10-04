package com.mie.kreaworkbench.util

import android.webkit.MimeTypeMap

/** 按响应 Content-Type 推扩展名；拿不到返回 null（调用方再按文件名后缀/默认值兜底）。 */
fun extFromContentType(ct: String?): String? = when (ct?.substringBefore(';')?.trim()?.lowercase()) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/bmp" -> "bmp"
    "image/avif" -> "avif"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    "video/x-matroska" -> "mkv"
    else -> null
}

/** 入相册用的图片 MIME；未知扩展名走 MimeTypeMap，再不行按 png。 */
fun mimeForImage(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    "heic" -> "image/heic"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "image/png"
}

/** 入相册用的视频 MIME；未知扩展名走 MimeTypeMap，再不行按 mp4。 */
fun mimeForVideo(ext: String): String = when (ext.lowercase()) {
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    "avi" -> "video/x-msvideo"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "video/mp4"
}
