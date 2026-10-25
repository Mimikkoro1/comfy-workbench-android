package com.mie.kreaworkbench.data.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** r44：value_not_in_list 的缺失名收集——模型文件字段与其它 combo 选项值分开，候选项列表不进摘要。 */
class ModelMissingParseTest {
    private fun body(errors: String): JSONObject = JSONObject(
        """
        {"error": {"message": "Prompt outputs failed validation",
                   "details": "",
                   "errors": [$errors]}}
        """.trimIndent(),
    )

    @Test
    fun modelFileFieldGoesToModelNames() {
        val json = body(
            """
            {"type": "value_not_in_list", "message": "Value not in list",
             "details": "vae_name: 'Moss Image/flux2-vae.safetensors' not in [ 'a.safetensors', 'b.safetensors' ]",
             "extra_info": {"input_name": "vae_name"}}
            """.trimIndent(),
        )
        val (models, options) = missingValueNames(json)
        assertEquals(listOf("Moss Image/flux2-vae.safetensors"), models)
        assertEquals(emptyList<String>(), options)
    }

    @Test
    fun nonModelFieldGoesToOptionNames() {
        val json = body(
            """
            {"type": "value_not_in_list", "message": "Value not in list",
             "details": "attention: 'comfy kitchen attention' not in [ 'default', 'sageattention' ]",
             "extra_info": {"input_name": "attention"}}
            """.trimIndent(),
        )
        val (models, options) = missingValueNames(json)
        assertEquals(emptyList<String>(), models)
        assertEquals(listOf("comfy kitchen attention"), options)
    }

    @Test
    fun modelFieldWithoutQuotedMarkerFallsBackToOldExtraction() {
        // r37.1 的老格式兜底：details 没有可解析的「'x' not in [」时模型字段仍按旧截取（field: 前缀形）
        val json = body(
            """
            {"type": "value_not_in_list", "message": "Value not in list",
             "details": "ckpt_name: 'old.safetensors'",
             "extra_info": {"input_name": "ckpt_name"}}
            """.trimIndent(),
        )
        val (models, _) = missingValueNames(json)
        assertEquals(listOf("old.safetensors"), models)
    }

    @Test
    fun otherErrorTypesIgnored() {
        val json = body(
            """
            {"type": "return_of_unused_input", "message": "Return of unused input",
             "details": "'x' not in [ 'y' ]",
             "extra_info": {"input_name": "vae_name"}}
            """.trimIndent(),
        )
        val (models, options) = missingValueNames(json)
        assertEquals(emptyList<String>(), models)
        assertEquals(emptyList<String>(), options)
    }
}
