package com.mie.kreaworkbench.data.api

import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * r41：连接失败分步分类（纯 JVM，方便单测）。
 * 测试连接按 ①地址 → ②HTTP /system_stats → ③内容校验 → ④WebSocket 握手 的阶段收集结果，
 * 这里把「哪个阶段、什么原因」映射成用户能看懂的分类。
 */

/** 探测走到哪一步了。 */
enum class ConnStage { HTTP, CONTENT, WS }

/** 一条错误：分类 + HTTP 状态码（如有）+ 给开发者看的技术摘要（类名: 消息）。 */
data class ConnError(val kind: ConnErrorKind, val httpCode: Int?, val tech: String)

enum class ConnErrorKind {
    /** 地址格式不对，请求没发出去。 */
    BAD_URL,
    /** 安卓 17 没给局域网权限，系统静默拦截（表现为超时）。 */
    LOCAL_NET_BLOCKED,
    TIMEOUT,
    REFUSED,
    NO_ROUTE,
    UNKNOWN_HOST,
    TLS,
    AUTH_REQUIRED,
    NOT_FOUND,
    HTTP_OTHER,
    /** 2xx 但内容不是 ComfyUI 的 /system_stats。 */
    NOT_COMFY,
    WS_REJECTED,
    WS_AUTH,
    WS_FAILED,
    OTHER,
}

/** 一次完整探测的结果。WS 失败不阻止保存（出图只靠 HTTP）。 */
data class ConnectionReport(
    val httpOk: Boolean,
    val version: String,
    val latencyMs: Long,
    val error: ConnError?,
    val wsError: ConnError?,
    val detail: String,
)

/**
 * 分类规则：
 * - 特例：SDK ≥ 37、未授权、目标是局域网时，超时 → LOCAL_NET_BLOCKED（系统拦截表现为静默超时；拒连不算）。
 * - 异常链：SSL* → TLS；SocketTimeout/InterruptedIO → TIMEOUT；ConnectException(refused) → REFUSED；
 *   其余 ConnectException / NoRouteToHost / SocketException(unreachable) → NO_ROUTE；UnknownHost → UNKNOWN_HOST。
 * - HTTP/CONTENT 阶段按状态码：401/403 → AUTH_REQUIRED，404 → NOT_FOUND，其余非 2xx → HTTP_OTHER，
 *   2xx 且 CONTENT 阶段（内容校验没过）→ NOT_COMFY。
 * - WS 阶段：401 → WS_AUTH，其他状态码 → WS_REJECTED，无响应异常 → WS_FAILED。
 */
fun classifyConnectionError(
    stage: ConnStage,
    cause: Throwable?,
    httpCode: Int?,
    sdkInt: Int,
    hasLanPermission: Boolean,
    hostIsLocal: Boolean,
): ConnErrorKind {
    when (stage) {
        ConnStage.WS -> {
            if (httpCode == 401) return ConnErrorKind.WS_AUTH
            if (httpCode != null) return ConnErrorKind.WS_REJECTED
            return ConnErrorKind.WS_FAILED
        }
        ConnStage.HTTP, ConnStage.CONTENT -> {
            cause?.let { return classifyIo(it, sdkInt, hasLanPermission, hostIsLocal) ?: ConnErrorKind.OTHER }
            val code = httpCode ?: return ConnErrorKind.OTHER
            return when {
                code in 200..299 ->
                    if (stage == ConnStage.CONTENT) ConnErrorKind.NOT_COMFY else ConnErrorKind.OTHER
                code == 401 || code == 403 -> ConnErrorKind.AUTH_REQUIRED
                code == 404 -> ConnErrorKind.NOT_FOUND
                else -> ConnErrorKind.HTTP_OTHER
            }
        }
    }
}

