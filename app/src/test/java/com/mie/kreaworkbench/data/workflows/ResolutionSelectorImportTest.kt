package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r44 复现单测：Moss 两段采样工作流的 ResolutionSelector 导入后，
 * aspect_ratio 必须以 select 下拉出现在参数页（带 kwb_combo），当前值不丢。
 * 夹具只保留 850（ResolutionSelector）/ 698（EmptyLatentImage）两个节点。
 */
class ResolutionSelectorImportTest {
    private val wf = FixtureLoader.loadWf("resolution_selector.workflow.json")

    private fun aspectSpec(candidates: List<Candidate>): JSONObject =
        candidates.map { it.spec }.firstOrNull {
            Specs.nodeId(it) == "850" && Specs.field(it) == "aspect_ratio"
        } ?: error("no aspect_ratio candidate in ${candidates.map { Specs.key(it.spec) }}")

    /** 老 combo 格式：required.aspect_ratio[0] 是字符串数组。 */
    private fun objectInfoOld(): JSONObject = JSONObject(
        """
        {"ResolutionSelector": {"input": {"required": {
            "aspect_ratio": [["1:1 (Square)", "2:3 (Portrait Photo)", "3:2 (Landscape Photo)", "16:9 (Widescreen)", "9:16 (Mobile Portrait)"]],
            "megapixels": ["FLOAT", {"default": 1.5, "min": 0.5, "max": 4.0, "step": 0.1}],
            "multiple": ["INT", {"default": 32, "min": 16, "max": 64, "step": 16}]
        }}}}
        """.trimIndent(),
    )

    /** 新 combo 格式（新版 ComfyUI schema）：["COMBO", {"options": [...]}]。 */
    private fun objectInfoNew(): JSONObject = JSONObject(
        """
        {"ResolutionSelector": {"input": {"required": {
            "aspect_ratio": ["COMBO", {"options": ["1:1 (Square)", "2:3 (Portrait Photo)", "3:2 (Landscape Photo)", "16:9 (Widescreen)", "9:16 (Mobile Portrait)"]}],
            "megapixels": ["FLOAT", {"default": 1.5, "min": 0.5, "max": 4.0, "step": 0.1}],
            "multiple": ["INT", {"default": 32, "min": 16, "max": 64, "step": 16}]
        }}}}
        """.trimIndent(),
    )

    @Test
    fun oldComboFormatUpgradesToSelect() {
        val upgraded = upgradeCombos(inferSpecs(wf), objectInfoOld())
        val spec = aspectSpec(upgraded)
        assertEquals("select", spec.optString("type"))
        assertEquals("ResolutionSelector|aspect_ratio", spec.optString("kwb_combo"))
        assertEquals("2:3 (Portrait Photo)", spec.opt("current_value"))
    }

    @Test
    fun newComboFormatUpgradesToSelect() {
        val upgraded = upgradeCombos(inferSpecs(wf), objectInfoNew())
        val spec = aspectSpec(upgraded)
        assertEquals("select", spec.optString("type"))
        assertEquals("ResolutionSelector|aspect_ratio", spec.optString("kwb_combo"))
        assertEquals("2:3 (Portrait Photo)", spec.opt("current_value"))
    }

    @Test
    fun offlineImportKeepsTheRowWithBuiltinChoices() {
        // object_info 拉不到（离线导入）：这一行不能消失——退回 select + 内置选项兜底，
        // 当前值必须在内置列表里；在线时 kwb_combo 的真实枚举优先
        val upgraded = upgradeCombos(inferSpecs(wf), null)
        val spec = aspectSpec(upgraded)
        assertEquals("select", spec.optString("type"))
        assertEquals("ResolutionSelector|aspect_ratio", spec.optString("kwb_combo"))
        assertEquals("2:3 (Portrait Photo)", spec.opt("current_value"))
        val choices = Specs.choices(spec).map { it.second }
        assertTrue("builtin choices must be non-empty", choices.isNotEmpty())
        assertTrue("current value must be among choices", "2:3 (Portrait Photo)" in choices)
    }

    @Test
    fun megapixelsAndMultipleUnchanged() {
        val upgraded = upgradeCombos(inferSpecs(wf), objectInfoNew())
        val specs = upgraded.map { it.spec }
        val mega = specs.first { Specs.nodeId(it) == "850" && Specs.field(it) == "megapixels" }
        val mult = specs.first { Specs.nodeId(it) == "850" && Specs.field(it) == "multiple" }
        assertEquals("float", Specs.type(mega))
        assertEquals("int", Specs.type(mult))
        assertNotNull(mega.opt("current_value"))
        assertNotNull(mult.opt("current_value"))
    }
}
