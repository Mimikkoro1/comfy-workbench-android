package com.mie.kreaworkbench.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 删除路径的账目 + 并发守卫（round18 建立，round19 扩写）。
 *
 * 三个症状都出自「删图片行」这条路径：
 *  - 不留墓碑地删行 → needsWork 的 `done > 图片数 + 墓碑数` 永远为真 →
 *    引擎把删掉的产物当成没拿全，每次冷启动重下（「重启后变慢」）；
 *  - 一批删除里每行 bump 一次 revision → 每个观测者各跑一次全表 SELECT →
 *    同一连接上多个游标并发填 CursorWindow → `Couldn't read row N, col 0 from CursorWindow` 闪退；
 *  - round18 只把「读」串行化了，漏了「写」，而且把 bump 提前到删第 1 张时——
 *    于是 bump 唤起的全表读与剩余几行的写事务在同一连接上并发，真机**依然 100% 闪退**
 *    （round19 实测堆栈：`toImage(GalleryDb.kt:436)` ← `images()` ← `CustomModel.reload`）。
 *
 * 这里用源码级守卫把这几条锁死：不需要 Android 运行时，也不会因为重构而悄悄失效。
 */
class DeleteAccountingGuardTest {

    /**
     * 测试 JVM 的工作目录是 app/（Gradle 默认），所以先按 app/ 找，再回退到仓库根。
     */
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("..", relative), File("../..", relative))
        val f = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到源文件，试过：${candidates.joinToString { it.absolutePath }}",
            f != null,
        )
        return f!!.readText()
    }

    private fun galleryDb() = source("app/src/main/java/com/mie/kreaworkbench/data/db/GalleryDb.kt")

    @Test
    fun `no tombstone-less image deletion entry exists`() {
        val db = galleryDb()
        assertFalse(
            "GalleryDb 不应再有「删行不留墓碑」的入口：删 row 必须走 tombstoneAndDelete",
            db.contains("fun deleteImages(") || db.contains("fun deleteAllImages("),
        )
    }

    @Test
    fun `cache eviction and clear go through tombstoneAndDelete`() {
        val cache = source("app/src/main/java/com/mie/kreaworkbench/data/CacheManager.kt")
        assertFalse(
            "CacheManager 不能直接删 images 行（会破坏 needsWork 的账）",
            cache.contains("deleteImages(") || cache.contains("deleteAllImages("),
        )
        assertTrue(
            "淘汰与清缓存都必须落墓碑",
            cache.contains("db.tombstoneAndDelete(row)") && cache.contains("db.tombstoneAndDelete(it)"),
        )
    }

    @Test
    fun `decode-failure path records a tombstone`() {
        val engine = source("app/src/main/java/com/mie/kreaworkbench/service/GenerationEngine.kt")
        // saveImages 里解不出像素时：删文件 + 补墓碑，否则该任务永远算「还缺一张」
        val bad = engine.indexOf("outW <= 0 || outH <= 0")
        assertTrue("saveImages 的坏文件护栏应仍在", bad > 0)
        val window = engine.substring(bad, (bad + 900).coerceAtMost(engine.length))
        assertTrue(
            "坏文件护栏里必须补墓碑（addTombstone），否则 needsWork 永远为真",
            window.contains("addTombstone("),
        )
    }

    /**
     * round19 的正面修复之一：写操作必须和读共享同一把锁。
     *
     * 判据：`readableDatabase` / `writableDatabase` 这两个取连接的调用**只允许**出现在
     * `read` / `write` 两个加锁入口里，其余一律通过入口访问。这样「以后新增一个查询忘了上锁」
     * 会立刻被这条守卫抓住。
     */
    @Test
    fun `every database access goes through the serialised helpers`() {
        val db = galleryDb()
        val hits = Regex(".*(readableDatabase|writableDatabase).*")
            .findAll(db)
            .map { it.value.trim() }
            .toList()
        assertEquals(
            "readableDatabase/writableDatabase 只允许出现在 read/write 两个入口里，当前命中：$hits",
            2,
            hits.size,
        )
        assertTrue(
            "两个入口都必须自己上 dbLock：读读、读写、写写全部串行（round19）",
            hits.all { it.contains("synchronized(dbLock)") },
        )
    }

    /**
     * round19 的正面修复之二：开 WAL。
     *
     * 非 WAL 时连接池只有一条连接，读写共用连接、CursorWindow 按连接共享——这正是崩溃的土壤。
     * 必须在第一次 `getWritableDatabase()` 之前调用，所以放在 `init` 里。
     */
    @Test
    fun `write ahead logging is enabled before the database opens`() {
        val db = galleryDb()
        assertTrue(
            "必须开 WAL（治本）：不开的话读写共用一条连接，游标窗口会被并发的写操作顶掉",
            db.contains("setWriteAheadLoggingEnabled(true)"),
        )
        val initAt = db.indexOf("setWriteAheadLoggingEnabled(true)")
        val firstUse = db.indexOf("readableDatabase)")
        assertTrue(
            "开 WAL 必须在第一次取连接之前（init 里），否则 SQLiteOpenHelper 会直接抛异常",
            initAt in 1 until firstUse,
        )
    }

    /**
     * round19 的正面修复之三：批量删除整批删完才 bump。
     *
     * round18 的 `bumpRevision = i == 0` 让「全表读」在「剩余写操作」之前启动，
     * 这是真机 100% 闪退的直接时序来源。
     */
    @Test
    fun `batch delete bumps revision only after the whole loop`() {
        val album = source("app/src/main/java/com/mie/kreaworkbench/util/Album.kt")
        assertTrue("persistDelete 应保留按批 bump 的开关", album.contains("bumpRevision: Boolean = true"))
        val gallery = source("app/src/main/java/com/mie/kreaworkbench/ui/screens/gallery/GalleryScreen.kt")
        assertFalse(
            "不许再出现「删第 1 张就 bump」：那会把全表读叫起来跟剩余写操作撞车",
            gallery.contains("bumpRevision = i == 0"),
        )
        assertTrue(
            "批量删除应逐行 bumpRevision = false，整批落地后再统一 bump 一次",
            gallery.contains("persistDelete(app, row, bumpRevision = false)"),
        )
    }

    /**
     * round19b：「同时删除服务器上的文件」是历史遗留。
     *
     * 大改版直连 ComfyUI 之后，手机端已经没有删除远端资源的能力
     * （`ComfyApi.deleteRemoteImage` 一直是个空实现，round19b 已把它删掉）。
     * 删除弹窗只做一次确认 + 说明本次删几个文件，不许再冒出服务器相关的勾选框。
     */
    @Test
    fun `delete dialog no longer asks about server files`() {
        val gallery = source("app/src/main/java/com/mie/kreaworkbench/ui/screens/gallery/GalleryScreen.kt")
        val viewer = source("app/src/main/java/com/mie/kreaworkbench/ui/screens/viewer/ViewerScreen.kt")
        assertFalse("图库删除弹窗不该再出现服务器选项", gallery.contains("gallery_delete_server"))
        assertFalse("查看页删除弹窗不该再出现服务器选项", viewer.contains("gallery_delete_server"))
        assertFalse("图库不该再有 alsoServer 状态/参数", gallery.contains("alsoServer"))
        assertFalse("查看页不该再有 alsoServer 状态/参数", viewer.contains("alsoServer"))
        val album = source("app/src/main/java/com/mie/kreaworkbench/util/Album.kt")
        assertFalse("persistDelete 不该再调远端删除", album.contains("deleteRemoteImage("))
        val api = source("app/src/main/java/com/mie/kreaworkbench/data/comfy/ComfyApi.kt")
        assertFalse("空实现的 deleteRemoteImage 应已删除", api.contains("fun deleteRemoteImage"))
        assertTrue(
            "弹窗要说清本次删除几个文件（带上选中数量）",
            gallery.contains("stringResource(R.string.gallery_delete_body, selected.size)"),
        )
    }

    /**
     * round19 的正面修复之四：收集器兜底。
     *
     * `viewModelScope` 没有 CoroutineExceptionHandler——上一轮真机崩溃就发生在
     * `revision.collect { reload() }` 这条路径上，异常直接终结进程（不是「图库崩」，是整个 App 死）。
     */
    @Test
    fun `revision collectors swallow their own failures`() {
        val gallery = source("app/src/main/java/com/mie/kreaworkbench/ui/screens/gallery/GalleryScreen.kt")
        val custom = source("app/src/main/java/com/mie/kreaworkbench/ui/screens/custom/CustomModel.kt")
        assertTrue(
            "画廊的 revision 收集器必须走兜底版 reload",
            gallery.contains("c.engine.revision.collect { safeReload() }"),
        )
        assertTrue(
            "定制页的 revision 收集器必须走兜底版 reload",
            custom.contains("c.engine.revision.collect { safeReload() }"),
        )
    }

    @Test
    fun `viewer deletion keeps tombstones`() {
        val viewer = source("app/src/main/java/com/mie/kreaworkbench/ui/screens/viewer/ViewerScreen.kt")
        assertTrue("查看页删除也必须走 persistDelete", viewer.contains("persistDelete("))
    }
}