/** 顺着 cause 链认异常；认不出返回 null（调用方按状态码兜底）。 */
private fun classifyIo(
    cause: Throwable,
    sdkInt: Int,
    hasLanPermission: Boolean,
    hostIsLocal: Boolean,
): ConnErrorKind? {
    var c: Throwable? = cause
    var depth = 0
    while (c != null && depth < 8) {
        val kind: ConnErrorKind? = when (c) {
            is SSLException -> ConnErrorKind.TLS
            is SocketTimeoutException, is InterruptedIOException -> ConnErrorKind.TIMEOUT
            is ConnectException ->
                if (c.message?.let { m ->
                        m.contains("refused", ignoreCase = true) ||
                            m.contains("ECONNREFUSED", ignoreCase = true)
                    } == true
                ) {
                    ConnErrorKind.REFUSED
                } else {
                    ConnErrorKind.NO_ROUTE
                }
            is NoRouteToHostException -> ConnErrorKind.NO_ROUTE
            is SocketException ->
                if (c.message?.contains("unreachable", ignoreCase = true) == true) ConnErrorKind.NO_ROUTE else null
            is UnknownHostException -> ConnErrorKind.UNKNOWN_HOST
            else -> null
        }
        if (kind != null) {
            // r41 特例：安卓 17 没给局域网权限时系统拦截表现为静默超时；拒连等真实网络错误不算
            if (kind == ConnErrorKind.TIMEOUT && sdkInt >= 37 && !hasLanPermission && hostIsLocal) {
                return ConnErrorKind.LOCAL_NET_BLOCKED
            }
            return kind
        }
        c = c.cause
        depth++
    }
    return null
}

/** WS 握手探测的原始结局：握手成功，或握手失败（异常 + 可选的 HTTP 状态码）。
 *  超时不是结局——探测函数等不到结局时传 null，由 wsProbeError 按超时映射。
 *  r41.1 之前结局和超时共用 null，连上了也被当成超时报 WS 失败。 */
sealed interface WsHandshakeOutcome {
    data object Opened : WsHandshakeOutcome
    data class Failed(val cause: Throwable, val httpCode: Int?) : WsHandshakeOutcome
}

/**
 * r41.1：一次 WS 握手探测的结局 → ConnError（null = 握手成功，无错误）。
 * outcome 传 null = 6 秒内没等到任何结局（超时）→ WS_FAILED；
 * 失败按 WS 阶段分类。握手被拒时状态码常藏在异常消息里
 * （OkHttp: Expected HTTP 101 response but was '401 …'），response 拿不到就从消息里抠。
 */
fun wsProbeError(
    outcome: WsHandshakeOutcome?,
    sdkInt: Int,
    hasLanPermission: Boolean,
    hostIsLocal: Boolean,
): ConnError? = when (outcome) {
    null -> ConnError(ConnErrorKind.WS_FAILED, null, "ws probe timed out (6s)")
    WsHandshakeOutcome.Opened -> null
    is WsHandshakeOutcome.Failed -> {
        // 消息形如 Expected HTTP 101 response but was '403 Forbidden'——状态码后跟空格+原因短语，
        // 旧正则多要求一个闭合引号，从来匹配不上
        val code = outcome.httpCode
            ?: outcome.cause.message?.let { Regex("was '(\\d{3})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ConnError(
            classifyConnectionError(ConnStage.WS, outcome.cause, code, sdkInt, hasLanPermission, hostIsLocal),
            code,
            techSummary(outcome.cause),
        )
    }
}

/**
 * r41.1：测试结果里该显示到 ping 行的那条错误：失败 → 主错误；成功 → WS 提示（无则 null，ping 行留空）。
 * 版本/延迟不进 ping 行——收起态摘要行由 serverVersion/serverLatency 负责，
 * ping 里再拼一遍会让「ping != 摘要」的去重判断失效，版本延迟显示两遍。
 */
fun pingLineError(report: ConnectionReport): ConnError? = report.error ?: report.wsError

/**
 * 200 且响应是 JSON 对象且含 "system" 对象才算 ComfyUI 的 /system_stats
 * （os 字段不强求；比扫描用的 parseComfyStats 略松——那里还要 devices 数组）。
 */
fun isComfyStats(code: Int, body: String): Boolean {
    if (code !in 200..299) return false
    val t = body.trim()
    if (!t.startsWith("{")) return false
    val json = try {
        JSONObject(t)
    } catch (_: Exception) {
        return false
    }
    return json.optJSONObject("system") != null
}

/** 给开发者看的技术摘要：类名 + 消息原文（详细信息里允许出现类名）。 */
fun techSummary(t: Throwable): String {
    val m = t.message.orEmpty().replace('\n', ' ').take(300)
    return if (m.isBlank()) t.javaClass.name else "${t.javaClass.name}: $m"
}
