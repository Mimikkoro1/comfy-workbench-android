package com.mie.kreaworkbench.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 地址输入框规范化：全角转半角、补 http://、无主机名判无效。 */
class NormalizeServerUrlTest {

    @Test
    fun addsSchemeAndTrims() {
        assertEquals("http://192.168.1.100:8188", normalizeServerUrl("192.168.1.100:8188"))
        assertEquals("http://192.168.1.100:8188", normalizeServerUrl("  http://192.168.1.100:8188  "))
    }

    @Test
    fun convertsFullWidthPunctuation() {
        // 全角冒号（输入法常见误输）
        assertEquals("http://192.168.1.100:8188", normalizeServerUrl("192.168.1.100：8188"))
        // 全角斜杠带路径
        assertEquals("http://10.0.0.2:8188/x", normalizeServerUrl("10.0.0.2：8188／x"))
    }

    @Test
    fun keepsExplicitScheme() {
        assertEquals("https://comfy.example.com", normalizeServerUrl("https://comfy.example.com"))
        // 大写 scheme 也认，统一转小写
        assertEquals("http://192.168.1.100:8188", normalizeServerUrl("HTTP://192.168.1.100:8188"))
    }

    @Test
    fun rejectsBlankAndHostless() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("   "))
        assertNull(normalizeServerUrl("http://"))
        assertNull(normalizeServerUrl("：8188"))
        assertNull(normalizeServerUrl("http://:8188"))
    }
}
