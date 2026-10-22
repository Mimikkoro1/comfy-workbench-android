package com.mie.kreaworkbench.data.discovery

import org.json.JSONObject

/** ComfyUI /system_stats 命中：GPU 名（devices[0].name）与版本（system.comfyui_version），都可能为空串。 */
data class ComfyHit(val gpu: String, val version: String)

/** 抽卡服务 /api/health 命中：服务版本字符串。 */
data class GachaHealthHit(val version: String)

/**
 * r39 局域网扫描的纯函数（无 Android 依赖，纯 JVM 单测）：
 * 子网主机枚举 + 两个服务的探测响应校验/解析 + 结果排序键。
 */

/** IPv4 四字节（网络序）→ int（高位是首字节）。 */
fun ipv4BytesToInt(bytes: ByteArray): Int =
    ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)

/** int → 点分十进制。 */
fun intToIpv4(v: Int): String =
    "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

/**
 * 要扫描的主机 IP 列表（升序、点分十进制）。
 * prefix < 24 一律按 /24 处理（只扫本机所在的 /24）；≥24 按实际网段。
 * 去掉网络地址、广播地址和本机自己（[self]，默认等于 [selfIp]）。
 */
fun hostsToScan(selfIp: Int, prefix: Int, self: Int = selfIp): List<String> {
    val p = if (prefix < 24) 24 else prefix.coerceAtMost(32)
    val mask = if (p == 0) 0 else (-1 shl (32 - p))
    val network = selfIp and mask
    val broadcast = network or mask.inv()
    val out = ArrayList<String>()
    var v = network + 1
    while (v < broadcast) {
        if (v != self) out.add(intToIpv4(v))
        v++
    }
    return out
}

/**
 * ComfyUI /system_stats 响应校验：200 + JSON 对象含 system 对象且含 devices 数组才算命中。
 * 顺带取 devices[0].name（GPU 名）与 system.comfyui_version；不是 JSON / 缺结构返回 null。
 */
fun parseComfyStats(text: String): ComfyHit? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return null
    val json = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        return null
    }
    val system = json.optJSONObject("system") ?: return null
    val devices = json.optJSONArray("devices") ?: return null
    val gpu = devices.optJSONObject(0)?.optString("name").orEmpty().trim()
    val version = system.optString("comfyui_version").trim()
    return ComfyHit(gpu = gpu, version = version)
}

/**
 * 抽卡服务 /api/health 响应校验：200 + ok==true 且带非空 version 字符串。
 * ComfyUI 的 /system_stats（没有 ok 字段）不会被误判。
 */
fun parseGachaHealth(text: String): GachaHealthHit? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return null
    val json = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        return null
    }
    if (!json.optBoolean("ok")) return null
    val version = json.optString("version").trim()
    if (version.isEmpty()) return null
    return GachaHealthHit(version)
}

/** 结果排序键：从 http://IP:PORT 里取 IP 的数值；解析不出返回 Long.MAX_VALUE（排最后）。 */
fun lanHitIpKey(url: String): Long {
    val host = url.substringAfter("://").substringBefore(':').substringBefore('/')
    val parts = host.split(".")
    if (parts.size != 4) return Long.MAX_VALUE
    var v = 0L
    for (part in parts) {
        val n = part.toIntOrNull() ?: return Long.MAX_VALUE
        if (n !in 0..255) return Long.MAX_VALUE
        v = (v shl 8) or n.toLong()
    }
    return v
}
