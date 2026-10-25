package com.mie.kreaworkbench.ui.screens.viewer

/** 重建后的起播位置：负数当 0；已知时长时不超过时长。 */
internal fun sanitizeResumePositionMs(savedMs: Long, durationMs: Long): Long {
    val p = savedMs.coerceAtLeast(0L)
    return if (durationMs > 0L) p.coerceAtMost(durationMs) else p
}
