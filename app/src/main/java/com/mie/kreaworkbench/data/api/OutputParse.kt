package com.mie.kreaworkbench.data.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * /history 输出的解析规则（round6，来自插件源码核实）：
 * - 文本节点（ShowAnything|Mie 等）：ui.text 是普通字符串，但 ComfyUI 合并 ui 输出时按列表展开，
 *   所以 /history 里可能是 字符串 / 字符串列表 / 单字符列表 三种之一。
 * - 视频节点（VHS_VideoCombine）：结果在 ui.gifs（不在 images）；原生 SaveVideo/SaveWEBM 在
 *   images/videos，带 animated 标记或 mp4/webm 扩展名。
 */

/**
 * 归一化文本输出：
 * a) 字符串 → 原样；
 * b) 列表每个元素（先 toString）长度都 ≤1 → 用 "" 拼接（单字符列表还原成完整文字）；
 * c) 其余列表 → 用 "\n" 拼接。
 */
fun normalizeOutputText(value: Any?): String? = when (value) {
    null -> null
    is String -> value
    is JSONArray -> {
        val parts = ArrayList<String>(value.length())
        var allTiny = value.length() > 0
        for (i in 0 until value.length()) {
            val s = value.opt(i)?.toString() ?: ""
            parts.add(s)
            if (s.length > 1) allTiny = false
        }
        if (allTiny) parts.joinToString("") else parts.joinToString("\n")
    }
    else -> value.toString()
}

/** 历史 outputs 里的一条产物（图片或视频）。 */
data class OutputEntry(
    val filename: String,
    val subfolder: String,
    val type: String,
    val video: Boolean,
)

/** 按扩展名 / format / animated 判定是不是视频。gif 一律走图片分支（Coil 可展示，进 ExoPlayer 会黑屏）。 */
fun isVideoEntry(o: JSONObject): Boolean {
    if (o.optString("filename").lowercase().endsWith(".gif")) return false
    if (o.optString("format").startsWith("video/")) return true
    if (o.optBoolean("animated", false)) return true
    return when (o.optString("filename").lowercase().substringAfterLast('.')) {
        "mp4", "webm", "mov", "mkv" -> true
        else -> false
    }
}

/**
 * 遍历单个 prompt 的 outputs（所有输出节点），收集 images/gifs/videos 里的图片与视频、
 * text/string 里的文本。VHS 的结果在 gifs，视频混在 images 里也按扩展名归到视频。
 *
 * r44：只要存在任何 type=="output" 的图片/视频项（SaveImage / SaveImageAdvanced /
 * VHS_VideoCombine 等真正落盘到 output 的节点），就丢掉所有 type=="temp" 的项
 * （PreviewImage、rgthree Image Comparer 等预览类）——两段采样工作流不再把第一段的
 * 残余噪点预览当成结果收进图库。只有 temp、没有任何 output 项时照旧全收（老预览型
 * 工作流不能因此变空）。文本输出不受影响，仍全收。type 缺省按 "output" 处理。
 *
 * [selectedNode] 非空且该节点在结果里有项时：它的项排最前（内部顺序不变）。
 */
fun parseOutputEntries(outputs: JSONObject?, selectedNode: String? = null): Pair<List<OutputEntry>, List<String>> {
    if (outputs == null) return emptyList<OutputEntry>() to emptyList<String>()
    // 记下每条来自哪个节点，供指定出图节点排序用
    data class Tagged(val nodeId: String, val entry: OutputEntry)
    val tagged = ArrayList<Tagged>()
    val texts = ArrayList<String>()
    val keys = outputs.keys()
    while (keys.hasNext()) {
        val nid = keys.next()
        val node = outputs.optJSONObject(nid) ?: continue
        for (key in listOf("images", "gifs", "videos")) {
            val arr = node.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val filename = o.optString("filename")
                if (filename.isBlank()) continue
                tagged.add(
                    Tagged(
                        nid,
                        OutputEntry(
                            filename = filename,
                            subfolder = o.optString("subfolder"),
                            type = o.optString("type", "output"),
                            video = isVideoEntry(o),
                        ),
                    ),
                )
            }
        }
        for (key in listOf("text", "string")) {
            if (!node.has(key)) continue
            normalizeOutputText(node.opt(key))?.let { if (it.isNotBlank()) texts.add(it) }
        }
    }
    var kept = if (tagged.any { it.entry.type == "output" }) {
        tagged.filter { it.entry.type != "temp" }
    } else {
        tagged
    }
    if (!selectedNode.isNullOrBlank()) {
        val front = kept.filter { it.nodeId == selectedNode }
        if (front.isNotEmpty()) {
            kept = front + kept.filter { it.nodeId != selectedNode }
        }
    }
    return kept.map { it.entry } to texts
}
