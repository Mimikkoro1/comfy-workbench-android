package com.mie.kreaworkbench.data.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** 标准化后的单条提示词（库文件里统一只存这五个字段）。 */
data class PromptEntry(
    val id: String,
    val prompt: String,
    val title: String,
    val category: String,
    val tags: List<String>,
)

/** 库格式标记（round8 2.0）：txt=空行分块的可编辑文本库；json=标准五字段库。 */
const val FMT_TXT = "txt"
const val FMT_JSON = "json"

/** 库元数据，持久化在 filesDir/libraries/index.json。 */
data class LibraryMeta(
    val id: String,
    val name: String,
    val count: Int,
    val importedAt: Long,
    val fmt: String,
)

/** 导入结果。ok=false 时 error 给出原因（格式不对 / 超大 / 仅支持 json txt…），reasons 为单条跳过原因。 */
data class ImportResult(
    val ok: Boolean,
    val libId: String? = null,
    val name: String = "",
    val imported: Int = 0,
    val skipped: Int = 0,
    val reasons: List<String> = emptyList(),
    val error: String? = null,
)

/** txt 库编辑器的装载结果（round8 2.3）。Missing 兜底覆盖：库已删 / 不是 txt 库 / 文件损坏。 */
sealed class LibraryEditorLoad {
    data object Missing : LibraryEditorLoad()
    data object TooBig : LibraryEditorLoad()
    data class Ready(val name: String, val text: String) : LibraryEditorLoad()
}

/** txt 库编辑保存结果：TooLong 整体拒绝（库未改动），Missing 为保存间隙库被删。 */
sealed class SaveTxtResult {
    data object Ok : SaveTxtResult()
    data class TooLong(val index: Int) : SaveTxtResult()
    data object Missing : SaveTxtResult()
    data class Error(val message: String) : SaveTxtResult()
}

/**
 * 本地提示词库仓库：多库并存，取代原 8199 工作台的 library_meta / library_prompt。
 * 库文件：filesDir/libraries/<libId>.json，标准 JSON 数组 [{id,prompt,title,category,tags}]。
 * 索引：filesDir/libraries/index.json，{"libraries":[...], "current": <libId|null>}。
 * 冷却记忆（round11）：filesDir/libraries/<libId>.cooldown.json，{"texts":[…最近抽中的提示词文本，MRU 序]}。
 * 所有读写走单把互斥锁；meta()/draw() 用内存缓存，不重复读文件。
 */
class PromptLibraryRepo(private val ctx: Context) {

    private val dir get() = File(ctx.filesDir, "libraries")
    private val indexFile get() = File(dir, "index.json")
    private val mutex = Mutex()

    private val _libraries = MutableStateFlow<List<LibraryMeta>>(emptyList())
    val libraries: StateFlow<List<LibraryMeta>> = _libraries
    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId

    private var loaded = false
    private var cachedEntries: List<PromptEntry> = emptyList()
    private var cachedForId: String? = null

