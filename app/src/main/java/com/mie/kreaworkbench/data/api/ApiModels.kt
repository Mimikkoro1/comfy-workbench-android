package com.mie.kreaworkbench.data.api

import org.json.JSONArray
import org.json.JSONObject

/** network=true 可自动重试；auth=true 是凭据问题（401），调用方应提示而非重试。
 *  code 是 HTTP 状态码（非 HTTP 来源的异常为 0）：调用方据此区分 404（资源不存在）与其他失败。 */
class ApiException(
    message: String,
    val network: Boolean = false,
    val auth: Boolean = false,
    val code: Int = 0,
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
)

data class ModelCatalog(
    val source: String,
    val unet: List<String>,
    val clip: List<String>,
    val vae: List<String>,
    val samplers: List<String>,
    val schedulers: List<String>,
    val defaults: JSONObject,
    val sizePresets: List<Triple<String, Int, Int>>,
)

data class PingResult(
    val workbenchVersion: String,
    val comfyOk: Boolean,
    val comfyUrl: String,
    val latencyMs: Long,
)

fun JSONArray.strings(): List<String> = buildList {
    for (i in 0 until length()) add(optString(i))
}
