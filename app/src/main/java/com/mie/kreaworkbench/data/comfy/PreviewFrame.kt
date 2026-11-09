package com.mie.kreaworkbench.data.comfy

/**
 * r50：ComfyUI /ws 二进制预览帧头解析（PLAN_live_preview_r50 §5.2）。
 * 大端 uint32：[0..3] = eventType（1 = PREVIEW_IMAGE），[4..7] = imageFormat（1=JPEG，2=PNG），
 * 从 offset 8 起是图像字节。本轮只认 type=1；type=4（带 JSON metadata）实测出现再补解析。
 * 纯字节运算，JVM 单测可覆盖；BitmapFactory 解码在 ComfyProgress 里，不在此处。
 */
internal object PreviewFrame {

    /** 返回图像字节段；null = 不是本轮支持的预览帧（头部不足 9 字节或 type≠1）。 */
    fun imageBytes(frame: ByteArray): ByteArray? {
        if (frame.size <= 8) return null
        if (eventType(frame) != 1L) return null
        return frame.copyOfRange(8, frame.size)
    }

    /** 帧头事件类型（诊断用）；不足 4 字节返回 -1。 */
    fun eventType(frame: ByteArray): Long = if (frame.size < 4) -1L else beUInt32(frame, 0)

    /** 帧头图像格式（诊断用）：1=JPEG、2=PNG；不足 8 字节返回 -1。 */
    fun imageFormat(frame: ByteArray): Long = if (frame.size < 8) -1L else beUInt32(frame, 4)

    private fun beUInt32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
}
