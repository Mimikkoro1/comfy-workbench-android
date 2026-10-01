package com.mie.kreaworkbench.ui.screens.custom

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.buildCustomBody
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.data.db.JobRow
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.data.workflows.ImportedWorkflowMeta
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_720P
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_SOURCE
import com.mie.kreaworkbench.data.workflows.outputKindOf
import com.mie.kreaworkbench.data.workflows.pickOutputNode
import com.mie.kreaworkbench.startGenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 导入工作流的数据源 VM（生成页与「模型和采样」设置页共用同一实例，数据只有这一份）：
 * 按定义的 user_facing_inputs 生成控件，表单值持久化在 filesDir/workflows/<id>/last_values.json
 * （图片字段不存；抽卡筛选存私有键 kwb_draw_filter）。出图走 buildCustomBody → 现有提交/幂等/进度/取消链路。
 */
class CustomModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KreaApp).container
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())
    val live = c.engine.snapshot

    /** 抽卡可用性：当前提示词库存在且条目数 > 0。导入/删除/切库实时生效，UI 据此显隐抽卡相关控件。 */
    val hasPromptLibrary: StateFlow<Boolean> = combine(
        c.library.libraries,
        c.library.currentId,
    ) { libs, cur -> (libs.firstOrNull { it.id == cur }?.count ?: 0) > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var loading by mutableStateOf(true)
        private set
    var displayName by mutableStateOf("")
        private set
    var message by mutableStateOf("")
    var submitting by mutableStateOf(false)
    var drawingKey by mutableStateOf("")
    var results by mutableStateOf<List<ImageRow>>(emptyList())
    var gallery by mutableStateOf<List<ImageRow>>(emptyList())

    // 定义与工作流
    var specs by mutableStateOf(emptyList<JSONObject>())
        private set
    var outputNode by mutableStateOf("")
        private set
    var outputKind by mutableStateOf("image")
        private set
    private var workflowJson: JSONObject? = null
    private var boundId: String? = null

    // 表单值（key = "node|field"）
    var textValues by mutableStateOf(emptyMap<String, String>())
        private set
    var boolValues by mutableStateOf(emptyMap<String, Boolean>())
        private set
    var randomOn by mutableStateOf(emptyMap<String, Boolean>())
        private set
    var sizesSelected by mutableStateOf(emptyList<String>())
        private set
    var batchSize by mutableStateOf(1)
        private set

    // model 下拉选项：key → 本机 /object_info 拉到的列表（拉不到=空列表）
    var modelChoices by mutableStateOf(emptyMap<String, List<String>>())
        private set

    // kwb_combo select 的在线选项：key → /object_info 实时枚举（拉不到=空列表，退 definition.choices）
    var comboChoices by mutableStateOf(emptyMap<String, List<String>>())
        private set

    // 抽卡共享筛选（决策 2）：所有 prompt_pool 抽卡共用，存 last_values 私有键 kwb_draw_filter
    var drawCat by mutableStateOf("")
    var drawInc by mutableStateOf("")
    var drawExc by mutableStateOf("")
    var drawCategories by mutableStateOf(listOf("全部" to ""))

    // file:image（本版最多一个 spec）
    var sourcePath by mutableStateOf<String?>(null)
    var sourceUri by mutableStateOf<String?>(null)
    var srcW by mutableStateOf(0)
    var srcH by mutableStateOf(0)

    /** 单尺寸化（direct10c）：尺寸恒为一个，张数只由 batch 决定。 */
    fun sizeCount(): Int = 1

    /** 右上角下拉直切的数据源：WorkflowStore 的 meta 列表（与工作流/库页同一份，不另建存储）。 */
    val workflowList: StateFlow<List<ImportedWorkflowMeta>> = c.workflowStore.workflows

    /** 右上角下拉直切：只改 currentWorkflow，CustomScreen 因 workflowId 变化自动走 bind()（含刷盘与文本卡清理）。 */
    fun selectWorkflow(id: String) {
        viewModelScope.launch { c.settings.update { it.copy(currentWorkflow = id) } }
    }

    fun totalCount(): Int = sizeCount() * batchSize

    /** 主提示词：example_key=="prompt" 优先 → 第一个 prompt_pool → 第一个 text（负向除外）。 */
    fun mainPrompt(): String {
        val key = mainPromptKey() ?: return ""
        return textValues[key].orEmpty()
    }

    fun mainPromptKey(): String? {
        val byExample = specs.firstOrNull {
            (Specs.type(it) == "text" || Specs.type(it) == "prompt_pool") &&
                Specs.exampleKey(it) == "prompt" && !it.optBoolean("kwb_negative")
        }
        val pool = specs.firstOrNull { Specs.type(it) == "prompt_pool" && !it.optBoolean("kwb_negative") }
        val text = specs.firstOrNull { Specs.type(it) == "text" && !it.optBoolean("kwb_negative") }
        return (byExample ?: pool ?: text)?.let { Specs.key(it) }
    }

    /** 生成页/设置页按当前工作流绑定定义（两页共用同一 VM 实例，boundId 去重）；
     *  工作流已删除 → 提示后置空（回空状态，tab 随 currentWorkflow 切换）。 */
    fun bind(id: String) {
        if (boundId == id) return
        val previous = boundId
        // 编辑后 300ms 内切走：旧工作流的防抖落盘还挂着，先记下再取消。
        // 旧实现只 cancel 不刷盘，这次编辑静默丢失，restore 只能读到上次落盘的旧值（甚至全默认）。
        val pendingFlush = persistJob?.isActive == true
        persistJob?.cancel()
        boundId = id
        // 换绑时清掉上一个工作流残留的参考图选择（图片字段不持久化）
        sourcePath = null
        sourceUri = null
        viewModelScope.launch {
            // ① 挂起的编辑先刷盘到旧工作流（目标必须是旧 id，此时 boundId 已指向新工作流）；
            //    刷盘失败不拦换绑，只是丢这次编辑
            if (pendingFlush && previous != null) {
                try {
                    withContext(Dispatchers.IO) { persistNow(previous) }
                } catch (_: Exception) {
                }
            }
            // ② 文本卡保留到切换工作流（round7 fix2）：换绑即清空全部文本行。
            //    首次绑定（previous==null，含进程重启后恢复绑定）不清：重启后还没换过工作流的文本卡要保住。
            if (previous != null) {
                if (withContext(Dispatchers.IO) { c.db.purgeTextRows() } > 0) {
                    c.engine.revision.value = c.engine.revision.value + 1
                }
            }
            // ③ 再加载新工作流：restore 读到的一定是已把挂起编辑落盘后的 last_values.json
            loading = true
            try {
                val pair = c.workflowStore.get(id)
                if (pair == null) {
                    message = getApplication<Application>().str(R.string.err_workflow_deleted)
                    c.settings.update { it.copy(currentWorkflow = "") }
                    return@launch
                }
                val (wf, def) = pair
                workflowJson = wf
                displayName = c.workflowStore.labelOf(id) ?: id
                outputNode = def.optString("kwb_output_node").ifBlank { pickOutputNode(wf) }
                outputKind = outputKindOf(def)
                specs = buildList {
                    val arr = def.optJSONArray("user_facing_inputs")
                    if (arr != null) for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(it) }
                    }
                }
                restore(id)
                loadChoices()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    private suspend fun restore(id: String) {
        val saved = c.workflowStore.lastValues(id)
        batchSize = saved.optInt("batch", c.settings.current().batch).coerceIn(1, 8)
        val values = saved.optJSONObject("values") ?: JSONObject()
        sizesSelected = saved.optJSONArray("sizes")?.let { arr ->
            buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { s -> s.isNotBlank() }?.let { add(it) } }
        }.orEmpty()
        // direct10c 起尺寸单选：旧数据存过多个时只取第一个，不崩、不空白
        if (sizesSelected.size > 1) sizesSelected = listOf(sizesSelected.first())
        val randoms = saved.optJSONObject("random") ?: JSONObject()
        // 抽卡筛选的私有键：不进 values、不参与填参，只在这里恢复
        val filter = saved.optJSONObject("kwb_draw_filter")
        drawCat = filter?.optString("cat").orEmpty()
        drawInc = filter?.optString("inc").orEmpty()
        drawExc = filter?.optString("exc").orEmpty()

        val t = HashMap<String, String>()
        val b = HashMap<String, Boolean>()
        val r = HashMap<String, Boolean>()
        for (spec in specs) {
            val key = Specs.key(spec)
            when (Specs.type(spec)) {
                "text", "prompt_pool", "select", "model" ->
                    t[key] = values.optString(key, Specs.defaultText(spec))
                "int", "float", "int_random" -> {
                    val raw = values.opt(key)
                    t[key] = when (raw) {
                        is Number -> trimNum(raw.toDouble())
                        is String -> raw
                        else -> trimNum(Specs.defaultNumber(spec))
                    }
                    if (Specs.type(spec) == "int_random") {
                        r[key] = if (randoms.has(key)) randoms.optBoolean(key) else Specs.randomDefault(spec)
                    }
                }
                "bool" -> b[key] = if (values.has(key)) values.optBoolean(key) else Specs.defaultBool(spec)
                "image_sizes" -> if (sizesSelected.isEmpty()) {
                    // definition 的 default 也可能给多个，同样只取第一个
                    sizesSelected = Specs.defaultSizes(spec).firstOrNull()?.let { listOf(it) } ?: emptyList()
                }
            }
        }
        // 视频没有存过尺寸时默认 720P。已保存的宽高或 source 不动；定义里的 source 也保留。
        if (outputKind == "video" && !saved.has("sizes") && sizesSelected.singleOrNull() != VIDEO_SIZE_SOURCE) {
            sizesSelected = listOf(VIDEO_SIZE_720P)
        }
        textValues = t
        boolValues = b
        randomOn = r
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** model spec 的下拉选项 + kwb_combo select 的在线选项（只读 GET）；失败留空，UI 走兜底。 */
    private suspend fun loadChoices() {
        val wf = workflowJson ?: return
        for (spec in specs) {
            val key = Specs.key(spec)
            when (Specs.type(spec)) {
                "model" -> {
                    val cls = wf.optJSONObject(Specs.nodeId(spec))?.optString("class_type").orEmpty()
                    if (cls.isBlank()) {
                        modelChoices = modelChoices + (key to emptyList())
                        continue
                    }
                    try {
                        val choices = c.api.modelChoices(cls, Specs.field(spec))
                        modelChoices = modelChoices + (key to choices)
                    } catch (_: Exception) {
                        modelChoices = modelChoices + (key to emptyList())
                    }
                }
                "select" -> {
                    val combo = spec.optString("kwb_combo")
                    if (combo.isBlank()) continue
                    // class_type 自身可能带「|Mie」后缀（如 Florence2DescribeImage|Mie|task），
                    // 必须从右往左切：最后一段是字段名，其余全是 class
                    val cls = combo.substringBeforeLast('|')
                    val field = combo.substringAfterLast('|', "")
                    if (cls.isBlank() || field.isBlank() || cls == combo) continue
                    try {
                        comboChoices = comboChoices + (key to c.api.comboChoices(cls, field))
                    } catch (_: Exception) {
                        comboChoices = comboChoices + (key to emptyList())
                    }
                }
            }
        }
    }

    private var persistJob: Job? = null

    /** 表单值落盘：打字/拖滑条高频触发，做 300ms debounce；提交前等关键路径用 persistNow() 直写。 */
    fun persist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(300)
            persistNow(boundId)
        }
    }

    /** 落盘目标显式传 id：bind() 换绑刷盘时写旧 id，其余调用方传 boundId。 */
    private suspend fun persistNow(id: String?) {
        if (id == null) return
        val values = JSONObject()
        for (spec in specs) {
            val key = Specs.key(spec)
            when (Specs.type(spec)) {
                "text", "prompt_pool", "select", "model" -> values.put(key, textValues[key].orEmpty())
                "int", "int_random" -> values.put(key, textValues[key]?.toLongOrNull() ?: 0L)
                "float" -> values.put(key, textValues[key]?.toDoubleOrNull() ?: 0.0)
                "bool" -> values.put(key, boolValues[key] ?: false)
                "image_sizes" -> values.put(key, JSONArray(sizesSelected))
            }
        }
        val randoms = JSONObject()
        randomOn.forEach { (k, v) -> randoms.put(k, v) }
        val doc = JSONObject()
            .put("values", values)
            .put("sizes", JSONArray(sizesSelected))
            .put("random", randoms)
            .put("batch", batchSize)
            .put(
                "kwb_draw_filter",
                JSONObject().put("cat", drawCat).put("inc", drawInc).put("exc", drawExc),
            )
        c.workflowStore.saveValues(id, doc)
    }

    // ---- 各控件的更新入口 ----

    fun setText(key: String, value: String, maxChars: Int = 2000) {
        textValues = textValues + (key to value.take(maxChars))
    }

    fun setBool(key: String, value: Boolean) {
        boolValues = boolValues + (key to value)
    }

    fun setRandom(key: String, value: Boolean) {
        randomOn = randomOn + (key to value)
    }

    /** 设置当前尺寸（单选语义：替换，不是追加）。 */
    fun setSize(value: String) {
        sizesSelected = listOf(value)
    }

    fun setBatch(n: Int) {
        batchSize = n.coerceIn(1, 8)
    }

    fun setIntText(key: String, raw: String) {
        textValues = textValues + (key to raw.filter { it.isDigit() || it == '-' }.take(20))
    }

    fun setFloatText(key: String, raw: String) {
        textValues = textValues + (key to raw.take(20))
    }

    // ---- 抽卡共享筛选（决策 2，按工作流记忆在 kwb_draw_filter） ----

    fun onDrawCat(value: String) {
        drawCat = value
        persist()
    }

    fun onDrawInc(value: String) {
        drawInc = value
        persist()
    }

    fun onDrawExc(value: String) {
        drawExc = value
        persist()
    }

    /** 分类列表来自当前库 meta；换库后原分类不存在 → 重置为「全部」。逻辑自 T2iModel 移植。 */
    private suspend fun refreshDrawCategories() {
        try {
            val meta = c.library.meta()
            val arr = meta.optJSONArray("categories")
            val list = mutableListOf("全部" to "")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    list.add(o.optInt("count").toString() to name)
                }
            }
            drawCategories = list
            if (drawCat.isNotEmpty() && list.none { it.second == drawCat }) {
                drawCat = ""
                persist()
            }
        } catch (_: Exception) {
        }
    }

    // ---- 图片选择（file:image，参考图生图页交互） ----

    fun useUri(uri: Uri) {
        sourceUri = uri.toString()
        sourcePath = null
        val app = getApplication<Application>()
        viewModelScope.launch {
            val (w, h) = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri).use { input ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, o)
                    o.outWidth to o.outHeight
                }
            }
            srcW = w
            srcH = h
        }
    }

    fun usePath(path: String) {
        sourcePath = path
        sourceUri = null
        viewModelScope.launch {
            val (w, h) = withContext(Dispatchers.IO) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                o.outWidth to o.outHeight
            }
            srcW = w
            srcH = h
        }
    }

    // ---- 抽卡（每个 text/prompt_pool 都可以；prompt_pool 带共享筛选，text 不带） ----

    fun draw(key: String) {
        viewModelScope.launch {
            if (!c.library.hasCurrent()) {
                message = getApplication<Application>().str(R.string.err_need_library)
                return@launch
            }
            drawingKey = key
            message = ""
            try {
                val json = c.library.draw(drawCat, drawInc, drawExc)
                val maxChars = specs.firstOrNull { Specs.key(it) == key }?.let { Specs.maxChars(it) } ?: 2000
                setText(key, json.optString("prompt"), maxChars)
                persist()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                drawingKey = ""
            }
        }
    }

    /**
     * 「恢复导入时默认值」（设置页按钮）：仅设置页字段（model/select/int/float/bool）改用
     * definition 的 default；提示词、抽卡筛选、image_sizes、int_random、batch 保持现值不动。
     * 不需要新文件：definition.json 自导入起未被写过，就是导入时默认值快照。
     * 以内存当前值为基准（它是 last_values 的实时镜像），改完 persistNow() 落盘。
     */
    fun restoreDefaults() {
        val id = boundId ?: return
        viewModelScope.launch {
            try {
                val def = c.workflowStore.definition(id) ?: return@launch
                val arr = def.optJSONArray("user_facing_inputs") ?: return@launch
                val t = textValues.toMutableMap()
                for (i in 0 until arr.length()) {
                    val spec = arr.optJSONObject(i) ?: continue
                    when (Specs.type(spec)) {
                        "model", "select" -> t[Specs.key(spec)] = Specs.defaultText(spec)
                        "int", "float" -> t[Specs.key(spec)] = trimNum(Specs.defaultNumber(spec))
                    }
                }
                textValues = t
                persistNow(boundId)
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // ---- 生成 ----

    fun generate() {
        val id = boundId ?: return
        val wf = workflowJson ?: return
        viewModelScope.launch {
            submitting = true
            message = ""
            try {
                // 每次提交用独立的任务 id：jobs 表主键（CONFLICT_REPLACE）、图片下载去重
                // （hasLocal(cq_<id>, idx)）、engine 的 running/live 全以它为 key——
                // 复用工作流 UUID 会让同一工作流第二次出图覆盖上一条任务行、同 job_id 同 idx 全部跳过下载
                val jobClientId = UUID.randomUUID().toString()
                val values = JSONObject()
                for (spec in specs) {
                    val key = Specs.key(spec)
                    when (Specs.type(spec)) {
                        "text", "prompt_pool" -> values.put(key, textValues[key].orEmpty())
                        "select" -> values.put(key, textValues[key].orEmpty())
                        "model" -> textValues[key].orEmpty().takeIf { it.isNotBlank() }?.let { values.put(key, it) }
                        "int" -> values.put(key, clampSpec(spec, textValues[key]).toLong())
                        "int_random" -> values.put(key, textValues[key]?.toLongOrNull() ?: 0L)
                        "float" -> values.put(key, clampSpec(spec, textValues[key]))
                        "bool" -> values.put(key, boolValues[key] ?: Specs.defaultBool(spec))
                        "image_sizes" -> {
                            val selected = sizesSelected.singleOrNull()
                            // source 先占成 720P；下面若读到参考图再换成它的像素。没有参考图就不拦截。
                            val payload = if (outputKind == "video" && selected == VIDEO_SIZE_SOURCE) {
                                JSONArray().put(VIDEO_SIZE_720P)
                            } else {
                                JSONArray(sizesSelected)
                            }
                            values.put(key, payload)
                        }
                    }
                }
                val firstRandom = specs.firstOrNull { Specs.type(it) == "int_random" }
                val seed = firstRandom?.let { textValues[Specs.key(it)]?.toLongOrNull() ?: 0L } ?: 0L
                val seedRandom = firstRandom?.let { randomOn[Specs.key(it)] ?: Specs.randomDefault(it) } ?: true
                val body = buildCustomBody(
                    clientId = jobClientId,
                    workflowId = id,
                    prompt = mainPrompt(),
                    values = values,
                    batch = batchSize,
                    seed = seed,
                    seedRandom = seedRandom,
                    outputNode = outputNode,
                    outputKind = outputKind,
                )
                // 参考图：按原样暂存直传（与图生图一致，压缩只在上传超时/失败且用户确认后进行）；
                // 暂存文件名跟任务 id 走，避免同工作流两个任务共用/互删同一个 uploads 文件
                val hasImageSpec = specs.any { Specs.type(it) == "file:image" }
                if (hasImageSpec) {
                    if (sourcePath == null && sourceUri == null) {
                        message = getApplication<Application>().str(R.string.err_need_reference)
                        return@launch
                    }
                    val original = withContext(Dispatchers.IO) { stageOriginal(getApplication(), jobClientId) }
                    body.put("local_jpeg", original.absolutePath)
                    if (outputKind == "video" && sizesSelected.singleOrNull() == VIDEO_SIZE_SOURCE) {
                        val (w, h) = withContext(Dispatchers.IO) { decodeBounds(original) }
                        if (w >= 16 && h >= 16) {
                            val sizeSpec = specs.firstOrNull { Specs.type(it) == "image_sizes" }
                            if (sizeSpec != null) {
                                body.optJSONObject("values")?.put(Specs.key(sizeSpec), JSONArray().put("${w}x${h}"))
                            }
                        }
                    }
                }
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    c.db.insertJob(
                        JobRow(jobClientId, "", "custom", if (hasImageSpec) "uploading" else "pending", body.toString(), now, now, totalCount(), 0, ""),
                    )
                }
                persistNow(boundId)
                startGenerationService(getApplication())
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                submitting = false
            }
        }
    }

    /** spec 的 min/max 夹紧（没写不夹）。 */
    private fun clampSpec(spec: JSONObject, raw: String?): Double {
        val v = raw?.toDoubleOrNull() ?: Specs.defaultNumber(spec)
        var out = v
        Specs.min(spec)?.let { if (out < it) out = it }
        Specs.max(spec)?.let { if (out > it) out = it }
        return out
    }

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    private fun stageOriginal(app: Application, id: String): File {
        val dir = File(app.filesDir, "uploads").apply { mkdirs() }
        val out = File(dir, "$id${originalExt(app)}")
        if (sourceUri != null) {
            val input = app.contentResolver.openInputStream(Uri.parse(sourceUri)) ?: error(app.str(R.string.err_cannot_read_image))
            input.use { ins ->
                FileOutputStream(out).use { ins.copyTo(it) }
            }
        } else {
            File(sourcePath!!).copyTo(out, overwrite = true)
        }
        return out
    }

    private fun originalExt(app: Application): String {
        if (sourceUri != null) {
            val type = try {
                app.contentResolver.getType(Uri.parse(sourceUri))
            } catch (_: Exception) {
                null
            }
            return when (type) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> ".jpg"
            }
        }
        val name = sourcePath.orEmpty()
        val dot = name.lastIndexOf('.')
        val ext = if (dot >= 0) name.substring(dot) else ".jpg"
        return ext.lowercase()
    }

    fun cancel(clientJobId: String) = c.engine.cancel(clientJobId)

    fun resume(clientJobId: String) {
        c.engine.kick(clientJobId)
        startGenerationService(getApplication())
    }

    /** 视频下载失败后的重试：重启轮询协程（jobId 已记录，不会重新提交）。 */
    fun retryDownload(clientJobId: String) {
        c.engine.retryDownload(clientJobId)
        startGenerationService(getApplication())
    }

    fun answerUploadAsk(clientId: String, compress: Boolean) = c.engine.answerUploadAsk(clientId, compress)

    private suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            // 文本结果没有本地文件（内容在行里），不能按文件存在过滤掉
            c.db.images().filter { it.kind == "text" || File(it.localPath).exists() }
        }
        gallery = loaded
        results = loaded.take(20)
        backfillSeed(loaded)
    }

    /** 「从图库选择」参考图网格的数据源：只要图片行（文本行没有文件、视频不能当参考图）。 */
    fun pickerImages(): List<ImageRow> = gallery.filter { it.kind == "image" }

    /** 最近文本结果（生成页大卡；最多 4 条）。文本卡不做长期保留：保留到切换工作流，bind() 换绑时统一清空。 */
    fun textResults(): List<ImageRow> = gallery.filter { it.kind == "text" }.take(4)

    /** ThumbRow 资产行：按当前工作流输出类型过滤（图工作流只看图、视频只看视频、反推不给缩略）；
     *  数据源为全量 gallery，跨工作流「最近资产」语义保持，调用处 take(20)。 */
    fun mediaResults(): List<ImageRow> = when (outputKind) {
        "image" -> gallery.filter { it.kind == "image" }
        "video" -> gallery.filter { it.kind == "video" }
        else -> emptyList()
    }

    // ---- 种子回填（direct10c）：随机模式下生成完成后，把这次实际提交给 ComfyUI 的种子显示进数字框 ----

    private var lastSeedImgId = 0L

    /** 引擎按 BuiltFlow 记录的实际种子落在 ImageRow.seed；revision 每张图下载后 bump，这里随后执行。 */
    private fun backfillSeed(images: List<ImageRow>) {
        val id = boundId ?: return
        val firstRandom = specs.firstOrNull { Specs.type(it) == "int_random" } ?: return
        val key = Specs.key(firstRandom)
        // 只在随机模式回填；固定模式提交值就是框里现值
        if (randomOn[key] != true) return
        val newest = images.firstOrNull { it.mode == "custom" } ?: return
        if (newest.id <= lastSeedImgId) return
        lastSeedImgId = newest.id
        // 只回填当前绑定工作流的图（paramsJson = 提交 body，带 workflow_id），别的工作流的图跳过
        val wfId = try {
            JSONObject(newest.paramsJson).optString("workflow_id")
        } catch (_: Exception) {
            ""
        }
        if (wfId != id) return
        // 批量时每张各自随机，取同批第一张（idx 最小）的种子作为「这次」的种子
        val first = images.filter { it.clientJobId == newest.clientJobId }.minByOrNull { it.idx } ?: newest
        val seedText = first.seed.takeIf { it > 0L }?.toString() ?: return
        if (textValues[key] != seedText) {
            setText(key, seedText)
            persist()
        }
    }

    init {
        viewModelScope.launch {
            reload()
            c.engine.revision.collect { reload() }
        }
        // 当前提示词库变化（导入/切换/删除）时刷新抽卡筛选的分类列表（自 T2iModel 移植）
        viewModelScope.launch {
            c.library.currentId.collect {
                refreshDrawCategories()
            }
        }
    }
}
