package com.mie.kreaworkbench.data.ssh

import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str

/**
 * SSH target and error classification (v0.5.2 SSH forwarding).
 *
 * On the desktop this is `ssh -M -N -f` + `ssh -O forward -L`. Android has no ssh
 * binary, so an SSH library (JSch) reproduces the same semantics: validate the target
 * -> one reused connection -> local forward. This file keeps only the platform-free validation and error classification; user-facing text lives in [SshErrorKind.text].
 */

/** An SSH destination: `user@host:22`. The username is mandatory: JSch's default on Android is u0_aXXX, which is meaningless. */
data class SshTarget(
    val host: String,
    val user: String,
    val port: Int = 22,
) {
    val destination: String get() = "$user@$host"

    /** For display in the UI (the port is shown when it is not 22). */
    val display: String get() = if (port == 22) destination else "$destination:$port"
}

/** Why target validation failed. A plain value, assertable in JVM unit tests. */
enum class SshTargetIssue { EMPTY_HOST, EMPTY_USER, CONTROL_CHARS, BAD_HOST, LEADING_DASH, BAD_USER, BAD_PORT }

object SshTargets {
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f]")
    private val HOSTNAME = Regex("[A-Za-z0-9._-]+")
    private val USERNAME = Regex("[A-Za-z0-9._-]+")

    /** Validates host/port/user; null means OK. An unvalidated string never reaches the connection parameters. */
    fun issue(host: String, user: String, port: Int): SshTargetIssue? = when {
        host.isBlank() -> SshTargetIssue.EMPTY_HOST
        user.isBlank() -> SshTargetIssue.EMPTY_USER
        CONTROL_CHARS.containsMatchIn(host) -> SshTargetIssue.CONTROL_CHARS
        CONTROL_CHARS.containsMatchIn(user) -> SshTargetIssue.CONTROL_CHARS
        // The option-injection check must come before the charset check: `-oProxyCommand=...`
        // also fails the hostname charset, and reporting that first would hide the more important fact that it is an option, not a host
        host.startsWith("-") -> SshTargetIssue.LEADING_DASH
        !HOSTNAME.matches(host) -> SshTargetIssue.BAD_HOST
        !USERNAME.matches(user) -> SshTargetIssue.BAD_USER
        port !in 1..65535 -> SshTargetIssue.BAD_PORT
        else -> null
    }

    /** Returns the target when valid, otherwise throws [SshException] (UNKNOWN + localized text). Never returns a half-built target. */
    fun require(host: String, user: String, port: Int): SshTarget {
        val cleanHost = host.trim()
        val cleanUser = user.trim()
        val issue = issue(cleanHost, cleanUser, port)
        if (issue != null) throw SshException(SshErrorKind.UNKNOWN, issue.text())
        return SshTarget(cleanHost, cleanUser, port)
    }
}

/**
 * Error classification. Order is priority: a changed host key also contains "WARNING",
 * so it is matched first; an unreadable key looks like an auth failure, so KEY_INVALID comes before AUTH_FAILED.
 */
enum class SshErrorKind { HOST_KEY_CHANGED, KEY_INVALID, AUTH_FAILED, UNREACHABLE, TIMEOUT, UNKNOWN }

class SshException(
    val kind: SshErrorKind,
    message: String?,
    /** Raw library text; goes to logs / the detail line only, never shown as the user message. */
    val detail: String = "",
) : Exception(message)

object SshErrors {
    private val HOST_KEY_CHANGED = Regex(
        "remote host identification has changed|host key verification failed|" +
            "hostkey has been changed|reject hostkey|changedhostkey|" +
            "offending (?:key|ecdsa|rsa|ed25519)",
        RegexOption.IGNORE_CASE,
    )
    private val KEY_INVALID = Regex(
        "invalid privatekey|invalid key format|failed to parse key|invalid key|" +
            "arrayindexoutofbounds",
        RegexOption.IGNORE_CASE,
    )
    private val AUTH_FAILED = Regex(
        "auth fail|auth cancel|userauth fail|userauth_failure|auth_failures|" +
            "permission denied|authentication failed|too many authentication failures",
        RegexOption.IGNORE_CASE,
    )
    private val UNREACHABLE = Regex(
        "connection refused|no route to host|could not resolve hostname|unknownhost|" +
            "network is unreachable|connection closed by remote host|socket is not established|" +
            "connection reset",
        RegexOption.IGNORE_CASE,
    )
    private val TIMEOUT = Regex("timed out|timeout", RegexOption.IGNORE_CASE)

    fun classify(text: String, timedOut: Boolean = false): SshErrorKind = when {
        HOST_KEY_CHANGED.containsMatchIn(text) -> SshErrorKind.HOST_KEY_CHANGED
        KEY_INVALID.containsMatchIn(text) -> SshErrorKind.KEY_INVALID
        AUTH_FAILED.containsMatchIn(text) -> SshErrorKind.AUTH_FAILED
        UNREACHABLE.containsMatchIn(text) -> SshErrorKind.UNREACHABLE
        timedOut || TIMEOUT.containsMatchIn(text) -> SshErrorKind.TIMEOUT
        else -> SshErrorKind.UNKNOWN
    }
}

/** Validation failure -> one sentence the user can act on (localized). */
fun SshTargetIssue.text(): String {
    val app = KreaApp.instance
    return app.str(
        when (this) {
            SshTargetIssue.EMPTY_HOST -> R.string.ssh_err_no_host
            SshTargetIssue.EMPTY_USER -> R.string.ssh_err_no_user
            SshTargetIssue.CONTROL_CHARS -> R.string.ssh_err_bad_target
            SshTargetIssue.BAD_HOST -> R.string.ssh_err_bad_host
            SshTargetIssue.LEADING_DASH -> R.string.ssh_err_bad_host
            SshTargetIssue.BAD_USER -> R.string.ssh_err_bad_user
            SshTargetIssue.BAD_PORT -> R.string.ssh_err_bad_port
        },
    )
}

/** Error kind -> one sentence; [target] feeds the hostname into the text. */
fun SshErrorKind.text(target: SshTarget?): String {
    val app = KreaApp.instance
    val who = target?.display.orEmpty()
    return when (this) {
        SshErrorKind.HOST_KEY_CHANGED -> app.str(R.string.ssh_err_host_key, who)
        SshErrorKind.KEY_INVALID -> app.str(R.string.ssh_err_key_invalid)
        SshErrorKind.AUTH_FAILED -> app.str(R.string.ssh_err_auth, who)
        SshErrorKind.UNREACHABLE -> app.str(R.string.ssh_err_unreachable, who)
        SshErrorKind.TIMEOUT -> app.str(R.string.ssh_err_timeout, who)
        SshErrorKind.UNKNOWN -> app.str(R.string.ssh_err_failed, who)
    }
}