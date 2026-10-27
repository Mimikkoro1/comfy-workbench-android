package com.mie.kreaworkbench.data.ssh

import android.content.Context
import com.jcraft.jsch.Session
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.settings.SshAuthMode
import com.mie.kreaworkbench.data.settings.SshConfig
import com.mie.kreaworkbench.ui.locale.str
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide owner of the SSH tunnel (v0.5.2).
 *
 * The desktop gets its tunnel in a **separate process** via `ssh -M -N -f`; Android has
 * no such process, so the tunnel lives inside the app process and must never hang off a
 * ViewModel -- Activity recreation (opening settings, rotating) clears ViewModels and would
 * kill the tunnel with them. This scope lives as long as the app process: only an explicit [stop] (user disconnect) or process death releases it. Background survival is covered by the foreground service (see SshTunnelService).
 *
 * One tunnel = one reused SSH connection + one or two local forwards:
 *   phone 127.0.0.1:<local port> --SSH--> <remote host>:<ComfyUI port>
 * Once up, the app treats `http://127.0.0.1:<local port>` as the ComfyUI address; the rest of the code never learns a tunnel exists.
 *
 * The machine-facing part is [SshForwarder] (no Android dependency, integration-tested
 * against a real sshd); this file owns state, the concurrency gate, the watchdog and which 127.0.0.1 address is usable right now.
 */
class SshTunnel(private val ctx: Context) {

    enum class Phase { IDLE, CONNECTING, UP, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** `user@host:22`, for display. */
        val target: String = "",
        /** ComfyUI local address while the tunnel is up (http://127.0.0.1:xxxx). */
        val comfyUrl: String = "",
        /** Local address of the forwarded gacha server; empty when not forwarded. */
        val gachaUrl: String = "",
        val detail: String = "",
        val errorKind: SshErrorKind? = null,
        /** Error text, already localized; shown as-is. */
        val error: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The tunnel's own lifetime: process-wide, unaffected by any screen being recreated. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At most one connect/rebuild at a time, so concurrent requests cannot each open a connection. */
    private val gate = Mutex()

    @Volatile private var session: Session? = null
    private var activeFingerprint = ""
    @Volatile private var comfyUrl = ""
    @Volatile private var gachaUrl = ""
    private var comfyLocalPort = 0
    private var gachaLocalPort = 0
    private var lastAttemptAt = 0L
    private var watchdog: Job? = null

    /** Publishes tunnel up/down to the outside (the foreground service anchor). */
    var onStateChanged: ((State) -> Unit)? = null

    private val knownHosts by lazy { TofuHostKeyRepository(File(ctx.filesDir, "ssh/known_hosts")) }

    fun isUp(): Boolean = session?.isConnected == true && comfyUrl.isNotBlank()

    /**
     * Entry point: return the local address when the tunnel works, open one when it does not.
     * Every request path (HTTP / WS / upload / download) resolves its address here, so after a
     * drop the next request rebuilds it; the user never has to press Connect in settings again.
     */
    suspend fun ensureUp(cfg: SshConfig): String {
        if (!cfg.configured) return ""
        if (isUp() && activeFingerprint == cfg.fingerprint()) return comfyUrl
        return gate.withLock {
            if (isUp() && activeFingerprint == cfg.fingerprint()) return@withLock comfyUrl
            val now = System.currentTimeMillis()
            // Do not retry immediately after a failure: polling runs every 1.5s and without this gate it would become a connection storm
            if (now - lastAttemptAt < RETRY_MIN_MS && _state.value.phase == Phase.FAILED) return@withLock ""
            lastAttemptAt = now
            withContext(Dispatchers.IO) { build(cfg) }
            if (_state.value.phase == Phase.UP) comfyUrl else ""
        }
    }

    /** The user pressed Connect: ignore the backoff and try once right now. */
    suspend fun connect(cfg: SshConfig): State {
        if (!cfg.configured) return _state.value
        gate.withLock {
            lastAttemptAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) { build(cfg) }
        }
        return _state.value
    }

    /** Disconnect (explicit user action). Idempotent. */
    fun stop() {
        watchdog?.cancel()
        watchdog = null
        runCatching { session?.disconnect() }
        session = null
        activeFingerprint = ""
        comfyUrl = ""
        gachaUrl = ""
        publish(State(phase = Phase.IDLE))
    }

    /** Forget a remembered host key (recovery path after the key changed and the server was verified). */
    fun forgetHostKey(host: String) {
        knownHosts.forget(host)
    }

    /** Remembered hosts, shown and resettable in settings. */
    fun knownHosts(): Set<String> = knownHosts.hosts()

    // ---- internals ----

