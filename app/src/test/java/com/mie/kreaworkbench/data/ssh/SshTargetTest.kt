package com.mie.kreaworkbench.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SSH target validation (v0.5.2).
 *
 * These strings end up as JSch connection parameters, so validate before concatenating:
 * control characters, `-`-prefixed fake options and out-of-range ports must never get through.
 */
class SshTargetTest {

    @Test
    fun `plain host with user and default port passes`() {
        assertNull(SshTargets.issue("192.168.1.10", "lute", 22))
        assertNull(SshTargets.issue("comfy.example.com", "ubuntu", 2222))
        assertNull(SshTargets.issue("host_name-1.local", "root", 65535))
    }

    @Test
    fun `empty host and empty user are rejected separately`() {
        assertEquals(SshTargetIssue.EMPTY_HOST, SshTargets.issue("  ", "lute", 22))
        assertEquals(SshTargetIssue.EMPTY_USER, SshTargets.issue("host", "", 22))
        assertEquals(SshTargetIssue.EMPTY_USER, SshTargets.issue("host", "  ", 22))
    }

    @Test
    fun `control characters never make it into the connection parameters`() {
        assertEquals(SshTargetIssue.CONTROL_CHARS, SshTargets.issue("ho\u0000st", "lute", 22))
        assertEquals(SshTargetIssue.CONTROL_CHARS, SshTargets.issue("host", "lu\u001fste", 22))
    }

    @Test
    fun `host that looks like a flag is refused`() {
        assertEquals(SshTargetIssue.LEADING_DASH, SshTargets.issue("-oProxyCommand=evil", "lute", 22))
    }

    @Test
    fun `scheme, slash and space in the host are refused`() {
        assertEquals(SshTargetIssue.BAD_HOST, SshTargets.issue("ssh://host", "lute", 22))
        assertEquals(SshTargetIssue.BAD_HOST, SshTargets.issue("host:22", "lute", 22))
        assertEquals(SshTargetIssue.BAD_HOST, SshTargets.issue("user@host", "lute", 22))
    }

    @Test
    fun `port outside 1 to 65535 is refused`() {
        assertEquals(SshTargetIssue.BAD_PORT, SshTargets.issue("host", "lute", 0))
        assertEquals(SshTargetIssue.BAD_PORT, SshTargets.issue("host", "lute", 65536))
        assertEquals(SshTargetIssue.BAD_PORT, SshTargets.issue("host", "lute", -1))
    }

    @Test
    fun `require trims the values it returns`() {
        val target = SshTargets.require(" 192.168.1.10 ", " lute ", 22)
        assertEquals("192.168.1.10", target.host)
        assertEquals("lute", target.user)
        assertEquals("lute@192.168.1.10", target.destination)
        assertEquals("lute@192.168.1.10:2222", SshTargets.require("192.168.1.10", "lute", 2222).display)
    }
}

/**
 * Error classification (v0.5.2).
 *
 * A wrong classification shows the user a useless "connection failed": JSch's dialect (Auth
 * fail / socket is not established) and OpenSSH's wording both have to be recognised, and the
 * order matters - a changed host key message also contains WARNING, and an unreadable key is reported as an auth failure, so both must come before the generic failure.
 */
class SshErrorsTest {

    @Test
    fun `host key change outranks everything else`() {
        assertEquals(
            SshErrorKind.HOST_KEY_CHANGED,
            SshErrors.classify("com.jcraft.jsch.JSchException: HostKey has been changed: ecdsa-sha2-nistp256"),
        )
        assertEquals(
            SshErrorKind.HOST_KEY_CHANGED,
            SshErrors.classify("WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!"),
        )
        assertEquals(SshErrorKind.HOST_KEY_CHANGED, SshErrors.classify("reject HostKey: host"))
    }

    @Test
    fun `a private key that cannot be parsed is not reported as a wrong password`() {
        assertEquals(
            SshErrorKind.KEY_INVALID,
            SshErrors.classify("com.jcraft.jsch.JSchException: invalid privatekey: [B@1f2"),
        )
        assertEquals(
            SshErrorKind.KEY_INVALID,
            SshErrors.classify("java.lang.ArrayIndexOutOfBoundsException: Index 64 out of bounds"),
        )
    }

    @Test
    fun `jsch and openssh auth failures classify together`() {
        assertEquals(SshErrorKind.AUTH_FAILED, SshErrors.classify("jsch: Auth fail"))
        assertEquals(SshErrorKind.AUTH_FAILED, SshErrors.classify("Auth cancel"))
        assertEquals(SshErrorKind.AUTH_FAILED, SshErrors.classify("USERAUTH fail"))
        assertEquals(SshErrorKind.AUTH_FAILED, SshErrors.classify("Permission denied (publickey)."))
        assertEquals(SshErrorKind.AUTH_FAILED, SshErrors.classify("Too many authentication failures"))
    }

    @Test
    fun `unreachable hosts are separated from timeouts`() {
        assertEquals(SshErrorKind.UNREACHABLE, SshErrors.classify("java.net.ConnectException: Connection refused"))
        assertEquals(SshErrorKind.UNREACHABLE, SshErrors.classify("java.net.UnknownHostException: comfy.lan"))
        assertEquals(SshErrorKind.UNREACHABLE, SshErrors.classify("socket is not established"))
        assertEquals(SshErrorKind.TIMEOUT, SshErrors.classify("Session.connect: java.net.SocketTimeoutException"))
        assertEquals(SshErrorKind.TIMEOUT, SshErrors.classify("", timedOut = true))
    }

    @Test
    fun `anything else is unknown rather than silently mislabelled`() {
        assertEquals(SshErrorKind.UNKNOWN, SshErrors.classify(""))
        assertEquals(SshErrorKind.UNKNOWN, SshErrors.classify("kex error: no matching algorithm"))
    }
}