package com.mie.kreaworkbench.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r42 反馈入口纯函数：GitHub Issue 预填链接、正文模板、诊断地址脱敏。 */
class FeedbackLinksTest {

    @Test
    fun buildsPrefilledIssueUrl() {
        val url = buildGithubIssueUrl("https://github.com/Mimikkoro1/comfy-workbench-android", "[Feedback] ", "body")
        assertTrue(url.startsWith("https://github.com/Mimikkoro1/comfy-workbench-android/issues/new?title="))
        assertTrue(url.contains("&body=body"))
    }

    @Test
    fun encodesSpecialCharacters() {
        val url = buildGithubIssueUrl("https://github.com/x/y/", "t", "a&b#c d\ne")
        val bodyPart = url.substringAfter("&body=")
        // body 参数里不允许裸的 & # 空格（& 会截断参数、# 会截断 fragment）
        assertFalse(bodyPart.contains("&"))
        assertFalse(bodyPart.contains("#"))
        assertFalse(bodyPart.contains(" "))
        assertTrue(bodyPart.contains("%26"))
        assertTrue(bodyPart.contains("%23"))
        assertTrue(bodyPart.contains("%20"))
        assertFalse(url.contains("+"))
    }

    @Test
    fun truncatesLongBodyWithinLimitAndKeepsTitle() {
        val url = buildGithubIssueUrl("https://github.com/x/y", "[Feedback] title", "x".repeat(5000))
        assertTrue(url.length <= 1900)
        assertFalse(url.endsWith("%"))
        // title 保留完整（[Feedback] title → %5BFeedback%5D%20title）
        assertTrue(url.contains("%5BFeedback%5D%20title"))
    }

    @Test
    fun truncatesMultibyteBodyWithoutCuttingEscape() {
        val url = buildGithubIssueUrl("https://github.com/x/y", "t", "中".repeat(2000))
        assertTrue(url.length <= 1900)
        val bodyPart = url.substringAfter("&body=")
        // 末尾要么是完整 %XX（其后再无残缺 %），要么是不带 % 的普通字符
        assertTrue(bodyPart.substringAfterLast("%").length >= 2)
    }

    @Test
    fun bodyTemplateContainsAllFields() {
        val body = feedbackIssueBody("0.4.2 (101)", "vivo V2458A", "Android 16 (SDK 36)", "hint text")
        assertTrue(body.contains("0.4.2 (101)"))
        assertTrue(body.contains("vivo V2458A"))
        assertTrue(body.contains("Android 16 (SDK 36)"))
        assertTrue(body.contains("hint text"))
    }

    @Test
    fun sanitizeUrlStripsCredentials() {
        assertEquals("http://10.0.0.1:8188", sanitizeUrl("http://user:pass@10.0.0.1:8188"))
        val out = sanitizeUrl("http://user:pass@10.0.0.1:8188")
        assertFalse(out.contains("pass"))
        assertFalse(out.contains("user"))
        // 没有凭据的地址原样返回
        assertEquals("http://10.0.0.1:8188", sanitizeUrl("http://10.0.0.1:8188"))
    }
}
