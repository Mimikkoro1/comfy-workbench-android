package com.mie.kreaworkbench.ui.screens.custom

import org.junit.Assert.assertEquals
import org.junit.Test

/** r44：复制用的全文拼接（摘要 + 详情 + 原文）。 */
class ErrorCopyTextTest {
    @Test
    fun allThreeJoinedWithBlankLines() {
        assertEquals("a\n\nb\n\nc", errorCopyText("a", "b", "c"))
    }

    @Test
    fun onlyError() {
        assertEquals("a", errorCopyText("a", null, null))
    }

    @Test
    fun blankSegmentsSkipped() {
        assertEquals("a\n\nc", errorCopyText("a", "  ", "c"))
        assertEquals("b", errorCopyText("", "b", ""))
    }

    @Test
    fun allBlankReturnsEmpty() {
        assertEquals("", errorCopyText("", null, ""))
        assertEquals("", errorCopyText("  ", "  ", "  "))
    }

    @Test
    fun segmentsAreTrimmed() {
        assertEquals("a\n\nb", errorCopyText(" a \n", "\tb", null))
    }
}
