package com.mie.kreaworkbench.data.api

/**
 * 服务器地址规范化与候选（r41；normalizeServerUrl 原在 Http.kt，round34 引入，这里迁入并扩展）。
 * 只在点「测试连接」、落库前或输入框失焦回填时用，输入过程中不调用。
 */

/** 服务类型：决定没写端口时补哪个默认端口。 */
enum class ServerKind(val defaultPort: Int) { COMFY(8188), GACHA(8787) }

/** ComfyUI / 抽卡服务自己的接口根段：路径里遇到就从这段起全部截掉（反向代理子目录保留）。 */
private val API_ROOT_SEGMENTS = setOf(
    "api", "ws", "system_stats", "prompt", "history", "queue",
    "object_info", "view", "upload", "interrupt", "health",
)

/**
 * 地址输入框的规范化：去首尾空格、全角冒号/斜杠转半角、没写 scheme 补 http://
 * （大写 HTTP:// 也认，统一转小写）、去掉 ?/# 及之后的内容、路径里遇到服务自己的接口根段截断、
 * 私网 IPv4（10/8、172.16/12、192.168/16）没写端口时补 [ServerKind.defaultPort]
 * （用户显式写 https 的不补；域名、公网 IP、`.local`、单段主机名、IPv6 一律不补），
 * 最后交 trimBase 清洗与 HttpUrl 强校验。
 * 空串、没有主机名（「http://」「：8188」）或解析不过返回 null，调用方就地提示。
 */
fun normalizeServerUrl(raw: String, kind: ServerKind = ServerKind.COMFY): String? {
    if (raw.isBlank()) return null
    var s = raw.trim().replace('：', ':').replace('／', '/')
    val explicit = s.startsWith("http://", true) || s.startsWith("https://", true)
    val https = explicit && s.startsWith("https://", true)
    s = if (explicit) {
        val idx = s.indexOf("://")
        s.substring(0, idx).lowercase() + s.substring(idx)
    } else {
        "http://$s"
    }
    s = s.substringBefore('?').substringBefore('#')
    s = truncateAtApiSegment(s)
    val host = s.substringAfter("://").substringBefore('/').substringAfterLast('@')
    if (host.isBlank() || host.startsWith(':')) return null
    if (!https) s = addDefaultPort(s, kind)
    return trimBase(s).ifBlank { null }
}

/**
 * 按顺序尝试的地址列表。没写协议且主机是域名（非 IP、非 [IPv6]、非 `.local`、含 `.`）时
 * 返回两项（先 https 后 http，同一规范化主体）；其余情况返回单项；规范化失败返回空列表。
 */
fun serverUrlCandidates(raw: String, kind: ServerKind = ServerKind.COMFY): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return emptyList()
    val explicit = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
    if (explicit || !isDomainHost(trimmed)) {
        return listOfNotNull(normalizeServerUrl(trimmed, kind))
    }
    val https = normalizeServerUrl("https://$trimmed", kind)
    val http = normalizeServerUrl("http://$trimmed", kind)
    return listOfNotNull(https, http).distinct()
}

/** 主机是不是普通域名：非空、非 IPv6、非 `.local`、非单段主机名、非纯 IPv4。 */
private fun isDomainHost(raw: String): Boolean {
    val host = raw.replace('：', ':').replace('／', '/')
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':')
    if (host.isBlank() || host.startsWith('[') || host.contains(':')) return false
    if (host.endsWith(".local")) return false
    if (!host.contains('.')) return false
    if (host.split('.').all { it.toIntOrNull() != null }) return false
    return true
}

/** 路径里遇到 ComfyUI/抽卡服务自己的接口根段就从这段起截掉；其他子路径原样保留。 */
private fun truncateAtApiSegment(s: String): String {
    val schemeEnd = s.indexOf("://") + 3
    val rest = s.substring(schemeEnd)
    val pathIdx = rest.indexOf('/')
    if (pathIdx < 0) return s
    val authority = rest.substring(0, pathIdx)
    val segments = rest.substring(pathIdx).trimStart('/').split('/')
    val cut = segments.indexOfFirst { it.lowercase() in API_ROOT_SEGMENTS }
    if (cut < 0) return s
    val kept = segments.take(cut).joinToString("/")
    return s.substring(0, schemeEnd) + authority + (if (kept.isEmpty()) "" else "/$kept")
}

/** 私网 IPv4（10/8、172.16/12、192.168/16）没写端口时补默认端口。IPv6 / 已带端口不动。 */
private fun addDefaultPort(s: String, kind: ServerKind): String {
    val schemeEnd = s.indexOf("://") + 3
    val rest = s.substring(schemeEnd)
    val pathIdx = rest.indexOf('/')
    val authority = if (pathIdx >= 0) rest.substring(0, pathIdx) else rest
    val after = if (pathIdx >= 0) rest.substring(pathIdx) else ""
    val host = authority.substringAfterLast('@')
    // 带 @ 的凭据里可能有冒号，只看 @ 后的主机；含冒号 = 已带端口或 IPv6（[::1]），都不补
    if (host.contains(':')) return s
    if (!isPrivateIpv4ForDefaultPort(host)) return s
    return s.substring(0, schemeEnd) + authority + ":" + kind.defaultPort + after
}

private fun isPrivateIpv4ForDefaultPort(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    var v = 0
    for (p in parts) {
        val n = p.toIntOrNull() ?: return false
        if (n !in 0..255) return false
        v = (v shl 8) or n
    }
    val a = (v ushr 24) and 0xFF
    val b = (v ushr 16) and 0xFF
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}
