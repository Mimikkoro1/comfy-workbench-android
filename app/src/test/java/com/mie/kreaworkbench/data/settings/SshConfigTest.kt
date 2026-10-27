package com.mie.kreaworkbench.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence codec for the SSH tunnel config and the "do we need to reconnect" decision (v0.5.2).
 *
 * Two things must hold:
 * 1. The three credentials never enter the plaintext JSON (they are encrypted separately via ServerCrypto).
 * 2. The fingerprint covers everything that affects the connection - miss one item and a
 */
class SshConfigTest {

    private val full = SshConfig(
        enabled = true,
        host = "comfy.example.com",
        port = 2222,
        user = "lute",
        authMode = SshAuthMode.PASSWORD,
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----",
        keyPassphrase = "pw",
        password = "secret",
        remoteHost = "10.0.0.5",
        remotePort = 8888,
        gachaForward = true,
        gachaRemoteHost = "10.0.0.5",
        gachaRemotePort = 8787,
    )

    @Test
    fun `json never carries credentials`() {
        val json = SshConfig.toJson(full)
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertFalse(json.contains("pw"))
    }

    @Test
    fun `round trip keeps every non secret field`() {
        val back = SshConfig.parse(SshConfig.toJson(full), full.privateKey, full.keyPassphrase, full.password)
        assertEquals(full, back)
    }

    @Test
    fun `credentials come back from the encrypted side channel`() {
        val back = SshConfig.parse(SshConfig.toJson(full), "KEY", "PASS", "PW")
        assertEquals("KEY", back.privateKey)
        assertEquals("PASS", back.keyPassphrase)
        assertEquals("PW", back.password)
    }

    @Test
    fun `an empty or broken store falls back to defaults instead of crashing`() {
        assertEquals(SshConfig(), SshConfig.parse("", "", "", ""))
        val broken = SshConfig.parse("{not json", "", "", "")
        assertFalse(broken.enabled)
        assertEquals(SshConfig.DEFAULT_COMFY_PORT, broken.remotePort)
        assertEquals(SshConfig.DEFAULT_REMOTE_HOST, broken.remoteHost)
    }

    @Test
    fun `out of range ports are clamped rather than trusted`() {
        val raw = SshConfig.toJson(full).replace("\"port\":2222", "\"port\":99999")
        assertEquals(65535, SshConfig.parse(raw, "", "", "").port)
    }

    @Test
    fun `configured needs both the switch and a host`() {
        assertTrue(full.configured)
        assertFalse(full.copy(enabled = false).configured)
        assertFalse(full.copy(host = "").configured)
    }

    @Test
    fun `every field that changes the connection changes the fingerprint`() {
        val base = full.fingerprint()
        assertNotEquals(base, full.copy(host = "other.example.com").fingerprint())
        assertNotEquals(base, full.copy(port = 22).fingerprint())
        assertNotEquals(base, full.copy(user = "root").fingerprint())
        assertNotEquals(base, full.copy(authMode = SshAuthMode.KEY).fingerprint())
        assertNotEquals(base, full.copy(remoteHost = "127.0.0.1").fingerprint())
        assertNotEquals(base, full.copy(remotePort = 8188).fingerprint())
        assertNotEquals(base, full.copy(gachaForward = false).fingerprint())
        assertNotEquals(base, full.copy(gachaRemotePort = 9000).fingerprint())
        // A new key or password obviously means a rebuild, otherwise changing credentials keeps the old tunnel
        assertNotEquals(base, full.copy(password = "other").fingerprint())
        assertNotEquals(base, full.copy(privateKey = "other").fingerprint())
        assertNotEquals(base, full.copy(keyPassphrase = "").fingerprint())
    }

    @Test
    fun `an unchanged config keeps its fingerprint`() {
        assertEquals(full.fingerprint(), full.copy().fingerprint())
        assertEquals(64, full.fingerprint().length)
    }
}