    private fun build(cfg: SshConfig) {
        val target = try {
            SshTargets.require(cfg.host, cfg.user, cfg.port)
        } catch (e: SshException) {
            fail(e.kind, e.message.orEmpty(), e.detail, null)
            return
        }
        // An empty key needs no network round trip: reporting "no key yet" locally is clearer than an SSH-side auth failure
        if (cfg.authMode == SshAuthMode.KEY && cfg.privateKey.isBlank()) {
            fail(SshErrorKind.KEY_INVALID, ctx.str(R.string.ssh_err_key_missing), "empty private key", target)
            return
        }
        publish(
            State(
                phase = Phase.CONNECTING,
                target = target.display,
                detail = ctx.str(R.string.ssh_connecting),
            ),
        )
        val forwarder = SshForwarder(
            target = target,
            auth = SshAuth(
                mode = cfg.authMode,
                privateKey = cfg.privateKey.trim(),
                keyPassphrase = cfg.keyPassphrase,
                password = cfg.password,
            ),
            knownHosts = knownHosts,
        )
        var fresh: Session? = null
        try {
            fresh = forwarder.open()
            val bound = forwarder.forward(fresh, ForwardSpec(cfg.remoteHost, cfg.remotePort, comfyLocalPort))
            val url = "http://${SshForwarder.LOOPBACK}:$bound"

            // "Tunnel up" is not "ComfyUI usable": a successful handshake with nothing listening on
            // 8188 is common, so we really ask through the forward; otherwise the user sees "connected" and gets nothing
            val version = probeComfySystemStats(url) ?: run {
                forwarder.close(fresh)
                fail(
                    SshErrorKind.UNREACHABLE,
                    ctx.str(R.string.ssh_err_no_comfy, "${cfg.remoteHost}:${cfg.remotePort}"),
                    "forward up but ComfyUI silent",
                    target,
                )
                return
            }

            var gacha: String = ""
            if (cfg.gachaForward) {
                val gPort = forwarder.forward(fresh, ForwardSpec(cfg.gachaRemoteHost, cfg.gachaRemotePort, gachaLocalPort))
                gacha = "http://${SshForwarder.LOOPBACK}:$gPort"
            }

            session = fresh
            activeFingerprint = cfg.fingerprint()
            comfyUrl = url
            gachaUrl = gacha
            comfyLocalPort = bound
            gachaLocalPort = gacha.takeIf { it.isNotBlank() }?.substringAfterLast(':')?.toIntOrNull() ?: 0
            publish(
                State(
                    phase = Phase.UP,
                    target = target.display,
                    comfyUrl = url,
                    gachaUrl = gacha,
                    detail = ctx.str(R.string.ssh_up_detail, target.display, bound, version),
                ),
            )
            watch(target)
        } catch (e: CancellationException) {
            forwarder.close(fresh)
            throw e
        } catch (e: SshException) {
            forwarder.close(fresh)
            fail(e.kind, e.kind.text(target), e.detail, target)
        } catch (e: Throwable) {
            forwarder.close(fresh)
            val text = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            val kind = SshErrors.classify(text)
            fail(kind, kind.text(target), text, target)
        }
    }

    private fun fail(kind: SshErrorKind, message: String, detail: String, target: SshTarget?) {
        session = null
        comfyUrl = ""
        gachaUrl = ""
        activeFingerprint = ""
        publish(
            State(
                phase = Phase.FAILED,
                target = target?.display.orEmpty(),
                errorKind = kind,
                error = message,
                detail = detail,
            ),
        )
    }

    private fun publish(next: State) {
        _state.value = next
        runCatching { onStateChanged?.invoke(next) }
    }

    /**
     * Watchdog: mark the state FAILED when the connection drops (network switch, server reboot,
     * Doze), so the next request rebuilds. JSch's ServerAlive is not enough -- it only pings an
     * idle session, while a zombie tunnel (connection alive, forward dead) has to be caught here.
     */
    private fun watch(target: SshTarget) {
        watchdog?.cancel()
        watchdog = scope.launch {
            var misses = 0
            while (true) {
                delay(WATCHDOG_MS)
                val s = session ?: return@launch
                // A dropped SSH connection is fatal at once; an HTTP probe has to fail several times --
                // ComfyUI's HTTP side can stall briefly while sampling, and tearing the tunnel down after
                // a single failed probe would kill it mid-generation
                val alive = s.isConnected && probeComfySystemStats(comfyUrl, WATCHDOG_PROBE_MS) != null
                misses = if (alive) 0 else misses + 1
                if (misses >= WATCHDOG_MISSES) {
                    runCatching { s.disconnect() }
                    fail(
                        SshErrorKind.UNREACHABLE,
                        SshErrorKind.UNREACHABLE.text(target),
                        "watchdog: forward did not answer $misses times in a row, tunnel dropped",
                        target,
                    )
                    return@launch
                }
            }
        }
    }

    private companion object {
        const val WATCHDOG_MS = 25_000L
        const val WATCHDOG_PROBE_MS = 5_000
        const val WATCHDOG_MISSES = 3
        const val RETRY_MIN_MS = 5_000L
    }
}