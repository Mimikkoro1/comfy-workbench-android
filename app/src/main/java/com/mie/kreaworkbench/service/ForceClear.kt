package com.mie.kreaworkbench.service

/**
 * r44：设置页「强制结束全部任务」连服务器队列一起清时的结果分类。
 * UI 据此给一句话反馈（清空成功 / 服务器没反应 / 服务器仍忙）。
 */
enum class ForceClearOutcome { CLEARED, SERVER_STILL_BUSY, SERVER_UNREACHABLE }

/**
 * 清空结果分类：连不上 → 不可达（本地已重置，电脑恢复后可再试）；
 * 队列里还有剩（执行线程死了 interrupt 不动）→ 仍忙，提示去电脑上重启 ComfyUI；
 * 否则算清空成功。
 */
fun classifyClear(reachable: Boolean, runningLeft: Int, pendingLeft: Int): ForceClearOutcome = when {
    !reachable -> ForceClearOutcome.SERVER_UNREACHABLE
    runningLeft > 0 || pendingLeft > 0 -> ForceClearOutcome.SERVER_STILL_BUSY
    else -> ForceClearOutcome.CLEARED
}
