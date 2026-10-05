package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.1–3.7 fixture 逐表锁定 inferSpecs 输出（期望值来自 PLAN r15，Grok 已在 JVM 实跑核对）。
 * 所有断言按 "node_id|field" 建 Map 后比较，不依赖 JSONObject 的键顺序。
 */
class FixtureInferenceTest {

    private fun infer(name: String): List<Candidate> =
        inferSpecs(FixtureLoader.loadWf(name))

    private fun enabledCount(all: List<Candidate>): Int =
        all.count { it.spec.optBoolean("enabled") }

    private fun lowKeyTypes(all: List<Candidate>): Map<String, String> =
        all.filter { !it.spec.optBoolean("enabled") }
            .associate { Specs.key(it.spec) to it.spec.optString("type") }

    private fun assertModel(spec: JSONObject, cls: String, label: String, def: String) {
        assertEquals("class_type", cls, spec.optString("class_type"))
        assertEquals("type", "model", spec.optString("type"))
        assertEquals("tier", "model", spec.optString("tier"))
        assertEquals("confidence", "high", spec.optString("confidence"))
        assertTrue("enabled", spec.optBoolean("enabled"))
        assertEquals("label", label, spec.optString("label"))
        assertEquals("default", def, spec.optString("default"))
    }

    private fun assertPrompt(spec: JSONObject, def: String) {
        assertEquals("class_type", "CLIPTextEncode", spec.optString("class_type"))
        assertEquals("type", "prompt_pool", spec.optString("type"))
        assertEquals("confidence", "medium", spec.optString("confidence"))
        assertTrue("enabled", spec.optBoolean("enabled"))
        assertEquals("label", "提示词", spec.optString("label"))
        assertEquals("max_chars", 2000, spec.optInt("max_chars"))
        assertEquals("default", def, spec.optString("default"))
    }

    private fun assertSizes(spec: JSONObject, def: String) {
        assertEquals("type", "image_sizes", spec.optString("type"))
        assertEquals("confidence", "high", spec.optString("confidence"))
        assertTrue("enabled", spec.optBoolean("enabled"))
        assertEquals("label", "输出尺寸", spec.optString("label"))
        assertEquals("default", def, spec.optString("default"))
    }

    private fun assertSeed(spec: JSONObject, def: Long) {
        assertEquals("type", "int_random", spec.optString("type"))
        assertEquals("confidence", "high", spec.optString("confidence"))
        assertTrue("enabled", spec.optBoolean("enabled"))
        assertEquals("label", "种子值", spec.optString("label"))
        assertEquals("default", def, spec.optLong("default"))
        assertTrue("random_default", spec.optBoolean("random_default"))
    }

    // ---------- §3.1 kr2turbo_t2i ----------

    @Test
    fun kr2turboT2iEnabledTable() {
        val all = infer("kr2turbo_t2i.workflow.json")
        assertEquals("候选总数", 17, all.size)
        assertEquals("enabled 数", 6, enabledCount(all))
        val m = specMap(all)
        // width/height 已合并进 sizes，不再单独出现
        assertFalse("9|width", m.containsKey("9|width"))
        assertFalse("9|height", m.containsKey("9|height"))
        assertModel(m.getValue("2|unet_name").spec, "UNETLoader", "扩散模型", "Krea2\\Krea2Turbo_FP8.safetensors")
        assertModel(m.getValue("5|clip_name").spec, "CLIPLoader", "文本编码器", "Krea2\\qwen3-vl-4b-heretic-bf16.safetensors")
        assertModel(m.getValue("6|vae_name").spec, "VAELoader", "VAE", "Krea2\\qwen_image_vae.safetensors")
        assertPrompt(m.getValue("7|text").spec, "placeholder prompt: a person standing by a window, soft light")
        assertSizes(m.getValue("9|sizes").spec, "1152x1728")
        assertSeed(m.getValue("12|seed").spec, 80913082042543L)
        // 现状锁定：JSON 里整数写法的小数参数（cfg=1 / denoise=1 / shift=3）被推成 int，与真机一致
        assertEquals(
            "low key→type",
            mapOf(
                "2|weight_dtype" to "text",
                "4|shift" to "int",
                "5|type" to "text",
                "5|device" to "text",
                "9|batch_size" to "int",
                "12|scheduler" to "text",
                "12|denoise" to "int",
                "12|cfg" to "int",
                "12|sampler_name" to "text",
                "12|steps" to "int",
                "16|filename_prefix" to "text",
            ),
            lowKeyTypes(all),
        )
    }

