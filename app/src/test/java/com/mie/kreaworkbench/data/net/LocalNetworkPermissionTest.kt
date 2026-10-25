package com.mie.kreaworkbench.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 局域网权限判定（r41）：主机分类、地址取主机、权限真值表。 */
class LocalNetworkPermissionTest {

    // ---- isLocalNetworkHost ----

    @Test
    fun localIpv4Ranges() {
        assertTrue(isLocalNetworkHost("10.0.0.52"))
        assertTrue(isLocalNetworkHost("172.16.0.1"))
        assertTrue(isLocalNetworkHost("172.31.255.1"))
        assertTrue(isLocalNetworkHost("192.168.1.100"))
        assertTrue(isLocalNetworkHost("169.254.1.1"))
    }

    @Test
    fun localIpv6Ranges() {
        assertTrue(isLocalNetworkHost("fd00::1"))
        assertTrue(isLocalNetworkHost("fe80::1"))
    }

    @Test
    fun localNames() {
        assertTrue(isLocalNetworkHost("mypc.local"))
        assertTrue(isLocalNetworkHost("mypc"))
    }

    @Test
    fun nonLocalHosts() {
        // 172.16/12 边界外、公网、域名
        assertFalse(isLocalNetworkHost("172.15.0.1"))
        assertFalse(isLocalNetworkHost("172.32.0.1"))
        assertFalse(isLocalNetworkHost("8.8.8.8"))
        assertFalse(isLocalNetworkHost("foo.example.com"))
        // 回环
        assertFalse(isLocalNetworkHost("127.0.0.1"))
        // CGNAT/Tailscale 段：文档未明确，先不算
        assertFalse(isLocalNetworkHost("100.64.0.1"))
    }

    // ---- urlTargetsLocalNetwork ----

    @Test
    fun extractsHostFromBareAndFullUrls() {
        assertTrue(urlTargetsLocalNetwork("10.0.0.52:8188"))
        assertTrue(urlTargetsLocalNetwork("http://192.168.1.100:8188/"))
        // 大写协议 + IPv6 方括号
        assertTrue(urlTargetsLocalNetwork("HTTP://[fd00::1]:8188"))
    }

    @Test
    fun publicOrUnparsableUrlsAreNotLocal() {
        assertFalse(urlTargetsLocalNetwork("https://foo.example.com"))
        assertFalse(urlTargetsLocalNetwork(""))
        assertFalse(urlTargetsLocalNetwork("http://"))
    }

    // ---- needsLocalNetworkPermission 真值表：只有 37+、未授权、局域网 为 true ----

    @Test
    fun needsPermissionTruthTable() {
        for (sdk in intArrayOf(36, 37, 38)) {
            for (granted in booleanArrayOf(false, true)) {
                for (local in booleanArrayOf(false, true)) {
                    val expected = sdk >= 37 && !granted && local
                    assertEquals("sdk=$sdk granted=$granted local=$local", expected,
                        needsLocalNetworkPermission(sdk, granted, local))
                }
            }
        }
    }
}
