package com.mie.kreaworkbench.data.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r44：custom 收图优先 SaveImage——有 output 产物时丢弃 temp 预览（Moss 两段采样的坑）。 */
class OutputParseSaveFirstTest {
    private fun img(name: String, type: String) =
        JSONObject().put("filename", name).put("subfolder", "").put("type", type)

    private fun outputs(vararg nodes: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject()
        for ((k, v) in nodes) o.put(k, v)
        return o
    }

    private fun names(entries: List<OutputEntry>) = entries.map { it.filename }

    @Test
    fun tempPreviewsDroppedWhenOutputExists() {
        // Moss 结构：650/651 是两段 PreviewImage（temp），732 是 SaveImage（output），737 是 rgthree 对比（temp）
        val outputs = outputs(
            "650" to JSONObject().put("images", JSONArray().put(img("preview1.png", "temp"))),
            "651" to JSONObject().put("images", JSONArray().put(img("preview2.png", "temp"))),
            "732" to JSONObject().put("images", JSONArray().put(img("final.png", "output"))),
            "737" to JSONObject().put("images", JSONArray().put(img("compare.png", "temp"))),
        )
        val (entries, _) = parseOutputEntries(outputs)
        assertEquals(listOf("final.png"), names(entries))
        assertEquals("output", entries[0].type)
    }

    @Test
    fun onlyTempStillAllKept() {
        val outputs = outputs(
            "650" to JSONObject().put("images", JSONArray().put(img("preview1.png", "temp"))),
            "651" to JSONObject().put("images", JSONArray().put(img("preview2.png", "temp"))),
        )
        val (entries, _) = parseOutputEntries(outputs)
        assertEquals(2, entries.size)
        assertEquals(listOf("preview1.png", "preview2.png"), names(entries))
    }

    @Test
    fun multipleOutputNodesAllKept() {
        val outputs = outputs(
            "732" to JSONObject().put("images", JSONArray().put(img("a.png", "output"))),
            "900" to JSONObject().put("images", JSONArray().put(img("b.png", "output"))),
        )
        val (entries, _) = parseOutputEntries(outputs)
        assertEquals(listOf("a.png", "b.png"), names(entries))
    }

    @Test
    fun missingTypeDefaultsToOutput() {
        val outputs = outputs(
            "732" to JSONObject().put("images", JSONArray().put(JSONObject().put("filename", "a.png"))),
            "650" to JSONObject().put("images", JSONArray().put(img("preview.png", "temp"))),
        )
        val (entries, _) = parseOutputEntries(outputs)
        assertEquals(listOf("a.png"), names(entries))
    }

    @Test
    fun selectedNodeSortsFirst() {
        val outputs = outputs(
            "732" to JSONObject().put("images", JSONArray().put(img("a.png", "output"))),
            "900" to JSONObject().put("images", JSONArray().put(img("b.png", "output"))),
            "901" to JSONObject().put("images", JSONArray().put(img("c.png", "output"))),
        )
        val (entries, _) = parseOutputEntries(outputs, "901")
        assertEquals(listOf("c.png", "a.png", "b.png"), names(entries))
    }

    @Test
    fun videosInGifsKeptPreviewDropped() {
        val video = JSONObject()
            .put("filename", "out_00001.mp4")
            .put("subfolder", "")
            .put("type", "output")
            .put("format", "video/h264")
        val outputs = outputs(
            "100" to JSONObject().put("gifs", JSONArray().put(video)),
            "101" to JSONObject().put("images", JSONArray().put(img("preview.png", "temp"))),
        )
        val (entries, _) = parseOutputEntries(outputs)
        assertEquals(1, entries.size)
        assertTrue(entries[0].video)
        assertEquals("out_00001.mp4", entries[0].filename)
    }

    @Test
    fun textOutputsUntouched() {
        val outputs = outputs(
            "732" to JSONObject().put("images", JSONArray().put(img("a.png", "output"))),
            "200" to JSONObject().put("text", "hello world"),
            "650" to JSONObject().put("images", JSONArray().put(img("preview.png", "temp"))),
        )
        val (entries, texts) = parseOutputEntries(outputs)
        assertEquals(listOf("a.png"), names(entries))
        assertEquals(listOf("hello world"), texts)
    }
}
