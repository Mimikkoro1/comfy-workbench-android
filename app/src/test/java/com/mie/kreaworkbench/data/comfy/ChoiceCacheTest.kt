package com.mie.kreaworkbench.data.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** r42：参数页下拉选项本地缓存的纯逻辑 + 文件读写。 */
class ChoiceCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- serverKey ----

    @Test
    fun serverKeyNormalizesWritingVariants() {
        val a = serverKey("http://10.0.0.2:8188")
        assertEquals(a, serverKey("http://10.0.0.2:8188/"))
        assertEquals(a, serverKey("HTTP://10.0.0.2:8188"))
        assertEquals(a, serverKey("10.0.0.2:8188"))
        assertEquals(16, a.length)
    }

    @Test
    fun serverKeySeparatesServers() {
        val base = serverKey("http://10.0.0.2:8188")
        assertFalse(base == serverKey("http://10.0.0.2:8189"))
        assertFalse(base == serverKey("http://10.0.0.3:8188"))
    }

    // ---- encode / decode ----

    @Test
    fun encodeDecodeRoundTrip() {
        val entries = mapOf(
            "UNETLoader|unet_name" to listOf("a.safetensors", "b.safetensors"),
            "KSampler|sampler_name" to listOf("euler", "res_multistep"),
        )
        val decoded = decodeEntries(encodeEntries(entries, 1234L))
        assertEquals(entries, decoded)
    }

    @Test
    fun emptyListsNotWritten() {
        val entries = mapOf(
            "k1" to listOf("v"),
            "kEmpty" to emptyList<String>(),
        )
        val decoded = decodeEntries(encodeEntries(entries, 0L))!!
        assertTrue(decoded.containsKey("k1"))
        assertFalse(decoded.containsKey("kEmpty"))
    }

    @Test
    fun longListTruncatedToLimit() {
        val big = (0 until 6000).map { "m$it" }
        val decoded = decodeEntries(encodeEntries(mapOf("k" to big), 0L))!!
        assertEquals(5000, decoded["k"]!!.size)
        assertEquals("m0", decoded["k"]!!.first())
    }

    @Test
    fun decodeRejectsGarbageAndWrongStructure() {
        assertNull(decodeEntries("not json at all"))
        assertNull(decodeEntries(""))
        assertNull(decodeEntries("{}"))
        assertNull(decodeEntries("{\"v\":2,\"entries\":{\"k\":[\"a\"]}}"))
        assertNull(decodeEntries("{\"v\":1,\"entries\":[\"not\",\"an\",\"object\"]}"))
        // entries 缺失
        assertNull(decodeEntries("{\"v\":1}"))
    }

    @Test
    fun decodeSkipsNonStringElements() {
        val decoded = decodeEntries(
            """
            {"v":1,"ts":0,"entries":{"k":["a",42,null,"b",{"x":1}],"empty":[],"onlyBad":[7]}}
            """.trimIndent(),
        )!!
        assertEquals(listOf("a", "b"), decoded["k"])
        assertFalse(decoded.containsKey("empty"))
        assertFalse(decoded.containsKey("onlyBad"))
    }

    // ---- merge ----

    @Test
    fun mergeOverridesAndKeeps() {
        val old = mapOf("k1" to listOf("old1"), "k2" to listOf("old2"), "k3" to listOf("old3"))
        val fresh = mapOf("k1" to listOf("new1"), "k2" to emptyList<String>())
        val merged = mergeEntries(old, fresh)
        assertEquals(listOf("new1"), merged["k1"]) // 新值覆盖
        assertEquals(listOf("old2"), merged["k2"]) // fresh 空列表不覆盖
        assertEquals(listOf("old3"), merged["k3"]) // 旧有、新无的 key 保留
    }

    @Test
    fun mergeIntoEmptyOld() {
        val merged = mergeEntries(emptyMap(), mapOf("k" to listOf("v")))
        assertEquals(mapOf("k" to listOf("v")), merged)
    }

    // ---- 文件级 ----

    @Test
    fun writeThenReadBack() {
        val dir = tmp.newFolder("objinfo")
        val url = "http://10.0.0.2:8188"
        val entries = mapOf("UNETLoader|unet_name" to listOf("a.safetensors"))
        writeCache(dir, url, entries)
        assertEquals(entries, readCache(dir, url))
        assertNotNull(File(dir, "${serverKey(url)}.json"))
    }

    @Test
    fun corruptedFileReadsAsEmptyAndDeleted() {
        val dir = tmp.newFolder("objinfo")
        val url = "http://10.0.0.2:8188"
        val f = File(dir, "${serverKey(url)}.json")
        f.writeText("\u00ff\u00fe garbage \u0000 not json")
        assertTrue(readCache(dir, url).isEmpty())
        // 损坏文件被顺手删掉
        assertFalse(f.exists())
        // 空串内容同样当没有缓存，不抛
        f.writeText("")
        assertTrue(readCache(dir, url).isEmpty())
    }

    @Test
    fun emptyFreshDoesNotTouchCache() {
        val dir = tmp.newFolder("objinfo")
        val url = "http://10.0.0.2:8188"
        writeCache(dir, url, mapOf("k" to listOf("v")))
        // 全空的 fresh 不覆盖
        writeCache(dir, url, mapOf("k" to emptyList<String>()))
        assertEquals(mapOf("k" to listOf("v")), readCache(dir, url))
    }

    @Test
    fun prunesOldestServerBeyondLimit() {
        val dir = tmp.newFolder("objinfo")
        val urls = (0 until 9).map { "http://10.0.0.$it:8188" }
        for (u in urls) {
            writeCache(dir, u, mapOf("k" to listOf("v")))
            // 保证 lastModified 有区分度（文件系统时间戳精度可能是秒/毫秒）
            File(dir, "${serverKey(u)}.json").setLastModified(1_700_000_000_000L + urls.indexOf(u) * 1_000L)
        }
        writeCache(dir, "http://10.9.9.9:8188", mapOf("k" to listOf("v")))
        val remaining = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.map { it.name }.toSet()
        assertEquals(8, remaining.size)
        // 最旧的 10.0.0.0 被删
        assertFalse(remaining.contains("${serverKey(urls[0])}.json"))
        assertTrue(remaining.contains("${serverKey("http://10.9.9.9:8188")}.json"))
    }

    @Test
    fun differentServersDoNotSeeEachOthersCache() {
        val dir = tmp.newFolder("objinfo")
        writeCache(dir, "http://10.0.0.2:8188", mapOf("k" to listOf("serverA")))
        writeCache(dir, "http://10.0.0.3:8188", mapOf("k" to listOf("serverB")))
        assertEquals(mapOf("k" to listOf("serverA")), readCache(dir, "http://10.0.0.2:8188/"))
        assertEquals(mapOf("k" to listOf("serverB")), readCache(dir, "HTTP://10.0.0.3:8188"))
        // 没缓存的机器读到空
        assertTrue(readCache(dir, "http://10.0.0.4:8188").isEmpty())
    }
}
