package com.mie.kreaworkbench.data.comfy

import com.mie.kreaworkbench.data.api.trimBase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * r42：参数页下拉选项的本地缓存（filesDir/objinfo/<serverKey>.json）。
 * 纯逻辑（encode/decode/merge/serverKey）可 JVM 单测；文件读写走薄封装 [readCache] / [writeCache]。
 * 条目 key = "<classType>|<field>"，与 ComfyApi.modelChoices / comboChoices 的取值维度一致。
 */

private const val CACHE_VERSION = 1

/** 单个列表最多缓存多少项。 */
private const val MAX_LIST_ITEMS = 5000

/** 单个缓存文件上限（UTF-8 字节数），超出丢最大的条目。 */
private const val MAX_FILE_BYTES = 2 * 1024 * 1024

/** objinfo/ 目录最多保留几台服务器的缓存文件（切换服务器不会无限增长）。 */
const val CHOICE_CACHE_MAX_SERVERS = 8

/**
 * 服务器地址 → 缓存文件名：小写化 → 规范化（trimBase）→ 去结尾斜杠 → SHA-1 前 16 位。
 * 同一服务器写法略不同（大小写、结尾斜杠、缺协议）得到同一个 key。
 * 注意先 lowercase 再 trimBase：大写 "HTTP://" 经 trimBase 会被当成无 scheme 而拼错。
 */
fun serverKey(url: String): String {
    val clean = trimBase(url.lowercase()).trimEnd('/')
    if (clean.isBlank()) return ""
    val digest = MessageDigest.getInstance("SHA-1").digest(clean.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * 编码整份缓存：`{"v":1,"ts":<毫秒>,"entries":{"<classType>|<field>":["a","b",…]}}`。
 * 空列表一律不写入；单列表截到 5000 项；整份超过 2MB 时从最大条目开始丢直到达标。
 */
fun encodeEntries(entries: Map<String, List<String>>, nowMs: Long): String {
    var kept: Map<String, List<String>> = entries
        .filterValues { it.isNotEmpty() }
        .mapValues { (_, v) -> v.take(MAX_LIST_ITEMS) }
    var json = encodeJson(kept, nowMs)
    while (json.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES && kept.isNotEmpty()) {
        val biggest = kept.maxByOrNull { (_, v) -> v.sumOf { it.length } }!!.key
        kept = kept - biggest
        json = encodeJson(kept, nowMs)
    }
    return json
}

private fun encodeJson(entries: Map<String, List<String>>, nowMs: Long): String {
    val obj = JSONObject()
    obj.put("v", CACHE_VERSION)
    obj.put("ts", nowMs)
    val e = JSONObject()
    for ((k, v) in entries) e.put(k, JSONArray(v))
    obj.put("entries", e)
    return obj.toString()
}

/**
 * 解析缓存文件内容。解析失败 / 版本不对 / 结构不对返回 null（调用方当作没有缓存），
 * 绝不抛异常；列表里非字符串元素跳过；解析出空列表的 key 丢掉。
 */
fun decodeEntries(text: String): Map<String, List<String>>? {
    if (text.isBlank()) return null
    val json = try {
        JSONObject(text)
    } catch (_: Exception) {
        return null
    }
    if (json.optInt("v") != CACHE_VERSION) return null
    val entries = json.optJSONObject("entries") ?: return null
    val out = LinkedHashMap<String, List<String>>()
    try {
        for (key in entries.keys()) {
            val arr = entries.optJSONArray(key) ?: continue
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                if (v is String && v.isNotEmpty()) list.add(v)
            }
            if (list.isNotEmpty()) out[key] = list
        }
    } catch (_: Exception) {
        return null
    }
    return out
}

/** 合并：fresh 覆盖 old；fresh 里为空的 key 不覆盖（拉取失败/返回空不能把好缓存冲掉）。 */
fun mergeEntries(
    old: Map<String, List<String>>,
    fresh: Map<String, List<String>>,
): Map<String, List<String>> {
    val out = LinkedHashMap(old)
    for ((k, v) in fresh) if (v.isNotEmpty()) out[k] = v
    return out.filterValues { it.isNotEmpty() }
}

/** 读某服务器的选项缓存；文件不存在/损坏返回空 map（损坏文件顺手删掉），不抛。 */
fun readCache(dir: File, url: String): Map<String, List<String>> {
    val key = serverKey(url)
    if (key.isBlank()) return emptyMap()
    val f = File(dir, "$key.json")
    if (!f.isFile) return emptyMap()
    val text = try {
        f.readText(Charsets.UTF_8)
    } catch (_: Exception) {
        return emptyMap()
    }
    val decoded = decodeEntries(text)
    if (decoded == null) {
        runCatching { f.delete() }
        return emptyMap()
    }
    return decoded
}

/**
 * 合并写入某服务器的选项缓存（临时文件 → rename 原子替换；rename 不成立时退化覆盖写）。
 * fresh 全为空不写；全程 runCatching，失败就当没有缓存这回事。
 */
fun writeCache(dir: File, url: String, fresh: Map<String, List<String>>) {
    val key = serverKey(url)
    if (key.isBlank()) return
    val nonEmpty = fresh.filterValues { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) return
    runCatching {
        if (!dir.isDirectory) dir.mkdirs()
        val f = File(dir, "$key.json")
        val old = if (f.isFile) {
            decodeEntries(f.readText(Charsets.UTF_8)).orEmpty()
        } else {
            emptyMap()
        }
        val merged = mergeEntries(old, nonEmpty)
        if (merged.isEmpty()) return
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(encodeEntries(merged, System.currentTimeMillis()), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
        pruneCacheDir(dir)
    }
}

/** objinfo/ 最多保留 [CHOICE_CACHE_MAX_SERVERS] 个缓存文件，超出按最后修改时间删最旧的。 */
private fun pruneCacheDir(dir: File) {
    val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return
    if (files.size <= CHOICE_CACHE_MAX_SERVERS) return
    files.sortedBy { it.lastModified() }
        .take(files.size - CHOICE_CACHE_MAX_SERVERS)
        .forEach { runCatching { it.delete() } }
}
