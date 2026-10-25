package com.mie.kreaworkbench.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveExpiryTest {
    private val t = 1_000_000_000L

    @Test
    fun `done phases expire after five minutes`() {
        for (phase in listOf("success", "partial", "cancelled")) {
            assertEquals("$phase just finished", false, isLiveExpired(phase, t, t + LIVE_TTL_DONE_MS - 1))
            assertEquals("$phase at ttl", true, isLiveExpired(phase, t, t + LIVE_TTL_DONE_MS))
        }
    }

    @Test
    fun `failed stays longer than done phases`() {
        assertEquals(false, isLiveExpired("failed", t, t + LIVE_TTL_DONE_MS))
        assertEquals(true, isLiveExpired("failed", t, t + LIVE_TTL_FAILED_MS))
    }

    @Test
    fun `non-terminal and paused never expire`() {
        for (phase in listOf("paused", "queued", "running", "downloading", "uploading", "upload_ask", "submitting", "")) {
            assertEquals("$phase", false, isLiveExpired(phase, t, t + 365L * 24 * 60 * 60 * 1000))
        }
    }

    @Test
    fun `unknown finish time never expires`() {
        for (phase in listOf("success", "partial", "failed", "cancelled")) {
            assertEquals("$phase", false, isLiveExpired(phase, 0L, t))
            assertEquals("$phase", false, isLiveExpired(phase, -1L, t))
        }
    }

    @Test
    fun `ttl constants have the planned values`() {
        assertEquals(5 * 60_000L, LIVE_TTL_DONE_MS)
        assertEquals(30 * 60_000L, LIVE_TTL_FAILED_MS)
        assertTrue(LIVE_TTL_FAILED_MS > LIVE_TTL_DONE_MS)
    }
}
