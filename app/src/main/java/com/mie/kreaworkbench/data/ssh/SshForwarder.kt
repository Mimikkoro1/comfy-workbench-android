package com.mie.kreaworkbench.data.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.mie.kreaworkbench.data.settings.SshAuthMode
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import org.json.JSONObject

/**
 * The machine-facing half of SSH forwarding (v0.5.2): open one connection, bind local
 *
 * forwards on the phone, and probe ComfyUI through them. This layer **deliberately has no
 * Android dependency** (no Context, notifications or foreground service), so it runs on a
 * plain JVM against a real sshd: see SshForwarderIntegrationTest. The state machine, the watchdog and the foreground anchor live in SshTunnel.
 *
 * Desktop equivalents:
 *  - `ssh -M -N -f`                -> one long-lived connection (Session) shared by every forward
 *  - `ssh -O forward -L 127.0.0.1...` -> setPortForwardingL, bound to loopback only, never 0.0.0.0
 *  - `ExitOnForwardFailure=yes`     -> a failed bind throws (never silently ends up "connected but no forward")
 *  - `BatchMode=yes`                -> no interactive prompt: logins that need a passphrase or 2FA must fail
 *  - `StrictHostKeyChecking=accept-new` → TofuHostKeyRepository
 *  - `ServerAliveInterval/CountMax` → setServerAliveInterval/CountMax
 */

/** Authentication material. */
data class SshAuth(
    val mode: SshAuthMode,
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val password: String = "",
)

/** One local forward: phone `127.0.0.1:<localPort>` -> remote `<host>:<port>`. */
data class ForwardSpec(
    val remoteHost: String,
    val remotePort: Int,
    /** 0 = pick automatically; a non-zero, still-free port is reused (stable local address, cached URLs keep working). */
    val preferredLocalPort: Int = 0,
)

class SshForwarder(
    private val target: SshTarget,
    private val auth: SshAuth,
    private val knownHosts: TofuHostKeyRepository,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
) {

    /**
     * Opens the reused connection. Every failure throws [SshException] with kind set from
     * the real cause; the caller (SshTunnel) turns the kind into a user-facing sentence.
     */
    fun open(): Session {
        val jsch = JSch()
        if (auth.mode == SshAuthMode.KEY) {
            try {
                jsch.addIdentity(
                    IDENTITY,
                    auth.privateKey.toByteArray(Charsets.UTF_8),
                    null,
                    auth.keyPassphrase.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() },
                )
            } catch (e: Exception) {
                // A malformed key (a pasted .pub, a truncated file) can surface as a bare runtime
                // exception in JSch: a truncated OpenSSH key yields ArrayIndexOutOfBoundsException
                throw SshException(SshErrorKind.KEY_INVALID, "private key rejected", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        jsch.hostKeyRepository = knownHosts
        val session = try {
            jsch.getSession(target.user, target.host, target.port)
        } catch (e: JSchException) {
            throw classify(e, "getSession")
        }
        session.setConfig("StrictHostKeyChecking", "ask")
        // Authenticate only with the method the user picked: key sign-in must not silently fall
        // back to a password, and password sign-in must not try keys, otherwise the reported cause does not match what the user entered
        session.setConfig(
            "PreferredAuthentications",
            if (auth.mode == SshAuthMode.KEY) "publickey" else "password,keyboard-interactive",
        )
        session.userInfo = TunnelUserInfo(auth.password, auth.keyPassphrase)
        session.timeout = connectTimeoutMs
        session.serverAliveInterval = SERVER_ALIVE_MS
        session.serverAliveCountMax = SERVER_ALIVE_COUNT
        if (auth.mode == SshAuthMode.PASSWORD) session.setPassword(auth.password)
        try {
            session.connect(connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw classify(e, "connect")
        }
        return session
    }

    /** Opens a local forward and returns the port actually bound. A failed bind throws (= ExitOnForwardFailure). */
    fun forward(session: Session, spec: ForwardSpec): Int = try {
        session.setPortForwardingL(LOOPBACK, pickLocalPort(spec.preferredLocalPort, spec.remotePort), spec.remoteHost, spec.remotePort)
    } catch (e: Exception) {
        throw classify(e, "forward ${spec.remoteHost}:${spec.remotePort}")
    }

    /** Picks a genuinely bindable free loopback port. */
    fun pickLocalPort(preferred: Int = 0, avoid: Int = 0): Int {
        if (preferred in PORT_RANGE && preferred != avoid && isLoopbackFree(preferred)) return preferred
        var candidate = 0
        repeat(8) {
            candidate = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
            if (candidate != avoid && candidate in PORT_RANGE) return candidate
        }
        return candidate
    }

    fun isLoopbackFree(port: Int): Boolean = try {
        ServerSocket().use { s ->
            s.reuseAddress = false
            s.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 1)
            true
        }
    } catch (_: Exception) {
        false
    }

    fun close(session: Session?) {
        runCatching { session?.disconnect() }
    }

    /** Library exception -> kind; the raw text stays in detail (never in the user message). */
    private fun classify(e: Throwable, where: String): SshException {
        val text = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        val kind = SshErrors.classify(text, timedOut = text.contains("timeout", ignoreCase = true))
        return SshException(kind, "ssh $where failed", text)
    }

    /** Never prompt: logins needing a passphrase or 2FA must fail, i.e. BatchMode=yes. */
    private class TunnelUserInfo(private val password: String, private val passphrase: String) : UserInfo {
        override fun getPassphrase(): String? = passphrase.ifBlank { null }
        override fun getPassword(): String? = password.ifBlank { null }
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_PROBE_TIMEOUT_MS = 6_000
        private const val IDENTITY = "krea-ssh"
        private const val SERVER_ALIVE_MS = 15_000
        private const val SERVER_ALIVE_COUNT = 3
        private val PORT_RANGE = 1024..65535
    }
}

/**
 * Asks ComfyUI once through the tunnel: the version string on success, null otherwise.
 *
 * "SSH handshake OK" is not "ComfyUI usable" -- the remote not listening on 8188 is the
 * most common failure, so we really ask. Both the initial connect and the watchdog use this.
 */
fun probeComfySystemStats(
    baseUrl: String,
    timeoutMs: Int = SshForwarder.DEFAULT_PROBE_TIMEOUT_MS,
): String? {
    if (baseUrl.isBlank()) return null
    return runCatching {
        val conn = URI("$baseUrl/system_stats").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val version = JSONObject(body).optJSONObject("system")?.optString("comfyui_version").orEmpty()
            if (version.isBlank()) "ComfyUI" else "ComfyUI $version"
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}