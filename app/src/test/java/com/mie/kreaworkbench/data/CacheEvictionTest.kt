package com.mie.kreaworkbench.data

import com.mie.kreaworkbench.data.db.ImageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存淘汰策略（round18）。
 *
 * 背景：needsWork 用「jobs.done > 图片行数 + 墓碑数」判断任务是否还有产物没拿全。
 * 淘汰/清缓存时如果只删行不留墓碑，这个差值永远为正 → 任务永远算「未完成」→
 * 每次冷启动都重新下载刚被淘汰的文件，再把缓存顶回上限、再被淘汰，
 * 表现为「重启之后整个 App 变慢」。所以淘汰策略必须精确、可验证。
 */
class CacheEvictionTest {

    private fun row(id: Long, sizeBytes: Long, createdAt: Long = id) = ImageRow(
        id = id,
        jobId = "job",
        clientJobId = "cq_job",
        idx = id.toInt(),
        mode = "custom",
        prompt = "",
        paramsJson = "",
        seed = 0,
        width = 0,
        height = 0,
        remoteFilename = "f$id.png",
        remoteSubfolder = "",
        localPath = "/tmp/f$id.png",
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        savedToAlbum = false,
        albumUri = "",
    )

    @Test
    fun `no eviction when total is within the limit`() {
        val rows = listOf(row(1, 100), row(2, 100))
        assertTrue(evictionVictims(rows, totalBytes = 200, limit = 200).isEmpty())
    }

    @Test
    fun `evicts oldest first and stops as soon as the total fits`() {
        val rows = listOf(row(1, 100), row(2, 100), row(3, 100))
        // 300 字节要降到 <= 100：删最旧两条就够，第三条不能动
        val victims = evictionVictims(rows, totalBytes = 300, limit = 100)
        assertEquals(listOf(1L, 2L), victims.map { it.id })
    }

    @Test
    fun `limit zero means unlimited`() {
        val rows = listOf(row(1, 100), row(2, 100))
        assertTrue(evictionVictims(rows, totalBytes = 200, limit = 0).isEmpty())
    }

    @Test
    fun `zero byte rows terminate instead of looping forever`() {
        // 0 字节行删了也不减总量：策略必须能在选完所有行后收敛，不能无限返回同一行
        val rows = listOf(row(1, 0), row(2, 0))
        val victims = evictionVictims(rows, totalBytes = 10, limit = 5)
        assertEquals(listOf(1L, 2L), victims.map { it.id })
        assertTrue(evictionVictims(rows, totalBytes = 0, limit = 5).isEmpty())
    }

    @Test
    fun `never selects more rows than needed even with a huge overflow`() {
        val rows = listOf(row(1, 50), row(2, 50), row(3, 50), row(4, 50))
        // 200 -> <=150：只删最旧一条
        assertEquals(listOf(1L), evictionVictims(rows, totalBytes = 200, limit = 150).map { it.id })
    }
}
