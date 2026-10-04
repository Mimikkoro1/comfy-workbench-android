package com.mie.kreaworkbench.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mie.kreaworkbench.KreaApp
import java.util.concurrent.TimeUnit

/**
 * 服务被系统杀掉后的兜底（v0.5 需求 §3）：有网时周期性把「已提交过、还没拿全结果」的任务拉起来续跑。
 * 只续 jobId 非空的（提交已完成，凭 prompt_id 走 /history 恢复下载），绝不重新 POST /prompt。
 * Worker 在进程里直接驱动引擎，不起前台服务（后台起 FGS 会被 Android 12+ 拒）。
 */
class ResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? KreaApp ?: return Result.success()
        return try {
            app.container.db.needsWork()
                .filter { it.jobId.isNotBlank() && it.state != "paused" }
                .forEach { app.container.engine.kick(it.clientJobId) }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

fun scheduleResumeWork(context: Context) {
    try {
        val req = PeriodicWorkRequestBuilder<ResumeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("resume_downloads", ExistingPeriodicWorkPolicy.KEEP, req)
    } catch (_: Exception) {
    }
}
