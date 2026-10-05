package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.1 文件判型、§4.13 combo 升级、§4.14 buildDefinition、§3.8 定义文件。 */
class ClassifyAndDefinitionTest {

    // ---------- §4.1 按内容判型 ----------

    @Test
    fun classifyByContent() {
        assertEquals(
            "空 UI 工作流",
            FileKind.UI_WORKFLOW,
            classifyFile("a.json", """{"nodes":[],"links":[]}""").first,
        )
        // 即使同时有 API 节点也仍是 UI_WORKFLOW（判定顺序在 API 之前）
        assertEquals(
            "UI 优先于 API",
            FileKind.UI_WORKFLOW,
            classifyFile("a.json", """{"nodes":[],"links":[],"1":{"class_type":"SaveImage","inputs":{}}}""").first,
        )
        assertEquals("非工作流", FileKind.UNKNOWN, classifyFile("a.json", """{"a":1}""").first)
        assertEquals("定义", FileKind.DEFINITION, classifyFile("a.json", """{"user_facing_inputs":[]}""").first)
    }

    // ---------- §4.13 upgradeCombos ----------

    @Test
    fun upgradeCombos() {
        val wf = JSONObject(
            """{"1":{"class_type":"KSampler","inputs":{"sampler_name":"euler","denoise":1}}}""",
        )
        val objectInfo = JSONObject(
            """{"KSampler":{"input":{"required":{"sampler_name":[["euler","dpmpp_2m"]]}}}}""",
        )
        val m = specMap(upgradeCombos(inferSpecs(wf), objectInfo))
        val sampler = m.getValue("1|sampler_name").spec
        assertEquals("type", "select", sampler.optString("type"))
        assertEquals("kwb_combo", "KSampler|sampler_name", sampler.optString("kwb_combo"))
        assertEquals("置信度不变", "low", sampler.optString("confidence"))
        assertFalse("默认勾选不变", sampler.optBoolean("enabled"))
        // denoise 是 int 不是 text，不升级
        val denoise = m.getValue("1|denoise").spec
        assertEquals("int 不升级", "int", denoise.optString("type"))
        assertFalse("无 kwb_combo", denoise.has("kwb_combo"))
        // objectInfo = null（网络不通等）→ 原样
        val untouched = specMap(upgradeCombos(inferSpecs(wf), null))
        assertEquals("text 原样", "text", untouched.getValue("1|sampler_name").spec.optString("type"))
        assertFalse("无 kwb_combo", untouched.getValue("1|sampler_name").spec.has("kwb_combo"))
    }

    // ---------- §4.14 buildDefinition ----------

    @Test
    fun buildDefinitionStripsRuntimeAndVideoHelp() {
        val sizesSpec = JSONObject()
            .put("node_id", "9").put("field", "width").put("type", "image_sizes")
            .put("class_type", "EmptyLatentImage").put("confidence", "high")
            .put("current_value", 1152).put("node_title", "尺寸").put("enabled", true)
            .put("_merge_key", "9").put("label", "输出尺寸")
            .put("default", "1152x1728").put("help", HELP_OUTPUT_SIZE)
        val textSpec = JSONObject()
            .put("node_id", "7").put("field", "text").put("type", "prompt_pool")
            .put("class_type", "CLIPTextEncode").put("confidence", "medium")
            .put("current_value", "x").put("node_title", "提示词").put("enabled", true)
            .put("label", "提示词").put("default", "x")
        val def = buildDefinition("名字", "wf.json", "16", listOf(sizesSpec, textSpec), "video")
        assertEquals("workflow_file", "wf.json", def.optString("workflow_file"))
        assertEquals("display_name", "名字", def.optString("display_name"))
        assertEquals("category", "video", def.optString("category"))
        assertEquals("output_kind", "video", def.optString("output_kind"))
        assertEquals("kwb_output", "video", def.optString("kwb_output"))
        assertEquals("kwb_output_node 原样", "16", def.optString("kwb_output_node"))
        val specs = def.optJSONArray("user_facing_inputs")!!
        assertEquals("spec 数", 2, specs.length())
        val sizes = specs.optJSONObject(0)!!
        assertEquals("field 强制 sizes", "sizes", sizes.optString("field"))
        assertTrue("allow_custom", sizes.optBoolean("allow_custom"))
        assertEquals("视频尺寸说明", HELP_OUTPUT_SIZE_VIDEO, sizes.optString("help"))
        for (key in listOf("class_type", "confidence", "current_value", "node_title", "enabled", "_merge_key")) {
            assertFalse("sizes 剥离 $key", sizes.has(key))
            assertFalse("text 剥离 $key", specs.optJSONObject(1)!!.has(key))
        }
        // 图片 kind 保留图片尺寸说明
        val defImage = buildDefinition(
            "n", "wf.json", "16",
            listOf(JSONObject(sizesSpec.toString())), "image",
        )
        assertEquals(
            "图片尺寸说明不变",
            HELP_OUTPUT_SIZE,
            defImage.optJSONArray("user_facing_inputs")!!.optJSONObject(0)!!.optString("help"),
        )
    }

    // ---------- §3.8 定义文件 ----------

    @Test
    fun definitionFixturePresets() {
        val text = FixtureLoader.loadText("kr2turbo_t2i.definition.json")
        val (kind, json) = classifyFile("kr2turbo_t2i.definition.json", text)
        assertEquals(FileKind.DEFINITION, kind)
        val arr = json.optJSONArray("user_facing_inputs")!!
        val sizes = (0 until arr.length())
            .map { arr.optJSONObject(it)!! }
            .first { it.optString("type") == "image_sizes" }
        val presets = Specs.sizePresets(sizes)
        assertEquals("预设 11 项（r14fix2）", 11, presets.size)
        assertEquals("第一项", "1152x1728", presets[0].second)
        assertTrue("含 9:16 竖屏", presets.any { it.second == "1088x1920" })
        assertTrue("含竖屏高清", presets.any { it.second == "1440x2560" })
        assertEquals("defaultSizes", listOf("1152x1728"), Specs.defaultSizes(sizes))
    }
}
