package com.mie.kreaworkbench.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r41 地址规范化扩展：补默认端口、截接口路径、?/# 剥离、候选列表。（round34 的 NormalizeServerUrlTest 保持原样。） */
class ServerUrlNormalizerTest {

    private fun norm(raw: String, kind: ServerKind = ServerKind.COMFY): String? =
        normalizeServerUrl(raw, kind)

    @Test
    fun blankIsNull() {
        assertNull(norm(""))
    }

    @Test
    fun privateIpv4GetsDefaultPort() {
        assertEquals("http://192.168.1.100:8188", norm("192.168.1.100"))
        assertEquals("http://10.0.0.52:8188", norm("10.0.0.52"))
        assertEquals("http://172.16.0.5:8188", norm("172.16.0.5"))
        // 公网段不补
        assertEquals("http://172.32.0.5", norm("172.32.0.5"))
        // 显式 http 补，显式 https 不补
        assertEquals("http://192.168.1.100:8188", norm("http://192.168.1.100"))
        assertEquals("https://192.168.1.100", norm("https://192.168.1.100"))
    }

    @Test
    fun gachaKindGetsItsPort() {
        assertEquals("http://192.168.1.100:8787", norm("192.168.1.100", ServerKind.GACHA))
    }

    @Test
    fun existingPortAndTrailingSlashUnchanged() {
        assertEquals("http://192.168.1.100:8188", norm("http://192.168.1.100:8188/"))
        assertEquals("http://192.168.1.100:8188", norm("192.168.1.100：8188"))
    }

    @Test
    fun httpsDomainKeptAsIs() {
        assertEquals("https://foo.example.com", norm("HTTPS://foo.example.com/"))
        // 域名不加 www.、不补端口
        assertEquals("http://foo.example.com", norm("foo.example.com"))
    }

    @Test
    fun apiSegmentsTruncated() {
        assertEquals("http://foo.example.com:8189", norm("foo.example.com:8189/api/system_stats"))
        assertEquals("http://192.168.1.100:8188", norm("http://192.168.1.100:8188/ws?clientId=x#y"))
    }

    @Test
    fun reverseProxySubpathKept() {
        // 已有行为：普通子路径（反向代理子目录）保留
        assertEquals("http://10.0.0.2:8188/x", norm("10.0.0.2：8188／x"))
    }

    @Test
    fun ipv6OnlySchemeAdded() {
        assertEquals("http://[::1]:8188", norm("[::1]:8188"))
        // [::1] 没端口也不补
        assertEquals("http://[::1]", norm("[::1]"))
    }

    @Test
    fun hostlessIsNull() {
        assertNull(norm("http://"))
        assertNull(norm("：8188"))
    }

    // ---- serverUrlCandidates ----

    @Test
    fun schemeLessDomainTriesHttpsFirst() {
        val list = serverUrlCandidates("foo.example.com")
        assertEquals(listOf("https://foo.example.com", "http://foo.example.com"), list)
    }

    @Test
    fun ipAndExplicitSchemeAndLocalNameAreSingleCandidate() {
        assertEquals(listOf("http://192.168.1.100:8188"), serverUrlCandidates("192.168.1.100"))
        assertEquals(listOf("https://foo.example.com"), serverUrlCandidates("https://foo.example.com"))
        assertEquals(listOf("http://mypc.local"), serverUrlCandidates("mypc.local"))
    }

    @Test
    fun blankIsEmptyList() {
        assertEquals(emptyList<String>(), serverUrlCandidates(""))
    }
}
