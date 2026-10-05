package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.2–4.11 规则级合成用例（内联小 JSON，不建文件）。 */
class InferRulesTest {

    private fun one(wf: JSONObject): JSONObject {
        val all = inferSpecs(wf)
        assertEquals("候选数", 1, all.size)
        return all[0].spec
    }

    // ---------- §4.2 连线输入不暴露 ----------

    @Test
    fun linkInputsAreNotExposed() {
        val wf = JSONObject(
            """{"1":{"class_type":"CLIPTextEncode","_meta":{"title":"T"},""" +
                """"inputs":{"text":["1",1],"clip":["1",0]}}}""",
        )
        assertTrue("连线引用不产生候选", inferSpecs(wf).isEmpty())
    }

    // ---------- §4.3 LOADER_MAP 全表 ----------

    @Test
    fun loaderMapFullTable() {
        val cases = listOf(
            listOf("CheckpointLoaderSimple", "ckpt_name", "大模型"),
            listOf("UNETLoader", "unet_name", "扩散模型"),
            listOf("UnetLoaderGGUF", "unet_name", "扩散模型"),
            listOf("VAELoader", "vae_name", "VAE"),
            listOf("CLIPLoader", "clip_name", "文本编码器"),
            listOf("CLIPVisionLoader", "clip_name", "视觉编码器"),
            listOf("LoraLoader", "lora_name", "LoRA"),
            listOf("LoraLoaderModelOnly", "lora_name", "LoRA"),
            listOf("LatentUpscaleModelLoader", "model_name", "放大模型"),
        )
        for ((cls, field, label) in cases) {
            val wf = JSONObject(
                """{"1":{"class_type":"$cls","inputs":{"$field":"x.safetensors"}}}""",
            )
            val spec = one(wf)
            assertEquals("$cls class_type", cls, spec.optString("class_type"))
            assertEquals("$cls type", "model", spec.optString("type"))
            assertEquals("$cls tier", "model", spec.optString("tier"))
            assertEquals("$cls confidence", "high", spec.optString("confidence"))
            assertTrue("$cls enabled", spec.optBoolean("enabled"))
            assertEquals("$cls label", label, spec.optString("label"))
        }
    }

    @Test
    fun loaderNonModelFieldsAreLow() {
        // 加载器上的非模型字段：无 _meta.title 时用 class_type 当标题
        val wf = JSONObject(
            """{"1":{"class_type":"VAELoader","inputs":{"vae_name":"v.safetensors","extra":"y"}}}""",
        )
        val m = specMap(inferSpecs(wf))
        val extra = m.getValue("1|extra").spec
        assertEquals("type", "text", extra.optString("type"))
        assertEquals("confidence", "low", extra.optString("confidence"))
        assertFalse("enabled", extra.optBoolean("enabled"))
        assertEquals("label", "VAELoader · extra", extra.optString("label"))
        // LoraLoader 的 strength_model 数字 → low float
        val wf2 = JSONObject(
            """{"1":{"class_type":"LoraLoader","inputs":{"lora_name":"l.safetensors","strength_model":0.8}}}""",
        )
        val m2 = specMap(inferSpecs(wf2))
        val strength = m2.getValue("1|strength_model").spec
        assertEquals("type", "float", strength.optString("type"))
        assertEquals("default", 0.8, strength.optDouble("default"), 0.0)
        assertEquals("confidence", "low", strength.optString("confidence"))
    }

    // ---------- §4.4 自定义 Text 节点 ----------

    @Test
    fun customTextNodeIsLowText() {
        val wf = JSONObject(
            """{"1":{"class_type":"Text Multiline","_meta":{"title":"AusBoss Text"},""" +
                """"inputs":{"text":"this is a long enough custom text over twenty"}}}""",
        )
        val spec = one(wf)
        assertEquals("type", "text", spec.optString("type"))
        assertEquals("confidence", "low", spec.optString("confidence"))
        assertFalse("enabled", spec.optBoolean("enabled"))
        assertEquals("label", "AusBoss Text · text", spec.optString("label"))
    }

    // ---------- §4.5 CLIPTextEncode 长度边界 ----------

    @Test
    fun clipTextEncodeLengthBoundary() {
        fun wfWith(text: String) = JSONObject(
            """{"1":{"class_type":"CLIPTextEncode","_meta":{"title":"P"},"inputs":{"text":"$text"}}}""",
        )
        val short = one(wfWith("short"))
        assertEquals("短文本 type", "text", short.optString("type"))
        assertEquals("短文本 conf", "low", short.optString("confidence"))
        assertFalse("短文本 enabled", short.optBoolean("enabled"))
        // 规则是 > 20：恰好 20 字符仍是 low text
        assertEquals("边界字符串长度", 20, "exactly twenty chars".length)
        val boundary = one(wfWith("exactly twenty chars"))
        assertEquals("20 字符 type", "text", boundary.optString("type"))
        assertEquals("20 字符 conf", "low", boundary.optString("confidence"))
        val long = one(wfWith("this prompt is definitely longer than twenty"))
        assertEquals(">20 type", "prompt_pool", long.optString("type"))
        assertEquals(">20 conf", "medium", long.optString("confidence"))
        assertTrue(">20 enabled", long.optBoolean("enabled"))
    }

