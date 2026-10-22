package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置模板资产守卫（r35，r37 / r38 E7 更新）：直接读仓库里 src/main/assets/builtin_templates 目录下的 6 个 JSON，
 * 断言「能被导入识别链吃下」「模型槽预填官方默认名（r37 定稿白名单）」「没有私人内容/本地路径残留」。
 * 纯 JVM 测试，不碰 Android 运行时；资产路径从 user.dir 向上找，兼容从仓库根或 app/ 起跑。
 */
class BuiltinTemplateTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "src/main/assets/builtin_templates")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("builtin_templates assets dir not found from ${System.getProperty("user.dir")}")
    }

    private fun loadText(entry: BuiltinTemplates.Entry): String {
        val f = File(assetsDir(), entry.asset)
        assertTrue("missing asset ${entry.asset}", f.isFile)
        return f.readText()
    }

    private fun loadWf(entry: BuiltinTemplates.Entry): JSONObject = JSONObject(loadText(entry))

    @Test
    fun allListedAssetsExistAndAreApiWorkflows() {
        for (t in BuiltinTemplates.all) {
            val wf = loadWf(t)
            assertTrue("${t.asset} should be an API workflow", isApiWorkflow(wf))
            assertTrue("${t.asset} output node not pickable", pickOutputNode(wf).isNotBlank())
        }
    }

    /** r37 定稿（PLAN §3.2）：官方工作流提取的默认模型名，模板资产 → node|field → 文件名。 */
    private val officialModelDefaults = mapOf(
        "Krea2_T2I.json" to mapOf(
            "2|unet_name" to "krea2_turbo_fp8_scaled.safetensors",
            "5|clip_name" to "qwen3vl_4b_fp8_scaled.safetensors",
            "6|vae_name" to "qwen_image_vae.safetensors",
        ),
        "Krea2_I2I.json" to mapOf(
            "2|unet_name" to "krea2_turbo_fp8_scaled.safetensors",
            "5|clip_name" to "qwen3vl_4b_fp8_scaled.safetensors",
            "6|vae_name" to "qwen_image_vae.safetensors",
        ),
        "Qwen21_T2I.json" to mapOf(
            "1|unet_name" to "qwen_image_2.1_int8_convrot.safetensors",
            "2|clip_name" to "qwen3vl_8b_int8_convrot.safetensors",
            "3|vae_name" to "qwen_image_2.1_vae_bf16.safetensors",
        ),
        "Qwen21_Edit.json" to mapOf(
            "476|unet_name" to "qwen_image_2.1_int8_convrot.safetensors",
            "477|clip_name" to "qwen3vl_8b_int8_convrot.safetensors",
            "478|vae_name" to "qwen_image_2.1_vae_bf16.safetensors",
        ),
        "Zimage_T2I.json" to mapOf(
            "16|unet_name" to "z_image_turbo_bf16.safetensors",
            "18|clip_name" to "qwen_3_4b.safetensors",
            "17|vae_name" to "ae.safetensors",
        ),
        // r38 E7：Moss 文生图（官方 Moss_image_t2i.json，节点重编号 1~9）
        "Moss_T2I.json" to mapOf(
            "1|unet_name" to "moss_image_sft_int8_convrot.safetensors",
            "2|clip_name" to "qwen3.5_9b_w4a8_gptq.safetensors",
            "3|vae_name" to "flux2-vae_small.safetensors",
        ),
    )

    @Test
    fun modelFieldsAreOfficialDefaults() {
        for (t in BuiltinTemplates.all) {
            val wf = loadWf(t)
            val modelSpecs = inferSpecs(wf).filter { it.spec.optString("type") == "model" }
            assertTrue("${t.asset} should expose model specs", modelSpecs.isNotEmpty())
            val expected = officialModelDefaults.getValue(t.asset)
            assertEquals(
                "${t.asset} model slot set drifted",
                expected.keys,
                modelSpecs.map { Specs.key(it.spec) }.toSet(),
            )
            for (cand in modelSpecs) {
                val key = Specs.key(cand.spec)
                assertEquals(
                    "${t.asset} model $key must stay the official default",
                    expected.getValue(key),
                    cand.spec.optString("default"),
                )
            }
        }
    }

    @Test
    fun recognitionProducesPromptAndSlotSpecs() {
        for (t in BuiltinTemplates.all) {
            val wf = loadWf(t)
            val def = buildDefinition(
                displayName = t.displayName,
                workflowFileName = t.asset,
                outputNode = pickOutputNode(wf),
                orderedSpecs = inferSpecs(wf).filter { it.spec.optBoolean("enabled") }.map { it.spec },
                outputKind = inferOutputKind(wf),
            )
            val specs = def.optJSONArray("user_facing_inputs")!!
            assertTrue("${t.asset} definition has no specs", specs.length() > 0)
            val types = (0 until specs.length()).map { specs.optJSONObject(it)!!.optString("type") }
            // 每个模板都要有模型选择和提示词/参考图槽，缺一不可用
            assertTrue("${t.asset} no model spec", "model" in types)
            assertTrue(
                "${t.asset} neither prompt nor image slot",
                "prompt_pool" in types || "file:image" in types,
            )
        }
    }

    @Test
    fun noPrivateOrLocalContentLeft() {
        val forbidden = listOf(
            ".sft", // 非官方模型扩展名；r37 起官方默认 safetensors 名放行（白名单见下）
            "172.16.", "StableYogi", "Muse", // 内网地址 / 作者署名
            "微信图片", ".jpg", // 本地文件名
            "NSFW", "nsfw", "脱光", // 私人提示词内容
            "VisualLoRALoader", "lora_data", // LoRA 挂载
        )
        val officialNames = officialModelDefaults.values.flatMap { it.values }.toSet()
        for (t in BuiltinTemplates.all) {
            val text = loadText(t)
            for (bad in forbidden) {
                assertTrue("${t.asset} leaks \"$bad\"", bad !in text)
            }
            // r37：资产里出现的每个模型文件名都必须在官方默认名白名单里（防私人/本机文件名回流）
            val found = Regex("[A-Za-z0-9._/-]+\\.safetensors").findAll(text).map { it.value }.toSet()
            for (name in found) {
                assertTrue("${t.asset} leaks non-official model file \"$name\"", name in officialNames)
            }
        }
    }

    @Test
    fun seedsAreZeroed() {
        for (t in BuiltinTemplates.all) {
            val wf = loadWf(t)
            val keys = wf.keys()
            while (keys.hasNext()) {
                val nid = keys.next()
                val inputs = wf.optJSONObject(nid)?.optJSONObject("inputs") ?: continue
                val ik = inputs.keys()
                while (ik.hasNext()) {
                    val v = inputs.opt(ik.next())
                    if (v is Number) {
                        assertTrue("${t.asset} node $nid has a non-zeroed seed-like value $v", v.toLong() < 1_000_000_000L)
                    }
                }
            }
        }
    }

    @Test
    fun manifestIdsAreUniqueAndFixed() {
        val ids = BuiltinTemplates.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("builtin-") })
    }
}
