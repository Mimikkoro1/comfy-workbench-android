package com.mie.kreaworkbench.ui.screens.custom

import org.junit.Assert.assertEquals
import org.junit.Test

/** r43 生成失败弹窗文本拼接：error 主体 + 空一行 + 详情。 */
class ErrorDialogTextTest {

    @Test
    fun detailNullReturnsTrimmedError() {
        assertEquals("模型不存在", errorDialogText(" 模型不存在 ", null))
    }

    @Test
    fun detailAppendedAfterBlankLine() {
        assertEquals("失败\n\nnode: kSampler", errorDialogText("失败", "node: kSampler"))
    }

    @Test
    fun blankErrorKeepsDetailOnly() {
        val r = errorDialogText("  ", "node: kSampler")
        assertEquals("node: kSampler", r)
        assertEquals(false, r.startsWith("\n"))
    }

    @Test
    fun blankDetailReturnsErrorOnly() {
        assertEquals("失败", errorDialogText("失败", "  \n\t "))
        assertEquals("失败", errorDialogText("失败", ""))
    }

    @Test
    fun bothBlankIsEmpty() {
        assertEquals("", errorDialogText("", null))
        assertEquals("", errorDialogText(" ", ""))
    }
}