    // ---------- §4.6 CLIPTextEncode 负向（标题带 Negative，短文本也算） ----------

    @Test
    fun negativeByTitle() {
        val spec = one(
            JSONObject(
                """{"1":{"class_type":"CLIPTextEncode","_meta":{"title":"Negative Prompt"},""" +
                    """"inputs":{"text":"bad"}}}""",
            ),
        )
        assertEquals("type", "text", spec.optString("type"))
        assertEquals("confidence", "high", spec.optString("confidence"))
        assertTrue("enabled", spec.optBoolean("enabled"))
        assertTrue("kwb_negative", spec.optBoolean("kwb_negative"))
        assertEquals("label", "负向提示词", spec.optString("label"))
    }

    // ---------- §4.7 TextEncode 系（r14fix3） ----------

    @Test
    fun textEncodeFamily() {
        // negative_prompt 字段 → 负向
        val neg = one(
            JSONObject(
                """{"1":{"class_type":"TextEncodeQwenImageEdit","_meta":{"title":"编辑"},""" +
                    """"inputs":{"negative_prompt":"x"}}}""",
            ),
        )
        assertEquals("negative_prompt type", "text", neg.optString("type"))
        assertTrue("negative_prompt enabled", neg.optBoolean("enabled"))
        assertTrue("kwb_negative", neg.optBoolean("kwb_negative"))
        assertEquals("label", "负向提示词", neg.optString("label"))
        // 标题含 Negative 的 prompt → 负向（不是 prompt_pool）
        val titled = one(
            JSONObject(
                """{"1":{"class_type":"TextEncodeQwenImageEdit","_meta":{"title":"Negative"},""" +
                    """"inputs":{"prompt":"this prompt is definitely longer than twenty"}}}""",
            ),
        )
        assertEquals("title-Negative type", "text", titled.optString("type"))
        assertTrue("kwb_negative", titled.optBoolean("kwb_negative"))
        // prompt 恰好 20 字符 → low text
        assertEquals(20, "exactly twenty chars".length)
        val boundary = one(
            JSONObject(
                """{"1":{"class_type":"TextEncodeQwenImageEdit","_meta":{"title":"编辑"},""" +
                    """"inputs":{"prompt":"exactly twenty chars"}}}""",
            ),
        )
        assertEquals("20 字符 type", "text", boundary.optString("type"))
        assertEquals("20 字符 conf", "low", boundary.optString("confidence"))
        assertFalse("20 字符 enabled", boundary.optBoolean("enabled"))
        // >20 → prompt_pool / medium / 提示词
        val long = one(
            JSONObject(
                """{"1":{"class_type":"TextEncodeQwenImageEdit","_meta":{"title":"编辑"},""" +
                    """"inputs":{"prompt":"this prompt is definitely longer than twenty"}}}""",
            ),
        )
        assertEquals(">20 type", "prompt_pool", long.optString("type"))
        assertEquals(">20 conf", "medium", long.optString("confidence"))
        assertEquals(">20 label", "提示词", long.optString("label"))
    }

    // ---------- §4.8 CLIPTextEncode|Mie（现状锁定） ----------

    @Test
    fun clipTextEncodeWithSuffixStaysLow() {
        // 现状锁定：正向规则用 classType == "CLIPTextEncode" 精确匹配，没走 baseClass，
        // 带节点包后缀的 CLIPTextEncode|Mie 长文本不会进 prompt_pool（LOADER_MAP 同样精确匹配）。
        val spec = one(
            JSONObject(
                """{"1":{"class_type":"CLIPTextEncode|Mie","_meta":{"title":"P"},""" +
                    """"inputs":{"text":"this prompt is definitely longer than twenty"}}}""",
            ),
        )
        assertEquals("type", "text", spec.optString("type"))
        assertEquals("confidence", "low", spec.optString("confidence"))
        assertFalse("enabled", spec.optBoolean("enabled"))
        assertEquals("label", "P · text", spec.optString("label"))
    }

    // ---------- §4.9 VHS_LoadVideo ----------

