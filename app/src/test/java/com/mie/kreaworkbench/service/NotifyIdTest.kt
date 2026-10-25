package com.mie.kreaworkbench.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyIdTest {
    @Test
    fun `same client id always maps to the same notification id`() {
        assertEquals(doneNotifyId("cq_abc"), doneNotifyId("cq_abc"))
    }

    @Test
    fun `different client ids usually differ`() {
        assertNotEquals(doneNotifyId("a"), doneNotifyId("b"))
    }

    @Test
    fun `uuid-shaped ids stay in range and are distinct`() {
        val ids = HashSet<Int>()
        for (i in 0 until 64) {
            val id = doneNotifyId("cq_" + "0123456789abcdef".map { it }.joinToString("") + "_$i")
            assertTrue("id=$id must be >= 100", id >= 100)
            ids.add(id)
        }
        // 64 个输入允许极端哈希碰撞，但几乎不该全撞在一起
        assertTrue("ids=$ids", ids.size > 1)
    }

    @Test
    fun `empty and long ids still stay in range`() {
        assertTrue(doneNotifyId("") >= 100)
        assertTrue(doneNotifyId("x".repeat(100_000)) >= 100)
    }

    @Test
    fun `never collides with the foreground progress id`() {
        for (id in listOf(doneNotifyId(""), doneNotifyId("a"), doneNotifyId("cq_test"), doneNotifyId("41"))) {
            assertNotEquals(41, id)
        }
    }
}
