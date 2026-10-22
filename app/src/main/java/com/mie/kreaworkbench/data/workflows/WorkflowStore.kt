package com.mie.kreaworkbench.data.workflows

import android.content.Context
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 导入工作流的索引条目，持久化在 filesDir/workflows/index.json。 */
data class ImportedWorkflowMeta(
    val id: String,
    val displayName: String,
    val source: String, // "pc" = 成对定义导入，"api" = 裸 API 工作流
    val importedAt: Long,
)

/**
 * 导入工作流仓库：
 * - 每个工作流一个目录：filesDir/workflows/<id>/workflow.json（API 格式原图）、definition.json（PC 同格式定义）、last_values.json（表单值）
 * - 索引：filesDir/workflows/index.json，{"workflows":[{id, display_name, source, imported_at}],
 *   "builtin_seed_version": N（r37，可选，旧文件缺省当 0），
 *   "deleted_builtins": [id...]（r38 E3，可选，旧文件缺省空；被删内置的墓碑，播种跳过）}
 * - id 用 UUID，同名文件可以导两次；删除是物理删除。
 * 读写全部走 locked（IO 线程 + 互斥锁），与 PromptLibraryRepo 同一写法。
 */
class WorkflowStore(private val ctx: Context) {

    private val dir get() = File(ctx.filesDir, "workflows")
    private val indexFile get() = File(dir, "index.json")
    private val mutex = Mutex()

    private val _workflows = MutableStateFlow<List<ImportedWorkflowMeta>>(emptyList())
    val workflows: StateFlow<List<ImportedWorkflowMeta>> = _workflows

    private var loaded = false

    /** 已播种到的内置种子版本（r37）：随 index.json 持久化，低于 SEED_VERSION 就迁移重灌。 */
    private var seedVersion = 0L

    /** r38 E3：被用户删掉的内置模板 id（墓碑）。随 index.json 顶层 deleted_builtins 持久化，播种跳过。 */
    private var tombstones: Set<String> = emptySet()

    /** r38 E3：墓碑的观察流——库页「已删除的内置模板」行与恢复弹窗据此刷新。 */
    private val _deletedBuiltins = MutableStateFlow<Set<String>>(emptySet())
    val deletedBuiltinsFlow: StateFlow<Set<String>> = _deletedBuiltins

