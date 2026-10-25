package com.mie.kreaworkbench.service

/**
 * r44：冷启动与服务器对账时，单个「已经提交过」的任务的裁决。
 * SKIP = 没提交过（没有 comfy_prompts），走原有提交幂等逻辑，对账绝不碰它；
 * KEEP = 留在本地（不可达 / 还在队列 / 有历史 / 只错过一次）；
 * DROP_AS_LOST = 服务器可达、连续两次快照都不在队列也不在历史 → 判「任务丢失」。
 */
enum class ReconcileVerdict { KEEP, DROP_AS_LOST, SKIP }

fun reconcileVerdict(
    hasSubmitted: Boolean,
    reachable: Boolean,
    inQueue: Boolean,
    inHistory: Boolean,
    missesInARow: Int,
): ReconcileVerdict = when {
    !hasSubmitted -> ReconcileVerdict.SKIP
    !reachable -> ReconcileVerdict.KEEP
    inQueue || inHistory -> ReconcileVerdict.KEEP
    missesInARow >= 2 -> ReconcileVerdict.DROP_AS_LOST
    else -> ReconcileVerdict.KEEP
}
