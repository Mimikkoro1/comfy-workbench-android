package com.mie.kreaworkbench.data

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
): JSONObject = JSONObject()
    .put("client_job_id", clientId)
    .put("mode", "custom")
    .put("workflow_id", workflowId)
    .put("prompt", prompt.take(4000))
    .put("batch_count", batch.coerceIn(1, 8))
    .put("seed", seed)
    .put("seed_random", seedRandom)
    .put("output_node", outputNode)
    .put("kwb_output", outputKind)
    .put("values", values)
