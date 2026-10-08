package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * round16 LoRA 开关与强度：entries 识别、applyLoraStates、resolveLoraOn / resolveLoraStrength、
 * loraManagedSpecKeys（PLAN r16 §7.2 / §7.2b）。所有断言不依赖 JSONObject 的键顺序。
 */
class LoraToggleTest {

    private fun loraMix(): JSONObject = FixtureLoader.loadWf("lora_mix.workflow.json")

    private fun loraMixEntries(): List<LoraEntry> = loraEntries(loraMix())

    // ---------- §7.2 1. lora_mix entries ----------

    @Test
    fun loraMixEntriesTable() {
        val entries = loraMixEntries()
        assertEquals("key 顺序", listOf("2", "3", "4", "5:lora_1", "5:lora_2", "7"), entries.map { it.key })
        val byKey = entries.associateBy { it.key }

        val e2 = byKey.getValue("2")
        assertEquals("LoraLoader", e2.classType)
        assertEquals("Style LoRA", e2.nodeTitle)
        assertNull("单加载器 slot 为 null", e2.slot)
        assertEquals("lora_name", e2.fileField)
        assertEquals("style_a.safetensors", e2.fileName)
        assertEquals(listOf("strength_model", "strength_clip"), e2.strengthFields)
        assertEquals(0.8, e2.strengthDefaults["strength_model"]!!, 0.0)
        assertEquals(0.6, e2.strengthDefaults["strength_clip"]!!, 0.0)
        assertTrue("单加载器默认开", e2.defaultOn)

        val e3 = byKey.getValue("3")
        assertEquals(listOf("strength_model"), e3.strengthFields)
        assertEquals(1.0, e3.strengthDefaults["strength_model"]!!, 0.0)
        assertEquals("detail_b.safetensors", e3.fileName)

        val e4 = byKey.getValue("4")
        assertEquals("LoraLoader|pysssss", e4.classType)
        assertEquals("lora_name", e4.fileField)
        assertEquals(listOf("strength_model", "strength_clip"), e4.strengthFields)
        assertEquals(0.7, e4.strengthDefaults["strength_model"]!!, 0.0)
        assertEquals(0.7, e4.strengthDefaults["strength_clip"]!!, 0.0)

        val s1 = byKey.getValue("5:lora_1")
        assertEquals("lora_1", s1.slot)
        assertNull("Power 槽无 fileField", s1.fileField)
        assertEquals("multi_d.safetensors", s1.fileName)
        assertEquals(listOf("strength"), s1.strengthFields)
        assertEquals(1.0, s1.strengthDefaults["strength"]!!, 0.0)
        assertTrue("槽原 on=true", s1.defaultOn)

        val s2 = byKey.getValue("5:lora_2")
        assertFalse("槽原 on=false", s2.defaultOn)
        assertEquals("multi_e.safetensors", s2.fileName)
        assertEquals(listOf("strength", "strengthTwo"), s2.strengthFields)
        assertEquals(0.5, s2.strengthDefaults["strength"]!!, 0.0)
        assertEquals(0.4, s2.strengthDefaults["strengthTwo"]!!, 0.0)

        val e7 = byKey.getValue("7")
        assertEquals("NunchakuFluxLoraLoader", e7.classType)
        assertEquals("lora_name", e7.fileField)
        assertEquals(listOf("lora_strength"), e7.strengthFields)
        assertEquals(0.9, e7.strengthDefaults["lora_strength"]!!, 0.0)

        // 6（连线强度）、8（Stack）、9（None）不出现
        assertNull("连线强度不识别", byKey["6"])
        assertNull("Stack 类不识别", byKey["8"])
        assertNull("None 不识别", byKey["9"])
    }

    // ---------- §7.2 2. 现有 fixture ----------

    @Test
    fun existingFixturesEntries() {
        val wan = loraEntries(FixtureLoader.loadWf("wan22_i2v_4step.workflow.json"))
        assertEquals(listOf("81", "82"), wan.map { it.key })
        assertTrue(wan.all { it.strengthFields == listOf("strength_model") })
        assertTrue(wan.all { it.fileField == "lora_name" })
        assertEquals(listOf("4"), loraEntries(FixtureLoader.loadWf("qwen21_edit.workflow.json")).map { it.key })
        for (name in listOf(
            "kr2turbo_t2i.workflow.json",
            "krea2_t2i.workflow.json",
            "florence2_caption.workflow.json",
            "qwen21_t2i.workflow.json",
        )) {
            assertTrue(name, loraEntries(FixtureLoader.loadWf(name)).isEmpty())
        }
    }

