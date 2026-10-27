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
import androidx.core.content.ContextCompat
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.MainActivity
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str

/**
 * Keeps the process holding the SSH tunnel in the foreground (v0.5.2).
 *
 * Android freezes cached background processes: their sockets stop being serviced and their
 * coroutines stop being scheduled, so a tunnel that worked fine in the foreground dies
 * silently once the user switches away. The desktop's separate `ssh -M -N -f` process is immune; the Android equivalent is a foreground service.
 *
 * The service never touches SSH: it only keeps the process alive (plus a partial wake
 * lock so the tunnel's sockets stay serviced while the screen is off). It starts with the tunnel and stops on disconnect or failure; nothing runs permanently.
 */
class SshTunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureNotifyChannels(this)
        startForegroundSafely(intent?.getStringExtra(EXTRA_DETAIL).orEmpty())
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundSafely(detail: String) {
        try {
            val n = buildNotification(detail)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (_: Exception) {
            // Notifications disabled / background start refused: degrade to "no anchor" instead of breaking the connect flow
        }
    }

    private fun buildNotification(detail: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            4,
            Intent(this, SshTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH_SSH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(str(R.string.ssh_notify_title))
            .setContentText(detail.ifBlank { str(R.string.ssh_notify_text) })
            .setStyle(Notification.BigTextStyle().bigText(detail.ifBlank { str(R.string.ssh_notify_text) }))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, str(R.string.ssh_notify_stop), stop).build())
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krea:ssh").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val WAKELOCK_MS = 10 * 60 * 1000L
        private const val ACTION_STOP = "com.mie.kreaworkbench.ssh.TUNNEL_STOP"
        private const val EXTRA_DETAIL = "detail"

        /** Best effort: API 31+ refuses a background start; degrade to "no anchor" rather than crash. */
        fun start(context: Context, detail: String) {
            val app = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, SshTunnelService::class.java).putExtra(EXTRA_DETAIL, detail),
                )
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, SshTunnelService::class.java)) }
        }
    }
}

internal const val CH_SSH = "krea_ssh"

fun ensureSshChannel(context: Context) {
    val mgr = context.getSystemService(NotificationManager::class.java) ?: return
    mgr.createNotificationChannel(
        NotificationChannel(CH_SSH, context.str(R.string.channel_ssh), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.str(R.string.channel_ssh_desc)
            setShowBadge(false)
        },
    )
}

/** Set the channel up when the app starts: the tunnel may come up outside settings (from a generation job). */
object SshTunnelAnchor {
    fun attach(app: KreaApp) {
        val tunnel = app.container.tunnel
        tunnel.onStateChanged = { st ->
            when (st.phase) {
                com.mie.kreaworkbench.data.ssh.SshTunnel.Phase.UP -> SshTunnelService.start(app, st.detail)
                com.mie.kreaworkbench.data.ssh.SshTunnel.Phase.IDLE,
                com.mie.kreaworkbench.data.ssh.SshTunnel.Phase.FAILED,
                -> SshTunnelService.stop(app)
                // CONNECTING: do not start/stop during a rebuild, the notification would flicker
                else -> Unit
            }
        }
    }
}