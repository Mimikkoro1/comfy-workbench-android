package com.mie.kreaworkbench.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** r44：强制结束清服务器队列的结果分类规则。 */
class ForceClearOutcomeTest {
    @Test
    fun unreachableWinsOverEverything() {
        assertEquals(ForceClearOutcome.SERVER_UNREACHABLE, classifyClear(false, 0, 0))
        assertEquals(ForceClearOutcome.SERVER_UNREACHABLE, classifyClear(false, 3, 5))
    }

    @Test
    fun emptyQueueIsCleared() {
        assertEquals(ForceClearOutcome.CLEARED, classifyClear(true, 0, 0))
    }

    @Test
    fun anythingLeftIsStillBusy() {
        assertEquals(ForceClearOutcome.SERVER_STILL_BUSY, classifyClear(true, 1, 0))
        assertEquals(ForceClearOutcome.SERVER_STILL_BUSY, classifyClear(true, 0, 2))
        assertEquals(ForceClearOutcome.SERVER_STILL_BUSY, classifyClear(true, 1, 3))
    }
}