    // ---------- §7.2 3. applyLoraStates 关闭 ----------

    @Test
    fun applyLoraStatesOff() {
        val wf = loraMix()
        val n = applyLoraStates(
            wf,
            JSONObject("""{"2":false,"7":false,"5:lora_1":false,"5:lora_2":true}"""),
            null,
        )
        assertEquals("返回值", 5, n)
        val i2 = wf.getJSONObject("2").getJSONObject("inputs")
        assertTrue("strength_model 是 Double", i2.opt("strength_model") is Double)
        assertEquals(0.0, i2.optDouble("strength_model"), 0.0)
        assertEquals(0.0, i2.optDouble("strength_clip"), 0.0)
        assertTrue(i2.opt("strength_clip") is Double)
        val i7 = wf.getJSONObject("7").getJSONObject("inputs")
        assertTrue(i7.opt("lora_strength") is Double)
        assertEquals(0.0, i7.optDouble("lora_strength"), 0.0)
        val slots = wf.getJSONObject("5").getJSONObject("inputs")
        assertFalse("lora_1 关", slots.getJSONObject("lora_1").optBoolean("on"))
        assertTrue("lora_2 开", slots.getJSONObject("lora_2").optBoolean("on"))
        assertEquals("关的槽强度不动", 1.0, slots.getJSONObject("lora_1").optDouble("strength"), 0.0)
        assertEquals(0.5, slots.getJSONObject("lora_2").optDouble("strength"), 0.0)
        assertEquals(0.4, slots.getJSONObject("lora_2").optDouble("strengthTwo"), 0.0)
        // 3、4 没给状态 → 不动
        val i3 = wf.getJSONObject("3").getJSONObject("inputs")
        assertTrue("整数原值不变", i3.opt("strength_model") is Int)
        assertEquals(1, i3.optInt("strength_model"))
        assertEquals(0.7, wf.getJSONObject("4").getJSONObject("inputs").optDouble("strength_model"), 0.0)
        // 文件名都不动
        assertEquals("style_a.safetensors", i2.optString("lora_name"))
        assertEquals("nunchaku_g.safetensors", i7.optString("lora_name"))
    }

    // ---------- §7.2 4. 整数强度写整数 0 ----------

    @Test
    fun integerStrengthZero() {
        val wf = loraMix()
        val n = applyLoraStates(wf, JSONObject("""{"3":false}"""), null)
        assertEquals(1, n)
        val v = wf.getJSONObject("3").getJSONObject("inputs").opt("strength_model")
        assertFalse("不写 Double", v is Double)
        assertEquals(0, (v as Number).toInt())
    }

    // ---------- §7.2 5. 开着不写 ----------

    @Test
    fun onWithoutStrengthsWritesNothing() {
        val wf = loraMix()
        val before = wf.getJSONObject("2").getJSONObject("inputs").optDouble("strength_model")
        val n = applyLoraStates(wf, JSONObject("""{"2":true,"3":true,"4":true}"""), null)
        assertEquals(0, n)
        assertEquals(before, wf.getJSONObject("2").getJSONObject("inputs").optDouble("strength_model"), 0.0)
    }

    // ---------- §7.2 6. mirror 场景：关 81 不影响 82 ----------

    @Test
    fun mirrorNodeOffOnlyAffectsItself() {
        val wf = FixtureLoader.loadWf("wan22_i2v_4step.workflow.json")
        wf.getJSONObject("81").getJSONObject("inputs").put("strength_model", 0.5)
        wf.getJSONObject("82").getJSONObject("inputs").put("strength_model", 0.5)
        val n = applyLoraStates(wf, JSONObject("""{"81":false}"""), null)
        assertEquals(1, n)
        assertEquals(0.0, wf.getJSONObject("81").getJSONObject("inputs").optDouble("strength_model"), 0.0)
        assertEquals("82 不受影响", 0.5, wf.getJSONObject("82").getJSONObject("inputs").optDouble("strength_model"), 0.0)
    }

    // ---------- §7.2 7. 无效 key / null / 非 Boolean ----------

