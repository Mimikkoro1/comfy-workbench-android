package com.mie.kreaworkbench.data

import android.content.Context
import com.mie.kreaworkbench.data.db.GalleryDb
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.util.VideoThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // 淘汰策略走纯函数 evictionVictims（可单测）；删行一律 tombstoneAndDelete（round18 修复）：
        // 以前用 deleteImages 删行不留墓碑，needsWork 的「done > 图片数 + 墓碑数」永远不成立，
        // 任务被判为「还有产物没拿全」→ 每次冷启动都重新下载已经淘汰的文件，
        // 再把缓存顶回上限、再被淘汰，形成死循环，表现为「重启后整个 App 变慢」。
        while (true) {
            val victims = withContext(Dispatchers.IO) {
                evictionVictims(db.imagesAscending(), db.totalBytes(), limit)
            }
            if (victims.isEmpty()) break
            for (row in victims) {
                withContext(Dispatchers.IO) {
                    File(row.localPath).delete()
                    if (row.kind == "video") VideoThumb.thumbPath(row.localPath)?.delete()
                    db.tombstoneAndDelete(row)
                }
            }
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            val rows = db.images()
            // 收藏的行/文件一律保留（round34）：确认弹窗里向用户承诺了「已收藏的图片会保留」
            val keep = buildSet {
                for (row in rows.filter { it.favorite }) {
                    add(File(row.localPath).absolutePath)
                    if (row.kind == "video") VideoThumb.thumbPath(row.localPath)?.let { add(it.absolutePath) }
                }
            }
            rows.forEach { row ->
                if (row.favorite) return@forEach
                File(row.localPath).delete()
                if (row.kind == "video") VideoThumb.thumbPath(row.localPath)?.delete()
            }
            // 目录兜底清理同样要跳过收藏的文件（含视频缩略图）
            File(context.filesDir, "images").listFiles()?.forEach { f ->
                if (f.absolutePath in keep) return@forEach
                f.delete()
            }
            // 同样必须留墓碑（round18）：清空缓存后不能让引擎把删掉的行当成「没拿全的产物」再下回来；
            // 收藏的行不删，账目不变
            rows.filter { !it.favorite }.forEach { db.tombstoneAndDelete(it) }
        }
    }

    fun usedBytes(): Long = db.totalBytes()
}

/**
 * 缓存淘汰策略：在 [rows] 已按创建时间从旧到新排好的前提下，选出「删掉后总量能降到 [limit] 以下」
 * 所需的最少前缀。空列表 = 不用淘汰（含 limit<=0 = 不限量、或没有行可删）。
 * 收藏的行（round34）不参选：可能因此达不到上限，返回空让调用方收手。
 *
 * 抽成纯函数是为了让这条账能上单测：这里每选出一条，调用方都必须走 `tombstoneAndDelete`（带墓碑），
 * 否则引擎会把删掉的产物当成「没拿全」再下回来（round18 的「重启后 App 变慢」根因）。
 */
fun evictionVictims(rows: List<ImageRow>, totalBytes: Long, limit: Long): List<ImageRow> {
    if (limit <= 0) return emptyList()
    var total = totalBytes
    val victims = ArrayList<ImageRow>()
    for (row in rows) {
        if (total <= limit) break
        if (row.favorite) continue
        victims.add(row)
        total -= row.sizeBytes
    }
    return victims
}
