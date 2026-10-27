package com.mie.kreaworkbench.data.ssh

import com.mie.kreaworkbench.data.settings.SshAuthMode
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs forwarding against a **real sshd** (v0.5.2).
 *
 * The tunnel chain (library handshake / the local forward is really bound / data really
 * flows / a dropped session really tears it down) cannot be proven by unit tests, because the
 * library only shows its behaviour against a real server. So this test uses a throwaway sshd
 * (non-root, system config untouched) as the target and a fake ComfyUI as the downstream.
 *
 * Without the fixture (no sshd, no key) the whole class skips itself, so the suite stays portable.
 */
class SshForwarderIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Point these at any throwaway sshd to run the suite for real; without it every test
    // in the class skips itself, so CI needs no fixture.
    private val sshPort = System.getenv("KWB_SSH_FIXTURE_PORT")?.toIntOrNull() ?: 2222
    private val keyFile = File(
        System.getenv("KWB_SSH_FIXTURE_KEY")
            ?: File(System.getProperty("user.home"), ".ssh/id_ed25519").path,
    )

    private fun fixtureReady(): Boolean {
        if (!keyFile.isFile) return false
        return try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), sshPort), 500) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun requireFixture() {
        assumeTrue(
            "an sshd fixture is required: 127.0.0.1:$sshPort accepting ${keyFile.path} " +
                "(override with KWB_SSH_FIXTURE_PORT / KWB_SSH_FIXTURE_KEY)",
            fixtureReady(),
        )
    }

    /** Fake ComfyUI: answers just the one /system_stats field the app probes for. */
    private fun fakeComfy(): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/system_stats") { ex ->
            val body = """{"system":{"os":"linux","comfyui_version":"9.9.9"}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return server to server.address.port
    }

    private fun forwarder(knownHosts: File, user: String = "lute", key: String = keyFile.readText()) = SshForwarder(
        target = SshTarget("127.0.0.1", user, sshPort),
        auth = SshAuth(SshAuthMode.KEY, privateKey = key),
        knownHosts = TofuHostKeyRepository(knownHosts),
    )

    private fun loopbackAccepts(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 400) }
        true
    } catch (_: Exception) {
        false
    }

    @Test
    fun `the tunnel really carries http to the remote port and dies with the session`() {
        requireFixture()
        val (comfy, comfyPort) = fakeComfy()
        val forwarder = forwarder(File(tmp.root, "known_hosts"))
        val session = forwarder.open()
        try {
            // The downstream is not a port sshd itself listens on: the forward only counts if the connection really reaches another service
            val localPort = forwarder.forward(session, ForwardSpec("127.0.0.1", comfyPort))
            assertTrue("the forwarded local port should accept connections", loopbackAccepts(localPort))
            val version = probeComfySystemStats("http://127.0.0.1:$localPort", 5_000)
            assertEquals("ComfyUI 9.9.9", version)
            forwarder.close(session)
            // A closed session must close the local listener too; keeping it would leave a zombie tunnel (address alive, nothing answering)
            assertFalse("the local listener should be gone after the session closes", loopbackAccepts(localPort))
        } finally {
            forwarder.close(session)
            comfy.stop(0)
        }
    }

    @Test
    fun `two forwards share the one ssh connection`() {
        requireFixture()
        val (a, aPort) = fakeComfy()
        val (b, bPort) = fakeComfy()
        val forwarder = forwarder(File(tmp.root, "known_hosts"))
        val session = forwarder.open()
        try {
            val pa = forwarder.forward(session, ForwardSpec("127.0.0.1", aPort))
            val pb = forwarder.forward(session, ForwardSpec("127.0.0.1", bPort))
            assertTrue(pa != pb)
            assertEquals("ComfyUI 9.9.9", probeComfySystemStats("http://127.0.0.1:$pa", 5_000))
            assertEquals("ComfyUI 9.9.9", probeComfySystemStats("http://127.0.0.1:$pb", 5_000))
        } finally {
            forwarder.close(session)
            a.stop(0); b.stop(0)
        }
    }

    @Test
    fun `a remote port nobody listens on is reported as unreachable, not as success`() {
        requireFixture()
        val forwarder = forwarder(File(tmp.root, "known_hosts"))
        val session = forwarder.open()
        try {
            // A port nothing listens on remotely: the forward binds, but data through it must fail - this is exactly the "connected but nothing loads" case
            val dead = forwarder.forward(session, ForwardSpec("127.0.0.1", 1))
            assertNull(probeComfySystemStats("http://127.0.0.1:$dead", 2_000))
        } finally {
            forwarder.close(session)
        }
    }

    @Test
    fun `a key the server does not accept is an auth failure`() {
        requireFixture()
        val forwarder = forwarder(File(tmp.root, "known_hosts"), user = "no-such-user")
        val error = runCatching { forwarder.open() }.exceptionOrNull()
        assertTrue("expected an SshException, got $error", error is SshException)
        assertEquals(SshErrorKind.AUTH_FAILED, (error as SshException).kind)
    }

    @Test
    fun `a public key pasted as the private key is a key error, not a password problem`() {
        requireFixture()
        val pub = File("${keyFile.path}.pub").readText()
        val forwarder = forwarder(File(tmp.root, "known_hosts"), key = pub)
        val error = runCatching { forwarder.open() }.exceptionOrNull()
        assertTrue("expected an SshException, got $error", error is SshException)
        assertEquals(SshErrorKind.KEY_INVALID, (error as SshException).kind)
    }

    @Test
    fun `a host whose key changed is refused instead of silently trusted`() {
        requireFixture()
        val hosts = File(tmp.root, "known_hosts")
        // Plant a fake host key for this machine that differs from the real one - i.e. a MITM key swap.
        // Entry name as JSch actually stores it (the integration test confirms [host]:port for non-default ports)
        hosts.writeText("[127.0.0.1]:$sshPort " + Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val error = runCatching { forwarder(hosts).open() }.exceptionOrNull()
        assertTrue("expected an SshException, got $error", error is SshException)
        assertEquals(SshErrorKind.HOST_KEY_CHANGED, (error as SshException).kind)
    }

    @Test
    fun `an unknown host is trusted once and remembered on disk`() {
        requireFixture()
        val hosts = File(tmp.root, "known_hosts")
        val (comfy, comfyPort) = fakeComfy()
        val session = forwarder(hosts).open()
        forwarder(hosts).close(session)
        comfy.stop(0)
        assertTrue(hosts.isFile)
        assertTrue(
            "the host should be remembered after the first connection (actual content=${hosts.readText()})",
            hosts.readText().contains("[127.0.0.1]:$sshPort"),
        )
        // The second connection uses the record from disk: a mismatch would surface as "host key changed"
        val again = forwarder(hosts).open()
        forwarder(hosts).close(again)
        // The "forget host key" action only has the bare host name, and it must really remove this entry
        TofuHostKeyRepository(hosts).forget("127.0.0.1")
        assertFalse("the host should not survive forgetting: ${hosts.readText()}", hosts.readText().contains("127.0.0.1"))
    }
}