package com.mie.kreaworkbench.util

import android.content.Context
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

fun prepareUploadJpeg(context: Context, uri: Uri): File {
    val raw = File(context.cacheDir, "upload_src.bin")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { context.str(R.string.err_cannot_read_image) }
        FileOutputStream(raw).use { input.copyTo(it) }
    }
    return prepareUploadJpeg(context, raw)
}

fun prepareUploadJpeg(context: Context, source: File): File {
    val exif = ExifInterface(source)
    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error(context.str(R.string.err_decode))
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    val bmp = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error(context.str(R.string.err_decode))
    val oriented = applyExif(bmp, orientation)
    val cropped = fit16(oriented)
    val out = File(context.cacheDir, "upload.jpg")
    var quality = 90
    while (quality >= 40) {
        FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (out.length() <= 2L * 1024L * 1024L) break
        quality -= 5
    }
    if (oriented !== bmp) bmp.recycle()
    if (cropped !== oriented) oriented.recycle()
    cropped.recycle()
    return out
}

fun decodeBounds(path: String): Pair<Int, Int> {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    return o.outWidth to o.outHeight
}

private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.postRotate(90f)
            m.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.postRotate(270f)
            m.preScale(-1f, 1f)
        }
        else -> return src
    }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

private fun fit16(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val longEdge = max(w, h)
    val targetLong = (minOf(longEdge, 2048) / 16) * 16
    val scale = targetLong.toFloat() / longEdge.toFloat()
    var tw = ((w * scale).roundToInt() / 16) * 16
    var th = ((h * scale).roundToInt() / 16) * 16
    tw = tw.coerceAtLeast(16)
    th = th.coerceAtLeast(16)
    val cover = maxOf(tw.toFloat() / w, th.toFloat() / h)
    val sw = max(1, (w * cover).roundToInt())
    val sh = max(1, (h * cover).roundToInt())
    val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
    val x = ((sw - tw) / 2).coerceAtLeast(0)
    val y = ((sh - th) / 2).coerceAtLeast(0)
    val cw = tw.coerceAtMost(sw - x)
    val ch = th.coerceAtMost(sh - y)
    return Bitmap.createBitmap(scaled, x, y, cw, ch)
}
