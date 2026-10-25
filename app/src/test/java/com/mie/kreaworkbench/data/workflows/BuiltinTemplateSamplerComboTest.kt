package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r49 补修回归：内置模板（seed 定义）也必须把 sampler_name / scheduler 升级成下拉。
 * 第一版只测了纯函数、spec 都带着 class_type——而真实定义（buildDefinition 存盘）剥掉
 * class_type，升级靠 workflow.json 查节点才能认出采样器类，漏掉后内置模板在真机上仍然
 * 混进「编辑提示词」弹窗。这里直接读 assets 资产，走 WorkflowStore.builtinAssetPairLocked
 * 的同一条播种链（inferSpecs → 勾选过滤 → buildDefinition）再跑运行时升级。
 */
class BuiltinTemplateSamplerComboTest {

    private val samplerFields = setOf("sampler_name", "scheduler")

    private fun asset(name: String): JSONObject {
        val rel = "app/src/main/assets/builtin_templates/$name"
        val f = listOf(File(rel), File("..", rel), File("../..", rel)).firstOrNull { it.isFile }
            ?: error("找不到内置资产：$rel（试过 ${listOf(".", "..", "../..").joinToString()}）")
        return JSONObject(f.readText())
    }

    /** WorkflowStore.builtinAssetPairLocked 的播种链复刻（纯函数部分）。 */
    private fun seededSpecs(name: String): Pair<JSONObject, List<JSONObject>> {
        val wf = asset(name)
        val def = buildDefinition(
            displayName = name,
            workflowFileName = name,
            outputNode = pickOutputNode(wf),
            orderedSpecs = inferSpecs(wf).filter { it.spec.optBoolean("enabled") }.map { it.spec },
            outputKind = inferOutputKind(wf),
        )
        val specs = ArrayList<JSONObject>()
        val arr = def.optJSONArray("user_facing_inputs")!!
        for (i in 0 until arr.length()) specs.add(arr.optJSONObject(i)!!)
        return wf to specs
    }

    /** 与 CustomScreen.kt 生成页 prompts 过滤同一条谓词。 */
    private fun promptDialogSpecs(specs: List<JSONObject>) = specs.filter {
        Specs.type(it) in setOf("text", "prompt_pool") &&
            Specs.tier(it) != "advanced" && !it.optBoolean("kwb_negative")
    }

    @Test
    fun krea2AndZimageSamplerFieldsBecomeDropdowns() {
        for (name in listOf("Krea2_T2I.json", "Zimage_T2I.json")) {
            val (wf, specs) = seededSpecs(name)
            val inDef = specs.filter { Specs.field(it) in samplerFields }
            assertTrue("$name 定义里应有采样字段（r44 默认勾选）", inDef.isNotEmpty())
            assertTrue(
                "$name 采样字段播种后是 text（复现前提）",
                inDef.all { Specs.type(it) == "text" },
            )
            val upgraded = upgradeSamplerComboSpecs(specs, wf)
            val dialog = promptDialogSpecs(upgraded)
            assertTrue("$name 弹窗不能是空的（提示词还在）", dialog.isNotEmpty())
            assertTrue(
                "$name 弹窗混入采样字段",
                dialog.none { Specs.field(it) in samplerFields },
            )
            for (s in upgraded.filter { Specs.field(it) in samplerFields }) {
                assertEquals("$name ${Specs.key(s)} type", "select", Specs.type(s))
                val combo = s.optString("kwb_combo")
                assertTrue(
                    "$name ${Specs.key(s)} kwb_combo=$combo 应为 采样器类|字段",
                    combo.substringBeforeLast('|') in setOf("KSampler", "KSamplerAdvanced") &&
                        combo.substringAfterLast('|', "") == Specs.field(s),
                )
                assertTrue("$name ${Specs.key(s)} 保底 choices 非空", s.optJSONArray("choices")!!.length() > 0)
                // 保底列表必须装得下模板自己的默认值（否则离线首用就是红字）
                val def = s.optString("default")
                val choices = s.optJSONArray("choices")!!.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                assertTrue("$name 默认值 $def 不在保底列表", def in choices)
            }
        }
    }

    @Test
    fun allBuiltinTemplatesPromptDialogClean() {
        for (t in BuiltinTemplates.all) {
            val (wf, specs) = seededSpecs(t.asset)
            val upgraded = upgradeSamplerComboSpecs(specs, wf)
            val dialog = promptDialogSpecs(upgraded)
            assertTrue(
                "${t.id} 弹窗混入采样字段",
                dialog.none { Specs.field(it) in samplerFields },
            )
            // 定义里出现的采样字段必须全为 select + kwb_combo（Qwen21_Edit 的快采样器
            // 不在 r44 默认勾选集、定义里本来就没有，trivially 通过）
            for (s in upgraded.filter { Specs.field(it) in samplerFields }) {
                assertEquals("${t.id} ${Specs.key(s)} type", "select", Specs.type(s))
                assertFalse("${t.id} ${Specs.key(s)} 缺 kwb_combo", s.optString("kwb_combo").isBlank())
            }
            // 幂等：重复升级不再变化（loadBound 每次绑定都会跑）
            val again = upgradeSamplerComboSpecs(upgraded, wf)
            assertEquals(upgraded.toString(), again.toString())
        }
    }

    @Test
    fun mossErSdeDefaultCoveredByFallback() {
        // Moss 默认采样器 er_sde 曾不在保底列表（r49 第一版）：离线且无缓存时下拉装不下
        // 自己模板的默认值。这里钉死它必须在。
        val (wf, specs) = seededSpecs("Moss_T2I.json")
        val upgraded = upgradeSamplerComboSpecs(specs, wf)
        val sampler = upgraded.first { Specs.field(it) == "sampler_name" }
        assertEquals("er_sde", sampler.optString("default"))
        val choices = sampler.optJSONArray("choices")!!
            .let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        assertTrue("保底列表缺 er_sde", "er_sde" in choices)
    }

    @Test
    fun qwenEditSamplerFieldsHiddenUntilManuallyChecked() {
        // Qwen21_Edit 用 QwenImage21FastSampler：r44 默认勾选集不含它 → 播种定义里没有采样
        // 字段（所以它从来没进过弹窗）；但用户在导入页手动勾选后仍会以 text 落定义，
        // 运行时升级必须把它认成采样器类（QwenImage21FastSampler 在升级类集里）。
        val (wf, specs) = seededSpecs("Qwen21_Edit.json")
        assertTrue("播种定义不该有采样字段", specs.none { Specs.field(it) in samplerFields })

        val full = inferSpecs(wf).map { it.spec }
        val upgraded = upgradeSamplerComboSpecs(full, wf)
        val qwenSampler = upgraded.first {
            Specs.field(it) == "sampler_name" && Specs.nodeId(it) == "540"
        }
        assertEquals("select", Specs.type(qwenSampler))
        assertEquals("QwenImage21FastSampler|sampler_name", qwenSampler.optString("kwb_combo"))
        assertTrue(qwenSampler.optJSONArray("choices")!!.length() > 0)
    }
}
