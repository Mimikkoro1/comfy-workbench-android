package com.mie.kreaworkbench.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入工作流（custom 模式）：参数全部来自动态表单，「模型和采样」页（当前工作流设置页）与生成页共用同一份值。
 * values 键为 "<node_id>|<field>"；prompt 只用于任务列表/历史显示；
 * seed/seed_random 取第一个 int_random spec 的当前值与开关；output_node 为出图节点。
 */
fun buildCustomBody(
    clientId: String,
    workflowId: String,
    prompt: String,
    values: JSONObject,
    batch: Int,
    seed: Long,
    seedRandom: Boolean,
    outputNode: String,
    outputKind: String = "image",
    workflowName: String = "",
    loraStates: JSONObject? = null,
    loraStrengths: JSONObject? = null,
): JSONObject = JSONObject()
    .put("client_job_id", clientId)
    .put("mode", "custom")
    .put("workflow_id", workflowId)
    .put("prompt", prompt.take(12000))
    .put("batch_count", batch.coerceIn(1, 8))
    .put("seed", seed)
    .put("seed_random", seedRandom)
    .put("output_node", outputNode)
    .put("kwb_output", outputKind)
    .put("values", values)
    // 仅供图片信息页显示「用的哪个工作流」（paramsJson = 提交 body）；服务端/构图不读这个键
    .apply { if (workflowName.isNotBlank()) put("workflow_name", workflowName.take(200)) }
    // LoRA 开关 / 强度（round16）：服务端不读，只有 buildCustomWorkflows 用；老 body 无这些键 → 不变
    .apply { if (loraStates != null && loraStates.length() > 0) put("kwb_lora", loraStates) }
    .apply { if (loraStrengths != null && loraStrengths.length() > 0) put("kwb_lora_strength", loraStrengths) }

private val SIZE_TOKEN = Regex("""\d+[xX×]\d+""")

/**
 * 看图页「重新生成」：沿用这张图记下的提交体，只换新任务号、强制随机种子、只出 1 张。
 * 有宽高时，把尺寸数组收成这一张的尺寸，避免把当时的整批尺寸再跑一遍。
 * 没有 workflow_id 或正文不是 JSON 时返回 null。
 */
fun regenerateBody(
    paramsJson: String,
    prompt: String,
    width: Int,
    height: Int,
    newClientId: String,
): JSONObject? {
    val body = try {
        JSONObject(paramsJson)
    } catch (_: Exception) {
        return null
    }
    if (body.optString("workflow_id").isBlank()) return null
    body.put("client_job_id", newClientId)
    if (body.optString("mode").isBlank()) body.put("mode", "custom")
    body.put("seed_random", true)
    body.put("batch_count", 1)
    val text = prompt.trim()
    if (text.isNotBlank()) body.put("prompt", text.take(12000))
    val values = body.optJSONObject("values")
    if (values != null && width > 0 && height > 0) {
        val size = "${width}x${height}"
        val names = ArrayList<String>()
        val keys = values.keys()
        while (keys.hasNext()) names.add(keys.next())
        for (key in names) {
            val arr = values.optJSONArray(key) ?: continue
            if (arr.length() == 0) continue
            var sizes = true
            for (i in 0 until arr.length()) {
                if (!SIZE_TOKEN.matches(arr.optString(i))) {
                    sizes = false
                    break
                }
            }
            if (sizes) values.put(key, JSONArray().put(size))
        }
    }
    body.remove("comfy_prompts")
    body.remove("submit_attempted")
    body.remove("manual_retry")
    return body
}
