package com.mie.kreaworkbench.service

import android.content.Context
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.isVideoStage
import com.mie.kreaworkbench.ui.locale.knownStage
import com.mie.kreaworkbench.ui.locale.parseSamplingStage
import com.mie.kreaworkbench.ui.locale.qty
import com.mie.kreaworkbench.ui.locale.str
import java.util.Locale

data class LiveJob(
    val clientJobId: String,
    val mode: String,
    val phase: String,
    val queuePosition: Int? = null,
    val step: Int = 0,
    val stepMax: Int = 0,
    val imageIndex: Int = 0,
    val done: Int = 0,
    val total: Int = 1,
    val download: Float = -1f,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val bytesPerSec: Double = 0.0,
    val downloadFailed: Boolean = false,
    val stage: String = "",
    val error: String = "",
    /** r42：ComfyUI execution_error 详细报错（多行文本）。只在当次进程内存里有效，重启后为 null。 */
    val errorDetail: String? = null,
    /** r44：提交瞬间 400 的 ComfyUI 原始报错（error.message + details）。只内存，重启后为 null。 */
    val rawError: String? = null,
    /** r44：进入当前 phase 的时间戳（内存；put 时 phase 没变沿用旧值，变了重置）。0 = 未知。 */
    val phaseSince: Long = 0L,
    /** r44：引擎看门狗置位的「长时间无进展」时间戳（内存）；0 = 没卡。只提示，不自动取消。 */
    val stalledSince: Long = 0L,
    val paths: List<String> = emptyList(),
    val prompt: String = "",
    val createdAt: Long = 0L,
)

/**
 * 进度条数据（r21）：JobStatusCard 大卡与底栏细进度条（JobProgressSlim）共用同一条
 * phase → 比例 的换算，避免两处复制 if/else 分支后走样。
 */
data class JobProgress(
    /** 比例 0..1；-1 = 该阶段给不出确定比例（排队/提交中等）。 */
    val bar: Float,
    /** true = 在动但没确定比例（如下载中总字节未知），画不定动画。 */
    val indeterminate: Boolean,
)

/** 单一进度换算：与 round20 之前 JobStatusCard 内联逻辑一致，仅是提取复用。 */
fun LiveJob.jobProgress(): JobProgress {
    val indeterminate = phase == "downloading" && bytesTotal <= 0L && bytesDone > 0L
    val bar = when (phase) {
        "running" -> if (stepMax > 0) step.toFloat() / stepMax else -1f
        "downloading" -> if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else -1f
        "uploading", "upload_ask" -> if (download >= 0f) download else -1f
        else -> -1f
    }
    return JobProgress(bar = bar, indeterminate = indeterminate)
}

data class DoneEvent(
    val title: String,
    val text: String,
    val thumbPath: String?,
)

fun LiveJob.line(context: Context): String = when (phase) {
    "uploading" -> context.str(R.string.phase_uploading, (download.coerceAtLeast(0f) * 100).toInt())
    "upload_ask" -> context.str(R.string.phase_upload_ask)
    "upload_fail_ask" -> context.str(R.string.phase_upload_fail_ask)
    "submitting" -> if (error.isBlank()) {
        context.str(R.string.phase_submitting)
    } else {
        context.str(R.string.phase_submit_retry, error)
    }
    "queued" -> {
        val ahead = queuePosition ?: 0
        if (ahead > 0) context.qty(R.plurals.queued_ahead, ahead, ahead) else context.str(R.string.phase_queued)
    }
    "running" -> {
        // 生成中进度按优先级拼接，互不覆盖（round13 第 1 项）：
        // ① 张数（第 x/y 张，仅批量 > 1）→ ② 步数（x/y 步）→ ③ 引擎阶段（采样器进度只在
        // ≥2 个采样器时显示、永远放最后；单采样器的「采样 1/1」没有信息量，此前还会把
        // 张数/步数整个盖掉）。视频合成阶段始终显示（视频任务一段只出一张，张数段自然为空）。
        val segs = ArrayList<String>(3)
        if (total > 1) {
            val idx = if (imageIndex > 0) imageIndex else (done + 1).coerceAtLeast(1)
            segs.add(context.str(R.string.phase_image_of, idx, total))
        }
        if (stepMax > 0) segs.add(context.str(R.string.phase_step_of, step, stepMax))
        val st = stage.trim()
        when {
            st.isBlank() -> {}
            isVideoStage(st) -> segs.add(context.knownStage(st))
            else -> parseSamplingStage(st)?.let { (k, n) ->
                if (n > 1) segs.add(context.knownStage(st))
            }
        }
        when {
            segs.isEmpty() -> context.str(R.string.phase_running_plain)
            // " · " 分隔与生成页参数摘要行（customSummary）一致
            else -> context.str(R.string.phase_running_plain) + " · " + segs.joinToString(" · ")
        }
    }
    "downloading" ->
        if (downloadFailed) {
            context.str(R.string.phase_download_retry)
        } else {
            context.str(R.string.phase_downloading, downloadDetail())
        }
    "success", "partial" -> context.str(R.string.phase_done)
    "failed" -> context.str(R.string.phase_failed, error.ifBlank { context.str(R.string.err_unknown_reason) })
    "cancelled" -> context.str(R.string.phase_cancelled)
    "paused" -> context.str(R.string.paused_net)
    else -> context.str(R.string.phase_submitting)
}

/** 生成按钮主行。下载中写成「下载中 42% · 网速」，总大小未知时不写百分比。 */
fun LiveJob.downloadCapsule(context: Context, queuedExtra: Int): String? {
    if (phase != "downloading" || downloadFailed) return null
    val speed = speedLabel(bytesPerSec)
    val pct = when {
        bytesTotal > 0L -> ((bytesDone.toDouble() * 100.0) / bytesTotal.toDouble()).toInt().coerceIn(0, 100)
        download >= 0f -> (download * 100).toInt().coerceIn(0, 100)
        else -> null
    }
    val base = if (pct != null) {
        context.str(R.string.gen_capsule_download, pct, speed)
    } else {
        context.str(R.string.gen_capsule_download_speed, speed)
    }
    return if (queuedExtra > 0) base + " · " + context.str(R.string.queue_badge, queuedExtra) else base
}

private fun LiveJob.downloadDetail(): String {
    val pct = when {
        bytesTotal > 0 -> "${((bytesDone.toDouble() * 100.0) / bytesTotal).toInt().coerceIn(0, 100)}% · "
        download >= 0f -> "${(download * 100).toInt().coerceIn(0, 100)}% · "
        else -> ""
    }
    val size = if (bytesTotal > 0) {
        "${mbLabel(bytesDone)} / ${mbLabel(bytesTotal)} MB"
    } else {
        "${mbLabel(bytesDone)} MB"
    }
    return "$pct$size · ${speedLabel(bytesPerSec)}"
}

private fun mbLabel(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes.coerceAtLeast(0L) / 1024.0 / 1024.0)

private fun speedLabel(bps: Double): String {
    val kb = bps.coerceAtLeast(0.0) / 1024.0
    return if (kb >= 1024.0) {
        String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f KB/s", kb)
    }
}

val TERMINAL = setOf("success", "partial", "failed", "cancelled", "paused")
