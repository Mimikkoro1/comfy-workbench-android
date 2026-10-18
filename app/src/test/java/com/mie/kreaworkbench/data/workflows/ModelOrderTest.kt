package com.mie.kreaworkbench.data.workflows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelOrderTest {
    @Test
    fun fieldNamesFixOrderWhenClassTypeWasStripped() {
        assertEquals(0, modelDisplayOrder(spec("unet_name", "高噪扩散模型")))
        assertEquals(0, modelDisplayOrder(spec("ckpt_name", "Checkpoint")))
        assertEquals(1, modelDisplayOrder(spec("clip_name", "文本编码器")))
        assertEquals(2, modelDisplayOrder(spec("vae_name", "VAE")))
        assertEquals(3, modelDisplayOrder(spec("lora_name", "LoRA")))
        assertEquals(4, modelDisplayOrder(spec("model_name", "放大模型")))
    }

    @Test
    fun visionIsNotATextEncoder() {
        val spec = spec("clip_name", "视觉编码器").put("class_type", "CLIPVisionLoader")
        assertEquals(4, modelDisplayOrder(spec))
    }

    @Test
    fun workflowNodeSuppliesClassType() {
        val spec = spec("model", "模型").put("node_id", "7")
        val wf = JSONObject().put("7", JSONObject().put("class_type", "UNETLoader"))
        assertEquals(0, modelDisplayOrder(spec, wf))
    }

    private fun spec(field: String, label: String) = JSONObject()
        .put("node_id", "1")
        .put("field", field)
        .put("label", label)
        .put("type", "model")
}
