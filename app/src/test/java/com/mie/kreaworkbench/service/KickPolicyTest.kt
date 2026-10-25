package com.mie.kreaworkbench.service

import org.junit.Assert.assertEquals
import org.junit.Test

class KickPolicyTest {
    @Test
    fun `paused offline is not kicked`() {
        assertEquals(false, shouldKickOnReconcile("paused", online = false))
    }

    @Test
    fun `paused online is kicked`() {
        assertEquals(true, shouldKickOnReconcile("paused", online = true))
    }

    @Test
    fun `other states are kicked even offline`() {
        for (state in listOf("queued", "running", "submitting", "upload_fail_ask", "")) {
            assertEquals("state=$state", true, shouldKickOnReconcile(state, online = false))
        }
    }

    @Test
    fun `everything is kicked when online`() {
        for (state in listOf("paused", "queued", "running", "submitting", "upload_fail_ask", "")) {
            assertEquals("state=$state", true, shouldKickOnReconcile(state, online = true))
        }
    }
}