    // ---------- §3.2 kr2turbo_i2i ----------

    @Test
    fun kr2turboI2iEnabledTable() {
        val all = infer("kr2turbo_i2i.workflow.json")
        assertEquals("候选总数", 16, all.size)
        assertEquals("enabled 数", 6, enabledCount(all))
        val m = specMap(all)
        assertModel(m.getValue("2|unet_name").spec, "UNETLoader", "扩散模型", "Krea2\\Krea2Turbo_FP8.safetensors")
        assertModel(m.getValue("5|clip_name").spec, "CLIPLoader", "文本编码器", "Krea2\\qwen3-vl-4b-heretic-bf16.safetensors")
        assertModel(m.getValue("6|vae_name").spec, "VAELoader", "VAE", "Krea2\\qwen_image_vae.safetensors")
        assertPrompt(m.getValue("7|text").spec, "placeholder prompt: a person standing by a window, soft light")
        val image = m.getValue("10|image").spec
        assertEquals("class_type", "LoadImage", image.optString("class_type"))
        assertEquals("type", "file:image", image.optString("type"))
        assertEquals("confidence", "high", image.optString("confidence"))
        assertTrue("enabled", image.optBoolean("enabled"))
        assertFalse("file 槽无 label 键", image.has("label"))
        assertEquals("default", "example.png", image.optString("default"))
        assertEquals("help", "上传本次生成使用的参考图片。", image.optString("help"))
        assertSeed(m.getValue("13|seed").spec, 297982133758750L)
        // i2i 没有 latent 节点 → 没有 image_sizes
        assertFalse("无 image_sizes", all.any { it.spec.optString("type") == "image_sizes" })
        assertEquals(
            "low key→type",
            mapOf(
                "2|weight_dtype" to "text",
                "4|shift" to "int",
                "5|type" to "text",
                "5|device" to "text",
                "13|steps" to "int",
                "13|cfg" to "int",
                "13|sampler_name" to "text",
                "13|scheduler" to "text",
                "13|denoise" to "float",
                "16|filename_prefix" to "text",
            ),
            lowKeyTypes(all),
        )
    }

    @Test
    fun kr2turboI2iDenoiseIsFloat() {
        // 现状锁定：android-json（Android 同源实现）把 0.55 解析成 Double，
        // inferOne 用 `is Double || is Float` 判小数 → float。换 org.json:json 会变 BigDecimal
        // → 被推成 int、default 0，与真机不一致。
        val m = specMap(infer("kr2turbo_i2i.workflow.json"))
        val denoise = m.getValue("13|denoise").spec
        assertEquals("type", "float", denoise.optString("type"))
        assertEquals("default", 0.55, denoise.optDouble("default"), 0.0)
        assertEquals("current_value", 0.55, denoise.optDouble("current_value"), 0.0)
        assertEquals("confidence", "low", denoise.optString("confidence"))
        assertFalse("enabled", denoise.optBoolean("enabled"))
        assertEquals("label", "2 — image to image · denoise", denoise.optString("label"))
    }

    // ---------- §3.3 wan22_i2v_4step ----------

