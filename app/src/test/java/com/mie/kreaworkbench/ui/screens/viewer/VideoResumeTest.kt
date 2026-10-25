package com.mie.kreaworkbench.ui.screens.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoResumeTest {
    @Test
    fun `negative position clamps to zero`() {
        assertEquals(0L, sanitizeResumePositionMs(-5, 0))
        assertEquals(0L, sanitizeResumePositionMs(-5, 3000))
    }

    @Test
    fun `unknown duration keeps the position`() {
        assertEquals(0L, sanitizeResumePositionMs(0, 0))
        assertEquals(5000L, sanitizeResumePositionMs(5000, 0))
        assertEquals(5000L, sanitizeResumePositionMs(5000, -1))
    }

    @Test
    fun `known duration caps the position`() {
        assertEquals(3000L, sanitizeResumePositionMs(5000, 3000))
        assertEquals(2000L, sanitizeResumePositionMs(2000, 3000))
    }
}
