package com.mie.kreaworkbench.data.settings

import org.json.JSONObject
import java.security.MessageDigest

/** Authentication method: private key (optional passphrase) or password. */
enum class SshAuthMode { KEY, PASSWORD }

/**
 * SSH forwarding configuration (v0.5.2).
 *
 * The second route next to "type in an http URL": the app opens an SSH connection
 * to a machine, forwards that machine's ComfyUI port to the phone's own
 * 127.0.0.1:<ephemeral port>, and talks to that local address from then on. ComfyUI
 * at home can then keep listening on 127.0.0.1: no --listen 0.0.0.0, no public port.
 *
 * Credentials ([privateKey] / [keyPassphrase] / [password]) are excluded from [toJson]; they are encrypted separately.
 */
data class SshConfig(
    /** Route connections through the SSH tunnel. false = the plain direct URL. */
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_SSH_PORT,
    val user: String = "",
    val authMode: SshAuthMode = SshAuthMode.KEY,
    /** Private key body (PEM / OpenSSH text). Stored encrypted. */
    val privateKey: String = "",
    /** Private key passphrase, may be empty. Stored encrypted. */
    val keyPassphrase: String = "",
    /** Used for password sign-in. Stored encrypted. */
    val password: String = "",
    /** ComfyUI address as seen from the SSH host; the default assumes the same machine. */
    val remoteHost: String = DEFAULT_REMOTE_HOST,
    val remotePort: Int = DEFAULT_COMFY_PORT,
    /** Also forward the gacha server (8787 by default) over the same connection. */
    val gachaForward: Boolean = false,
    val gachaRemoteHost: String = DEFAULT_REMOTE_HOST,
    val gachaRemotePort: Int = DEFAULT_GACHA_PORT,
) {
    /** Whether a connection is even possible: tunnel enabled and a host is set. */
    val configured: Boolean get() = enabled && host.isNotBlank()

    /**
     * Fingerprint of everything that affects the connection. If any part of it changes,
     * the live tunnel no longer matches the configuration and must be rebuilt. Credentials
     */
    fun fingerprint(): String {
        val material = listOf(
            host.trim(), port.toString(), user.trim(), authMode.name,
            remoteHost.trim(), remotePort.toString(),
            gachaForward.toString(), gachaRemoteHost.trim(), gachaRemotePort.toString(),
            privateKey, keyPassphrase, password,
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_COMFY_PORT = 8188
        const val DEFAULT_GACHA_PORT = 8787
        const val DEFAULT_REMOTE_HOST = "127.0.0.1"

        /** The persisted JSON **holds no credentials**: those three fields go into separate encrypted keys (see SettingsStore). */
        fun toJson(cfg: SshConfig): String = JSONObject()
            .put("enabled", cfg.enabled)
            .put("host", cfg.host)
            .put("port", cfg.port)
            .put("user", cfg.user)
            .put("authMode", cfg.authMode.name)
            .put("remoteHost", cfg.remoteHost)
            .put("remotePort", cfg.remotePort)
            .put("gachaForward", cfg.gachaForward)
            .put("gachaRemoteHost", cfg.gachaRemoteHost)
            .put("gachaRemotePort", cfg.gachaRemotePort)
            .toString()

        /**
         * Parses the persisted JSON with the decrypted credentials. Missing fields or broken
         * JSON fall back to defaults, so one bad record cannot crash the settings screen (same tolerance as server_history).
         */
        fun parse(raw: String, privateKey: String, keyPassphrase: String, password: String): SshConfig {
            if (raw.isBlank()) return SshConfig(privateKey = privateKey, keyPassphrase = keyPassphrase, password = password)
            val o = runCatching { JSONObject(raw) }.getOrNull()
                ?: return SshConfig(privateKey = privateKey, keyPassphrase = keyPassphrase, password = password)
            val authMode = runCatching { SshAuthMode.valueOf(o.optString("authMode")) }.getOrDefault(SshAuthMode.KEY)
            return SshConfig(
                enabled = o.optBoolean("enabled", false),
                host = o.optString("host"),
                port = o.optInt("port", DEFAULT_SSH_PORT).coerceIn(1, 65535),
                user = o.optString("user"),
                authMode = authMode,
                privateKey = privateKey,
                keyPassphrase = keyPassphrase,
                password = password,
                remoteHost = o.optString("remoteHost").ifBlank { DEFAULT_REMOTE_HOST },
                remotePort = o.optInt("remotePort", DEFAULT_COMFY_PORT).coerceIn(1, 65535),
                gachaForward = o.optBoolean("gachaForward", false),
                gachaRemoteHost = o.optString("gachaRemoteHost").ifBlank { DEFAULT_REMOTE_HOST },
                gachaRemotePort = o.optInt("gachaRemotePort", DEFAULT_GACHA_PORT).coerceIn(1, 65535),
            )
        }
    }
}