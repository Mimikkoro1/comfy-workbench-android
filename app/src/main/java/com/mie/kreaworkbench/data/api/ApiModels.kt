package com.mie.kreaworkbench.data.api

import org.json.JSONArray
import org.json.JSONObject

/** network=true 可自动重试；auth=true 是凭据问题（401），调用方应提示而非重试。
 *  code 是 HTTP 状态码（非 HTTP 来源的异常为 0）：调用方据此区分 404（资源不存在）与其他失败。
 *  unreachable=true（round31）表示连接压根没建立：拒连 / 域名解析不了 / 无路由 / 连接超时。
 *  这类错误重试也不会自己好，提交阶段据此立刻判失败，而不是拖满重试窗口后 pause 假死。
 *  raw（r44）：服务器的原始报错文本（error.message + 各条 details），给「展开原文」用；没有为 null。 */
class ApiException(
    message: String,
    val network: Boolean = false,
    val auth: Boolean = false,
    val code: Int = 0,
    val unreachable: Boolean = false,
    val raw: String? = null,
) : Exception(message)

data class RemoteImage(
    val index: Int,
    val seed: Long,
    val filename: String,
    val subfolder: String,
    val type: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val url: String,
)

/** 文本输出（ShowAnything 等）：index = 所属 prompt 的序号（1..N）。 */
data class RemoteText(
    val index: Int,
    val text: String,
)

data class RemoteJob(
    val jobId: String,
    val clientJobId: String,
    val mode: String,
    val state: String,
    val prompt: String,
    val params: JSONObject,
    val total: Int,
    val done: Int,
    val queuePosition: Int?,
    val progressValue: Int,
    val progressMax: Int,
    val imageIndex: Int,
    val images: List<RemoteImage>,
    val videos: List<RemoteImage> = emptyList(),
    val texts: List<RemoteText> = emptyList(),
    val stage: String = "",
    val error: String?,
    /** r42：ComfyUI execution_error 的详细报错（节点/异常/调用栈，可展开可复制）；没有为 null。 */
    val errorDetail: String? = null,
)

fun JSONArray.strings(): List<String> = buildList {
    for (i in 0 until length()) add(optString(i))
}
