package com.mie.kreaworkbench.data.api

import android.content.Context
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.ui.locale.localized
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** auth = ServerRouter.interceptor：对当前生效线路统一加 Basic Auth（HTTP/WS/上传下载共用）。 */
fun jsonClient(auth: Interceptor? = null): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    // 整次调用的总时长上限：兜底任何阶段卡死（连接挂起、响应不到）都有确定的失败时刻
    .callTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .apply { if (auth != null) addInterceptor(auth) }
    .build()

fun fileClient(auth: Interceptor? = null): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .apply { if (auth != null) addInterceptor(auth) }
    .build()

/**
 * 清洗配置地址：去首尾斜杠、补 http scheme、剥掉混入的空白/控制字符。
 * 最终用 HttpUrl 强校验——解析不过（输入中途的脏值、手滑字符）返回 ""，
 * 让所有调用方走「地址未配置」的优雅失败路径，而不是 Request.Builder.url() 抛
 * IllegalArgumentException 炸掉调用协程（ComfyProgress 的独立 scope 一炸就是整个进程）。
 */
fun trimBase(url: String): String {
    val cleaned = url.trim().trimEnd('/').let { s ->
        if (s.isEmpty()) s else buildString {
            for (c in s) if (!c.isWhitespace() && !c.isISOControl()) append(c)
        }
    }
    if (cleaned.isBlank()) return ""
    val withScheme = if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
        cleaned
    } else {
        "http://$cleaned"
    }
    return if (withScheme.toHttpUrlOrNull() != null) withScheme else ""
}

/**
 * 地址输入框的规范化（round34）：去首尾空格、全角冒号/斜杠转半角、没写 scheme 补 http://
 * （大写 HTTP:// 也认，统一转小写），最后交 trimBase 清洗与 HttpUrl 强校验。
 * 空串、没有主机名（「http://」「：8188」）或解析不过返回 null，调用方就地提示。
 * 只在点「测试连接」或落库前用，输入过程中不调用。
 */
fun normalizeServerUrl(raw: String): String? {
    if (raw.isBlank()) return null
    var s = raw.trim().replace('：', ':').replace('／', '/')
    s = if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
        val idx = s.indexOf("://")
        s.substring(0, idx).lowercase() + s.substring(idx)
    } else {
        "http://$s"
    }
    val host = s.substringAfter("://").substringBefore('/')
        .substringBefore('?').substringBefore('#').substringAfterLast('@')
    if (host.isBlank() || host.startsWith(':')) return null
    return trimBase(s).ifBlank { null }
}

/** 配置的 ComfyUI 地址；空 = 未配置。 */
suspend fun SettingsStore.baseUrl(): String {
    val u = trimBase(current().serverUrl)
    if (u.isBlank()) throw ApiException(KreaApp.instance.str(R.string.ping_need_server))
    return u
}

private val BACKOFF = longArrayOf(0L, 1000L, 3000L, 6000L)

fun friendlyIo(cause: Throwable? = null): ApiException =
    ApiException(
        KreaApp.instance.str(R.string.err_cannot_connect),
        network = true,
        unreachable = isUnreachable(cause),
    )

/**
 * 连接压根没建立（round31）：拒连、域名解析失败、无路由、连接阶段超时。
 * 这类失败和「网络抖一下」不同——地址/端口填错时它会稳定复现，重试窗口再长也等不出结果。
 * OkHttp 把这几种都包成 IOException 抛出（callTimeout 则是 InterruptedIOException），这里顺着 cause 链认。
 */
private fun isUnreachable(cause: Throwable?): Boolean {
    var c = cause
    var depth = 0
    while (c != null && depth < 8) {
        when (c) {
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.net.NoRouteToHostException,
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException,
            -> return true
        }
        c = c.cause
        depth++
    }
    return false
}

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
            if (!idempotent || i == attempts - 1) throw friendlyIo(e)
        }
    }
    throw (last as? ApiException) ?: friendlyIo(last as? Throwable)
}

/**
 * enqueue + 可取消挂起：协程被取消（用户取消任务/服务停止）时立刻 cancel 底层请求。
 * 不能用阻塞 execute()——它不响应协程取消，断网时取消会留下后台跑完的请求，恢复网络后幽灵入队。
 */
internal suspend fun OkHttpClient.callBodyText(build: () -> Request): Pair<Int, String> = awaitText(build)

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
        // 401 是凭据缺失/错误，不是网络问题：明确报错，绝不进重试循环
        if (code == 401) throw authError()
        val err = errorText(json) ?: httpFallback(code)
        if (idempotent && code >= 500 && attempt < attempts - 1) {
            throw ApiException(err, network = true, code = code)
        }
        throw ApiException(err, network = code >= 500, code = code)
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
        // r37.1：模型文件不在服务器上（value_not_in_list）是升级后最常见的失败，
        // 原文是内部校验信息，先翻成用户能看懂的一句话；其余错误保持原样。
        modelMissingText(json, app)?.let { return it }
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

/** ComfyUI 加载器类节点的模型文件字段：value_not_in_list 命中这些字段时才翻成「模型不存在」。 */
private val MODEL_FILE_FIELDS = setOf(
    "ckpt_name", "unet_name", "clip_name", "vae_name", "lora_name", "model_name", "control_net_name",
)

/**
 * r37.1：预填/保存的模型文件服务器上没有时，ComfyUI 拒任务（400）并在 error/node_errors 里报
 * value_not_in_list，原文（Prompt outputs failed validation: Value not in list: vae_name: ...）
 * 全是内部信息。这里收集所有缺失的模型文件名，翻成一句用户能看懂的话（多个文件按当前语言拼接）；
 * 没有模型类缺失返回 null，走原有格式化。
 */
private fun modelMissingText(json: JSONObject, app: Context): String? {
    val missing = LinkedHashSet<String>()
    fun scan(errs: JSONArray?) {
        if (errs == null) return
        for (i in 0 until errs.length()) {
            val o = errs.optJSONObject(i) ?: continue
            if (o.optString("type") != "value_not_in_list") continue
            val details = o.optString("details")
            // 字段名优先取 extra_info.input_name，格式变了也有兜底；值是 details 冒号后的引号内文件名
            val field = o.optJSONObject("extra_info")?.optString("input_name").orEmpty()
                .ifBlank { details.substringBefore(':').trim() }
            if (field !in MODEL_FILE_FIELDS) continue
            val name = details.substringAfter(':', "").trim().trim('\'', '"')
            if (name.isNotBlank()) missing.add(name)
        }
    }
    scan(json.optJSONObject("error")?.optJSONArray("errors"))
    val nodeErrors = json.optJSONObject("node_errors")
    if (nodeErrors != null) {
        val keys = nodeErrors.keys()
        while (keys.hasNext()) scan(nodeErrors.optJSONObject(keys.next())?.optJSONArray("errors"))
    }
    if (missing.isEmpty()) return null
    val zh = app.localized().resources.configuration.locales[0].language == "zh"
    return app.str(R.string.err_model_not_found, missing.joinToString(if (zh) "、" else ", "))
}

fun httpFallback(code: Int): String = if (code == 401) {
    KreaApp.instance.str(R.string.err_auth_failed)
} else {
    KreaApp.instance.str(R.string.err_request_code, code)
}

fun authError(): ApiException = ApiException(KreaApp.instance.str(R.string.err_auth_failed), network = false, auth = true)

fun jsonBody(obj: JSONObject) = obj.toString().toRequestBody(JSON_MEDIA)
