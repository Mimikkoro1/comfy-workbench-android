package com.mie.kreaworkbench.data.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * r41：安卓 17（targetSdk 37 起）访问局域网地址需要 ACCESS_LOCAL_NETWORK（「附近设备」权限组）。
 * 没权限时 TCP 表现为静默超时；公网地址不受影响。低版本系统忽略该权限。
 * 判定逻辑纯 JVM，方便单测；Android 相关只有 [isLocalNetworkGranted]。
 */
// 用字符串常量而不是 Manifest.permission.*：旧编译 SDK 上这个名字不存在，直接写字符串最稳
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** 从这个 SDK 级别开始系统强制该权限；低于它一律视为已授权。 */
const val LOCAL_NETWORK_ENFORCED_SDK = 37

/**
 * 主机是否指向局域网：
 * - IPv4：10/8、172.16/12、192.168/16、169.254/16（链路本地）→ true；回环 127/8、其他公网 → false。
 * - IPv6：fc00::/7（ULA）、fe80::/10（链路本地）→ true；回环 ::1 → false。
 * - `.local` 结尾（mDNS）或不含 `.` 的单段主机名（局域网机器名）→ true；普通域名 → false。
 * - 100.64/10（CGNAT/Tailscale）文档未明确，先不算。
 */
fun isLocalNetworkHost(host: String): Boolean {
    val h = host.trim().trimEnd('.').removePrefix("[").removeSuffix("]").lowercase()
    if (h.isEmpty() || h == "localhost" || h == "::1" || h == "::") return false
    if (h.contains(':')) {
        // IPv6：只认第一个 hextet 落在 fc00::/7 或 fe80::/10 的地址
        val first = h.substringBefore(':').toIntOrNull(16) ?: return false
        return first in 0xfc00..0xfdff || first in 0xfe80..0xfebf
    }
    if (h.endsWith(".local")) return true
    if (!h.contains('.')) return true
    val parts = h.split('.')
    if (parts.size != 4) return false
    var v = 0
    for (p in parts) {
        val n = p.toIntOrNull() ?: return false
        if (n !in 0..255) return false
        v = (v shl 8) or n
    }
    val a = (v ushr 24) and 0xFF
    val b = (v ushr 16) and 0xFF
    return (a == 10) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254)
}

/**
 * 地址是否指向局域网。容忍没写协议、带端口、带路径、`?`/`#`、`[IPv6]`、user@host；
 * 空串或解析不出主机 → false。
 */
fun urlTargetsLocalNetwork(url: String): Boolean {
    val s = url.trim().replace('：', ':')
    if (s.isEmpty()) return false
    val authority = s.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']') else authority.substringBefore(':')
    if (host.isBlank()) return false
    return isLocalNetworkHost(host)
}

/** 是否需要申请（只有 37+、未授权、且目标是局域网才为 true）。 */
fun needsLocalNetworkPermission(sdkInt: Int, granted: Boolean, hostIsLocal: Boolean): Boolean =
    sdkInt >= LOCAL_NETWORK_ENFORCED_SDK && !granted && hostIsLocal

/** 当前是否已允许访问局域网；SDK < 37 的系统没有这个限制，一律 true。 */
fun isLocalNetworkGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_ENFORCED_SDK) return true
    return ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED
}