    @Test
    fun vhsLoadVideoDisabled() {
        val spec = one(
            JSONObject(
                """{"1":{"class_type":"VHS_LoadVideo","_meta":{"title":"Load Video"},""" +
                    """"inputs":{"video":"input.mp4"}}}""",
            ),
        )
        assertEquals("type", "file:video", spec.optString("type"))
        assertEquals("confidence", "high", spec.optString("confidence"))
        assertFalse("enabled（本版不支持视频输入）", spec.optBoolean("enabled"))
        assertEquals("help", "本版不支持视频输入。", spec.optString("help"))
        assertEquals("default", "input.mp4", spec.optString("default"))
    }

    // ---------- §4.10 mergeImageSizes ----------

    @Test
    fun mergeImageSizesCases() {
        // 有宽有高 → 合成一个 sizes；推断阶段不对齐 16
        val both = specMap(
            inferSpecs(
                JSONObject(
                    """{"1":{"class_type":"EmptySD3LatentImage",""" +
                        """"inputs":{"width":1000,"height":1500,"batch_size":1}}}""",
                ),
            ),
        )
        assertEquals("候选数", 2, both.size)
        val sizes = both.getValue("1|sizes").spec
        assertEquals("type", "image_sizes", sizes.optString("type"))
        assertEquals("default 原样 WxH", "1000x1500", sizes.optString("default"))
        assertEquals("confidence", "high", sizes.optString("confidence"))
        assertEquals("batch_size low int", "int", both.getValue("1|batch_size").spec.optString("type"))
        // 只有 width → sizes default "512x0"（现状锁定）
        val onlyWidth = specMap(
            inferSpecs(
                JSONObject("""{"1":{"class_type":"EmptyLatentImage","inputs":{"width":512}}}"""),
            ),
        )
        assertEquals("512x0", onlyWidth.getValue("1|sizes").spec.optString("default"))
        // 非白名单类不合并 → 两个 low int
        val other = specMap(
            inferSpecs(
                JSONObject("""{"1":{"class_type":"SomeOther","inputs":{"width":64,"height":64}}}"""),
            ),
        )
        assertEquals("候选数", 2, other.size)
        assertEquals("int", other.getValue("1|width").spec.optString("type"))
        assertEquals("int", other.getValue("1|height").spec.optString("type"))
        assertFalse("width 不勾", other.getValue("1|width").spec.optBoolean("enabled"))
    }

    // ---------- §4.11 mergeSameNameFields ----------

    @Test
    fun mergeSameNameFieldsCases() {
        // 同 class 同字段同值 → 合并进节点号小的节点，mirror_to 指向大节点
        val same = specMap(
            inferSpecs(
                JSONObject(
                    """{"1":{"class_type":"KSampler","inputs":{"steps":8,"seed":5}},""" +
                        """"2":{"class_type":"KSampler","inputs":{"steps":8,"seed":5}}}""",
                ),
            ),
        )
        assertEquals("候选数", 2, same.size)
        val steps = same.getValue("1|steps").spec
        val mirror = steps.optJSONArray("mirror_to")
        assertEquals("mirror_to 一条", 1, mirror?.length() ?: -1)
        assertEquals("目标节点", "2", mirror!!.optJSONObject(0)!!.optString("node_id"))
        assertEquals("目标字段", "steps", mirror.optJSONObject(0)!!.optString("field"))
        assertFalse("2|steps 已合并", same.containsKey("2|steps"))
        assertFalse("2|seed 已合并", same.containsKey("2|seed"))
        // cfg 值不同 → 各自独立
        val diff = specMap(
            inferSpecs(
                JSONObject(
                    """{"1":{"class_type":"KSampler","inputs":{"cfg":1.5}},""" +
                        """"2":{"class_type":"KSampler","inputs":{"cfg":2.0}}}""",
                ),
            ),
        )
        assertEquals("候选数", 2, diff.size)
        assertFalse("1|cfg 无 mirror", diff.getValue("1|cfg").spec.has("mirror_to"))
        assertFalse("2|cfg 无 mirror", diff.getValue("2|cfg").spec.has("mirror_to"))
        // 类型不同（int vs float）→ 不合并；同值 seed 仍合并
        val mixed = specMap(
            inferSpecs(
                JSONObject(
                    """{"1":{"class_type":"KSampler","inputs":{"steps":8,"seed":5}},""" +
                        """"2":{"class_type":"KSampler","inputs":{"steps":8.0,"seed":5}}}""",
                ),
            ),
        )
        assertEquals("候选数", 3, mixed.size)
        assertEquals("int steps", "int", mixed.getValue("1|steps").spec.optString("type"))
        assertEquals("float steps", "float", mixed.getValue("2|steps").spec.optString("type"))
        assertFalse("steps 不合并", mixed.getValue("1|steps").spec.has("mirror_to"))
        assertTrue("seed 同值合并", mixed.getValue("1|seed").spec.has("mirror_to"))
        assertFalse("2|seed 已合并", mixed.containsKey("2|seed"))
    }
}
