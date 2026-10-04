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
    val paths: List<String> = emptyList(),
    val prompt: String = "",
    val createdAt: Long = 0L,
)

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
            context.str(R.string.phase_video_retry)
        } else {
            context.str(R.string.phase_downloading, downloadDetail())
        }
    "success", "partial" -> context.str(R.string.phase_done)
    "failed" -> context.str(R.string.phase_failed, error.ifBlank { context.str(R.string.err_unknown_reason) })
    "cancelled" -> context.str(R.string.phase_cancelled)
    "paused" -> context.str(R.string.paused_net)
    else -> context.str(R.string.phase_submitting)
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
