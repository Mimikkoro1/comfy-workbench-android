package com.mie.kreaworkbench.ui.screens.library

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.comfy.NodeLookup
import com.mie.kreaworkbench.data.workflows.BuiltinTemplates
import com.mie.kreaworkbench.data.workflows.Candidate
import com.mie.kreaworkbench.data.workflows.FileKind
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.data.workflows.buildDefinition
import com.mie.kreaworkbench.data.workflows.classifyFile
import com.mie.kreaworkbench.data.workflows.imageSaverNodes
import com.mie.kreaworkbench.data.workflows.inferOutputKind
import com.mie.kreaworkbench.data.workflows.inferSpecs
import com.mie.kreaworkbench.data.workflows.outputKindOf
import com.mie.kreaworkbench.data.workflows.textOutputNodes
import com.mie.kreaworkbench.data.workflows.upgradeCombos
import com.mie.kreaworkbench.data.workflows.validateDefinition
import com.mie.kreaworkbench.data.workflows.videoOutputNodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 配置页里一个候选字段。spec 保留 runtime 字段，label/tier/勾选在 item 上。 */
data class WfItem(
    val spec: JSONObject,
    val checked: Boolean,
    val label: String,
    val tier: String,
    val nodeTitle: String,
    val confidence: String,
    val type: String,
    val unsupported: Boolean,
) {
    val nodeId: String get() = spec.optString("node_id")
    val preview: String
        get() = when (type) {
            "image_sizes" -> spec.optString("default")
            else -> when (val v = spec.opt("current_value")) {
                is JSONArray -> "连线引用"
                null -> ""
                else -> v.toString()
            }
        }
}

/** r38 E4/E5：配置页的数据来源——文件导入（原路 B）或库内已存工作流（复制并自定义 / 编辑参数）。 */
sealed interface Source {
    data class Uris(val uris: List<Uri>) : Source

    /** [mode]："copy" = 复制并自定义（保存成新工作流）；"edit" = 编辑参数（覆盖原工作流的 definition）。 */
    data class Stored(val id: String, val mode: String) : Source

    companion object {
        const val MODE_COPY = "copy"
        const val MODE_EDIT = "edit"
    }
}

/**
 * 路 B 配置页状态：读文件 → inferSpecs → 用户勾选/改名/排序/选 tier → 保存。
 * 保存时 default 已是工作流当前值（infer 时写入），去掉 runtime 字段生成 PC 同格式定义。
 * r38 E4/E5：构造参数从 uri 列表改为 [Source]，Stored 模式从库内工作流加载（用现有 definition
 * 预填勾选/名称/顺序），copy 保存成新工作流、edit 覆盖原 definition；页面 UI 不变。
 */
class WorkflowImportModel(app: Application, private val source: Source) {
    private val c = (app as KreaApp).container
    private val appContext: Application = app
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var workflowJson by mutableStateOf<JSONObject?>(null)
        private set
    var workflowFileName by mutableStateOf("")
        private set
    var displayName by mutableStateOf("")
    var items by mutableStateOf(emptyList<WfItem>())
        private set
    var outputNode by mutableStateOf("")
    var outputNodeChoices by mutableStateOf(emptyList<String>())
        private set
    var outputKind by mutableStateOf("image")
    var outputKindInferred by mutableStateOf("image")
        private set
    var saving by mutableStateOf(false)
        private set
    var problems by mutableStateOf<List<String>?>(null)
        private set
    var missingClasses by mutableStateOf<List<String>?>(null)
        private set
    var saved by mutableStateOf(false)
        private set
    var statusText by mutableStateOf(app.str(R.string.reading_workflow))
        private set
    private var pendingDef: JSONObject? = null
    /** 同一次导入（本配置页的读取 + 保存）复用，不跨导入。 */
    private val nodeCache = HashMap<String, NodeLookup>()
    /** 这一趟已经有请求失败：剩下的 class 不再打，避免弱网把每个节点都等到超时。 */
    private var nodeProbeStopped = false

