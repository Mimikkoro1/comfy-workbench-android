package com.mie.kreaworkbench.data.workflows

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r49 回归：KSampler 系的 sampler_name / scheduler 若停在 type=text（离线导入、节点探测失败、
 * 或 r44 之前导入的定义），会混进生成页「编辑提示词」弹窗。运行时经 upgradeSamplerComboSpecs
 * 升级成 select + kwb_combo 后，落到「模型和采样」页作为下拉。
 */
class SamplerComboSpecsTest {

    /** 与 CustomScreen.kt 生成页 prompts 过滤同一条谓词（弹窗只收真正的提示词）。 */
    private fun promptDialogSpecs(specs: List<JSONObject>) = specs.filter {
        Specs.type(it) in setOf("text", "prompt_pool") &&
            Specs.tier(it) != "advanced" && !it.optBoolean("kwb_negative")
    }

    private fun samplerSpec(cls: String, field: String, value: String) = JSONObject()
        .put("node_id", "1").put("field", field).put("class_type", cls)
        .put("type", "text").put("default", value).put("current_value", value)
        .put("label", "采样 · $field")

    @Test
    fun samplerFieldsLeavePromptDialogAndBecomeSelect() {
        val prompt = JSONObject()
            .put("node_id", "7").put("field", "text").put("class_type", "CLIPTextEncode")
            .put("type", "prompt_pool").put("default", "a cat").put("label", "提示词")
        val negative = JSONObject()
            .put("node_id", "8").put("field", "text").put("class_type", "CLIPTextEncode")
            .put("type", "text").put("kwb_negative", true).put("default", "blurry").put("label", "负向提示词")
        val specs = listOf(
            prompt,
            negative,
            samplerSpec("KSampler", "sampler_name", "euler"),
            samplerSpec("KSampler", "scheduler", "simple"),
        )

        val upgraded = upgradeSamplerComboSpecs(specs)

        // 弹窗过滤只剩提示词；负向词本来就被 kwb_negative 排除，采样两字段升级后不再匹配 text
        val dialog = promptDialogSpecs(upgraded)
        assertEquals(listOf("7|text"), dialog.map { Specs.key(it) })

        val sampler = upgraded.first { Specs.key(it) == "1|sampler_name" }
        assertEquals("select", Specs.type(sampler))
        assertEquals("KSampler|sampler_name", sampler.optString("kwb_combo"))
        assertFalse(sampler.has("kwb_negative"))
        // 保底 choices：服务器/缓存都拿不到时下拉也有合理选项
        assertTrue(sampler.optJSONArray("choices")!!.length() > 0)
        assertEquals("euler", sampler.optString("default"))

        val scheduler = upgraded.first { Specs.key(it) == "1|scheduler" }
        assertEquals("select", Specs.type(scheduler))
        assertEquals("KSampler|scheduler", scheduler.optString("kwb_combo"))
        val schedChoices = scheduler.optJSONArray("choices")!!
        assertTrue((0 until schedChoices.length()).any { schedChoices.optString(it) == "simple" })

        // 真正的提示词原样：type 不变、不写 kwb_combo
        val promptAfter = upgraded.first { Specs.key(it) == "7|text" }
        assertEquals("prompt_pool", Specs.type(promptAfter))
        assertFalse(promptAfter.has("kwb_combo"))
        assertEquals("text", Specs.type(upgraded.first { Specs.key(it) == "8|text" }))
    }

    @Test
    fun kSamplerAdvancedAndSuffixedClassCovered() {
        val specs = listOf(
            samplerSpec("KSamplerAdvanced", "sampler_name", "res_multistep"),
            samplerSpec("KSampler|Mie", "scheduler", "karras"),
        )
        val upgraded = upgradeSamplerComboSpecs(specs)
        assertTrue(upgraded.all { Specs.type(it) == "select" })
        // kwb_combo 用原始 class_type（可能带后缀），运行时解析时从右往左切
        assertEquals("KSamplerAdvanced|sampler_name", upgraded[0].optString("kwb_combo"))
        assertEquals("KSampler|Mie|scheduler", upgraded[1].optString("kwb_combo"))
    }

