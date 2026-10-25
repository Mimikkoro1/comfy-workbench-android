package com.mie.kreaworkbench.data.comfy

import org.json.JSONArray
import org.json.JSONObject

/**
 * r42：ComfyUI execution_error 的关键字段（WS 消息或 history status.messages 里取）。
 * 纯数据 + 纯解析（只用 org.json，可 JVM 单测）。
 */
data class ExecError(
    val promptId: String,
    val nodeId: String,
    val nodeType: String,
    val exceptionType: String,
    val exceptionMessage: String,
    val traceback: String,
)

/**
 * 解析 execution_error 的 data 对象（即 `{"type":"execution_error","data":{...}}` 里的 data）。
 * 缺字段一律空串、不抛异常；缺 prompt_id 返回 null。
 * traceback 是字符串数组，逐行 join；总长截到 4096（保留末尾），exception_message 截到 1000。
 */
fun parseExecutionError(data: JSONObject): ExecError? {
    val pid = data.optString("prompt_id").trim()
    if (pid.isBlank()) return null
    val tb = when (val t: Any? = data.opt("traceback")) {
        is JSONArray -> buildString {
            for (i in 0 until t.length()) append(t.optString(i)).append('\n')
        }.trimEnd('\n')
        is String -> t
        else -> ""
    }
    return ExecError(
        promptId = pid,
        nodeId = data.optString("node_id"),
        nodeType = data.optString("node_type"),
        exceptionType = data.optString("exception_type"),
        exceptionMessage = data.optString("exception_message").take(1000),
        traceback = tailTruncate(tb, 4096),
    )
}

/** 给「详情」弹窗和复制用的多行文本；空字段跳过（不出现空行）。 */
fun formatExecError(e: ExecError): String {
    return buildString {
        appendLine("prompt_id: ${e.promptId}")
        if (e.nodeId.isNotBlank()) appendLine("node_id: ${e.nodeId}")
        if (e.nodeType.isNotBlank()) appendLine("node_type: ${e.nodeType}")
        if (e.exceptionType.isNotBlank()) appendLine("exception_type: ${e.exceptionType}")
        if (e.exceptionMessage.isNotBlank()) appendLine("exception_message: ${e.exceptionMessage}")
        if (e.traceback.isNotBlank()) {
            appendLine("traceback:")
            append(e.traceback)
        }
    }.trimEnd()
}

/** 超过 [max] 时保留末尾（调用栈最有用的部分在尾部），开头加省略号。 */
private fun tailTruncate(s: String, max: Int): String {
    if (s.length <= max) return s
    return "…" + s.substring(s.length - max)
}

/** r42：按插入序的定长缓存，超出 [max] 条踢最老。WS 错误缓存用（不能被进度 clear 一起清）。 */
class BoundedCache<K : Any, V : Any>(private val max: Int) {
    private val map = LinkedHashMap<K, V>()

    fun put(key: K, value: V) = synchronized(this) {
        map[key] = value
        while (map.size > max) map.remove(map.keys.first())
    }

    operator fun get(key: K): V? = synchronized(this) { map[key] }
}
