package com.mie.kreaworkbench.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r44：排队卡住小字与无进展看门狗的判定规则。 */
class StallPolicyTest {
    @Test
    fun queuedShowsHintAfter90s() {
        val since = 1_000_000L
        assertFalse(queuedLooksStuck("queued", since, since + 89_999))
        assertTrue(queuedLooksStuck("queued", since, since + 90_000))
    }

    @Test
    fun onlyQueuedPhaseCounts() {
        val since = 1_000_000L
        assertFalse(queuedLooksStuck("running", since, since + 10 * 60_000L))
        assertFalse(queuedLooksStuck("downloading", since, since + 10 * 60_000L))
    }

    @Test
    fun unknownEntryTimeNeverStuck() {
        assertFalse(queuedLooksStuck("queued", 0L, 10_000_000L))
        assertFalse(queuedLooksStuck("queued", -1L, 10_000_000L))
    }

    @Test
    fun watchdogNeedsPositiveLastProgress() {
        val now = 10_000_000L
        assertFalse(noProgressStalled(0L, now))
        assertFalse(noProgressStalled(-1L, now))
    }

    @Test
    fun watchdogTriggersAt5Minutes() {
        val t = 1_000_000L
        assertFalse(noProgressStalled(t, t + 299_999))
        assertTrue(noProgressStalled(t, t + 300_000))
    }
}
