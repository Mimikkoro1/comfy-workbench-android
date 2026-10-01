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
 * - 索引：filesDir/workflows/index.json，{"workflows":[{id, display_name, source, imported_at}]}
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

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val list = ArrayList<ImportedWorkflowMeta>()
        var parsed = false
        if (indexFile.isFile) {
            try {
                val arr = JSONObject(indexFile.readText()).optJSONArray("workflows")
                if (arr != null) {
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
        indexFile.writeText(JSONObject().put("workflows", arr).toString())
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

    suspend fun rename(id: String, newName: String) = locked {
        ensureLoadedLocked()
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return@locked
        _workflows.value = _workflows.value.map {
            if (it.id == id) it.copy(displayName = trimmed) else it
        }
        persistIndexLocked()
    }

    /** 返回 true 表示删除的确实是导入工作流；内置 id 一律返回 false。 */
    suspend fun delete(id: String): Boolean = locked {
        ensureLoadedLocked()
        val target = _workflows.value.firstOrNull { it.id == id } ?: return@locked false
        _workflows.value = _workflows.value - target
        persistIndexLocked()
        wfDir(id).deleteRecursively()
        true
    }
}
