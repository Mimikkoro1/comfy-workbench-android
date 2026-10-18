package com.mie.kreaworkbench.data.comfy

/** 某条 prompt 连续没在 /queue 和 /history 里出现的计数。 */
data class PromptMiss(val firstAt: Long, val count: Int)

/**
 * 刚 POST /prompt 之后，ComfyUI 的 /queue 可能还没带上这条，共享缓存里也可能是旧快照。
 * 一次没看见就判「任务丢失」会把还在跑的任务整单踢出轮询。
 * 要同时满足「错过够多次」和「过了宽限时间」才算真丢。
 */
object PromptLostPolicy {
    const val GRACE_MS = 8_000L
    const val MIN_MISSES = 4

    fun isLost(missCount: Int, firstAt: Long, now: Long): Boolean {
        if (missCount < MIN_MISSES) return false
        if (now - firstAt < GRACE_MS) return false
        return true
    }
}
