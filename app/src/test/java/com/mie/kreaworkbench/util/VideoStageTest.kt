package com.mie.kreaworkbench.util

import com.mie.kreaworkbench.data.db.ImageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** round17：视频暂存纯函数（不碰 android.*）。 */
class VideoStageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- 1. videoExtFor ----------

    @Test
    fun videoExtForCases() {
        assertEquals("mp4", videoExtFor("a.MP4", null))
        assertEquals("mov", videoExtFor("clip", "video/quicktime"))
        assertEquals("gif", videoExtFor(null, "image/gif"))
        assertEquals("mp4", videoExtFor("x.txt", "application/octet-stream"))
        // 名字里的合法扩展名优先于 MIME
        assertEquals("webm", videoExtFor("a.webm", "video/mp4"))
    }

    // ---------- 2. stageVideoFile 正常复制 ----------

    @Test
    fun stageVideoFileCopies() {
        val src = tmp.newFile("src.mp4")
        src.writeBytes(byteArrayOf(1, 2, 3))
        val dir = File(tmp.root, "uploads/nested")
        val out = stageVideoFile(src, dir, "job-1", "mp4")
        assertEquals("job-1_video.mp4", out.name)
        assertEquals(dir.absolutePath, out.parentFile!!.absolutePath)
        assertTrue("目标目录自动创建", dir.isDirectory)
        assertTrue("内容一致", out.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue("源文件还在", src.isFile)
        assertTrue("源内容没变", src.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    // ---------- 3. stageVideoFile 源不存在 ----------

    @Test
    fun stageVideoFileMissingSource() {
        val missing = File(tmp.root, "gone.mp4")
        val dir = tmp.newFolder("uploads")
        try {
            stageVideoFile(missing, dir, "job-2", "mp4")
            throw AssertionError("应当抛 FileNotFoundException")
        } catch (e: FileNotFoundException) {
            // 预期路径；不留半截文件
            assertEquals(0, dir.listFiles()?.size ?: 0)
        }
    }

    // ---------- 4. pickableVideos ----------

    @Test
    fun pickableVideosFilters() {
        val rows = listOf(
            row(1, kind = "image", path = "/x/a.png"),
            row(2, kind = "video", path = "/x/v1.mp4"),
            row(3, kind = "text", path = ""),
            row(4, kind = "video", path = ""),
            row(5, kind = "video", path = "/x/v2.webm"),
        )
        val picked = pickableVideos(rows)
        assertEquals(listOf(2L, 5L), picked.map { it.id })
    }

    private fun row(id: Long, kind: String, path: String) = ImageRow(
        id = id,
        jobId = "j$id",
        clientJobId = "cj$id",
        idx = 0,
        mode = "custom",
        prompt = "",
        paramsJson = "",
        seed = 0,
        width = 0,
        height = 0,
        remoteFilename = "",
        remoteSubfolder = "",
        localPath = path,
        sizeBytes = 0,
        createdAt = id,
        savedToAlbum = false,
        albumUri = "",
        kind = kind,
    )
}
