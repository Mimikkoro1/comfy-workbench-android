package com.mie.kreaworkbench.ui.screens.custom

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawCatTest {
    @Test
    fun `null and blank become empty`() {
        assertEquals("", normalizeDrawCat(null))
        assertEquals("", normalizeDrawCat(""))
        assertEquals("", normalizeDrawCat("  "))
    }

    @Test
    fun `legacy sentinel quanbu becomes empty`() {
        assertEquals("", normalizeDrawCat("全部"))
        assertEquals("", normalizeDrawCat(" 全部 "))
    }

    @Test
    fun `real categories are trimmed and kept`() {
        assertEquals("风景", normalizeDrawCat("风景"))
        assertEquals("风景", normalizeDrawCat(" 风景 "))
    }

    @Test
    fun `category merely containing quanbu is kept`() {
        assertEquals("全部风景", normalizeDrawCat("全部风景"))
    }
}
