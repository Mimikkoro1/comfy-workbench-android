package com.mie.kreaworkbench.service

import android.content.Context
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.knownStage
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
        val idx = if (imageIndex > 0) imageIndex else (done + 1).coerceAtLeast(1)
        // 有阶段文案（采样 1/2 / 合成视频）就不显示「第 x/y 张」——视频任务一段只出一个
        val head = if (stage.isBlank()) {
            context.str(R.string.phase_image_of, idx, total)
        } else {
            context.knownStage(stage)
        }
        if (stepMax > 0) {
            context.str(R.string.phase_running_steps, head, step, stepMax)
        } else {
            context.str(R.string.phase_running, head)
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
