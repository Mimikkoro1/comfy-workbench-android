package com.mie.kreaworkbench.data.comfy

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * /queue 短缓存。提交、删除、中断会把代次加一，进行中的旧响应不得再写回，
 * 否则 POST /prompt 刚成功，轮询仍读到「队列里还没有这条」的快照。
 */
internal class QueueSnapshotCache(private val ttlMs: Long) {
    private val epoch = AtomicLong(0)

    @Volatile private var at: Long = 0

    @Volatile private var snapEpoch: Long = -1

    @Volatile private var json: JSONObject? = null

    fun invalidate() {
        epoch.incrementAndGet()
        json = null
    }

    fun currentEpoch(): Long = epoch.get()

    fun getIfFresh(now: Long): JSONObject? {
        val snap = json ?: return null
        if (snapEpoch != epoch.get()) return null
        if (now - at >= ttlMs) return null
        return snap
    }

    /** 代次已经变过（中间有人提交/删除）就丢掉这份响应。 */
    fun store(seenEpoch: Long, now: Long, value: JSONObject): Boolean {
        if (epoch.get() != seenEpoch) return false
        json = value
        at = now
        snapEpoch = seenEpoch
        if (epoch.get() != seenEpoch) {
            json = null
            return false
        }
        return true
    }
}
