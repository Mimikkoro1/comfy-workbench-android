package com.mie.kreaworkbench.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceTickTest {
    @Test
    fun `busy loop ticks slower than idle`() {
        assertEquals(5000L, serviceTickMs(true))
        assertEquals(2000L, serviceTickMs(false))
    }
}