    @Test
    fun wan22EnabledTable() {
        val all = infer("wan22_i2v_4step.workflow.json")
        assertEquals("候选总数", 48, all.size)
        assertEquals("enabled 数", 13, enabledCount(all))
        val m = specMap(all)

        val prompt = m.getValue("6|text").spec
        assertEquals("class_type", "CLIPTextEncode", prompt.optString("class_type"))
        assertEquals("type", "prompt_pool", prompt.optString("type"))
        assertEquals("confidence", "medium", prompt.optString("confidence"))
        assertTrue("enabled", prompt.optBoolean("enabled"))
        assertEquals("label", "提示词", prompt.optString("label"))
        assertEquals("default", "placeholder motion prompt: the subject walks forward slowly", prompt.optString("default"))

        val neg = m.getValue("7|text").spec
        assertEquals("type", "text", neg.optString("type"))
        assertEquals("confidence", "high", neg.optString("confidence"))
        assertTrue("enabled", neg.optBoolean("enabled"))
        assertTrue("kwb_negative", neg.optBoolean("kwb_negative"))
        assertEquals("label", "负向提示词", neg.optString("label"))
        assertEquals("default", "placeholder negative prompt: blurry, low quality", neg.optString("default"))

        assertModel(m.getValue("38|clip_name").spec, "CLIPLoader", "文本编码器", "Wan\\umt5_xxl_fp8_e4m3fn_scaled.safetensors")
        assertModel(m.getValue("39|vae_name").spec, "VAELoader", "VAE", "Wan_2.1\\wan_2.1_vae.safetensors")
        assertSeed(m.getValue("57|noise_seed").spec, 77L)

        val image = m.getValue("62|image").spec
        assertEquals("type", "file:image", image.optString("type"))
        assertEquals("confidence", "high", image.optString("confidence"))
        assertTrue("enabled", image.optBoolean("enabled"))
        assertFalse("file 槽无 label 键", image.has("label"))
        assertEquals("default", "rabbit.png", image.optString("default"))

        val length = m.getValue("63|length").spec
        assertEquals("type", "int", length.optString("type"))
        assertEquals("confidence", "high", length.optString("confidence"))
        assertTrue("enabled", length.optBoolean("enabled"))
        assertTrue("kwb_gen", length.optBoolean("kwb_gen"))
        assertEquals("label", "帧数", length.optString("label"))
        assertEquals("min", 5, length.optInt("min"))
        assertEquals("max", 241, length.optInt("max"))
        assertEquals("default", 81L, length.optLong("default"))

        assertModel(m.getValue("68|unet_name").spec, "UnetLoaderGGUF", "扩散模型", "Wan2.2\\I2V\\Wan2.2-I2V-A14B-HighNoise-Q6_K.gguf")
        // 两个 GGUF 加载器值不同 → 不合并，各自是 enabled 行
        assertModel(m.getValue("73|unet_name").spec, "UnetLoaderGGUF", "扩散模型", "Wan2.2\\I2V\\Wan2.2-I2V-A14B-LowNoise-Q6_K.gguf")

        val fps = m.getValue("76|frame_rate").spec
        assertEquals("type", "int", fps.optString("type"))
        assertEquals("confidence", "high", fps.optString("confidence"))
        assertTrue("enabled", fps.optBoolean("enabled"))
        assertTrue("kwb_gen", fps.optBoolean("kwb_gen"))
        assertEquals("label", "帧率", fps.optString("label"))
        assertEquals("min", 1, fps.optInt("min"))
        assertEquals("max", 60, fps.optInt("max"))
        assertEquals("default", 16L, fps.optLong("default"))

        assertSizes(m.getValue("77|sizes").spec, "1280x720")
        assertModel(m.getValue("81|lora_name").spec, "LoraLoaderModelOnly", "LoRA", "Wan2.2\\lightx2v_i2v_high_noise_model.safetensors")
        assertModel(m.getValue("82|lora_name").spec, "LoraLoaderModelOnly", "LoRA", "Wan2.2\\lightx2v_i2v_low_noise_model.safetensors")

        assertEquals(
            "low key→type",
            mapOf(
                "38|type" to "text",
                "38|device" to "text",
                "54|shift" to "int",
                "57|add_noise" to "text",
                "57|steps" to "int",
                "57|cfg" to "int",
                "57|sampler_name" to "text",
                "57|scheduler" to "text",
                "57|start_at_step" to "int",
                "57|end_at_step" to "int",
                "57|return_with_leftover_noise" to "text",
                "58|add_noise" to "text",
                "58|start_at_step" to "int",
                "58|end_at_step" to "int",
                "58|return_with_leftover_noise" to "text",
                "63|batch_size" to "int",
                "70|sage_attention" to "text",
                "70|allow_compile" to "bool",
                "71|enable_fp16_accumulation" to "bool",
                "76|loop_count" to "int",
                "76|filename_prefix" to "text",
                "76|format" to "text",
                "76|pix_fmt" to "text",
                "76|crf" to "int",
                "76|save_metadata" to "bool",
                "76|trim_to_audio" to "bool",
                "76|pingpong" to "bool",
                "76|save_output" to "bool",
                "77|upscale_method" to "text",
                "77|keep_proportion" to "text",
                "77|pad_color" to "text",
                "77|crop_position" to "text",
                "77|divisible_by" to "int",
                "77|device" to "text",
                "81|strength_model" to "int",
            ),
            lowKeyTypes(all),
        )
    }