    /** r38 E5：definition 被覆盖的 (id, 序号)；序号保证同一工作流连续两次编辑也能触发。 */
    private val _definitionEdits = MutableStateFlow<Pair<String, Long>?>(null)
    val definitionEdits: StateFlow<Pair<String, Long>?> = _definitionEdits
    private var editSeq = 0L

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val list = ArrayList<ImportedWorkflowMeta>()
        var parsed = false
        var hasTombField = false
        if (indexFile.isFile) {
            try {
                val root = JSONObject(indexFile.readText())
                val arr = root.optJSONArray("workflows")
                if (arr != null) {
                    seedVersion = root.optLong("builtin_seed_version", 0L)
                    val tombArr = root.optJSONArray("deleted_builtins")
                    if (tombArr != null) {
                        hasTombField = true
                        val tomb = LinkedHashSet<String>()
                        for (i in 0 until tombArr.length()) {
                            val v = tombArr.optString(i)
                            if (v.isNotBlank()) tomb.add(v)
                        }
                        tombstones = tomb
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id")
                        if (id.isBlank()) continue
                        list.add(
                            ImportedWorkflowMeta(
                                id = id,
                                displayName = o.optString("display_name", id),
                                source = o.optString("source", "api"),
                                importedAt = o.optLong("imported_at", 0L),
                            ),
                        )
                    }
                    parsed = true
                }
            } catch (_: Exception) {
            }
        }
        if (!parsed) {
            // index.json 缺失或损坏：扫 <id>/workflow.json + definition.json 恢复列表，
            // 避免目录还在、下次任何写操作 persistIndexLocked() 却把索引清空
            dir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                val id = d.name
                val wf = File(d, "workflow.json")
                val defFile = File(d, "definition.json")
                if (!wf.isFile || !defFile.isFile || list.any { it.id == id }) return@forEach
                val def = try {
                    JSONObject(defFile.readText())
                } catch (_: Exception) {
                    null
                }
                list.add(
                    ImportedWorkflowMeta(
                        id = id,
                        displayName = def?.optString("display_name")?.takeIf { it.isNotBlank() } ?: id,
                        source = "api",
                        importedAt = d.lastModified(),
                    ),
                )
            }
        }
        _workflows.value = list
        // r38 E3：老 index 墓碑推断（必须在播种判定之前，详见 [inferTombstones]）。
        if (!hasTombField) {
            val inferred = inferTombstones(_workflows.value.map { it.id }.toSet(), seedVersion, hasTombField)
            if (inferred.isNotEmpty()) {
                tombstones = inferred
                persistIndexLocked()
            }
        }
        _deletedBuiltins.value = tombstones
        // 空列表 = 首次安装或用户删光了：自动播种内置模板（r35）。
        // 只在装填时判一次，会话内删光不会立刻重现，下次启动再补。
        // r37 迁移：种子版本落后于资产时也要跑一次播种（重灌存量内置条目），跑完写回版本号。
        // r38 E3：墓碑里的内置不播种（删了就不回来）。
        if (_workflows.value.isEmpty() || seedVersion < BuiltinTemplates.SEED_VERSION) seedBuiltinsLocked()
    }

    private fun persistIndexLocked() {
        dir.mkdirs()
        val arr = JSONArray()
        for (m in _workflows.value) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("display_name", m.displayName)
                    .put("source", m.source)
                    .put("imported_at", m.importedAt),
            )
        }
        val tomb = JSONArray()
        for (id in tombstones) tomb.put(id)
        indexFile.writeText(
            JSONObject()
                .put("workflows", arr)
                .put("builtin_seed_version", seedVersion)
                .put("deleted_builtins", tomb)
                .toString(),
        )
    }

    private fun wfDir(id: String) = File(dir, id)

    /** 导入：写入三件套并登记索引，返回新 id。调用方已完成校验。 */
    suspend fun import(
        workflowJson: JSONObject,
        definitionJson: JSONObject,
        source: String,
        displayName: String,
    ): String = locked {
        ensureLoadedLocked()
        val id = UUID.randomUUID().toString()
        val d = wfDir(id)
        d.mkdirs()
        File(d, "workflow.json").writeText(workflowJson.toString())
        File(d, "definition.json").writeText(definitionJson.toString())
        _workflows.value = _workflows.value + ImportedWorkflowMeta(
            id = id,
            displayName = displayName,
            source = source,
            importedAt = System.currentTimeMillis(),
        )
        persistIndexLocked()
        id
    }

    /**
     * 内置模板播种（r35）/ 存量迁移重灌（r37）：走导入同一条识别链 inferSpecs → buildDefinition（高置信度默认勾选），
     * 按固定 id 去重，缺哪条补哪条；单条资产缺失/损坏不拦其余模板。调用方必须已持锁。
     * r37 起看种子版本号：SEED_VERSION 涨了之后，已存在的固定 id 内置条目从资产重灌一次
     * （workflow.json + definition.json），并清掉 last_values 里模型槽的空串值让新默认浮出来；
     * 副本（新 UUID）与用户自建按 id 天然隔离，永不触碰。
     */
    private fun seedBuiltinsLocked() {
        var changed = 0
        for (t in BuiltinTemplates.all) {
            // r38 E3：用户删过的内置（墓碑）不播种，删了就不回来
            if (t.id in tombstones) continue
            val exists = _workflows.value.any { it.id == t.id }
            if (exists && seedVersion >= BuiltinTemplates.SEED_VERSION) continue
            if (installBuiltinLocked(t)) changed++
        }
        if (changed > 0) {
            seedVersion = BuiltinTemplates.SEED_VERSION
            persistIndexLocked()
        }
    }

    /**
     * r38 E3：把一条内置模板资产落盘建索引（播种与单个恢复共用）。
     * 已存在同 id 条目 → 走 r37 重灌分支（重写文件 + 清 last_values 空模型槽）；
     * 不存在 → 新建索引条目。资产缺失/损坏返回 false（单条不拦其余）。调用方必须已持锁。
     */
    private fun installBuiltinLocked(t: BuiltinTemplates.Entry): Boolean {
        val (wf, def) = builtinAssetPairLocked(t) ?: return false
        val d = wfDir(t.id)
        d.mkdirs()
        File(d, "workflow.json").writeText(wf.toString())
        File(d, "definition.json").writeText(def.toString())
        val exists = _workflows.value.any { it.id == t.id }
        if (exists) {
            stripBlankModelValuesLocked(t.id, def)
        } else {
            _workflows.value = _workflows.value + ImportedWorkflowMeta(
                id = t.id,
                displayName = t.displayName,
                source = BuiltinTemplates.SOURCE,
                importedAt = System.currentTimeMillis(),
            )
        }
        return true
    }

    /** 读内置资产并走导入同一条识别链；资产缺失/损坏返回 null（单条不拦其余）。调用方必须已持锁。 */
    private fun builtinAssetPairLocked(t: BuiltinTemplates.Entry): Pair<JSONObject, JSONObject>? {
        val wf = try {
            JSONObject(
                ctx.assets.open("${BuiltinTemplates.DIR}/${t.asset}").bufferedReader().use { it.readText() },
            )
        } catch (_: Exception) {
            return null
        }
        val checked = inferSpecs(wf).filter { it.spec.optBoolean("enabled") }.map { it.spec }
        val def = buildDefinition(
            displayName = t.displayName,
            workflowFileName = t.asset,
            outputNode = pickOutputNode(wf),
            orderedSpecs = checked,
            outputKind = inferOutputKind(wf),
        )
        return wf to def
    }

    /**
     * 迁移（r37）：重灌后把 last_values 里「model 槽且值为空串」的键删掉，让资产新预填的
     * 官方默认名浮出来；用户已选的非空模型值不动。last_values 缺失/损坏静默跳过（读取侧
     * 本就回默认值）；原子写对齐 saveValues。
     */
    private fun stripBlankModelValuesLocked(id: String, def: JSONObject) {
        val modelKeys = HashSet<String>()
        val specs = def.optJSONArray("user_facing_inputs") ?: return
        for (i in 0 until specs.length()) {
            val s = specs.optJSONObject(i) ?: continue
            if (Specs.type(s) == "model") modelKeys.add(Specs.key(s))
        }
        if (modelKeys.isEmpty()) return
        val d = wfDir(id)
        val f = File(d, "last_values.json")
        if (!f.isFile) return
        try {
            val lv = JSONObject(f.readText())
            val values = lv.optJSONObject("values") ?: return
            val stale = modelKeys.filter { values.has(it) && values.optString(it).isEmpty() }
            if (stale.isEmpty()) return
            for (k in stale) values.remove(k)
            val tmp = File(d, "last_values.json.tmp")
            tmp.writeText(lv.toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * r38 E3：当前在墓碑里的内置模板（即被用户删掉的），按内置清单固定顺序返回。
     * 库页「已删除的内置模板」恢复弹窗用。
     */
    suspend fun deletedBuiltins(): List<BuiltinTemplates.Entry> = locked {
        ensureLoadedLocked()
        BuiltinTemplates.all.filter { it.id in tombstones }
    }

    /**
     * r38 E3：单个恢复被删的内置模板：先从资产重灌（失败不动墓碑），
     * 成功后移出墓碑并持久化。返回 true = 确实恢复了一条。
     */
    suspend fun restoreBuiltin(id: String): Boolean = locked {
        ensureLoadedLocked()
        if (id !in tombstones) return@locked false
        val t = BuiltinTemplates.all.firstOrNull { it.id == id } ?: return@locked false
        if (!installBuiltinLocked(t)) return@locked false
        tombstones = tombstones - id
        _deletedBuiltins.value = tombstones
        persistIndexLocked()
        true
    }

    suspend fun list(): List<ImportedWorkflowMeta> = locked {
        ensureLoadedLocked()
        _workflows.value
    }

    /** 含 file:image spec 的导入工作流（「用于图生图」目标列表）；数量小，每次现查可接受。 */
    suspend fun imageWorkflows(): List<ImportedWorkflowMeta> = locked {
        ensureLoadedLocked()
        _workflows.value.filter { m ->
            val def = try {
                readDefinitionLocked(m.id)
            } catch (_: ApiException) {
                null
            } ?: return@filter false
            val specs = def.optJSONArray("user_facing_inputs") ?: return@filter false
            for (i in 0 until specs.length()) {
                if (specs.optJSONObject(i)?.optString("type") == "file:image") return@filter true
            }
            false
        }
    }

    suspend fun labelOf(id: String?): String? = locked {
        ensureLoadedLocked()
        _workflows.value.firstOrNull { it.id == id }?.displayName
    }

    /** r38：按 id 取索引条目（不存在返回 null）；调用方要用 source 判定显示名本地化时用这个。 */
    suspend fun metaOf(id: String): ImportedWorkflowMeta? = locked {
        ensureLoadedLocked()
        _workflows.value.firstOrNull { it.id == id }
    }

    /** 工作流 + 定义一起读；不存在（已删除）返回 null。 */
    suspend fun get(id: String): Pair<JSONObject, JSONObject>? = locked {
        val d = wfDir(id)
        val wf = File(d, "workflow.json")
        val def = File(d, "definition.json")
        if (!wf.isFile || !def.isFile) return@locked null
        try {
            JSONObject(wf.readText()) to JSONObject(def.readText())
        } catch (e: Exception) {
            throw ApiException(ctx.str(R.string.err_wf_corrupt, e.message ?: ctx.str(R.string.err_json_parse)), network = false)
        }
    }

    suspend fun definition(id: String): JSONObject? = locked {
        readDefinitionLocked(id)
    }

    private fun readDefinitionLocked(id: String): JSONObject? {
        val f = File(wfDir(id), "definition.json")
        if (!f.isFile) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            throw ApiException(ctx.str(R.string.err_def_corrupt, e.message ?: ctx.str(R.string.err_json_parse)), network = false)
        }
    }

    suspend fun lastValues(id: String): JSONObject = locked {
        val f = File(wfDir(id), "last_values.json")
        if (!f.isFile) return@locked JSONObject()
        try {
            JSONObject(f.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    suspend fun saveValues(id: String, values: JSONObject) = locked {
        val d = wfDir(id)
        if (!File(d, "definition.json").isFile) return@locked
        d.mkdirs()
        // 原子写（对齐 Transfer.downloadOnce）：先写临时文件再 rename 替换，
        // 写盘中途进程被杀也不会留下截断/损坏的 last_values.json（读失败会静默回全默认值）
        val dest = File(d, "last_values.json")
        val tmp = File(d, "last_values.json.tmp")
        tmp.writeText(values.toString())
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * r38 E5：编辑参数保存——只覆盖该 id 的 definition.json（原子写，同 saveValues 的 tmp+rename），
     * workflow.json / id / source 不动；index 的 display_name 同步为新名；
     * last_values 的 values 只保留 key 仍在新 definition 里的条目（键 = Specs.key，
     * 与 CustomModel 读写 last_values 同一规则），其余删除后原子写回，新增参数从默认值开始。
     * 返回 false = 该 id 不存在。
     */
    suspend fun updateDefinition(id: String, def: JSONObject): Boolean = locked {
        ensureLoadedLocked()
        val d = wfDir(id)
        val dest = File(d, "definition.json")
        if (!dest.isFile) return@locked false
        val tmp = File(d, "definition.json.tmp")
        tmp.writeText(def.toString())
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val newName = def.optString("display_name").trim()
        if (newName.isNotBlank()) {
            _workflows.value = _workflows.value.map {
                if (it.id == id) it.copy(displayName = newName) else it
            }
            persistIndexLocked()
        }
        filterLastValuesLocked(id, def)
        _definitionEdits.value = id to ++editSeq
        true
    }

    /**
     * r38 E5：last_values 的 values 只留新 definition 还存在的 key，其余删除后原子写回。
     * 其余顶层键（sizes/random/batch/kwb_* 等）不是定义参数，不动；文件缺失/损坏静默跳过。
     */
    private fun filterLastValuesLocked(id: String, def: JSONObject) {
        val keep = HashSet<String>()
        val specs = def.optJSONArray("user_facing_inputs") ?: return
        for (i in 0 until specs.length()) {
            val s = specs.optJSONObject(i) ?: continue
            keep.add(Specs.key(s))
        }
        val d = wfDir(id)
        val f = File(d, "last_values.json")
        if (!f.isFile) return
        try {
            val lv = JSONObject(f.readText())
            val values = lv.optJSONObject("values") ?: return
            val stale = values.keys().asSequence().filter { it !in keep }.toList()
            if (stale.isEmpty()) return
            for (k in stale) values.remove(k)
            val tmp = File(d, "last_values.json.tmp")
            tmp.writeText(lv.toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    suspend fun rename(id: String, newName: String) = locked {
        ensureLoadedLocked()
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return@locked
        // 内置模板只读（r35）：不允许改名，改副本
        if (_workflows.value.firstOrNull { it.id == id }?.source == BuiltinTemplates.SOURCE) return@locked
        _workflows.value = _workflows.value.map {
            if (it.id == id) it.copy(displayName = trimmed) else it
        }
        persistIndexLocked()
    }

    /** 删除工作流（含内置）。删内置记墓碑（r38 E3），可单个恢复；返回 true = 确实删了一条。 */
    suspend fun delete(id: String): Boolean = locked {
        ensureLoadedLocked()
        val target = _workflows.value.firstOrNull { it.id == id } ?: return@locked false
        _workflows.value = _workflows.value - target
        if (target.source == BuiltinTemplates.SOURCE && BuiltinTemplates.all.any { it.id == target.id }) {
            tombstones = tombstones + target.id
            _deletedBuiltins.value = tombstones
        }
        persistIndexLocked()
        wfDir(id).deleteRecursively()
        true
    }
}

/**
 * r38 E3：老 index（没有 deleted_builtins 字段）的墓碑一次性推断（纯函数，可单测）：
 * - 已有墓碑字段 → 尊重现状，不推断（返回空集）；
 * - 从没播种过（seedVersion < 1）→ 不推断（返回空集）；
 * - 其余：内置清单里当前不在列表中的 id = 用户删过 → 全部入墓碑。
 * r38 E7：只看 `sinceSeed <= 存量 seedVersion` 的条目——存量版本里还没出现过的模板
 * （如从第 3 版才加的 Moss）不算「被删」，否则老设备升上来会永远不播种它。
 * 调用方只在 index 没有该字段时使用返回值。
 */
fun inferTombstones(present: Set<String>, seedVersion: Long, hasField: Boolean): Set<String> {
    if (hasField || seedVersion < 1) return emptySet()
    return BuiltinTemplates.all
        .filter { it.sinceSeed <= seedVersion && it.id !in present }
        .map { it.id }
        .toSet()
}
