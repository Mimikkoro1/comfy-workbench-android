package com.mie.kreaworkbench.ui.screens.library

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.known
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.library.ImportResult
import com.mie.kreaworkbench.data.library.LibraryEditorLoad
import com.mie.kreaworkbench.data.library.LibraryMeta
import com.mie.kreaworkbench.data.library.SaveTxtResult
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.data.workflows.FileKind
import com.mie.kreaworkbench.data.workflows.ImportParseException
import com.mie.kreaworkbench.data.workflows.classifyFile
import com.mie.kreaworkbench.data.workflows.exportDefinition
import com.mie.kreaworkbench.data.workflows.safeExportName
import com.mie.kreaworkbench.data.workflows.validateDefinition
import com.mie.kreaworkbench.data.workflows.withAutoOutputNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 工作流导入的结果弹窗。 */
data class WfImportOutcome(val ok: Boolean, val title: String, val detail: String)

/** 路 A 文件名不一致的一次性确认。 */
data class NameMismatchAsk(
    val displayName: String,
    val expected: String,
    val actual: String,
    val defJson: JSONObject,
    val wfJson: JSONObject,
)

class LibraryModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KreaApp).container
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())
    val libraries = c.library.libraries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<LibraryMeta>())
    val currentId = c.library.currentId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null as String?)
    val imported = c.workflowStore.workflows

    var importing by mutableStateOf(false)
        private set
    var result by mutableStateOf<ImportResult?>(null)

    var wfImporting by mutableStateOf(false)
        private set
    var wfOutcome by mutableStateOf<WfImportOutcome?>(null)
    var nameMismatch by mutableStateOf<NameMismatchAsk?>(null)
        private set

    /** SAF 选完文件后走这里：导入在后台协程做，importing=true 供界面转圈，不卡 UI。 */
    fun importLibrary(uri: Uri) {
        if (importing) return
        viewModelScope.launch {
            importing = true
            try {
                result = c.library.importLibrary(uri)
            } catch (e: Exception) {
                result = ImportResult(ok = false, error = e.message ?: e.javaClass.simpleName)
            } finally {
                importing = false
            }
        }
    }

    /**
     * 工作流导入分流（按内容判型，不按文件名）：
     * 1 定义 + 1 API 工作流 → 路 A 直接校验落库；只有 1 个 API 工作流 → 路 B 跳配置页（onPathB）。
     */
    fun importWorkflows(uris: List<Uri>, onPathB: (List<Uri>) -> Unit) {
        if (wfImporting || uris.isEmpty()) return
        val app = getApplication<Application>()
        if (uris.size > 2) {
            wfOutcome = WfImportOutcome(false, app.str(R.string.import_failed), app.str(R.string.import_pick_limit))
            return
        }
        viewModelScope.launch {
            wfImporting = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val name = queryDisplayName(app, uri)
                        val text = readCapped(app, uri, 20L * 1024 * 1024)
                        Triple(name, uri, classifyFile(name, text))
                    }
                }
                val defs = parsed.filter { it.third.first == FileKind.DEFINITION }
                val flows = parsed.filter { it.third.first == FileKind.API_WORKFLOW }
                when {
                    parsed.any { it.third.first == FileKind.UI_WORKFLOW } ->
                        wfOutcome = WfImportOutcome(
                            false,
                            app.str(R.string.import_failed),
                            app.str(R.string.import_ui_format),
                        )
                    parsed.any { it.third.first == FileKind.UNKNOWN } -> {
                        val bad = parsed.first { it.third.first == FileKind.UNKNOWN }.first
                        wfOutcome = WfImportOutcome(false, app.str(R.string.import_failed), app.str(R.string.import_unknown, bad))
                    }
                    defs.size == 1 && flows.size == 1 -> {
                        val (defName, _, defPair) = defs[0]
                        val (wfName, _, wfPair) = flows[0]
                        tryPathA(defPair.second, wfPair.second, defName, wfName)
                    }
                    defs.isEmpty() && flows.size == 1 -> onPathB(uris)
                    defs.isNotEmpty() && flows.isEmpty() -> {
                        val expected = defs[0].third.second.optString("workflow_file").ifBlank { "<workflow_file>" }
                        wfOutcome = WfImportOutcome(
                            false,
                            app.str(R.string.import_failed),
                            app.str(R.string.import_missing_wf, expected),
                        )
                    }
                    else -> wfOutcome = WfImportOutcome(
                        false,
                        app.str(R.string.import_failed),
                        app.str(R.string.import_combo, defs.size, flows.size),
                    )
                }
            } catch (e: ImportParseException) {
                wfOutcome = WfImportOutcome(false, app.str(R.string.import_failed), e.message ?: app.str(R.string.import_parse))
            } catch (e: Exception) {
                wfOutcome = WfImportOutcome(false, app.str(R.string.import_failed), e.message ?: e.javaClass.simpleName)
            } finally {
                wfImporting = false
            }
        }
    }

    private suspend fun tryPathA(defJson: JSONObject, wfJson: JSONObject, defName: String, wfName: String) {
        // 定义里的 workflow_file 与所选工作流文件名不一致时只弹一次确认，不拦死
        val expected = defJson.optString("workflow_file")
        if (expected.isNotBlank() && expected != wfName) {
            nameMismatch = NameMismatchAsk(
                displayName = defJson.optString("display_name").ifBlank { defName },
                expected = expected,
                actual = wfName,
                defJson = defJson,
                wfJson = wfJson,
            )
            return
        }
        finishPathA(defJson, wfJson)
    }

    fun answerNameMismatch(proceed: Boolean) {
        val ask = nameMismatch ?: return
        nameMismatch = null
        if (!proceed) return
        viewModelScope.launch {
            wfImporting = true
            try {
                finishPathA(ask.defJson, ask.wfJson)
            } finally {
                wfImporting = false
            }
        }
    }

    private suspend fun finishPathA(defJson: JSONObject, wfJson: JSONObject) {
        val app = getApplication<Application>()
        val problems = validateDefinition(defJson, wfJson)
        if (problems.isNotEmpty()) {
            wfOutcome = WfImportOutcome(false, app.str(R.string.validate_not_saved), problems.joinToString("\n") { "· $it" })
            return
        }
        val def = withAutoOutputNode(defJson, wfJson)
        val displayName = def.optString("display_name").ifBlank { "导入的工作流" }
        c.workflowStore.import(wfJson, def, source = "pc", displayName = displayName)
        wfOutcome = WfImportOutcome(true, app.str(R.string.import_done), app.str(R.string.import_done_detail, app.known(displayName)))
    }

    fun renameWorkflow(id: String, name: String) {
        viewModelScope.launch { c.workflowStore.rename(id, name) }
    }

    fun deleteWorkflow(id: String) {
        viewModelScope.launch {
            if (c.workflowStore.delete(id)) {
                // 删除的是当前工作流 → 置空进空状态（"" 是合法状态）
                if (c.settings.current().currentWorkflow == id) {
                    c.settings.update { it.copy(currentWorkflow = "") }
                }
            }
        }
    }

    /**
     * 导出（决策 4）：cacheDir/share 生成 <名字>.workflow.json（原样）与 <名字>.definition.json
     * （按 last_values.json 覆盖 default，即「当前保存后的参数」）两份文件，
     * 走 FileProvider + ACTION_SEND_MULTIPLE（application/json）系统分享。
     */
    fun exportWorkflow(id: String) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val name = c.workflowStore.labelOf(id) ?: return@launch
                val (wfJson, defJson) = c.workflowStore.get(id) ?: return@launch
                val saved = c.workflowStore.lastValues(id)
                val exportedDef = exportDefinition(defJson, saved)
                val base = safeExportName(name)
                val files = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "share").apply { mkdirs() }
                    // 清掉上一次的导出残留，分享面板只出现本次的两份
                    dir.listFiles()?.forEach { it.delete() }
                    val wfFile = File(dir, "$base.workflow.json")
                    val defFile = File(dir, "$base.definition.json")
                    wfFile.writeText(wfJson.toString())
                    defFile.writeText(exportedDef.toString())
                    listOf(wfFile, defFile)
                }
                val uris = ArrayList<Uri>(files.map {
                    FileProvider.getUriForFile(app, app.packageName + ".files", it)
                })
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/json"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, app.str(R.string.export_workflow_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(chooser)
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                val failed = getApplication<Application>().str(R.string.export_failed, err)
                wfOutcome = WfImportOutcome(false, failed, err)
            }
        }
    }

    fun setWorkflow(id: String) {
        viewModelScope.launch { c.settings.update { it.copy(currentWorkflow = id) } }
    }

    fun setCurrent(id: String) {
        viewModelScope.launch { c.library.setCurrent(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { c.library.delete(id) }
    }

    /** 可编辑库 id 集合（round8 2.3）：fmt=txt 且未超编辑器容量护栏。库列表变化后由界面触发重算。 */
    var editableIds by mutableStateOf<Set<String>>(emptySet())
        private set

    fun refreshEditable() {
        viewModelScope.launch {
            editableIds = try {
                c.library.editableLibraryIds()
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    /**
     * 导出（round8 2.1）：SAF CreateDocument 选好的目标 uri，格式由库 fmt 决定（在 Repo 内）。
     * err=null=成功；onDone 主线程回调，供界面 toast（成功/失败/取消三分支都在调用方）。
     */
    fun exportLibrary(lib: LibraryMeta, uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val err = try {
                c.library.exportLibrary(lib.id, uri)
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            onDone(err)
        }
    }

    /** txt 库编辑器装载（round8 2.3）。 */
    suspend fun loadEditor(libId: String): LibraryEditorLoad = c.library.loadEditor(libId)

    /** txt 库编辑保存（round8 2.3）。 */
    suspend fun saveTxtLibrary(libId: String, text: String): SaveTxtResult = c.library.saveTxtLibrary(libId, text)

    fun dismissResult() {
        result = null
    }

    fun dismissWfOutcome() {
        wfOutcome = null
    }
}

internal fun queryDisplayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val n = c.getString(0)
            if (!n.isNullOrBlank()) return n
        }
    }
    return uri.lastPathSegment ?: "未命名.json"
}

/** 流式读取文本并限制大小（超限抛 ImportParseException），不整文件读进内存再判断。 */
internal fun readCapped(ctx: Context, uri: Uri, capBytes: Long): String {
    val out = StringBuilder()
    val buf = CharArray(64 * 1024)
    var totalChars = 0L
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        (input as? java.io.FileInputStream)?.channel?.let { ch ->
            if (ch.size() > capBytes) throw ImportParseException(ctx.str(R.string.err_file_20mb))
        }
        input.reader(Charsets.UTF_8).buffered().use { reader ->
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                totalChars += n
                if (totalChars * 2 > capBytes) throw ImportParseException(ctx.str(R.string.err_file_20mb))
                out.append(buf, 0, n)
            }
        }
    } ?: throw ImportParseException(ctx.str(R.string.err_read_file))
    return out.toString().removePrefix("\uFEFF")
}