    @Test
    fun wan22MirrorTo() {
        val m = specMap(infer("wan22_i2v_4step.workflow.json"))
        // 同名同类同值合并（round6）：10 条 mirror_to，主节点都是节点号小的那个
        val expected = mapOf(
            "54|shift" to "55",
            "57|cfg" to "58",
            "57|steps" to "58",
            "57|scheduler" to "58",
            "57|sampler_name" to "58",
            "57|noise_seed" to "58",
            "70|sage_attention" to "75",
            "70|allow_compile" to "75",
            "71|enable_fp16_accumulation" to "72",
            "81|strength_model" to "82",
        )
        assertEquals("mirror_to 条数", 10, expected.size)
        for ((key, targetNode) in expected) {
            val spec = m.getValue(key).spec
            assertEquals("$key 主节点", key.substringBefore('|'), Specs.nodeId(spec))
            val arr = spec.optJSONArray("mirror_to")
            assertEquals("$key mirror_to 只有一条", 1, arr?.length() ?: -1)
            val t = arr!!.optJSONObject(0)!!
            assertEquals("$key 目标节点", targetNode, t.optString("node_id"))
            assertEquals("$key 目标字段", key.substringAfter('|'), t.optString("field"))
        }
        // 被合并掉的候选不能独立出现
        for (key in listOf(
            "55|shift", "58|cfg", "58|steps", "58|noise_seed", "58|scheduler", "58|sampler_name",
            "72|enable_fp16_accumulation", "75|sage_attention", "75|allow_compile", "82|strength_model",
        )) {
            assertFalse("被合并：$key", m.containsKey(key))
        }
        // 值不同的不合并：各自独立出现且无 mirror_to
        for (key in listOf(
            "57|add_noise", "58|add_noise",
            "57|start_at_step", "58|start_at_step",
            "57|end_at_step", "58|end_at_step",
            "57|return_with_leftover_noise", "58|return_with_leftover_noise",
        )) {
            assertTrue("独立：$key", m.containsKey(key))
            assertFalse("无 mirror_to：$key", m.getValue(key).spec.has("mirror_to"))
        }
    }

    // ---------- §3.4 florence2_caption ----------