    @Test
    fun nonSamplerAndAlreadySelectSpecsUntouched() {
        val steps = JSONObject()
            .put("node_id", "1").put("field", "steps").put("class_type", "KSampler")
            .put("type", "int").put("default", 20)
        val cfg = JSONObject()
            .put("node_id", "1").put("field", "cfg").put("class_type", "KSampler")
            .put("type", "float").put("default", 4.5)
        val otherSamplerName = JSONObject()
            .put("node_id", "3").put("field", "sampler_name").put("class_type", "SamplerCustom")
            .put("type", "text").put("default", "euler")
        val alreadySelect = JSONObject()
            .put("node_id", "1").put("field", "sampler_name").put("class_type", "KSampler")
            .put("type", "select").put("kwb_combo", "KSampler|sampler_name")
            .put("default", "dpmpp_2m")
        val specs = listOf(steps, cfg, otherSamplerName, alreadySelect)

        val upgraded = upgradeSamplerComboSpecs(specs)

        assertEquals("int", Specs.type(upgraded[0]))
        assertFalse(upgraded[0].has("kwb_combo"))
        assertEquals("float", Specs.type(upgraded[1]))
        // 非 KSampler 系的同名字段不动（升级按 class+field 判定，不按名字过滤）
        assertEquals("text", Specs.type(upgraded[2]))
        assertFalse(upgraded[2].has("kwb_combo"))
        // 已是 select 的原样（幂等，重复 loadBound 不叠加）
        assertEquals(alreadySelect.toString(), upgraded[3].toString())
    }

    @Test
    fun missingTypeAndDefinitionChoicesHandled() {
        // 老定义可能整个没有 type 键（Specs.type 缺省按 text），同样要升级
        val noType = JSONObject()
            .put("node_id", "1").put("field", "scheduler").put("class_type", "KSampler")
            .put("default", "simple")
        // 定义自带 choices（如 PC 成对导入）时保留，不覆盖成保底列表
        val withChoices = samplerSpec("KSampler", "sampler_name", "euler")
            .put("choices", JSONArray().put("euler").put("dpmpp_2m"))
        // 合并 spec 的 mirror_to 必须保留（一个控件写多个节点）
        val merged = samplerSpec("KSamplerAdvanced", "sampler_name", "euler")
            .put("mirror_to", JSONArray().put(JSONObject().put("node_id", "2").put("field", "sampler_name")))
        // 没有 class_type 的 spec 判不了来源，不动
        val noClass = JSONObject()
            .put("node_id", "9").put("field", "sampler_name").put("type", "text").put("default", "euler")

        val upgraded = upgradeSamplerComboSpecs(listOf(noType, withChoices, merged, noClass))

        assertEquals("select", Specs.type(upgraded[0]))
        assertEquals("KSampler|scheduler", upgraded[0].optString("kwb_combo"))
        assertEquals(
            "definition choices 保留",
            listOf("euler", "dpmpp_2m"),
            upgraded[1].optJSONArray("choices")!!.let { arr -> (0 until arr.length()).map { arr.optString(it) } },
        )
        assertTrue(upgraded[2].has("mirror_to"))
        assertEquals("text", Specs.type(upgraded[3]))
    }

