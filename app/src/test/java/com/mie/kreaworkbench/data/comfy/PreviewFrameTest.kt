package com.mie.kreaworkbench.data.comfy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r50：WS 二进制预览帧头解析（8 字节大端头，PLAN §5.2）。 */
class PreviewFrameTest {

    private fun frame(type: Long, format: Long, payload: Int): ByteArray {
        val body = ByteArray(payload) { (it % 251).toByte() }
        val out = ByteArray(8 + payload)
        out[0] = (type shr 24).toByte()
        out[1] = (type shr 16).toByte()
        out[2] = (type shr 8).toByte()
        out[3] = type.toByte()
        out[4] = (format shr 24).toByte()
        out[5] = (format shr 16).toByte()
        out[6] = (format shr 8).toByte()
        out[7] = format.toByte()
        System.arraycopy(body, 0, out, 8, payload)
        return out
    }

    @Test
    fun `type 1 jpeg yields image bytes`() {
        val f = frame(1, 1, 16)
        val img = PreviewFrame.imageBytes(f)!!
        assertEquals(16, img.size)
        assertArrayEquals(f.copyOfRange(8, 24), img)
    }

    @Test
    fun `type 1 png yields image bytes`() {
        assertEquals(4, PreviewFrame.imageBytes(frame(1, 2, 4))!!.size)
    }

    @Test
    fun `unknown format still sliced (BitmapFactory sniffs)`() {
        assertEquals(4, PreviewFrame.imageBytes(frame(1, 9, 4))!!.size)
    }

    @Test
    fun `non preview types rejected`() {
        assertNull(PreviewFrame.imageBytes(frame(2, 1, 8))) // PreviewText?
        assertNull(PreviewFrame.imageBytes(frame(4, 1, 8))) // 带 metadata 的新式预览，本轮不解析
        assertNull(PreviewFrame.imageBytes(frame(0, 1, 8)))
    }

    @Test
    fun `short and empty frames rejected`() {
        assertNull(PreviewFrame.imageBytes(ByteArray(0)))
        assertNull(PreviewFrame.imageBytes(ByteArray(4)))
        assertNull(PreviewFrame.imageBytes(frame(1, 1, 0))) // 只有头没有图
    }

    @Test
    fun `header fields readable for diagnostics`() {
        val f = frame(1, 2, 1)
        assertEquals(1L, PreviewFrame.eventType(f))
        assertEquals(2L, PreviewFrame.imageFormat(f))
        assertEquals(-1L, PreviewFrame.eventType(ByteArray(2)))
        assertEquals(-1L, PreviewFrame.imageFormat(ByteArray(4)))
    }
}