    @Test
    fun invalidStatesIgnored() {
        val wf = loraMix()
        val n = applyLoraStates(
            wf,
            JSONObject("""{"1":false,"6":false,"9":false,"12":false,"99":false,"5:lora_9":false}"""),
            null,
        )
        assertEquals(0, n)
        assertEquals("1.ckpt_name 不变", "example_ckpt.safetensors", wf.getJSONObject("1").getJSONObject("inputs").optString("ckpt_name"))
        assertTrue("6 的连线不变", wf.getJSONObject("6").getJSONObject("inputs").opt("strength_model") is org.json.JSONArray)
        assertEquals(1.0, wf.getJSONObject("9").getJSONObject("inputs").optDouble("strength_model"), 0.0)
        assertEquals(3.5, wf.getJSONObject("12").getJSONObject("inputs").optDouble("cfg"), 0.0)
        assertEquals(0, applyLoraStates(wf, null, null))
        assertEquals(0, applyLoraStates(wf, JSONObject(), null))
        assertEquals("非 Boolean 当没有", 0, applyLoraStates(wf, JSONObject("""{"2":"false"}"""), null))
    }

    // ---------- §7.2 8. resolveLoraOn ----------

    @Test
    fun resolveLoraOnPriority() {
        val entries = loraMixEntries()
        var m = resolveLoraOn(entries, null, null)
        assertEquals("5:lora_2 默认关", false, m.getValue("5:lora_2"))
        assertEquals("其余默认开", true, m.getValue("2"))
        assertEquals(true, m.getValue("5:lora_1"))
        assertEquals("只含 entries 的 key", entries.size, m.size)

        m = resolveLoraOn(entries, JSONObject("""{"kwb_lora_on":{"2":false,"5:lora_2":true,"99":false}}"""), null)
        assertEquals(false, m.getValue("2"))
        assertEquals(true, m.getValue("5:lora_2"))
        assertFalse("过期 key 丢掉", m.containsKey("99"))

        m = resolveLoraOn(entries, null, JSONObject("""{"kwb_lora_on":{"3":false}}"""))
        assertEquals(false, m.getValue("3"))

        m = resolveLoraOn(
            entries,
            JSONObject("""{"kwb_lora_on":{"3":true}}"""),
            JSONObject("""{"kwb_lora_on":{"3":false}}"""),
        )
        assertEquals("saved 优先于 def", true, m.getValue("3"))
    }

    // ---------- §7.2b 11. 写入强度 ----------

    @Test
    fun applyLoraStatesStrengths() {
        val wf = loraMix()
        val n = applyLoraStates(
            wf,
            JSONObject("""{"2":true}"""),
            JSONObject("""{"2|strength_model":0.5,"2|strength_clip":0.3,"5:lora_1|strength":0.7,"7|lora_strength":1.2}"""),
        )
        assertEquals(4, n)
        val i2 = wf.getJSONObject("2").getJSONObject("inputs")
        assertTrue(i2.opt("strength_model") is Double)
        assertEquals(0.5, i2.optDouble("strength_model"), 0.0)
        assertEquals(0.3, i2.optDouble("strength_clip"), 0.0)
        assertEquals(0.7, wf.getJSONObject("5").getJSONObject("inputs").getJSONObject("lora_1").optDouble("strength"), 0.0)
        assertEquals(1.2, wf.getJSONObject("7").getJSONObject("inputs").optDouble("lora_strength"), 0.0)
        // 3、4 strengths 没给 → 不变
        assertEquals(1, wf.getJSONObject("3").getJSONObject("inputs").optInt("strength_model"))
        assertEquals(0.7, wf.getJSONObject("4").getJSONObject("inputs").optDouble("strength_model"), 0.0)
    }

    // ---------- §7.2b 12. 关闭优先于 strengths ----------

    @Test
    fun offOverridesStrengths() {
        val wf = loraMix()
        val n = applyLoraStates(
            wf,
            JSONObject("""{"2":false}"""),
            JSONObject("""{"2|strength_model":0.5}"""),
        )
        assertEquals(2, n)
        val i2 = wf.getJSONObject("2").getJSONObject("inputs")
        assertEquals(0.0, i2.optDouble("strength_model"), 0.0)
        assertEquals(0.0, i2.optDouble("strength_clip"), 0.0)
    }

    // ---------- §7.2b 13. Power 槽 ----------