    @Test
    fun florence2EnabledTable() {
        val all = infer("florence2_caption.workflow.json")
        assertEquals("候选总数", 10, all.size)
        assertEquals("enabled 数", 2, enabledCount(all))
        val m = specMap(all)
        val seed = m.getValue("1|seed").spec
        assertEquals("class_type", "Florence2DescribeImage|Mie", seed.optString("class_type"))
        assertEquals("type", "int_random", seed.optString("type"))
        assertEquals("confidence", "high", seed.optString("confidence"))
        assertTrue("enabled", seed.optBoolean("enabled"))
        assertEquals("label", "种子值", seed.optString("label"))
        assertEquals("default", 1031366672835313L, seed.optLong("default"))
        val image = m.getValue("3|image").spec
        assertEquals("type", "file:image", image.optString("type"))
        assertEquals("default", "Knitted-cat.png", image.optString("default"))
        assertFalse("file 槽无 label 键", image.has("label"))
        // 现状锁定：Florence2ModelLoader|Mie 不在 LOADER_MAP（精确匹配）→ model_name 只是 low text
        val modelName = m.getValue("2|model_name").spec
        assertEquals("type", "text", modelName.optString("type"))
        assertEquals("confidence", "low", modelName.optString("confidence"))
        assertFalse("enabled", modelName.optBoolean("enabled"))
        assertEquals(
            "low key→type",
            mapOf(
                "1|task" to "text",
                "1|max_new_tokens" to "int",
                "1|num_beams" to "int",
                "1|do_sample" to "bool",
                "1|keep_model_loaded" to "bool",
                "2|model_name" to "text",
                "2|precision" to "text",
                "2|attention" to "text",
            ),
            lowKeyTypes(all),
        )
    }

    // ---------- §3.5 qwen21_t2i ----------

    @Test
    fun qwen21T2iEnabledTable() {
        val all = infer("qwen21_t2i.workflow.json")
        assertEquals("候选总数", 20, all.size)
        assertEquals("enabled 数", 7, enabledCount(all))
        val m = specMap(all)
        assertModel(m.getValue("1|unet_name").spec, "UNETLoader", "扩散模型", "qwen_image_2.1_int8_convrot.safetensors")
        assertModel(m.getValue("2|clip_name").spec, "CLIPLoader", "文本编码器", "qwen3vl_8b_int8_convrot.safetensors")
        assertModel(m.getValue("3|vae_name").spec, "VAELoader", "VAE", "qwen_image_2.1_vae_bf16.safetensors")
        val prompt = m.getValue("5|prompt").spec
        assertEquals("class_type", "TextEncodeQwenImage21", prompt.optString("class_type"))
        assertEquals("type", "prompt_pool", prompt.optString("type"))
        assertEquals("confidence", "medium", prompt.optString("confidence"))
        assertTrue("enabled", prompt.optBoolean("enabled"))
        // 节点标题是「提示词」，label 不能是「提示词 · prompt」
        assertEquals("label", "提示词", prompt.optString("label"))
        assertEquals("default", "placeholder prompt: a city street at night, photo", prompt.optString("default"))
        val neg = m.getValue("5|negative_prompt").spec
        assertEquals("type", "text", neg.optString("type"))
        assertEquals("confidence", "high", neg.optString("confidence"))
        assertTrue("enabled", neg.optBoolean("enabled"))
        assertTrue("kwb_negative", neg.optBoolean("kwb_negative"))
        assertEquals("label", "负向提示词", neg.optString("label"))
        assertEquals("空串也照样识别", "", neg.optString("default"))
        assertSizes(m.getValue("6|sizes").spec, "1088x1920")
        assertSeed(m.getValue("7|seed").spec, 447606998181262L)
        assertEquals(
            "low key→type",
            mapOf(
                "1|weight_dtype" to "text",
                "2|type" to "text",
                "2|device" to "text",
                "4|dtype" to "text",
                "4|device" to "text",
                "5|resolution" to "int",
                "6|batch_size" to "int",
                "7|scheduler" to "text",
                "7|denoise" to "int",
                "7|cfg" to "int",
                "7|sampler_name" to "text",
                "7|steps" to "int",
                "9|filename_prefix" to "text",
            ),
            lowKeyTypes(all),
        )
    }

    // ---------- §3.6 qwen21_edit ----------

