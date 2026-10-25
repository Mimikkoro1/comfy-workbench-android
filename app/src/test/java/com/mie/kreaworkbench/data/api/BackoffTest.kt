package com.mie.kreaworkbench.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun `first attempts use the table`() {
        assertEquals(0L, backoffDelayMs(0))
        assertEquals(1000L, backoffDelayMs(1))
        assertEquals(3000L, backoffDelayMs(2))
        assertEquals(6000L, backoffDelayMs(3))
    }

    @Test
    fun `out-of-range attempts clamp to last step`() {
        assertEquals(6000L, backoffDelayMs(4))
        assertEquals(6000L, backoffDelayMs(99))
        assertEquals(6000L, backoffDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `negative attempt waits nothing`() {
        assertEquals(0L, backoffDelayMs(-1))
    }

    @Test
    fun `default attempts equal table length`() {
        assertEquals(4, RETRY_ATTEMPTS)
    }

    @Test
    fun `table starts at zero and never decreases`() {
        assertEquals(0L, RETRY_BACKOFF_MS.first())
        for (i in 1 until RETRY_BACKOFF_MS.size) {
            assertTrue(RETRY_BACKOFF_MS[i] >= RETRY_BACKOFF_MS[i - 1])
        }
    }
}