    @Test
    fun powerSlotStates() {
        // 关：只写 on=false，强度不动（strengths 给了也不写）
        var wf = loraMix()
        var n = applyLoraStates(wf, JSONObject("""{"5:lora_1":false}"""), JSONObject("""{"5:lora_1|strength":0.7}"""))
        assertEquals(1, n)
        var slot = wf.getJSONObject("5").getJSONObject("inputs").getJSONObject("lora_1")
        assertFalse(slot.optBoolean("on"))
        assertEquals(1.0, slot.optDouble("strength"), 0.0)

        // 开：on=true + 两个强度都写
        wf = loraMix()
        n = applyLoraStates(
            wf,
            JSONObject("""{"5:lora_2":true}"""),
            JSONObject("""{"5:lora_2|strength":0.9,"5:lora_2|strengthTwo":0.2}"""),
        )
        assertEquals(3, n)
        slot = wf.getJSONObject("5").getJSONObject("inputs").getJSONObject("lora_2")
        assertTrue(slot.optBoolean("on"))
        assertEquals(0.9, slot.optDouble("strength"), 0.0)
        assertEquals(0.2, slot.optDouble("strengthTwo"), 0.0)

        // 无状态 + 原 on=false → 什么都不写
        wf = loraMix()
        n = applyLoraStates(wf, null, JSONObject("""{"5:lora_2|strength":0.9}"""))
        assertEquals(0, n)
        slot = wf.getJSONObject("5").getJSONObject("inputs").getJSONObject("lora_2")
        assertFalse(slot.optBoolean("on"))
        assertEquals(0.5, slot.optDouble("strength"), 0.0)
    }

    // ---------- §7.2b 14. 覆盖旧暴露参数 / mirror ----------

    @Test
    fun strengthsOverrideMirrorWrites() {
        val wf = FixtureLoader.loadWf("wan22_i2v_4step.workflow.json")
        wf.getJSONObject("81").getJSONObject("inputs").put("strength_model", 0.5)
        wf.getJSONObject("82").getJSONObject("inputs").put("strength_model", 0.5)
        val n = applyLoraStates(
            wf,
            null,
            JSONObject("""{"81|strength_model":0.9,"82|strength_model":0.4}"""),
        )
        assertEquals(2, n)
        assertEquals(0.9, wf.getJSONObject("81").getJSONObject("inputs").optDouble("strength_model"), 0.0)
        assertEquals(0.4, wf.getJSONObject("82").getJSONObject("inputs").optDouble("strength_model"), 0.0)
    }

    // ---------- §7.2b 15. 整数保持 ----------

    @Test
    fun integerStylePreserved() {
        var wf = loraMix()
        applyLoraStates(wf, null, JSONObject("""{"3|strength_model":2.0}"""))
        var v = wf.getJSONObject("3").getJSONObject("inputs").opt("strength_model")
        assertFalse("整数原值 + 整数新值 → 不写 Double", v is Double)
        assertEquals(2, (v as Number).toInt())

        wf = loraMix()
        applyLoraStates(wf, null, JSONObject("""{"3|strength_model":0.5}"""))
        v = wf.getJSONObject("3").getJSONObject("inputs").opt("strength_model")
        assertTrue("小数新值 → Double", v is Double)
        assertEquals(0.5, v as Double, 0.0)
    }

    // ---------- §7.2b 16. 非法值跳过 ----------

    @Test
    fun invalidStrengthValuesSkipped() {
        val wf = loraMix()
        val n = applyLoraStates(
            wf,
            null,
            JSONObject("""{"2|strength_model":"abc","2|strength_clip":"0.25","7|lora_strength":"NaN"}"""),
        )
        assertEquals(1, n)
        val i2 = wf.getJSONObject("2").getJSONObject("inputs")
        assertEquals("非法值不写", 0.8, i2.optDouble("strength_model"), 0.0)
        assertEquals(0.25, i2.optDouble("strength_clip"), 0.0)
        assertEquals("NaN 字符串不写", 0.9, wf.getJSONObject("7").getJSONObject("inputs").optDouble("lora_strength"), 0.0)
    }

    // ---------- §7.2b 17. resolveLoraStrength 优先级 ----------

