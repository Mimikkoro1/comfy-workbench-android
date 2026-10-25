package com.mie.kreaworkbench.data.comfy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r42：ComfyUI execution_error 的解析与格式化（WS 与 history 共用同一函数）。 */
class ExecErrorTest {

    private fun fullData(): JSONObject = JSONObject(
        """
        {
          "prompt_id": "pid-1",
          "node_id": "16",
          "node_type": "KSampler",
          "exception_type": "OOM",
          "exception_message": "CUDA out of memory",
          "traceback": ["line1", "line2", "line3"]
        }
        """,
    )

    @Test
    fun parsesAllFieldsAndJoinsTraceback() {
        val e = parseExecutionError(fullData())!!
        assertEquals("pid-1", e.promptId)
        assertEquals("16", e.nodeId)
        assertEquals("KSampler", e.nodeType)
        assertEquals("OOM", e.exceptionType)
        assertEquals("CUDA out of memory", e.exceptionMessage)
        assertEquals("line1\nline2\nline3", e.traceback)
    }

    @Test
    fun missingFieldsDefaultToEmpty() {
        val e = parseExecutionError(JSONObject().put("prompt_id", "p"))!!
        assertEquals("p", e.promptId)
        assertEquals("", e.nodeId)
        assertEquals("", e.nodeType)
        assertEquals("", e.exceptionType)
        assertEquals("", e.exceptionMessage)
        assertEquals("", e.traceback)
    }

    @Test
    fun missingPromptIdReturnsNull() {
        assertNull(parseExecutionError(JSONObject().put("node_type", "KSampler")))
        assertNull(parseExecutionError(JSONObject().put("prompt_id", "   ")))
        assertNull(parseExecutionError(JSONObject())) // 空对象
    }

    @Test
    fun longTracebackKeepsTailOnly() {
        val lines = (0 until 500).map { "trace line $it with padding 0123456789012345" }
        val data = fullData().put("traceback", org.json.JSONArray(lines))
        val e = parseExecutionError(data)!!
        assertTrue(e.traceback.length <= 4097) // 4096 + 省略号
        assertTrue(e.traceback.startsWith("…"))
        // 保留的是末尾：最后一行还在，第一行没了
        assertTrue(e.traceback.contains(lines.last()))
        assertFalse(e.traceback.contains(lines.first()))
    }

    @Test
    fun longExceptionMessageTruncated() {
        val data = fullData().put("exception_message", "x".repeat(2000))
        assertEquals(1000, parseExecutionError(data)!!.exceptionMessage.length)
    }

    @Test
    fun nonArrayTracebackDoesNotThrow() {
        // 字符串 traceback
        val s = parseExecutionError(fullData().put("traceback", "raw text"))!!
        assertEquals("raw text", s.traceback)
        // 缺失 traceback
        val none = parseExecutionError(JSONObject().put("prompt_id", "p"))!!
        assertEquals("", none.traceback)
    }

    @Test
    fun formattedTextContainsNodeAndException() {
        val text = formatExecError(parseExecutionError(fullData())!!)
        assertTrue(text.contains("KSampler"))
        assertTrue(text.contains("CUDA out of memory"))
        assertTrue(text.contains("pid-1"))
        assertTrue(text.contains("line1"))
    }

    @Test
    fun boundedCacheEvictsOldest() {
        val c = BoundedCache<String, String>(20)
        for (i in 0 until 21) c.put("k$i", "v$i")
        assertNull(c["k0"])
        assertEquals("v1", c["k1"])
        assertEquals("v20", c["k20"])
        // 覆盖已有 key 不挤出别人
        c.put("k1", "v1-new")
        assertEquals("v1-new", c["k1"])
        assertEquals("v20", c["k20"])
    }

    @Test
    fun boundedCacheIndependentKeys() {
        val c = BoundedCache<String, String>(20)
        for (i in 0 until 10) c.put("k$i", "v$i")
        // 模拟「其它 prompt 正常结束清进度」：对别的 key 读写不影响已有条目
        c.put("other", "x")
        assertEquals("x", c["other"])
        assertEquals("v5", c["k5"])
    }
}