    /** 冷却记忆（round11）：libId → 最近抽中的提示词文本（MRU 序，首位最近）；首次用到才从文件加载。 */
    private val cooldowns = HashMap<String, ArrayDeque<String>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 启动即把 index + 当前库加载进内存；未完成前 meta()/draw() 会在锁内再 ensure 一次
        scope.launch { reload() }
    }

    suspend fun reload() = locked { ensureLoadedLocked() }

    /**
     * 所有对外入口统一经这里：先切到 IO 线程再拿锁——文件读写和 JSON 解析绝不上主线程。
     * 调用方无需关心线程；返回值/异常原样透传。
     */
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    suspend fun hasCurrent(): Boolean = locked {
        ensureLoadedLocked()
        _currentId.value != null
    }

    suspend fun nameOf(id: String?): String? = locked {
        ensureLoadedLocked()
        _libraries.value.firstOrNull { it.id == id }?.name
    }

    /** 当前库的分类统计：{"categories":[{"name","count"}]}，按条数降序。无库时 categories 为空数组。 */
    suspend fun meta(): JSONObject = locked {
        ensureLoadedLocked()
        val counts = LinkedHashMap<String, Int>()
        cachedEntries.forEach {
            val c = it.category.ifBlank { UNCAT }
            counts[c] = (counts[c] ?: 0) + 1
        }
        val arr = JSONArray()
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { arr.put(JSONObject().put("name", it.key).put("count", it.value)) }
        JSONObject().put("categories", arr)
    }

    /**
     * 抽卡：分类过滤（空/「全部」=不限，忽略大小写）→ 包含关键词（prompt/title/tags 任一命中，需全部命中）
     * → 排除关键词（任一命中即排除）→ 冷却窗口（round11）→ 剩余候选随机一条。筛选后池空抛 ApiException。
     * 冷却窗口：筛选后池 > 20 时把最近抽中的文本按 MRU 序从候选排除（先与当前池取交集——切库、删卡、
     * 改筛选后不在池里的记忆自然失效）；每条先算排除结果、为空（该文案是最后剩余候选）就跳过，保底剩 1 张；
     * 池 ≤ 20 不冻结、全池随机。两种情况记忆都照常记录。返回 JSON 结构不变：pool_size 仍是筛选后池大小，不含冻结。
     */
    suspend fun draw(category: String, include: String, exclude: String): JSONObject = locked {
        ensureLoadedLocked()
        val libId = _currentId.value
        if (libId == null) throw ApiException(ctx.str(R.string.err_need_library), network = false)
        val cat = category.trim()
        val want = splitKeywords(include)
        val ban = splitKeywords(exclude)
        val pool = filterPool(cat, want, ban)
        if (pool.isEmpty()) {
            val sep = ctx.str(R.string.list_sep)
            val detail = buildList {
                if (cat.isNotEmpty() && cat != "全部") add(ctx.str(R.string.draw_cat, cat))
                if (want.isNotEmpty()) add(ctx.str(R.string.draw_include, want.joinToString(sep)))
                if (ban.isNotEmpty()) add(ctx.str(R.string.draw_exclude, ban.joinToString(sep)))
            }.joinToString(" · ")
            val message = if (detail.isNotEmpty()) {
                ctx.str(R.string.draw_empty_detail, detail)
            } else {
                ctx.str(R.string.draw_empty)
            }
            throw ApiException(message, network = false)
        }
        var candidates = pool
        if (pool.size > COOLDOWN_MAX) {
            for (text in cooldownLocked(libId)) {
                // 保底后置（round11 fix）：filterNot 一次排掉所有同文案条目，
                // 先算结果、为空（该文案是最后剩余候选）就跳过排除它，绝不把候选清空
                val next = candidates.filterNot { it.prompt == text }
                if (next.isNotEmpty()) candidates = next
            }
        }
        val card = candidates.random()
        rememberDrawLocked(libId, card.prompt)
        JSONObject()
            .put("prompt", card.prompt)
            .put("card", JSONObject().put("title", card.title).put("category", card.category))
            .put("pool_size", pool.size)
    }

    /**
     * 备选数（round11）：与 draw 同一过滤管线，返回筛选后池的条数。
     * 纯只读：无库/空池返回 0 不抛异常，不记录冷却记忆、不改任何状态。
     */
    suspend fun poolSize(category: String, include: String, exclude: String): Int = locked {
        ensureLoadedLocked()
        filterPool(category.trim(), splitKeywords(include), splitKeywords(exclude)).size
    }

    /** 设为当前库。 */
    suspend fun setCurrent(id: String) = locked {
        ensureLoadedLocked()
        if (_libraries.value.any { it.id == id }) {
            writeIndexLocked(_libraries.value, id)
            _currentId.value = id
            refreshCacheLocked(id)
        }
    }

    /**
     * 重命名库（round13 第 3 项）：只改索引里的 name。库的全部引用（currentId、抽卡筛选、
     * 生成页绑定）都走 id，改名不需要任何迁移。id 不存在或新名为空返回 false；重名拦截在 UI 层。
     */
    suspend fun renameLibrary(id: String, name: String): Boolean = locked {
        ensureLoadedLocked()
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@locked false
        val libs = _libraries.value
        if (libs.none { it.id == id }) return@locked false
        val updated = libs.map { if (it.id == id) it.copy(name = trimmed) else it }
        writeIndexLocked(updated, _currentId.value)
        _libraries.value = updated
        true
    }

    /** 某库的分组列表（名 → 条数），按条数降序；供「重命名分组」的选择列表（round13 第 3 项）。 */
    suspend fun categoriesOf(libId: String): List<Pair<String, Int>> = locked {
        ensureLoadedLocked()
        if (_libraries.value.none { it.id == libId }) return@locked emptyList()
        val entries = parseLibFile(libId) ?: return@locked emptyList()
        val counts = LinkedHashMap<String, Int>()
        entries.forEach {
            val c = it.category.ifBlank { UNCAT }
            counts[c] = (counts[c] ?: 0) + 1
        }
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    /**
     * 重命名分组（round13 第 3 项）：把该库所有 category==old 的条目改成 new，物理重写库文件；
     * 是当前库则强制刷新抽卡缓存（meta()/draw() 读的是缓存）。返回改到的条数（0=没有条目属于
     * 该分组），库不存在/参数为空返回 -1。new 与既有分组同名即合并，重名提示在 UI 层。
     */
    suspend fun renameCategory(libId: String, old: String, new: String): Int = locked {
        ensureLoadedLocked()
        val target = new.trim()
        if (_libraries.value.none { it.id == libId } || old.isBlank() || target.isBlank()) return@locked -1
        val f = File(dir, "$libId.json")
        if (!f.isFile) return@locked -1
        val entries = parseLibFile(libId) ?: return@locked -1
        var changed = 0
        val arr = JSONArray()
        for (e in entries) {
            val cat = if (e.category == old) {
                changed++
                target
            } else {
                e.category
            }
            val tags = JSONArray()
            e.tags.forEach { tags.put(it) }
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("prompt", e.prompt)
                    .put("title", e.title)
                    .put("category", cat)
                    .put("tags", tags),
            )
        }
        if (changed > 0) {
            f.writeText(arr.toString())
            if (_currentId.value == libId) {
                cachedForId = null
                refreshCacheLocked(libId)
            }
        }
        changed
    }

    /**
     * 删除库；删的是当前库就自动切到最近导入的那个（没有库了 current 置空），不会崩。
     */
    suspend fun delete(id: String) = locked {
        ensureLoadedLocked()
        val libs = _libraries.value.filterNot { it.id == id }
        if (libs.size == _libraries.value.size) return@locked
        File(dir, "$id.json").delete()
        File(dir, "$id.cooldown.json").delete()
        cooldowns.remove(id)
        val cur = if (_currentId.value == id) libs.maxByOrNull { it.importedAt }?.id else _currentId.value
        writeIndexLocked(libs, cur)
        _libraries.value = libs
        _currentId.value = cur
        refreshCacheLocked(cur)
    }

    /**
     * 新建空库（文本结果「存入提示词库」用）并设为当前；返回新库 id。
     */
    suspend fun createLibrary(name: String): String = locked {
        ensureLoadedLocked()
        val id = UUID.randomUUID().toString()
        dir.mkdirs()
        File(dir, "$id.json").writeText("[]")
        // 存入提示词库新建的库是无格式库（addEntry 条目全空字段）→ 固定 txt（round8 2.0）
        val meta = LibraryMeta(id = id, name = name, count = 0, importedAt = System.currentTimeMillis(), fmt = FMT_TXT)
        val libs = (_libraries.value + meta).sortedByDescending { it.importedAt }
        writeIndexLocked(libs, id)
        _libraries.value = libs
        _currentId.value = id
        cachedEntries = emptyList()
        cachedForId = id
        id
    }

    /**
     * 向指定库追加一条（round6 文本结果存库）；库不存在返回 false。
     * 只要求 prompt 非空，title/category/tags 缺省，格式与导入条目一致。
     * 与 createLibrary 一致：写入成功后把该库设为当前库——当前库从空变非空时，
     * 生成页的抽卡 UI（hasPromptLibrary）随之自动出现。
     */
    suspend fun addEntry(libId: String, prompt: String, title: String = "", category: String = "", tags: List<String> = emptyList()): Boolean = locked {
        ensureLoadedLocked()
        if (prompt.isBlank()) return@locked false
        val f = File(dir, "$libId.json")
        if (!f.isFile) return@locked false
        val arr = try {
            JSONArray(f.readText())
        } catch (_: Exception) {
            JSONArray()
        }
        val tagArr = JSONArray()
        tags.forEach { tagArr.put(it) }
        arr.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("prompt", prompt)
                .put("title", title)
                .put("category", category)
                .put("tags", tagArr),
        )
        f.writeText(arr.toString())
        val libs = _libraries.value.map { if (it.id == libId) it.copy(count = it.count + 1) else it }
        _libraries.value = libs
        _currentId.value = libId
        writeIndexLocked(libs, libId)
        // 缓存强制切到这个库重读（refreshCacheLocked 对同 id 直接返回，先清标记）
        cachedForId = null
        refreshCacheLocked(libId)
        true
    }

    /**
     * SAF 导入：.json 按计划解析规则、.txt 按空行分块；成功后复制进私有存储并自动设为当前库。
     * 20MB 上限：先用 SAF 查 OpenableColumns.SIZE 拒大文件（不整读进内存），查不到/查不准时
     * 流式读取计数，超限即中止。
     */
    suspend fun importLibrary(uri: Uri): ImportResult = locked {
        ensureLoadedLocked()
        val resolver = ctx.contentResolver
        val rawName = queryDisplayName(resolver, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "导入库"
        val lower = rawName.lowercase()
        val isJson = lower.endsWith(".json")
        val isTxt = lower.endsWith(".txt")
        if (!isJson && !isTxt) {
            return@locked ImportResult(ok = false, name = rawName, error = ctx.str(R.string.err_lib_ext))
        }
        val size = querySize(resolver, uri)
        if (size != null && size > MAX_BYTES) {
            return@locked ImportResult(ok = false, name = rawName, error = ctx.str(R.string.err_file_20mb))
        }
        val text = try {
            readTextCapped(resolver, uri)
        } catch (_: OversizeException) {
            return@locked ImportResult(ok = false, name = rawName, error = ctx.str(R.string.err_file_20mb))
        } catch (e: Exception) {
            return@locked ImportResult(ok = false, name = rawName, error = ctx.str(R.string.err_read, e.message ?: e.javaClass.simpleName))
        }
        val parsed = if (isJson) parseJson(text) else parseTxt(text)
        if (parsed.error != null) {
            return@locked ImportResult(ok = false, name = rawName, error = parsed.error)
        }
        if (parsed.entries.isEmpty()) {
            return@locked ImportResult(
                ok = false,
                name = rawName,
                error = ctx.str(R.string.err_no_prompts, parsed.total, parsed.skipped),
                reasons = parsed.reasons,
            )
        }
        val id = UUID.randomUUID().toString()
        dir.mkdirs()
        val arr = JSONArray()
        for (e in parsed.entries) {
            val tags = JSONArray()
            e.tags.forEach { tags.put(it) }
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("prompt", e.prompt)
                    .put("title", e.title)
                    .put("category", e.category)
                    .put("tags", tags),
            )
        }
        File(dir, "$id.json").writeText(arr.toString())
        val meta = LibraryMeta(
            id = id,
            name = rawName.substringBeforeLast('.'),
            count = parsed.entries.size,
            importedAt = System.currentTimeMillis(),
            fmt = if (isTxt) FMT_TXT else FMT_JSON,
        )
        val libs = (_libraries.value + meta).sortedByDescending { it.importedAt }
        writeIndexLocked(libs, id)
        _libraries.value = libs
        _currentId.value = id
        cachedEntries = parsed.entries
        cachedForId = id
        ImportResult(ok = true, libId = id, name = meta.name, imported = parsed.entries.size, skipped = parsed.skipped, reasons = parsed.reasons)
    }

    /**
     * 库导出（round8 2.1）：格式由 fmt 决定——
     * txt：每条取 prompt（跳过空段），块间一个空行（与 parseTxt 分块互逆，导出文件可直接再导入）；
     * json：标准五字段数组（id/prompt/title/category/tags），与 parseJson 可解析格式一致。
     * 不查重、不改库内容、不更新 meta。返回 null=成功，非 null=失败原因。
     */
    suspend fun exportLibrary(libId: String, uri: Uri): String? = locked {
        ensureLoadedLocked()
        val meta = _libraries.value.firstOrNull { it.id == libId } ?: return@locked ctx.str(R.string.err_lib_missing)
        val entries = parseLibFile(libId) ?: return@locked ctx.str(R.string.err_lib_corrupt)
        val content = if (meta.fmt == FMT_TXT) {
            entries.map { it.prompt }.filter { it.isNotBlank() }.joinToString("\n\n")
        } else {
            val arr = JSONArray()
            for (e in entries) {
                val tags = JSONArray()
                e.tags.forEach { tags.put(it) }
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("prompt", e.prompt)
                        .put("title", e.title)
                        .put("category", e.category)
                        .put("tags", tags),
                )
            }
            arr.toString()
        }
        try {
            val out = ctx.contentResolver.openOutputStream(uri) ?: return@locked ctx.str(R.string.err_cannot_write)
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            return@locked ctx.str(R.string.err_write, e.message ?: e.javaClass.simpleName)
        }
        null
    }

    /**
     * 可编辑库 id 集合（round8 2.3 容量护栏）：fmt=txt 且库文件 ≤ EDITOR_MAX_BYTES。
     * 超纲库不给编辑（行内不显示「编辑」），走导出 + 外部编辑器。
     */
    suspend fun editableLibraryIds(): Set<String> = locked {
        ensureLoadedLocked()
        _libraries.value.mapNotNull { m ->
            val f = File(dir, "${m.id}.json")
            if (m.fmt == FMT_TXT && f.isFile && f.length() <= EDITOR_MAX_BYTES) m.id else null
        }.toSet()
    }

    /**
     * 编辑器装载（round8 2.3）：Ready 带库名与预填文本——
     * entries 的 prompt 按 "\n\n" 拼接，与导出 txt 同一形态。装载时复检容量护栏。
     */
    suspend fun loadEditor(libId: String): LibraryEditorLoad = locked {
        ensureLoadedLocked()
        val meta = _libraries.value.firstOrNull { it.id == libId } ?: return@locked LibraryEditorLoad.Missing
        if (meta.fmt != FMT_TXT) return@locked LibraryEditorLoad.Missing
        val f = File(dir, "$libId.json")
        if (!f.isFile) return@locked LibraryEditorLoad.Missing
        if (f.length() > EDITOR_MAX_BYTES) return@locked LibraryEditorLoad.TooBig
        val entries = parseLibFile(libId) ?: return@locked LibraryEditorLoad.Missing
        LibraryEditorLoad.Ready(meta.name, entries.map { it.prompt }.filter { it.isNotBlank() }.joinToString("\n\n"))
    }

    /**
     * txt 库编辑保存（round8 2.3）：与 parseTxt 相同的分块规则解析（空行分块、trim、跳过空块）；
     * 单块超 MAX_PROMPT_CHARS 整体拒绝（不静默丢块）；不做内容去重；
     * 每块生成新 UUID id、title=shortTitle、category=未分类、tags 空（与 txt 导入语义一致）。
     * 重写库文件 → 更新 count → 是当前库则强制刷新抽卡缓存（同 addEntry 做法）。
     * 清空全部内容 = 合法保存（count=0）。锁内一次写入；库已被删返回 Missing。
     */
    suspend fun saveTxtLibrary(libId: String, text: String): SaveTxtResult = locked {
        ensureLoadedLocked()
        val meta = _libraries.value.firstOrNull { it.id == libId } ?: return@locked SaveTxtResult.Missing
        if (meta.fmt != FMT_TXT) return@locked SaveTxtResult.Missing
        if (!File(dir, "$libId.json").isFile) return@locked SaveTxtResult.Missing
        val blocks = txtBlocks(text)
        for ((i, b) in blocks.withIndex()) {
            if (b.length > MAX_PROMPT_CHARS) return@locked SaveTxtResult.TooLong(i + 1)
        }
        val arr = JSONArray()
        for (b in blocks) {
            arr.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("prompt", b)
                    .put("title", shortTitle(b))
                    .put("category", UNCAT)
                    .put("tags", JSONArray()),
            )
        }
        try {
            File(dir, "$libId.json").writeText(arr.toString())
        } catch (e: Exception) {
            return@locked SaveTxtResult.Error(e.message ?: e.javaClass.simpleName)
        }
        val libs = _libraries.value.map { if (it.id == libId) it.copy(count = blocks.size) else it }
        _libraries.value = libs
        writeIndexLocked(libs, _currentId.value)
        if (_currentId.value == libId) {
            cachedForId = null
            refreshCacheLocked(libId)
        }
        SaveTxtResult.Ok
    }

    // ---------- 内部 ----------

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        dir.mkdirs()
        val idx = try {
            JSONObject(indexFile.takeIf { it.exists() }?.readText() ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val libs = ArrayList<LibraryMeta>()
        val arr = idx.optJSONArray("libraries") ?: JSONArray()
        var fmtMigrated = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || !File(dir, "$id.json").exists()) continue
            val storedFmt = o.optString("fmt")
            val fmt = if (storedFmt == FMT_TXT || storedFmt == FMT_JSON) storedFmt else {
                // 旧索引（无 fmt 键）：按条目形态推断一次并写回（round8 2.0）
                fmtMigrated = true
                inferFmt(id)
            }
            libs.add(LibraryMeta(id, o.optString("name"), o.optInt("count"), o.optLong("importedAt"), fmt))
        }
        val stored = when (val c = idx.opt("current")) {
            is String -> c.ifBlank { null }
            else -> null
        }?.takeIf { c -> libs.any { it.id == c } }
        val cur = stored ?: libs.maxByOrNull { it.importedAt }?.id
        if (fmtMigrated) writeIndexLocked(libs, cur)
        _libraries.value = libs.sortedByDescending { it.importedAt }
        _currentId.value = cur
        refreshCacheLocked(cur)
    }

    private fun refreshCacheLocked(id: String?) {
        if (id == null) {
            cachedEntries = emptyList()
            cachedForId = null
            return
        }
        if (cachedForId == id) return
        cachedEntries = parseLibFile(id) ?: emptyList()
        cachedForId = id
    }

    /** 抽卡过滤管线（draw 与 poolSize 共用）：分类 → 包含 → 排除。只过滤不判空，池空由调用方处理。 */
    private fun filterPool(cat: String, want: List<String>, ban: List<String>): List<PromptEntry> {
        var pool = cachedEntries
        if (cat.isNotEmpty() && cat != "全部") {
            val c = cat.lowercase()
            pool = pool.filter { it.category.lowercase() == c }
        }
        if (want.isNotEmpty()) {
            pool = pool.filter { e -> want.all { w -> e.matches(w) } }
        }
        if (ban.isNotEmpty()) {
            pool = pool.filterNot { e -> ban.any { b -> e.matches(b) } }
        }
        return pool
    }

    /** 读某库的冷却记忆：首次访问从 <libId>.cooldown.json 惰性加载，缺失/损坏当空。必须在锁内调用。 */
    private fun cooldownLocked(libId: String): ArrayDeque<String> =
        cooldowns.getOrPut(libId) {
            val dq = ArrayDeque<String>()
            try {
                val arr = JSONObject(File(dir, "$libId.cooldown.json").readText()).optJSONArray("texts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotEmpty()) dq.addLast(s)
                }
            } catch (_: Exception) {
            }
            dq
        }

    /** 抽中一条：文本记入 MRU 头部（同文本先移除再置顶）、裁到上限、整文件重写。必须在锁内调用。 */
    private fun rememberDrawLocked(libId: String, prompt: String) {
        val dq = cooldownLocked(libId)
        dq.remove(prompt)
        dq.addFirst(prompt)
        while (dq.size > COOLDOWN_MAX) dq.removeLast()
        try {
            val arr = JSONArray()
            for (s in dq) arr.put(s)
            dir.mkdirs()
            File(dir, "$libId.cooldown.json").writeText(JSONObject().put("texts", arr).toString())
        } catch (_: Exception) {
            // 持久化失败只丢记忆，不影响本次抽卡（内存队列仍在，下次抽中会重写）
        }
    }

    /** 解析库文件为条目列表；文件缺失/损坏返回 null。纯读取，不触碰抽卡缓存。 */
    private fun parseLibFile(libId: String): List<PromptEntry>? {
        val f = File(dir, "$libId.json")
        if (!f.isFile) return null
        return try {
            val arr = JSONArray(f.readText())
            val list = ArrayList<PromptEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    PromptEntry(
                        id = o.optString("id"),
                        prompt = o.optString("prompt"),
                        title = o.optString("title"),
                        category = o.optString("category"),
                        tags = o.optJSONArray("tags")?.let { ta -> List(ta.length()) { ta.optString(it) } } ?: emptyList(),
                    ),
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 旧库 fmt 推断（round8 2.0）：全部条目满足任一形态才算 txt——
     * a) title/category/tags 全空（addEntry 造的条目）；
     * b) category=「未分类」且 tags 空且 title==shortTitle(prompt)（旧 txt 导入造的条目）。
     * 已知误判边界：json 导入且所有条目恰好都是默认 title/未分类/无 tags → 认成 txt（变为可编辑）；
     * 编辑保存按 txt 语义重写，丢掉的只是这些默认等价值（计划书已接受）。
     */
    private fun inferFmt(libId: String): String {
        val entries = parseLibFile(libId) ?: return FMT_JSON
        for (e in entries) {
            val okA = e.title.isBlank() && e.category.isBlank() && e.tags.isEmpty()
            val okB = e.category == UNCAT && e.tags.isEmpty() && e.title == shortTitle(e.prompt)
            if (!okA && !okB) return FMT_JSON
        }
        return FMT_TXT
    }

    private fun writeIndexLocked(libs: List<LibraryMeta>, current: String?) {
        dir.mkdirs()
        val arr = JSONArray()
        libs.sortedByDescending { it.importedAt }.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("count", it.count)
                    .put("importedAt", it.importedAt)
                    .put("fmt", it.fmt),
            )
        }
        indexFile.writeText(JSONObject().put("libraries", arr).put("current", current ?: JSONObject.NULL).toString())
    }

    private fun PromptEntry.matches(keyword: String): Boolean =
        prompt.contains(keyword, ignoreCase = true) ||
            title.contains(keyword, ignoreCase = true) ||
            tags.any { it.contains(keyword, ignoreCase = true) }

    /** 关键词切分与旧 8199 split_list 一致：，；;、换行 都当逗号，空段丢弃（不按空格切）。 */
    private fun splitKeywords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        var s = text
        for (sep in "，；;、\n\t") s = s.replace(sep, ',')
        return s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private class Parsed(
        val entries: List<PromptEntry>,
        val total: Int,
        val skipped: Int,
        val reasons: List<String>,
        val error: String? = null,
    )

    /**
     * JSON 解析规则（按计划）：顶层数组，或对象含 items/prompts 数组，否则整体拒绝；
     * 每条只要求 prompt 非空字符串，超长/缺 prompt 跳过记因；缺省补全；按 id 去重；未知字段丢弃。
     */
    private fun parseJson(text: String): Parsed {
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            val obj = try {
                JSONObject(text)
            } catch (_: Exception) {
                null
            }
            obj?.optJSONArray("items") ?: obj?.optJSONArray("prompts")
                ?: return Parsed(emptyList(), 0, 0, emptyList(), ctx.str(R.string.err_not_library))
        }
        val entries = ArrayList<PromptEntry>()
        val reasons = ArrayList<String>()
        val seen = HashSet<String>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val pos = i + 1
            val o = arr.optJSONObject(i)
            if (o == null) {
                skipped++
                reasons.add(ctx.str(R.string.err_entry_not_object, pos))
                continue
            }
            val prompt = (o.opt("prompt") as? String)?.trim().orEmpty()
            when {
                prompt.isEmpty() -> {
                    skipped++
                    reasons.add(ctx.str(R.string.err_entry_no_prompt, pos))
                    continue
                }
                prompt.length > MAX_PROMPT_CHARS -> {
                    skipped++
                    reasons.add(ctx.str(R.string.err_entry_too_long, pos, MAX_PROMPT_CHARS))
                    continue
                }
            }
            val id = when (val v = o.opt("id")) {
                is String -> v.trim()
                is Number, is Boolean -> v.toString()
                else -> ""
            }.ifBlank { hash12(prompt) }
            if (!seen.add(id)) {
                skipped++
                reasons.add(ctx.str(R.string.err_entry_dup_id, pos))
                continue
            }
            val title = (o.opt("title") as? String)?.trim().orEmpty().ifBlank { shortTitle(prompt) }
            val category = (o.opt("category") as? String)?.trim().orEmpty().ifBlank { UNCAT }
            val tags = when (val t = o.opt("tags")) {
                is String -> listOf(t.trim()).filter { it.isNotEmpty() }
                is org.json.JSONArray -> buildList {
                    for (j in 0 until t.length()) {
                        val s = (t.opt(j) as? String)?.trim().orEmpty()
                        if (s.isNotEmpty()) add(s)
                    }
                }
                else -> emptyList()
            }
            entries.add(PromptEntry(id, prompt, title, category, tags))
        }
        return Parsed(entries, arr.length(), skipped, reasons)
    }

    /** TXT：UTF-8（含 BOM），按空行分块，每块 trim 后非空即一条；分类固定「未分类」，id 按内容哈希去重。 */
    private fun parseTxt(text: String): Parsed {
        val entries = ArrayList<PromptEntry>()
        val reasons = ArrayList<String>()
        val seen = HashSet<String>()
        var total = 0
        var skipped = 0
        val block = StringBuilder()
        fun flush() {
            val raw = block.toString().trim()
            block.setLength(0)
            if (raw.isEmpty()) return
            total++
            if (raw.length > MAX_PROMPT_CHARS) {
                skipped++
                reasons.add(ctx.str(R.string.err_line_too_long, total, MAX_PROMPT_CHARS))
                return
            }
            val id = hash12(raw)
            if (!seen.add(id)) {
                skipped++
                reasons.add(ctx.str(R.string.err_line_dup, total))
                return
            }
            entries.add(PromptEntry(id, raw, shortTitle(raw), UNCAT, emptyList()))
        }
        for (line in text.lineSequence()) {
            if (line.isBlank()) flush() else {
                if (block.isNotEmpty()) block.append('\n')
                block.append(line)
            }
        }
        flush()
        return Parsed(entries, total, skipped, reasons)
    }

    /** 与 parseTxt 相同的分块规则：按空行分块、每块 trim、跳过空块。编辑保存用（round8 2.3），不动 parseTxt。 */
    private fun txtBlocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        val block = StringBuilder()
        fun flush() {
            val raw = block.toString().trim()
            block.setLength(0)
            if (raw.isNotEmpty()) blocks.add(raw)
        }
        for (line in text.lineSequence()) {
            if (line.isBlank()) flush() else {
                if (block.isNotEmpty()) block.append('\n')
                block.append(line)
            }
        }
        flush()
        return blocks
    }

    private fun shortTitle(prompt: String): String =
        prompt.replace(Regex("\\s+"), " ").trim().take(20)

    private fun hash12(s: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    private class OversizeException : Exception()

    /** 流式读取并计数，超过 MAX_BYTES 立即中止（SAF 查不到大小时兜底）；去 BOM。 */
    private fun readTextCapped(resolver: ContentResolver, uri: Uri): String {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(minOf(MAX_BYTES, 8L * 1024 * 1024).toInt())
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw OversizeException()
                out.write(buf, 0, n)
            }
            var s = out.toString("UTF-8")
            if (s.isNotEmpty() && s[0] == '\uFEFF') s = s.substring(1)
            return s
        } ?: throw IOException(ctx.str(R.string.err_cannot_open))
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** SAF 查文件大小；查不到返回 null（交给流式读取兜底）。 */
    private fun querySize(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val UNCAT = "未分类"
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_PROMPT_CHARS = 10_000
        /** 冷却窗口（round11）：每库最多记住最近 20 条文本；筛选后池 > 20 时冻结（排除记忆里的文本，保底剩 1 张）。 */
        const val COOLDOWN_MAX = 20
        /** 编辑器容量护栏（round8 2.3）：库文件超过 1MB 不给编辑，Compose 大文本会卡。 */
        const val EDITOR_MAX_BYTES = 1L * 1024 * 1024
    }
}
