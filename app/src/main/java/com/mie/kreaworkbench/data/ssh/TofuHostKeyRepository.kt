package com.mie.kreaworkbench.data.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.io.File
import java.util.Base64

/**
 * Trust-on-first-use (TOFU) host key store -- the equivalent of `StrictHostKeyChecking=accept-new`.
 *
 * An unknown host is remembered and let through; a changed key for the same host returns
 * CHANGED, and JSch then throws the equivalent of the command line's `Host key verification
 * failed` -- the one place that can stop a man-in-the-middle key swap.
 *
 * The file holds one `host base64(key)` per line, read and written only in app-private storage.
 */
class TofuHostKeyRepository(private val store: File) : HostKeyRepository {

    override fun check(host: String, key: ByteArray): Int {
        if (key.isEmpty()) return HostKeyRepository.OK
        val encoded = encode(key)
        val entries = read()
        val existing = entries[host]
        if (existing == null) {
            entries[host] = encoded
            write(entries)
            return HostKeyRepository.OK
        }
        return if (existing == encoded) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        val entries = read()
        entries[hostkey.host] = hostkey.key
        write(entries)
    }

    override fun remove(host: String?, type: String?) {
        val entries = read()
        if (host == null) entries.clear() else entries.remove(host)
        write(entries)
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        val entries = read()
        if (host == null) {
            entries.clear()
        } else if (key == null || entries[host] == encode(key)) {
            entries.remove(host)
        }
        write(entries)
    }

    override fun getKnownHostsRepositoryID(): String = "krea-tofu:$store"

    override fun getHostKey(): Array<HostKey> =
        read().entries.mapNotNull { (host, key) -> hostKey(host, key) }.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey>? {
        val key = read()[host ?: return null] ?: return null
        return hostKey(host, key)?.let { arrayOf(it) }
    }

    /**
     * Forgets a host (recovery path after a key change: verify the server, then trust it again).
     *
     * The UI only knows the host the user typed (`10.0.2.2`), while this store keeps JSch's
     * entry name (OpenSSH style: `[10.0.2.2]:2222` for a non-22 port), so we compare bare host
     * names; otherwise the Forget button would silently do nothing and the user would think it was cleared.
     */
    fun forget(host: String) {
        // Normalize the input too: the UI passes a bare host name, tests and other callers may pass an entry name (`a:22`)
        val wanted = bareHost(host.trim()).lowercase()
        if (wanted.isEmpty()) return
        val entries = read()
        val doomed = entries.keys.filter { bareHost(it).lowercase() == wanted }
        if (doomed.isEmpty()) return
        doomed.forEach { entries.remove(it) }
        write(entries)
    }

    /** Remembered hosts (used by the "reset host key" action in settings). */
    fun hosts(): Set<String> = read().keys

    private fun hostKey(host: String, base64Key: String): HostKey? = try {
        HostKey(host, Base64.getDecoder().decode(base64Key))
    } catch (_: JSchException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun read(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        if (!store.isFile) return map
        store.readLines().forEach { line ->
            val idx = line.indexOf(' ')
            if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1).trim()
        }
        return map
    }

    private fun write(entries: Map<String, String>) {
        runCatching {
            store.parentFile?.mkdirs()
            val tmp = File(store.parentFile, store.name + ".tmp")
            tmp.writeText(entries.entries.joinToString("\n") { (host, key) -> "$host $key" })
            if (!tmp.renameTo(store)) {
                store.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun encode(key: ByteArray): String = Base64.getEncoder().encodeToString(key)
}

/**
 * Entry name -> bare host name. JSch follows OpenSSH's known_hosts convention:
 * `host` on the default port, `[host]:port` otherwise.
 */
internal fun bareHost(entryKey: String): String {
    val key = entryKey.trim()
    return when {
        key.startsWith("[") -> key.substringAfter("[").substringBefore("]")
        else -> key.substringBeforeLast(':', key)
    }
}