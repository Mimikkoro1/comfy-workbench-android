package com.mie.kreaworkbench.data

import android.content.Context
import com.mie.kreaworkbench.data.db.GalleryDb
import com.mie.kreaworkbench.data.settings.SettingsStore
import java.io.File

class CacheManager(
    private val context: Context,
    private val db: GalleryDb,
    private val settings: SettingsStore,
) {
    fun imagesDir(): File = File(context.filesDir, "images").also { it.mkdirs() }

    suspend fun enforce() {
        val gb = settings.current().cacheLimitGb
        if (gb <= 0) return
        val limit = gb.toLong() * 1024L * 1024L * 1024L
        while (db.totalBytes() > limit) {
            val oldest = db.oldest() ?: break
            val file = File(oldest.localPath)
            if (file.exists()) file.delete()
            db.deleteImages(listOf(oldest.id))
        }
    }

    fun clearAll() {
        db.images().forEach { row ->
            val file = File(row.localPath)
            if (file.exists()) file.delete()
        }
        File(context.filesDir, "images").listFiles()?.forEach { it.delete() }
        db.deleteAllImages()
    }

    fun usedBytes(): Long = db.totalBytes()
}
