package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r44：KSamplerAdvanced 导入默认勾选最小集 + 种子连线向上追一跳。
 * 夹具为 Moss 两段采样的精简版：599 / 858 两个 KSamplerAdvanced、649 Seed (rgthree)、
 * 771 SimpleMath+（运算节点，第二段种子的中转）、773 PrimitiveInt。
 */
class LinkedSeedImportTest {
    private val wf = FixtureLoader.loadWf("moss_samplers.workflow.json")

    private fun specsOf(w: JSONObject) = inferSpecs(w).map { it.spec }

    private fun intRandomSpecs(specs: List<JSONObject>) = specs.filter { Specs.type(it) == "int_random" }

    @Test
    fun samplerMinimalSetEnabledByDefault() {
        val specs = specsOf(wf)
        val on = specs.filter {
            baseClass(it.optString("class_type")) in setOf("KSampler", "KSamplerAdvanced") &&
                Specs.field(it) in setOf("steps", "cfg", "sampler_name", "scheduler")
        }
        // steps 值不同（18/25）不合并 → 2 条；cfg/sampler_name/scheduler 同值合并 → 各 1 条
        assertEquals(5, on.size)
        assertTrue(on.all { it.optBoolean("enabled") })
        assertEquals(setOf("599", "858"), on.filter { Specs.field(it) == "steps" }
            .map { Specs.nodeId(it) }.toSet())
    }

    @Test
    fun spliceParamsStayDisabled() {
        val specs = specsOf(wf)
        val splice = specs.filter { Specs.field(it) in setOf("start_at_step", "end_at_step") }
        assertEquals(4, splice.size)
        assertTrue(splice.none { it.optBoolean("enabled") })
    }

    @Test
    fun oneSeedSpecFromRealSeedNode() {
        val seeds = intRandomSpecs(specsOf(wf))
        assertEquals(1, seeds.size)
        assertEquals("649", Specs.nodeId(seeds[0]))
        assertEquals("seed", Specs.field(seeds[0]))
        assertEquals(420, seeds[0].optLong("current_value"))
        assertEquals("Seed (1ST)", seeds[0].optString("node_title"))
        assertTrue(seeds[0].optBoolean("enabled"))
    }

    @Test
    fun oneHopStopsAtMathNode() {
        // 599 的 noise_seed 改指向 771（SimpleMath+ 运算节点）：一跳到头，不产生来自 771/773 的
        // 种子候选、也不抛异常（649 自身的字面 seed 仍按 r43 规则 5 出候选）
        val w = JSONObject(wf.toString())
        val n599 = w.optJSONObject("599")!!.optJSONObject("inputs")!!
        n599.put("noise_seed", org.json.JSONArray().put("771").put(0))
        val seeds = intRandomSpecs(specsOf(w))
        assertTrue(seeds.isNotEmpty())
        assertTrue(seeds.all { Specs.nodeId(it) == "649" })
    }

    @Test
    fun literalNoiseSeedWorkflowUnchanged() {
        // 老 KSampler 直接写字面 noise_seed：仍走规则 5，输出与改前一致
        val w = JSONObject(
            """
            {"6": {"class_type": "KSampler", "_meta": {"title": "K采样器"},
                   "inputs": {"seed": 7, "noise_seed": 42, "steps": 4, "cfg": 1,
                              "sampler_name": "euler", "scheduler": "simple", "denoise": 1}}}
            """.trimIndent(),
        )
        val specs = specsOf(w)
        val noise = specs.first { Specs.field(it) == "noise_seed" }
        assertEquals("int_random", Specs.type(noise))
        assertEquals("6", Specs.nodeId(noise))
        assertEquals(42, noise.optLong("current_value"))
        assertTrue(noise.optBoolean("enabled"))
        assertFalse(noise.has("mirror_to"))
    }
}
