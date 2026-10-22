package com.mie.kreaworkbench.data.discovery

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanMathTest {

    private fun ip(s: String): Int {
        val b = s.split(".").map { it.toInt() }
        return ipv4BytesToInt(byteArrayOf(b[0].toByte(), b[1].toByte(), b[2].toByte(), b[3].toByte()))
    }

    // ---- hostsToScan ----

    @Test
    fun slash24Lists253HostsExcludingSelfAndNetworkAndBroadcast() {
        val hosts = hostsToScan(ip("192.168.1.7"), 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertTrue(!hosts.contains("192.168.1.7"))
        assertTrue(!hosts.contains("192.168.1.0"))
        assertTrue(!hosts.contains("192.168.1.255"))
    }

    @Test
    fun slash16IsClampedToOwnSlash24() {
        val hosts = hostsToScan(ip("10.20.30.40"), 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.20.30.") })
        assertTrue(hosts.contains("10.20.30.41"))
    }

    @Test
    fun slash25ScansLowerHalfOnly() {
        val hosts = hostsToScan(ip("192.168.1.7"), 25)
        assertEquals(125, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.126", hosts.last())
    }

    @Test
    fun slash30KeepsOnlyUsableHosts() {
        // 192.168.1.4/30：网络 .4、广播 .7、可用 .5/.6；本机 .5 排掉 → 只剩 .6
        assertEquals(listOf("192.168.1.6"), hostsToScan(ip("192.168.1.5"), 30))
    }

    @Test
    fun selfIsExcludedEvenWhenGivenSeparately() {
        // /24 可用主机 254 个（去掉网络/广播），再排除指定的 self（.9）→ 253；本机 .7 保留在列表里
        val hosts = hostsToScan(ip("192.168.1.7"), 24, self = ip("192.168.1.9"))
        assertEquals(253, hosts.size)
        assertTrue(!hosts.contains("192.168.1.9"))
        assertTrue(hosts.contains("192.168.1.7"))
    }

    // ---- parseComfyStats ----

    private fun comfyStats(gpuName: String, version: String): String = JSONObject()
        .put(
            "system",
            JSONObject().put("os", "nt").put("comfyui_version", version),
        )
        .put(
            "devices",
            JSONArray().put(JSONObject().put("name", gpuName).put("type", "cuda")),
        )
        .toString()

    @Test
    fun parseComfyStatsAcceptsRealShape() {
        val hit = parseComfyStats(comfyStats("NVIDIA GeForce RTX 4070", "0.3.10"))
        assertEquals("NVIDIA GeForce RTX 4070", hit!!.gpu)
        assertEquals("0.3.10", hit.version)
    }

    @Test
    fun parseComfyStatsRejectsMissingDevicesOrSystem() {
        val noDevices = JSONObject().put("system", JSONObject().put("os", "nt")).toString()
        assertNull(parseComfyStats(noDevices))
        val noSystem = JSONObject().put("devices", JSONArray().put(JSONObject())).toString()
        assertNull(parseComfyStats(noSystem))
    }

    @Test
    fun parseComfyStatsRejectsHtmlEmptyAndGarbage() {
        assertNull(parseComfyStats("<html><body>404</body></html>"))
        assertNull(parseComfyStats(""))
        assertNull(parseComfyStats("not json {"))
    }

    // ---- parseGachaHealth ----

    @Test
    fun parseGachaHealthAcceptsOkTrueWithVersion() {
        val hit = parseGachaHealth(JSONObject().put("ok", true).put("version", "1.4").put("root", "x").toString())
        assertEquals("1.4", hit!!.version)
    }

    @Test
    fun parseGachaHealthRejectsOkFalseMissingVersionAndComfyShape() {
        assertNull(parseGachaHealth(JSONObject().put("ok", false).put("version", "1.4").toString()))
        assertNull(parseGachaHealth(JSONObject().put("ok", true).toString()))
        // ComfyUI 的 /system_stats 没有 ok 字段，不能误判成抽卡服务
        assertNull(parseGachaHealth(comfyStats("RTX", "0.3.10")))
        assertNull(parseGachaHealth("<html></html>"))
    }

    // ---- lanHitIpKey ----

    @Test
    fun lanHitIpKeySortsNumerically() {
        assertTrue(lanHitIpKey("http://192.168.1.9:8188") < lanHitIpKey("http://192.168.1.10:8188"))
        assertTrue(lanHitIpKey("http://10.0.0.1:8188") < lanHitIpKey("http://192.168.1.2:8188"))
        // 解析不出的一律排最后
        assertEquals(Long.MAX_VALUE, lanHitIpKey("http://example.com:8188"))
        assertEquals(Long.MAX_VALUE, lanHitIpKey("not a url"))
    }
}
