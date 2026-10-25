package com.mie.kreaworkbench.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** r44：冷启动对账的裁决规则。 */
class ReconcileVerdictTest {
    @Test
    fun notSubmittedIsSkipped() {
        assertEquals(ReconcileVerdict.SKIP, reconcileVerdict(false, true, false, false, 5))
        assertEquals(ReconcileVerdict.SKIP, reconcileVerdict(false, false, false, false, 0))
    }

    @Test
    fun unreachableAlwaysKeeps() {
        assertEquals(ReconcileVerdict.KEEP, reconcileVerdict(true, false, false, false, 5))
    }

    @Test
    fun inQueueOrHistoryKeeps() {
        assertEquals(ReconcileVerdict.KEEP, reconcileVerdict(true, true, true, false, 5))
        assertEquals(ReconcileVerdict.KEEP, reconcileVerdict(true, true, false, true, 5))
    }

    @Test
    fun oneMissStillKeeps() {
        assertEquals(ReconcileVerdict.KEEP, reconcileVerdict(true, true, false, false, 1))
        assertEquals(ReconcileVerdict.KEEP, reconcileVerdict(true, true, false, false, 0))
    }

    @Test
    fun twoMissesDropsAsLost() {
        assertEquals(ReconcileVerdict.DROP_AS_LOST, reconcileVerdict(true, true, false, false, 2))
        assertEquals(ReconcileVerdict.DROP_AS_LOST, reconcileVerdict(true, true, false, false, 3))
    }
}
