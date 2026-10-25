package com.mie.kreaworkbench.data.api

import android.content.Context
import android.os.Build
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.net.LOCAL_NETWORK_ENFORCED_SDK
import com.mie.kreaworkbench.data.net.isLocalNetworkGranted
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

/** 配置的 ComfyUI 地址；空 = 未配置。 */
suspend fun SettingsStore.baseUrl(): String {
    val u = trimBase(current().serverUrl)
    if (u.isBlank()) throw ApiException(KreaApp.instance.str(R.string.ping_need_server))
    return u
}

/** 网络请求重试的退避表（毫秒）；第 1 次尝试不等。Http.callJson 与 Transfer 共用（Transfer 在第 8 项接入）。 */
internal val RETRY_BACKOFF_MS = longArrayOf(0L, 1000L, 3000L, 6000L)

/** 默认重试次数 = 退避表长度。 */
internal val RETRY_ATTEMPTS: Int get() = RETRY_BACKOFF_MS.size

/** 第 i 次尝试前的等待；i 越界时按最后一档，i < 0 当 0。永不抛异常。 */
internal fun backoffDelayMs(i: Int): Long = RETRY_BACKOFF_MS[i.coerceIn(0, RETRY_BACKOFF_MS.size - 1)]

fun friendlyIo(cause: Throwable? = null): ApiException =
    ApiException(
        // r41：连接类失败且安卓 17 未给局域网权限时，提示里带上权限这条（公网用户也会看到这句，可接受；
        // friendlyIo 是同步函数，不去读 DataStore 判断目标是不是局域网）
        if (isUnreachable(cause) &&
            Build.VERSION.SDK_INT >= LOCAL_NETWORK_ENFORCED_SDK &&
            !isLocalNetworkGranted(KreaApp.instance)
        ) {
            KreaApp.instance.str(R.string.err_cannot_connect_lan_permission)
        } else {
            KreaApp.instance.str(R.string.err_cannot_connect)
        },
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
    attempts: Int = if (idempotent) RETRY_ATTEMPTS else 1,
): JSONObject {
    var last: Exception? = null
    for (i in 0 until attempts) {
        val wait = backoffDelayMs(i)
        if (wait > 0) delay(wait)
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
        // r44：原始报错随异常带出去（error 是对象形状时才有），给「展开原文」
        if (idempotent && code >= 500 && attempt < attempts - 1) {
            throw ApiException(err, network = true, code = code, raw = rawErrorText(json))
        }
        throw ApiException(err, network = code >= 500, code = code, raw = rawErrorText(json))
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
 * 全是内部信息。这里收集所有缺失的名字翻成用户能看懂的一句话；没有 value_not_in_list 返回 null。
 *
 * r44 放宽：value_not_in_list 命中任何字段都收集（只要 details 能解析出「'名字' not in [」），
 * 模型文件字段归 modelNames，其它 combo 选项值归 optionNames。候选列表（[...] 一大坨）不进摘要。
 */
internal fun missingValueNames(json: JSONObject): Pair<List<String>, List<String>> {
    val modelNames = LinkedHashSet<String>()
    val optionNames = LinkedHashSet<String>()
    val quoted = Regex("'(.+?)'\\s+not\\s+in\\s+\\[")
    fun scan(errs: JSONArray?) {
        if (errs == null) return
        for (i in 0 until errs.length()) {
            val o = errs.optJSONObject(i) ?: continue
            if (o.optString("type") != "value_not_in_list") continue
            val details = o.optString("details")
            // 字段名优先取 extra_info.input_name，格式变了也有兜底
            val field = o.optJSONObject("extra_info")?.optString("input_name").orEmpty()
                .ifBlank { details.substringBefore(':').trim() }
            val match = quoted.find(details)?.groupValues?.get(1)?.trim().orEmpty()
            if (field in MODEL_FILE_FIELDS) {
                // 模型文件字段：正则解析不到时沿用 r37.1 的截取，老报错格式不回退
                val name = match.ifBlank { details.substringAfter(':', "").trim().trim('\'', '"') }
                if (name.isNotBlank()) modelNames.add(name)
            } else if (match.isNotBlank()) {
                optionNames.add(match)
            }
        }
    }
    scan(json.optJSONObject("error")?.optJSONArray("errors"))
    val nodeErrors = json.optJSONObject("node_errors")
    if (nodeErrors != null) {
        val keys = nodeErrors.keys()
        while (keys.hasNext()) scan(nodeErrors.optJSONObject(keys.next())?.optJSONArray("errors"))
    }
    return modelNames.toList() to optionNames.toList()
}

private fun modelMissingText(json: JSONObject, app: Context): String? {
    val (modelNames, optionNames) = missingValueNames(json)
    if (modelNames.isEmpty() && optionNames.isEmpty()) return null
    val zh = app.localized().resources.configuration.locales[0].language == "zh"
    val sep = if (zh) "、" else ", "
    // 纯模型文件 → 「当前模型不存在」；混有 combo 选项值 → 「选项不在服务器可选列表里」
    return if (optionNames.isEmpty()) {
        app.str(R.string.err_model_not_found, modelNames.joinToString(sep))
    } else {
        app.str(R.string.err_model_value_not_found, (modelNames + optionNames).joinToString(sep))
    }
}

/**
 * r44：把 ComfyUI 的原始报错拼成多行文本（error.message / details + 每条 node_errors），
 * 随异常带出去给「展开原文」；纯字符串 error 没有原文。截断到 8000 字符防极端长选项列表。
 */
private fun rawErrorText(json: JSONObject): String? {
    val e = json.optJSONObject("error") ?: return null
    val lines = ArrayList<String>()
    val msg = e.optString("message")
    if (msg.isNotBlank()) lines.add(msg)
    val details = e.optString("details")
    if (details.isNotBlank()) lines.add(details)
    val nodeErrors = json.optJSONObject("node_errors")
    if (nodeErrors != null) {
        val keys = nodeErrors.keys()
        while (keys.hasNext()) {
            val errs = nodeErrors.optJSONObject(keys.next())?.optJSONArray("errors") ?: continue
            for (i in 0 until errs.length()) {
                val o = errs.optJSONObject(i) ?: continue
                val m = o.optString("message")
                if (m.isNotBlank()) lines.add(m)
                val d = o.optString("details")
                if (d.isNotBlank()) lines.add(d)
            }
        }
    }
    if (lines.isEmpty()) return null
    return lines.joinToString("\n").take(RAW_ERROR_MAX_CHARS)
}

fun httpFallback(code: Int): String = if (code == 401) {
    KreaApp.instance.str(R.string.err_auth_failed)
} else {
    KreaApp.instance.str(R.string.err_request_code, code)
}

fun authError(): ApiException = ApiException(KreaApp.instance.str(R.string.err_auth_failed), network = false, auth = true)

fun jsonBody(obj: JSONObject) = obj.toString().toRequestBody(JSON_MEDIA)

/** r44：原始报错文本的长度上限。 */
private const val RAW_ERROR_MAX_CHARS = 8000