    @Test
    fun resolveLoraStrengthPriority() {
        val entries = loraMixEntries()
        val specs = listOf(JSONObject("""{"node_id":"2","field":"strength_model","type":"float"}"""))

        // 全 null → 工作流原值（trimNum 风格）
        var m = resolveLoraStrength(entries, specs, null, null)
        assertEquals("0.8", m.getValue("2|strength_model"))
        assertEquals("0.6", m.getValue("2|strength_clip"))
        assertEquals("1", m.getValue("3|strength_model"))
        assertEquals("1", m.getValue("5:lora_1|strength"))
        assertEquals("0.4", m.getValue("5:lora_2|strengthTwo"))
        assertEquals("0.9", m.getValue("7|lora_strength"))

        // ② 旧的已暴露参数（saved.values）
        m = resolveLoraStrength(entries, specs, JSONObject("""{"values":{"2|strength_model":0.55}}"""), null)
        assertEquals("0.55", m.getValue("2|strength_model"))

        // ③ def 顶层
        m = resolveLoraStrength(entries, specs, null, JSONObject("""{"kwb_lora_strength":{"2|strength_clip":0.3}}"""))
        assertEquals("0.3", m.getValue("2|strength_clip"))

        // ① 最高优先
        m = resolveLoraStrength(
            entries,
            specs,
            JSONObject("""{"kwb_lora_strength":{"2|strength_model":0.7},"values":{"2|strength_model":0.55}}"""),
            null,
        )
        assertEquals("0.7", m.getValue("2|strength_model"))

        // 非法值跳过 → 回落
        m = resolveLoraStrength(entries, specs, JSONObject("""{"kwb_lora_strength":{"2|strength_model":"x"}}"""), null)
        assertEquals("0.8", m.getValue("2|strength_model"))

        // mirror 继承（wan22）：81 的旧参数值同时喂给 82
        val wanEntries = loraEntries(FixtureLoader.loadWf("wan22_i2v_4step.workflow.json"))
        val wanSpecs = listOf(
            JSONObject(
                """{"node_id":"81","field":"strength_model","type":"float",""" +
                    """"mirror_to":[{"node_id":"82","field":"strength_model"}]}""",
            ),
        )
        val wm = resolveLoraStrength(wanEntries, wanSpecs, JSONObject("""{"values":{"81|strength_model":0.6}}"""), null)
        assertEquals("0.6", wm.getValue("81|strength_model"))
        assertEquals("0.6", wm.getValue("82|strength_model"))
    }

    // ---------- §7.2b 18. loraManagedSpecKeys ----------

    @Test
    fun loraManagedSpecKeysHits() {
        val entries = loraMixEntries()
        val wanEntries = loraEntries(FixtureLoader.loadWf("wan22_i2v_4step.workflow.json"))
        val s81 = JSONObject(
            """{"node_id":"81","field":"strength_model","type":"float",""" +
                """"mirror_to":[{"node_id":"82","field":"strength_model"}]}""",
        )
        assertEquals(setOf("81|strength_model"), loraManagedSpecKeys(wanEntries, listOf(s81)))
        assertEquals(
            setOf("81|strength_model"),
            loraManagedSpecKeys(
                wanEntries,
                listOf(s81, JSONObject("""{"node_id":"57","field":"cfg","type":"float"}""")),
            ),
        )
        assertEquals(
            setOf("2|strength_model"),
            loraManagedSpecKeys(entries, listOf(JSONObject("""{"node_id":"2","field":"strength_model","type":"float"}"""))),
        )
        assertTrue(
            "6 不是 entry",
            loraManagedSpecKeys(entries, listOf(JSONObject("""{"node_id":"6","field":"strength_model","type":"float"}"""))).isEmpty(),
        )
    }

    // ---------- §7.1 lora_mix 的 inferSpecs 推断（规则 4b + 现状锁定） ----------

