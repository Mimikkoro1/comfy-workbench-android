package com.mie.kreaworkbench.data.api

import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

fun jsonClient(): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    // 整次调用的总时长上限：兜底任何阶段卡死（连接挂起、响应不到）都有确定的失败时刻
    .callTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()

fun fileClient(): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()

fun trimBase(url: String): String = url.trim().trimEnd('/')

suspend fun SettingsStore.baseUrl(): String = trimBase(current().serverUrl).ifBlank {
    throw ApiException(KreaApp.instance.str(R.string.ping_need_server))
}

private val BACKOFF = longArrayOf(0L, 1000L, 3000L, 6000L)

fun friendlyIo(): ApiException =
    ApiException(KreaApp.instance.str(R.string.err_cannot_connect), network = true)

suspend fun OkHttpClient.callJson(
    build: () -> Request,
    idempotent: Boolean,
    attempts: Int = if (idempotent) 4 else 1,
): JSONObject {
    var last: Exception? = null
    for (i in 0 until attempts) {
        if (BACKOFF[i] > 0) delay(BACKOFF[i])
        try {
            val (code, text) = awaitText(build)
            return parseJson(code, text, idempotent, i, attempts)
        } catch (e: ApiException) {
            if (e.network && idempotent && i < attempts - 1) {
                last = e
                continue
            }
            throw e
        } catch (e: IOException) {
            last = e
            if (!idempotent || i == attempts - 1) throw friendlyIo()
        }
    }
    throw (last as? ApiException) ?: friendlyIo()
}

/**
 * enqueue + 可取消挂起：协程被取消（用户取消任务/服务停止）时立刻 cancel 底层请求。
 * 不能用阻塞 execute()——它不响应协程取消，断网时取消会留下后台跑完的请求，恢复网络后幽灵入队。
 */
private suspend fun OkHttpClient.awaitText(build: () -> Request): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
        val call = newCall(build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val text = try {
                    response.body?.string().orEmpty()
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                if (cont.isActive) cont.resume(Pair(response.code, text))
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

private fun parseJson(code: Int, text: String, idempotent: Boolean, attempt: Int, attempts: Int): JSONObject {
    val json = if (text.trimStart().startsWith("{")) JSONObject(text) else JSONObject()
    if (code !in 200..299) {
        val err = errorText(json) ?: httpFallback(code)
        if (idempotent && code >= 500 && attempt < attempts - 1) {
            throw ApiException(err, network = true)
        }
        throw ApiException(err, network = code >= 500)
    }
    if (json.has("ok") && !json.optBoolean("ok")) {
        throw ApiException(json.optString("error", KreaApp.instance.str(R.string.err_request_failed)))
    }
    return json
}

/** 兼容两种错误形状：字符串 error，或 ComfyUI 的对象 error（message/details + node_errors）。 */
private fun errorText(json: JSONObject): String? {
    val e = json.optJSONObject("error")
    if (e != null) {
        val app = KreaApp.instance
        var text = e.optString("message").ifBlank { app.str(R.string.err_request_failed) }
        val details = e.optString("details")
        if (details.isNotBlank()) text = app.str(R.string.err_join_colon, text, details)
        val nodeErrors = json.optJSONObject("node_errors")
        if (nodeErrors != null) {
            val keys = nodeErrors.keys()
            while (keys.hasNext()) {
                val errs = nodeErrors.optJSONObject(keys.next())?.optJSONArray("errors") ?: continue
                for (i in 0 until errs.length()) {
                    val o = errs.optJSONObject(i) ?: continue
                    val m = o.optString("message")
                    val d = o.optString("details")
                    if (m.isNotBlank() || d.isNotBlank()) {
                        val piece = if (d.isNotBlank()) app.str(R.string.err_join_colon, m, d.take(200)) else m
                        text = app.str(R.string.err_join_semi, text, piece)
                    }
                    break
                }
            }
        }
        return text
    }
    return json.optString("error").ifBlank { null }
}

fun httpFallback(code: Int): String = KreaApp.instance.str(R.string.err_request_code, code)

fun jsonBody(obj: JSONObject) = obj.toString().toRequestBody(JSON_MEDIA)
