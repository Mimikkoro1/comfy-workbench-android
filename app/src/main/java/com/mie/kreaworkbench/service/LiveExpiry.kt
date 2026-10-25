package com.mie.kreaworkbench.service

/** success / partial / cancelled：5 分钟后出清（失败错误条要更久，见下）。 */
const val LIVE_TTL_DONE_MS = 5 * 60_000L

/** failed：留 30 分钟，底栏错误条可以多留一会。 */
const val LIVE_TTL_FAILED_MS = 30 * 60_000L

/**
 * 终态任务在 live 里是否到期；paused、非终态永远不到期；finishedAtMs<=0 视为未知，不到期。
 * paused 必须能靠「继续」按钮和自动续传回来，绝不能清。
 */
fun isLiveExpired(phase: String, finishedAtMs: Long, nowMs: Long): Boolean {
    val ttl = when (phase) {
        "success", "partial", "cancelled" -> LIVE_TTL_DONE_MS
        "failed" -> LIVE_TTL_FAILED_MS
        else -> return false
    }
    if (finishedAtMs <= 0L) return false
    return nowMs - finishedAtMs >= ttl
}

/** 后台服务循环忙时（有任务在跑）与空闲时的检查间隔。 */
const val SERVICE_TICK_BUSY_MS = 5_000L
const val SERVICE_TICK_IDLE_MS = 2_000L

fun serviceTickMs(busy: Boolean): Long = if (busy) SERVICE_TICK_BUSY_MS else SERVICE_TICK_IDLE_MS
