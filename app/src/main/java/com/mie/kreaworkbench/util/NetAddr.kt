package com.mie.kreaworkbench.util

/**
 * 判断地址是否算「外网」（v0.5.1 返工单 §1）：
 * 内网 = localhost、127.x、10.x、172.16–31.x、192.168.x、169.254.x、
 * IPv6 的 fe80::/fc00::/fd..、.local 结尾主机名，以及无点的单标签主机名。
 * 其余（域名、公网 IP）= 外网。
 */
fun isWanAddress(raw: String): Boolean {
    val t = raw.trim()
    if (t.isBlank()) return false
    val host = runCatching { java.net.URI(t).host }.getOrNull()
        ?: t.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
    val h = host.trim('[', ']').lowercase()
    if (h.isEmpty()) return false
    if (h == "localhost" || h.endsWith(".local")) return false
    if (h.contains(':')) {
        // IPv6：链路本地 fe80:: 与 ULA fc00::/fd00:: 算内网
        return !(h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd"))
    }
    val parts = h.split('.')
    if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return when {
            a == 127 || a == 10 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 169 && b == 254 -> false
            else -> true
        }
    }
    // 没写 scheme 的 "10.0.2.2" 这类前面已被当 host 截出来；走到这里的是主机名：单标签算内网，带点的算公网域名
    return h.contains('.')
}