    @Test
    fun offlineFallbackChoicesShownAndOffListValueKept() {
        // 离线且无 object_info 缓存：choices 恰为保底列表（非空、含常见值），下拉不会空白
        val specs = listOf(
            samplerSpec("KSampler", "sampler_name", "euler"),
            samplerSpec("KSampler", "scheduler", "simple"),
        )
        val upgraded = upgradeSamplerComboSpecs(specs)
        val fallbackSamplers = upgraded[0].optJSONArray("choices")!!
            .let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        assertTrue("保底列表非空", fallbackSamplers.isNotEmpty())
        assertTrue("含 euler", "euler" in fallbackSamplers)
        val fallbackSchedulers = upgraded[1].optJSONArray("choices")!!
            .let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        assertTrue("含 simple", "simple" in fallbackSchedulers)

        // 用户已选的值不在保底列表里（如服务器自定义采样器）不能被覆盖或丢掉：
        // 升级只补 choices，default/current_value 原样——运行时取值走 textValues
        // （last_values 优先、default 兜底），choices 只影响显示
        val exotic = samplerSpec("KSampler", "sampler_name", "euler_cfg_pp")
        val exoticUpgraded = upgradeSamplerComboSpecs(listOf(exotic)).single()
        assertEquals("select", Specs.type(exoticUpgraded))
        assertEquals("euler_cfg_pp", exoticUpgraded.optString("default"))
        assertEquals("euler_cfg_pp", exoticUpgraded.opt("current_value"))
        val exoticChoices = exoticUpgraded.optJSONArray("choices")!!
        assertTrue((0 until exoticChoices.length()).none { exoticChoices.optString(it) == "euler_cfg_pp" })
    }

    @Test
    fun upgradeDoesNotMutateStoredSpecs() {
        // loadBound 每次重读定义重新升级；升级必须纯内存——传入的原始 spec（即存盘定义的
        // JSON 对象）不能被就地改写，否则用户保存的定义会被悄悄变更
        val raw = samplerSpec("KSampler", "sampler_name", "euler")
        val rawScheduler = JSONObject()
            .put("node_id", "1").put("field", "scheduler").put("class_type", "KSamplerAdvanced")
        upgradeSamplerComboSpecs(listOf(raw, rawScheduler))

        assertEquals("原始 spec 仍是 text", "text", raw.optString("type"))
        assertFalse("原始 spec 无 kwb_combo", raw.has("kwb_combo"))
        assertFalse("原始 spec 无 choices", raw.has("choices"))
        assertEquals("缺 type 的原始 spec 仍缺 type", "", rawScheduler.optString("type"))
        assertFalse(rawScheduler.has("kwb_combo"))
    }

    @Test
    fun classResolvedFromWorkflowForStrippedDefinitions() {
        // 真实存盘定义（含内置 seed）的 spec 没有 class_type（buildDefinition 存盘即剥），
        // 类必须从 workflow.json 的节点解析——r49 第一版漏掉这条，内置模板全部没升到
        val wf = JSONObject(
            """{"12":{"class_type":"KSampler","inputs":{"sampler_name":"euler"}},
                "13":{"class_type":"SamplerCustom","inputs":{"sampler_name":"euler"}}}""",
        )
        val krea = JSONObject()
            .put("node_id", "12").put("field", "sampler_name").put("type", "text").put("default", "euler")
        val notSampler = JSONObject()
            .put("node_id", "13").put("field", "sampler_name").put("type", "text").put("default", "euler")

        val upgraded = upgradeSamplerComboSpecs(listOf(krea, notSampler), wf)

        assertEquals("select", Specs.type(upgraded[0]))
        assertEquals("KSampler|sampler_name", upgraded[0].optString("kwb_combo"))
        // workflow 里不是采样器类 → 不升（不按字段名过滤）
        assertEquals("text", Specs.type(upgraded[1]))
        assertFalse(upgraded[1].has("kwb_combo"))
    }

    @Test
    fun qwenFastSamplerUpgraded() {
        // Qwen21_Edit 的采样节点是 QwenImage21FastSampler（输入形状同 KSampler）；
        // r44 默认勾选集不含它（导入页默认不勾），但用户手动勾选存进定义后同样要升成下拉
        val specs = listOf(samplerSpec("QwenImage21FastSampler", "sampler_name", "euler"))
        val upgraded = upgradeSamplerComboSpecs(specs)
        assertEquals("select", Specs.type(upgraded[0]))
        assertEquals("QwenImage21FastSampler|sampler_name", upgraded[0].optString("kwb_combo"))
    }
}