    init {
        scope.launch {
            try {
                when (val src = source) {
                    is Source.Uris -> loadFromUris(src.uris)
                    is Source.Stored -> loadStored(src)
                }
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
                if (!saving) statusText = ""
            }
        }
    }

    private suspend fun loadFromUris(uris: List<Uri>) {
        val parsed = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val name = queryDisplayName(appContext, uri)
                val text = readCapped(appContext, uri, 20L * 1024 * 1024)
                name to classifyFile(name, text)
            }
        }
        val flow = parsed.firstOrNull { it.second.first == FileKind.API_WORKFLOW }
            ?: throw IllegalStateException(appContext.str(R.string.no_api_workflow))
        val wf = flow.second.second
        workflowJson = wf
        workflowFileName = flow.first
        displayName = flow.first.removeSuffix(".json")

        // 出图节点选择：SaveImage / SaveImageAdvanced / PreviewImage（含模糊兜底）
        val savers = imageSaverNodes(wf)
        outputNodeChoices = savers
        outputNode = savers.firstOrNull() ?: ""

        // 输出类型（round6）：按输出节点推断（video > text > image），路 B 可手改
        outputKindInferred = inferOutputKind(wf)
        outputKind = outputKindInferred
        if (outputKind == "text") outputNode = textOutputNodes(wf).firstOrNull() ?: outputNode
        if (outputKind == "video") outputNode = videoOutputNodes(wf).firstOrNull() ?: outputNode

        // 路 B 组合升级：只拉 text 候选的 /object_info/<class>，失败静默跳过、不重试。
        val candidates = inferSpecs(wf)
        statusText = appContext.str(R.string.checking_nodes)
        val upgraded = upgradeCombos(candidates, comboInfo(candidates))
        items = upgraded.map { cand -> cand.toItem() }
    }

    /**
     * r38 E4/E5：从库内已存工作流加载：workflow.json 走导入同一条推断链拿候选，
     * 再用现有 definition 预填——在 definition 里的项勾选、按 definition 顺序排前，
     * label/tier 取 definition 值；definition 有但推断不出的（PC 成对导入/手写定义）原样保留，
     * 不丢；其余候选不勾选排后。
     */
    private suspend fun loadStored(src: Source.Stored) {
        val id = src.id
        val (wf, def) = c.workflowStore.get(id)
            ?: throw IllegalStateException(appContext.str(R.string.err_workflow_deleted))
        workflowJson = wf
        // 源文件名取 definition 的 workflow_file，没有就用 id
        workflowFileName = def.optString("workflow_file").ifBlank { id }

        val meta = c.workflowStore.metaOf(id)
        val storedName = def.optString("display_name").ifBlank { meta?.displayName ?: id }
        displayName = when (src.mode) {
            // copy：副本名用当前语言的内置名拼后缀（与 WorkflowStore.copy 同款），落盘后固定
            Source.MODE_COPY -> {
                val base = if (meta?.source == BuiltinTemplates.SOURCE) {
                    BuiltinTemplates.nameResOf(id)?.let { appContext.str(it) } ?: storedName
                } else {
                    storedName
                }
                appContext.str(R.string.copy_name_suffix, base)
            }
            else -> storedName
        }

        // 出图节点/输出类型：优先取 definition 已有值，缺了再走推断
        val savers = imageSaverNodes(wf)
        outputNodeChoices = savers
        outputKindInferred = inferOutputKind(wf)
        outputKind = outputKindOf(def)
        if (outputKind == "text") {
            outputNode = def.optString("kwb_output_node").ifBlank { textOutputNodes(wf).firstOrNull() ?: "" }
        } else if (outputKind == "video") {
            outputNode = def.optString("kwb_output_node").ifBlank { videoOutputNodes(wf).firstOrNull() ?: "" }
        } else {
            outputNode = def.optString("kwb_output_node").ifBlank { savers.firstOrNull() ?: "" }
        }

        // 候选：与导入同一条链（组合升级，含节点探测）
        val candidates = inferSpecs(wf)
        statusText = appContext.str(R.string.checking_nodes)
        val upgraded = upgradeCombos(candidates, comboInfo(candidates))

        // 现有 definition 按 key（node_id|field）对齐预填
        val defByKey = LinkedHashMap<String, JSONObject>()
        val defSpecs = def.optJSONArray("user_facing_inputs")
        if (defSpecs != null) {
            for (i in 0 until defSpecs.length()) {
                val s = defSpecs.optJSONObject(i) ?: continue
                val k = Specs.key(s)
                if (k.isNotBlank() && !defByKey.containsKey(k)) defByKey[k] = s
            }
        }
        val candByKey = HashMap<String, Candidate>()
        for (cand in upgraded) {
            val k = Specs.key(cand.spec)
            if (k.isNotBlank() && !candByKey.containsKey(k)) candByKey[k] = cand
        }
        val merged = ArrayList<WfItem>()
        val usedKeys = HashSet<String>()
        // ① definition 顺序在前：推断能对上的用推断 spec（含当前值）+ definition 的 label/tier，勾选
        for ((k, defSpec) in defByKey) {
            val cand = candByKey[k]
            if (cand != null) {
                val spec = JSONObject(cand.spec.toString())
                if (defSpec.optString("label").isNotBlank()) spec.put("label", defSpec.optString("label"))
                if (defSpec.optString("tier").isNotBlank()) spec.put("tier", defSpec.optString("tier"))
                spec.put("enabled", true)
                merged.add(cand.copy(spec = spec).toItem())
            } else {
                // ② 推断不出的 definition 项：原样保留，勾选，不丢
                val spec = JSONObject(defSpec.toString())
                spec.put("enabled", true)
                merged.add(Candidate(spec, spec.optString("node_title")).toItem())
            }
            usedKeys.add(k)
        }
        // ③ 不在 definition 里的候选：不勾选，排后
        for (cand in upgraded) {
            val k = Specs.key(cand.spec)
            if (k in usedKeys) continue
            val spec = JSONObject(cand.spec.toString())
            spec.put("enabled", false)
            merged.add(Candidate(spec, cand.nodeName).toItem())
        }
        items = merged
    }

    /** 本次导入内按 class 取节点定义。失败后本趟不再发后续请求。 */
    private suspend fun lookupCached(cls: String): NodeLookup {
        if (cls.isBlank()) return NodeLookup(node = null, failed = false)
        nodeCache[cls]?.let { return it }
        if (nodeProbeStopped) return NodeLookup(node = null, failed = true)
        val got = c.api.objectInfoClass(cls)
        nodeCache[cls] = got
        if (got.failed) nodeProbeStopped = true
        return got
    }

    /** text 候选需要的节点定义。一个都没拿到（网络失败）返回 null，upgradeCombos 整段跳过。 */
    private suspend fun comboInfo(candidates: List<Candidate>): JSONObject? {
        val classes = candidates.mapNotNull { cand ->
            val spec = cand.spec
            if (spec.optString("type") != "text") return@mapNotNull null
            spec.optString("class_type").takeIf { it.isNotBlank() }
        }.distinct()
        if (classes.isEmpty()) return JSONObject()
        val bag = JSONObject()
        for (cls in classes) {
            val got = lookupCached(cls)
            if (got.failed) return if (bag.length() == 0) null else bag
            got.node?.let { bag.put(cls, it) }
        }
        return bag
    }

    /**
     * 保存前缺节点检查。复用上面的 cache。
     * 一个成功结果都没有 → null（无法检查，不拦）。否则返回已确认缺失的 class。
     */
    private suspend fun missingCached(classTypes: List<String>): List<String>? {
        val missing = ArrayList<String>()
        var anyOk = false
        for (cls in classTypes) {
            if (cls.isBlank()) continue
            val got = lookupCached(cls)
            if (got.failed) return if (anyOk) missing else null
            anyOk = true
            if (got.node == null) missing.add(cls)
        }
        return if (anyOk) missing else null
    }

    private fun Candidate.toItem(): WfItem {
        val type = spec.optString("type")
        val confidence = spec.optString("confidence", "low")
        return WfItem(
            spec = spec,
            checked = spec.optBoolean("enabled") && type != "file:video",
            label = spec.optString("label"),
            tier = spec.optString("tier", "basic").let { if (type == "model") "model" else it },
            nodeTitle = spec.optString("node_title"),
            confidence = confidence,
            type = type,
            unsupported = type == "file:video",
        )
    }

    fun toggleChecked(index: Int) {
        val it0 = items.getOrNull(index) ?: return
        if (it0.unsupported) return
        items = items.toMutableList().apply {
            set(index, it0.copy(checked = !it0.checked))
        }
    }

    fun rename(index: Int, label: String) {
        val it0 = items.getOrNull(index) ?: return
        items = items.toMutableList().apply {
            set(index, it0.copy(label = label))
        }
    }

    fun setTier(index: Int, tier: String) {
        val it0 = items.getOrNull(index) ?: return
        if (it0.type == "model") return
        items = items.toMutableList().apply {
            set(index, it0.copy(tier = tier))
        }
    }

    /** 上下移动：只对已勾选项排序——与最近的已勾选项交换位置。 */
    fun move(index: Int, delta: Int) {
        val list = items.toMutableList()
        var target = index + delta
        while (target in list.indices && !list[target].checked) target += delta
        if (target !in list.indices || target == index) return
        list[index] = list[target].also { list[target] = list[index] }
        items = list
    }

    /** 保存：本地校验 → 可选 /object_info 缺节点提醒（只提醒不拦）→ 落盘。
     *  r38 E4：Stored copy 模式与文件导入同路——import 成新 UUID 普通工作流，可改名/可删/可再编辑；原工作流不动。 */
    fun save() {
        val wf = workflowJson ?: return
        if (saving) return
        scope.launch {
            saving = true
            try {
                val checked = items.filter { it.checked }
                val specs = checked.map { item ->
                    val spec = JSONObject(item.spec.toString())
                    spec.put("label", item.label.trim().ifBlank { Specs.field(spec) })
                    if (item.type == "model") spec.put("tier", "model") else spec.put("tier", item.tier)
                    spec
                }
                val name = displayName.trim().ifBlank { workflowFileName.removeSuffix(".json") }
                val def = buildDefinition(name, workflowFileName, outputNode, specs, outputKind)
                val localProblems = validateDefinition(def, wf)
                if (localProblems.isNotEmpty()) {
                    problems = localProblems
                    return@launch
                }
                // 可选检查：按 class 查本机有没有这些节点（只提醒不拦）。失败不重试。
                statusText = appContext.str(R.string.checking_nodes)
                val classTypes = ArrayList<String>()
                val keys = wf.keys()
                while (keys.hasNext()) {
                    val cls = wf.optJSONObject(keys.next())?.optString("class_type").orEmpty()
                    if (cls.isNotBlank()) classTypes.add(cls)
                }
                val missing = missingCached(classTypes.distinct())
                if (!missing.isNullOrEmpty()) {
                    pendingDef = def
                    missingClasses = missing
                    return@launch
                }
                doSave(def, wf)
            } catch (e: Exception) {
                problems = listOf(e.message ?: e.javaClass.simpleName)
            } finally {
                saving = false
                statusText = ""
            }
        }
    }

    /** 缺节点提醒里选「仍然保存」。 */
    fun saveIgnoringMissing() {
        val wf = workflowJson ?: return
        val def = pendingDef ?: return
        missingClasses = null
        pendingDef = null
        scope.launch {
            saving = true
            try {
                doSave(def, wf)
            } finally {
                saving = false
            }
        }
    }

    fun dismissMissing() {
        missingClasses = null
        pendingDef = null
    }

    fun dismissProblems() {
        problems = null
    }

    private suspend fun doSave(def: JSONObject, wf: JSONObject) {
        // r38 E4/E5：copy / 文件导入 → import 成新工作流；edit → 只覆盖原工作流的 definition。
        val stored = source as? Source.Stored
        if (stored != null && stored.mode == Source.MODE_EDIT) {
            c.workflowStore.updateDefinition(stored.id, def)
        } else {
            c.workflowStore.import(wf, def, source = "api", displayName = def.optString("display_name"))
        }
        saved = true
    }
}
