package com.mie.kreaworkbench.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.MainActivity
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.qty
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    private var bornAt = 0L
    @Volatile private var shownId: String? = null
    @Volatile private var foreground = false

    private val engine get() = (application as KreaApp).container.engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bornAt = System.currentTimeMillis()
        ensureNotifyChannels(this)
        startFg(null, str(R.string.phase_preparing))
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krea:gen").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
        scope.launch {
            engine.snapshot.collect { jobs ->
                val active = jobs.filter { it.phase !in TERMINAL }
                val top = active.firstOrNull() ?: return@collect
                shownId = top.clientJobId
                val extra = if (active.size > 1) {
                    val n = active.size - 1
                    qty(R.plurals.more_jobs, n, n)
                } else {
                    ""
                }
                notifyProgress(top, extra)
            }
        }
        scope.launch {
            while (isActive) {
                val busy = engine.hasActive() || engine.hasUnfinished()
                if (busy) {
                    try {
                        wake?.acquire(10 * 60 * 1000L)
                    } catch (_: Exception) {
                    }
                    engine.reconcileActive()
                } else if (System.currentTimeMillis() - bornAt > 2000) {
                    delay(1200)
                    if (!engine.hasActive() && !engine.hasUnfinished()) {
                        stopForegroundSafe()
                        stopSelf()
                        break
                    }
                }
                delay(2000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bornAt = System.currentTimeMillis()
        startFg(null, str(R.string.phase_preparing))
        if (intent?.action == ACTION_CANCEL) {
            engine.cancel(intent.getStringExtra(EXTRA_ID) ?: shownId.orEmpty())
        } else {
            engine.reconcile()
        }
        if (!foreground) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startFg(job: LiveJob?, text: String) {
        try {
            val n = progressNotification(job, text)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_ID, n)
            }
            foreground = true
        } catch (_: Exception) {
            foreground = false
        }
    }

    private fun stopForegroundSafe() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun notifyProgress(job: LiveJob, extra: String) {
        if (!foreground) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(PROGRESS_ID, progressNotification(job, job.line(this) + extra))
    }

    private fun progressNotification(job: LiveJob?, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, GenerationService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ID, shownId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(str(R.string.notify_generating))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, str(R.string.action_cancel), cancel).build())
        if (job != null) {
            when {
                job.phase == "downloading" && job.bytesTotal > 0 -> {
                    val pct = ((job.bytesDone.toDouble() * 100.0) / job.bytesTotal).toInt().coerceIn(0, 100)
                    builder.setProgress(100, pct, false)
                }
                (job.phase == "uploading" || job.phase == "upload_ask") && job.download >= 0f -> {
                    builder.setProgress(100, (job.download * 100).toInt().coerceIn(0, 100), false)
                }
                job.phase == "running" && job.stepMax > 0 -> {
                    builder.setProgress(job.stepMax, job.step.coerceIn(0, job.stepMax), false)
                }
                job.phase == "downloading" -> builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    companion object {
        const val ACTION_CANCEL = "com.mie.kreaworkbench.CANCEL"
        const val EXTRA_ID = "id"
        private const val PROGRESS_ID = 41
    }
}

internal const val CH_PROGRESS = "krea_progress"
internal const val CH_DONE = "krea_done"

fun ensureNotifyChannels(context: Context) {
    val mgr = context.getSystemService(NotificationManager::class.java)
    mgr.createNotificationChannel(NotificationChannel(CH_PROGRESS, context.str(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
    mgr.createNotificationChannel(NotificationChannel(CH_DONE, context.str(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
}
