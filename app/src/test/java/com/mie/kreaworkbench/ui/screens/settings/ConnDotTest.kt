package com.mie.kreaworkbench.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** r43 设置首页连接状态点的三态换算。 */
class ConnDotTest {

    @Test
    fun idleStates() {
        assertEquals(ConnDot.UNKNOWN, connDotState(null, false))
        assertEquals(ConnDot.OK, connDotState(true, false))
        assertEquals(ConnDot.FAILED, connDotState(false, false))
    }

    @Test
    fun busyAlwaysUnknown() {
        assertEquals(ConnDot.UNKNOWN, connDotState(null, true))
        assertEquals(ConnDot.UNKNOWN, connDotState(true, true))
        assertEquals(ConnDot.UNKNOWN, connDotState(false, true))
    }
}
