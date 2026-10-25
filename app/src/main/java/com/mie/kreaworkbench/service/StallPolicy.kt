package com.mie.kreaworkbench.service

/** r44：排队中超过这么久，生成按钮的副行换成「卡住了？」提示（经验值，见 FEEDBACK_r44）。 */
const val QUEUED_HINT_AFTER_MS = 90_000L

/**
 * r44 看门狗阈值：排队/运行态超过这么久没有任何进展（步数、节点、名次、状态、张数都没动）
 * 就置 stalledSince。采样每步、节点切换、模型加载都会有事件；视频合成/超大分辨率偶尔长静默，
 * 所以取保守的 5 分钟。触发后只提示，由用户决定取消还是继续等。
 */
const val NO_EVENT_STALL_MS = 5 * 60_000L

/** 排队中是否该显示「卡住了？」：phase 是 queued 且超过 [QUEUED_HINT_AFTER_MS]；进入时间未知不算。 */
fun queuedLooksStuck(phase: String, phaseSinceMs: Long, nowMs: Long): Boolean =
    phase == "queued" && phaseSinceMs > 0 && nowMs - phaseSinceMs >= QUEUED_HINT_AFTER_MS

/** 是否长时间无进展：有上次进展时间且超过 [NO_EVENT_STALL_MS]。 */
fun noProgressStalled(lastProgressAtMs: Long, nowMs: Long): Boolean =
    lastProgressAtMs > 0 && nowMs - lastProgressAtMs >= NO_EVENT_STALL_MS
