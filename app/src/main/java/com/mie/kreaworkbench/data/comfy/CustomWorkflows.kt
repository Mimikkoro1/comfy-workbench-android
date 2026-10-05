package com.mie.kreaworkbench.data.comfy

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.data.workflows.WorkflowStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** 一张图的已填好工作流：seed 记进 comfy_prompts，width/height 供 per-image 尺寸记录。 */
data class BuiltFlow(
    val seed: Long,
    val wf: JSONObject,
    val width: Int,
    val height: Int,
)

/**
 * 导入工作流（mode == "custom"）的通用填参，对应 PC runner 的 replacements 语义：
 * 每张图解析一份新的工作流副本，先写 fixed_patches，再按 spec 顺序写值；
 * text/prompt_pool/select/file:image 的 mirror_to/also_patch 同值写到附加目标。
 * 出图顺序「尺寸在外层、批次在内层」，与 comfy_prompts 的 index 一致。
 */
suspend fun buildCustomWorkflows(ctx: Context, body: JSONObject): List<BuiltFlow> {
    val workflowId = body.optString("workflow_id")
    val (wfJson, defJson) = WorkflowStore(ctx).get(workflowId)
        ?: throw ApiException(ctx.str(R.string.err_workflow_deleted), network = false)
    val specs = defJson.optJSONArray("user_facing_inputs") ?: JSONArray()
    val n = body.optInt("batch_count", 1).coerceIn(1, 8)
    val baseSeed = body.optLong("seed", 0L)
    val randomize = body.optBoolean("seed_random", true)
    val values = body.optJSONObject("values") ?: JSONObject()
    val fixedPatches = Specs.fixedPatches(defJson)

    // 选中尺寸（image_sizes spec 的 values 是 "WxH" 数组）；没有 image_sizes spec 就单尺寸走 batch_count 张
    val sizesSpec = firstSpec(specs, "image_sizes")
    val sizes: List<Pair<Int, Int>> = if (sizesSpec != null) {
        parseSizes(values.optJSONArray(keyOf(sizesSpec)))
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                // 没选尺寸：退回工作流当前值
                val inputs = wfJson.optJSONObject(sizesSpec.optString("node_id"))?.optJSONObject("inputs")
                val w = (inputs?.opt("width") as? Number)?.toInt()?.align16()
                val h = (inputs?.opt("height") as? Number)?.toInt()?.align16()
                if (w != null && h != null) listOf(w to h) else emptyList()
            }
    } else {
        listOf((-1) to (-1)) // 占位：没有 image_sizes 时不写尺寸
    }

    val firstIntRandom = firstSpec(specs, "int_random")
    val result = ArrayList<BuiltFlow>(sizes.size * n)
    for ((w, h) in sizes) { // 尺寸在外层
        repeat(n) { // 批次在内层
            val wf = JSONObject(wfJson.toString())
            for ((nid, field, value) in fixedPatches) {
                wf.optJSONObject(nid)?.optJSONObject("inputs")?.putOpt(field, value)
            }
            var recordSeed = 0L
            for (i in 0 until specs.length()) {
                val spec = specs.optJSONObject(i) ?: continue
                val nid = Specs.nodeId(spec)
                val field = Specs.field(spec)
                val node = wf.optJSONObject(nid) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue
                val key = "$nid|$field"
                val raw = values.opt(key)
                when (Specs.type(spec)) {
                    "text", "prompt_pool" -> {
                        val text = (raw as? String)?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: Specs.defaultText(spec)
                        inputs.put(field, text)
                        mirrorTo(spec, wf, text)
                    }
                    "select" -> {
                        val text = (raw as? String)?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: Specs.defaultText(spec).trim()
                        if (text.isNotBlank()) {
                            inputs.put(field, text)
                            mirrorTo(spec, wf, text)
                        }
                    }
                    "file:image" -> {
                        val name = (raw as? String)?.trim().orEmpty()
                        if (name.isNotBlank()) {
                            inputs.put(field, name)
                            mirrorTo(spec, wf, name)
                        }
                    }
                    "model" -> {
                        val name = (raw as? String)?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: Specs.defaultText(spec).trim()
                        if (name.isNotBlank()) inputs.put(field, name)
                    }
                    "int" -> {
                        val v = clampNumber(raw, Specs.defaultNumber(spec), Specs.min(spec), Specs.max(spec)).toLong()
                        inputs.put(field, v)
                        mirrorTo(spec, wf, v)
                    }
                    "int_random" -> {
                        val seed = if (randomize) Random.nextLong(1, 1_000_000_000_000L) else baseSeed + result.size
                        inputs.put(field, seed)
                        mirrorTo(spec, wf, seed)
                        if (firstIntRandom != null && Specs.nodeId(firstIntRandom) == nid && Specs.field(firstIntRandom) == field) {
                            recordSeed = seed
                        }
                    }
                    "float" -> {
                        val v = clampNumber(raw, Specs.defaultNumber(spec), Specs.min(spec), Specs.max(spec))
                        inputs.put(field, v)
                        mirrorTo(spec, wf, v)
                    }
                    "bool" -> {
                        val v = raw as? Boolean ?: Specs.defaultBool(spec)
                        inputs.put(field, v)
                        mirrorTo(spec, wf, v)
                    }
                    "image_sizes" -> {
                        if (w >= 0 && h >= 0) {
                            inputs.put("width", w)
                            inputs.put("height", h)
                            if (inputs.has("batch_size")) inputs.put("batch_size", 1)
                        }
                    }
                }
            }
            result.add(BuiltFlow(seed = recordSeed, wf = wf, width = w.coerceAtLeast(0), height = h.coerceAtLeast(0)))
        }
    }
    return result
}

private fun keyOf(spec: JSONObject): String = "${Specs.nodeId(spec)}|${Specs.field(spec)}"

private fun firstSpec(specs: JSONArray, type: String): JSONObject? {
    for (i in 0 until specs.length()) {
        val spec = specs.optJSONObject(i) ?: continue
        if (Specs.type(spec) == type) return spec
    }
    return null
}

private fun mirrorTo(spec: JSONObject, wf: JSONObject, value: Any) {
    for ((nid, field) in Specs.extraTargets(spec)) {
        wf.optJSONObject(nid)?.optJSONObject("inputs")?.put(field, value)
    }
}

/** min/max 夹紧（spec 没写就不夹）；值缺失/非法退 spec.default（再退 0）。 */
private fun clampNumber(raw: Any?, fallback: Double, min: Double?, max: Double?): Double {
    var out = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    } ?: fallback
    min?.let { if (out < it) out = it }
    max?.let { if (out > it) out = it }
    return out
}

/** "WxH" 数组 → (w,h) 列表；解析不了的条目跳过。 */
@VisibleForTesting
internal fun parseSizes(arr: JSONArray?): List<Pair<Int, Int>>? {
    if (arr == null) return null
    val out = ArrayList<Pair<Int, Int>>()
    for (i in 0 until arr.length()) {
        val s = arr.optString(i).trim().lowercase()
        val m = Regex("^(\\d+)x(\\d+)$").find(s) ?: continue
        val w = m.groupValues[1].toInt().align16()
        val h = m.groupValues[2].toInt().align16()
        if (w >= 64 && h >= 64) out.add(w to h)
    }
    return out
}

/** 导入工作流不做 256..2560 的内置夹紧，只对齐 16、下限 64。 */
@VisibleForTesting
internal fun Int.align16(): Int = ((this / 16) * 16).coerceAtLeast(64)