    @Test
    fun loraMixInferenceTable() {
        val all = inferSpecs(loraMix())
        assertEquals("候选总数", 29, all.size)
        assertEquals("enabled 数", 10, all.count { it.spec.optBoolean("enabled") })
        val m = specMap(all)
        // 单 LoRA 文件字段全部是 model spec：2/3/6/9 走 LOADER_MAP，4/7 走规则 4b
        for (key in listOf("2|lora_name", "3|lora_name", "4|lora_name", "6|lora_name", "7|lora_name", "9|lora_name")) {
            val spec = m.getValue(key).spec
            assertEquals("$key type", "model", spec.optString("type"))
            assertEquals("$key tier", "model", spec.optString("tier"))
            assertEquals("$key confidence", "high", spec.optString("confidence"))
            assertTrue("$key enabled", spec.optBoolean("enabled"))
            assertEquals("$key label", "LoRA", spec.optString("label"))
        }
        assertEquals("9|lora_name 保留 None（现状锁定）", "None", m.getValue("9|lora_name").spec.optString("default"))
        // 强度字段推断不变：低置信 float/int
        val s2 = m.getValue("2|strength_model").spec
        assertEquals("2|strength_model float", "float", s2.optString("type"))
        assertEquals(0.8, s2.optDouble("default"), 0.0)
        assertFalse("low", s2.optBoolean("enabled"))
        val s3 = m.getValue("3|strength_model").spec
        assertEquals("3|strength_model int（JSON 整数）", "int", s3.optString("type"))
        assertEquals("7|lora_strength float", "float", m.getValue("7|lora_strength").spec.optString("type"))
        assertFalse("6|strength_model 连线不暴露", m.containsKey("6|strength_model"))
        // 现状锁定：Power Lora Loader 的 ➕ Add Lora 空串 → low text；dict 输入不产生候选
        val plus = m.getValue("5|➕ Add Lora").spec
        assertEquals("text", plus.optString("type"))
        assertFalse("low", plus.optBoolean("enabled"))
        assertFalse(m.containsKey("5|lora_1"))
        assertFalse(m.containsKey("5|lora_2"))
        // 现状锁定：CR LoRA Stack 字段全部低置信
        for ((key, typ) in mapOf(
            "8|switch_1" to "text",
            "8|lora_name_1" to "text",
            "8|model_weight_1" to "float",
            "8|clip_weight_1" to "float",
        )) {
            val spec = m.getValue(key).spec
            assertEquals("$key type", typ, spec.optString("type"))
            assertFalse("$key 不勾", spec.optBoolean("enabled"))
        }
    }

    // ---------- §7.2 9. 导入器规则 4b ----------

    @Test
    fun importerRule4b() {
        val wf = JSONObject(
            """{"1":{"class_type":"WanVideoLoraSelect","_meta":{"title":"选 LoRA"},""" +
                """"inputs":{"lora":"w.safetensors","strength":1.0,"low_mem_load":false}}}""",
        )
        val m = specMap(inferSpecs(wf))
        val lora = m.getValue("1|lora").spec
        assertEquals("type", "model", lora.optString("type"))
        assertEquals("tier", "model", lora.optString("tier"))
        assertEquals("confidence", "high", lora.optString("confidence"))
        assertTrue("enabled", lora.optBoolean("enabled"))
        assertEquals("label", "LoRA", lora.optString("label"))
        assertEquals("default", "w.safetensors", lora.optString("default"))
        assertEquals("强度字段照旧 low float", "float", m.getValue("1|strength").spec.optString("type"))
        assertFalse(m.getValue("1|strength").spec.optBoolean("enabled"))
        assertEquals("entries", listOf("1"), loraEntries(wf).map { it.key })
        assertEquals(listOf("strength"), loraEntries(wf)[0].strengthFields)

        // Efficient Loader 类名不含 lora → lora_name 仍是 low text，无 entry（现状锁定）
        val wf2 = JSONObject(
            """{"1":{"class_type":"Efficient Loader","_meta":{"title":"EL"},""" +
                """"inputs":{"lora_name":"x.safetensors","lora_model_strength":1.0}}}""",
        )
        val m2 = specMap(inferSpecs(wf2))
        assertEquals("text", m2.getValue("1|lora_name").spec.optString("type"))
        assertFalse(m2.getValue("1|lora_name").spec.optBoolean("enabled"))
        assertTrue("Efficient Loader 无 entry", loraEntries(wf2).isEmpty())
    }

    // ---------- §7.2 10. / §7.2b 19. exportDefinition ----------

    @Test
    fun exportDefinitionCarriesLoraOn() {
        val def = JSONObject("""{"user_facing_inputs":[]}""")
        val out = exportDefinition(def, JSONObject("""{"kwb_lora_on":{"2":false}}"""))
        assertFalse(out.optJSONObject("kwb_lora_on")!!.optBoolean("2", true))
        val plain = exportDefinition(def, JSONObject("""{"values":{}}"""))
        assertFalse("saved 没有就不写", plain.has("kwb_lora_on"))
    }

    @Test
    fun exportDefinitionCarriesLoraStrength() {
        val def = JSONObject("""{"user_facing_inputs":[]}""")
        val out = exportDefinition(def, JSONObject("""{"kwb_lora_strength":{"2|strength_model":0.5}}"""))
        assertEquals(
            0.5,
            out.optJSONObject("kwb_lora_strength")!!.optDouble("2|strength_model"),
            0.0,
        )
    }
}