    @Test
    fun qwen21EditEnabledTable() {
        val all = infer("qwen21_edit.workflow.json")
        assertEquals("候选总数", 22, all.size)
        assertEquals("enabled 数", 7, enabledCount(all))
        val m = specMap(all)
        assertModel(m.getValue("1|unet_name").spec, "UNETLoader", "扩散模型", "qwen_image_2.1_bf16.safetensors")
        assertModel(m.getValue("2|clip_name").spec, "CLIPLoader", "文本编码器", "qwen3vl_8b_int8_convrot.safetensors")
        assertModel(m.getValue("3|vae_name").spec, "VAELoader", "VAE", "qwen_image_2.1_vae_bf16.safetensors")
        assertModel(m.getValue("4|lora_name").spec, "LoraLoaderModelOnly", "LoRA", "Qwen-image\\example_edit_lora.safetensors")
        val image = m.getValue("5|image").spec
        assertEquals("type", "file:image", image.optString("type"))
        assertEquals("default", "input.png", image.optString("default"))
        assertFalse("file 槽无 label 键", image.has("label"))
        val neg = m.getValue("7|negative_prompt").spec
        assertEquals("type", "text", neg.optString("type"))
        assertTrue("enabled", neg.optBoolean("enabled"))
        assertTrue("kwb_negative", neg.optBoolean("kwb_negative"))
        assertEquals("label", "负向提示词", neg.optString("label"))
        assertSeed(m.getValue("8|seed").spec, 6202L)
        assertEquals(
            "low key→type",
            mapOf(
                "1|weight_dtype" to "text",
                "2|type" to "text",
                "2|device" to "text",
                "4|strength_model" to "int",
                "6|upscale_method" to "text",
                "6|megapixels" to "int",
                "6|resolution_steps" to "int",
                "7|prompt" to "text",
                "7|resolution" to "int",
                "8|scheduler" to "text",
                "8|denoise" to "int",
                "8|cfg" to "int",
                "8|sampler_name" to "text",
                "8|steps" to "int",
                "10|filename_prefix" to "text",
            ),
            lowKeyTypes(all),
        )
    }

    @Test
    fun qwen21EditShortPromptStaysLow() {
        // 现状锁定：占位 prompt「change background」17 字符 ≤ 20，触发「短文本不当提示词」→
        // low text、默认不勾。用户需在导入界面手动勾选；勾选后 r14fix3 的渲染逻辑会把它当主提示词。
        val m = specMap(infer("qwen21_edit.workflow.json"))
        val prompt = m.getValue("7|prompt").spec
        assertEquals("type", "text", prompt.optString("type"))
        assertEquals("confidence", "low", prompt.optString("confidence"))
        assertFalse("enabled", prompt.optBoolean("enabled"))
        assertEquals("label", "编辑指令 · prompt", prompt.optString("label"))
        assertEquals("default", "change background", prompt.optString("default"))
    }

    // ---------- §3.7 krea2_t2i ----------

    @Test
    fun krea2T2iEnabledTable() {
        val all = infer("krea2_t2i.workflow.json")
        assertEquals("候选总数", 16, all.size)
        assertEquals("enabled 数", 6, enabledCount(all))
        val m = specMap(all)
        assertSeed(m.getValue("3|seed").spec, 0L)
        assertSizes(m.getValue("5|sizes").spec, "832x1216")
        assertPrompt(m.getValue("6|text").spec, "placeholder prompt — batch script replaces this")
        assertModel(m.getValue("10|unet_name").spec, "UNETLoader", "扩散模型", "Krea2\\Krea2Turbo_bf16.safetensors")
        assertModel(m.getValue("11|clip_name").spec, "CLIPLoader", "文本编码器", "Krea2\\qwen3-vl-4b-heretic-bf16.safetensors")
        assertModel(m.getValue("12|vae_name").spec, "VAELoader", "VAE", "Krea2\\qwen_image_vae.safetensors")
        assertEquals(
            "low key→type",
            mapOf(
                "10|weight_dtype" to "text",
                "11|type" to "text",
                "11|device" to "text",
                "5|batch_size" to "int",
                "3|steps" to "int",
                "3|cfg" to "int",
                "3|sampler_name" to "text",
                "3|scheduler" to "text",
                "3|denoise" to "int",
                "29|filename_prefix" to "text",
            ),
            lowKeyTypes(all),
        )
    }
}
