package com.mie.kreaworkbench.data.ssh

import com.jcraft.jsch.HostKeyRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Trust-on-first-use host key store (v0.5.2).
 *
 * This is the only place in the whole tunnel that can stop a MITM server swap: an unknown
 * host is accepted and remembered, a remembered host with a new key must report CHANGED, and
 * the record must really land on disk - otherwise every cold start is a "first sight" and the protection is worthless.
 */
class TofuHostKeyRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(file: File = File(tmp.root, "known_hosts")) = TofuHostKeyRepository(file)

    @Test
    fun `an unknown host is trusted once and remembered`() {
        val file = File(tmp.root, "known_hosts")
        val repo = repo(file)
        assertEquals(HostKeyRepository.OK, repo.check("host:22", byteArrayOf(1, 2, 3)))
        assertTrue(file.isFile)
        assertTrue(repo.hosts().contains("host:22"))
    }

    @Test
    fun `the same key on a later connect still passes`() {
        val file = File(tmp.root, "known_hosts")
        repo(file).check("host:22", byteArrayOf(1, 2, 3))
        // A new instance = connecting again after an app restart, using the record on disk
        assertEquals(HostKeyRepository.OK, repo(file).check("host:22", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a different key for a known host is reported as changed`() {
        val file = File(tmp.root, "known_hosts")
        repo(file).check("host:22", byteArrayOf(1, 2, 3))
        assertEquals(HostKeyRepository.CHANGED, repo(file).check("host:22", byteArrayOf(9, 9, 9)))
        // The rejected key must not overwrite the existing record
        assertEquals(HostKeyRepository.OK, repo(file).check("host:22", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `forget drops exactly one host`() {
        val repo = repo()
        repo.check("a:22", byteArrayOf(1))
        repo.check("b:22", byteArrayOf(2))
        repo.forget("a:22")
        assertEquals(setOf("b:22"), repo.hosts())
        assertEquals(HostKeyRepository.OK, repo.check("a:22", byteArrayOf(3)))
    }

    @Test
    fun `forget matches the bracket-and-port form JSch actually stores`() {
        // JSch stores `[host]:port` OpenSSH-style for non-default ports, while the UI only passes a bare host name
        val repo = repo()
        repo.check("[127.0.0.1]:2222", byteArrayOf(5))
        repo.check("other.example.com", byteArrayOf(6))
        repo.forget("127.0.0.1")
        assertEquals(setOf("other.example.com"), repo.hosts())
    }

    @Test
    fun `bare host strips the port and the brackets`() {
        assertEquals("127.0.0.1", bareHost("[127.0.0.1]:2222"))
        assertEquals("127.0.0.1", bareHost("127.0.0.1:2222"))
        assertEquals("nas.example.com", bareHost("nas.example.com"))
    }

    @Test
    fun `a missing or empty store is not an error`() {
        val repo = repo(File(tmp.root, "nested/known_hosts"))
        assertTrue(repo.hosts().isEmpty())
        assertEquals(0, repo.getHostKey().size)
        assertEquals(HostKeyRepository.OK, repo.check("host:22", byteArrayOf(4)))
        assertTrue(File(tmp.root, "nested/known_hosts").isFile)
    }

    @Test
    fun `blank keys never create an entry`() {
        val repo = repo()
        assertEquals(HostKeyRepository.OK, repo.check("host:22", ByteArray(0)))
        assertFalse(repo.hosts().contains("host:22"))
    }

    @Test
    fun `persisted lines keep host and key on one line each`() {
        val file = File(tmp.root, "known_hosts")
        val repo = repo(file)
        repo.check("a:22", byteArrayOf(1, 2))
        repo.check("b:22", byteArrayOf(3))
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.substringAfter(' ').isNotBlank() })
    }
}