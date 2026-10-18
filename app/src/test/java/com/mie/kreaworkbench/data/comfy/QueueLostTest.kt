package com.mie.kreaworkbench.data.comfy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueLostTest {
    @Test
    fun lostNeedsBothMissesAndGrace() {
        assertFalse(PromptLostPolicy.isLost(1, 0L, 9_000L))
        assertFalse(PromptLostPolicy.isLost(3, 0L, 20_000L))
        assertFalse(PromptLostPolicy.isLost(4, 0L, 7_999L))
        assertTrue(PromptLostPolicy.isLost(4, 0L, 8_000L))
    }

    @Test
    fun invalidateDropsCacheAndRejectsStaleStore() {
        val cache = QueueSnapshotCache(1_000L)
        val epoch = cache.currentEpoch()
        assertTrue(cache.store(epoch, 0L, JSONObject().put("n", 1)))
        assertEquals(1, cache.getIfFresh(500L)?.optInt("n"))
        cache.invalidate()
        assertNull(cache.getIfFresh(500L))
        assertFalse(cache.store(epoch, 600L, JSONObject().put("n", 2)))
        assertNull(cache.getIfFresh(600L))
    }

    @Test
    fun cacheExpiresAfterTtl() {
        val cache = QueueSnapshotCache(1_000L)
        assertTrue(cache.store(cache.currentEpoch(), 0L, JSONObject().put("n", 1)))
        assertNull(cache.getIfFresh(1_000L))
    }
}
