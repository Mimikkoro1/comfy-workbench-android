package com.mie.kreaworkbench.ui.screens.custom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.ui.locale.workflowLabelStr
import com.mie.kreaworkbench.data.api.ServerKind
import com.mie.kreaworkbench.data.api.normalizeServerUrl
import com.mie.kreaworkbench.data.api.trimBase
import com.mie.kreaworkbench.data.buildCustomBody
import com.mie.kreaworkbench.data.gacha.GACHA_MAX_COUNT
import com.mie.kreaworkbench.data.gacha.GACHA_PROMPT_CAP
import com.mie.kreaworkbench.data.gacha.GachaCatalog
import com.mie.kreaworkbench.data.gacha.GachaMaster
import com.mie.kreaworkbench.data.gacha.GachaOptions
import com.mie.kreaworkbench.data.gacha.GachaRequest
import com.mie.kreaworkbench.data.gacha.GachaSelection
import com.mie.kreaworkbench.data.gacha.correctGachaSelection
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.data.db.JobRow
import com.mie.kreaworkbench.data.comfy.readCache
import com.mie.kreaworkbench.data.comfy.writeCache
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.data.workflows.LoraEntry
import com.mie.kreaworkbench.data.workflows.Specs
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_720P
import com.mie.kreaworkbench.data.workflows.VIDEO_SIZE_SOURCE
import com.mie.kreaworkbench.data.workflows.loraManagedSpecKeys
import com.mie.kreaworkbench.data.workflows.loraStrengthKey
import com.mie.kreaworkbench.data.workflows.loraEntries as loraEntriesOf
import com.mie.kreaworkbench.data.workflows.isTextToImageWorkflow
import com.mie.kreaworkbench.data.workflows.outputKindOf
import com.mie.kreaworkbench.data.workflows.pickOutputNode
import com.mie.kreaworkbench.data.workflows.resolveLoraOn
import com.mie.kreaworkbench.data.workflows.resolveLoraStrength
import com.mie.kreaworkbench.data.workflows.upgradeSamplerComboSpecs
import com.mie.kreaworkbench.startGenerationService
import com.mie.kreaworkbench.util.VIDEO_MAX_BYTES
import com.mie.kreaworkbench.util.VideoThumb
import com.mie.kreaworkbench.util.formatBytes
import com.mie.kreaworkbench.util.pickableVideos
import com.mie.kreaworkbench.util.probeFile
import com.mie.kreaworkbench.util.probeUri
import com.mie.kreaworkbench.util.stageVideoFile
import com.mie.kreaworkbench.util.videoExtFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
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

    /** r39：DataStore 首次发射后为 true。在此之前 UI 不渲染抽卡默认值、不触发目录刷新，防假默认闪现。 */
    var settingsLoaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            c.settings.flow.first()
            settingsLoaded = true
        }
    }

    val live = c.engine.snapshot

    /** r27.1：最近一次生成完成落库的行 id（引擎事件）。生成页背景只在这张图出现时播换图滑入。 */
    val lastResultId = c.engine.lastResultId

    /** 抽卡可用性：当前提示词库存在且条目数 > 0。导入/删除/切库实时生效，UI 据此显隐抽卡相关控件。 */
    val hasPromptLibrary: StateFlow<Boolean> = combine(
        c.library.libraries,
        c.library.currentId,
    ) { libs, cur -> (libs.firstOrNull { it.id == cur }?.count ?: 0) > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 备选数小字（round11）：prompt_pool 槽 = 共享筛选后的池大小。触发源为库列表（增删条目 count 变）、
     *  当前库、共享筛选三项；不含被冷却冻结的卡，数字不随抽卡跳动（触发源里没有抽卡动作）。
     *  null=尚未算出，UI 不显示避免闪 0。 */
    val drawPoolSize: StateFlow<Int?> = combine(
        c.library.libraries,
        c.library.currentId,
        snapshotFlow { listOf(drawCat, drawInc, drawExc) },
    ) { _, _, filter -> c.library.poolSize(filter[0], filter[1], filter[2]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 全库备选数（round11）：text 槽小字用，不带筛选——与 text 槽抽卡传空筛选的分流行为一致。 */
    val libraryPoolSize: StateFlow<Int?> = combine(
        c.library.libraries,
        c.library.currentId,
    ) { _, _ -> c.library.poolSize("", "", "") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var loading by mutableStateOf(true)
        private set

    /**
     * r27.2：媒体列表是否至少完成过一次读库。
     * 冷启动首帧 gallery 一定是空的；用这个标记让背景知道「还没加载完」，
     * 从而画深色底而不是先闪一下 GlassFallback 兜底图。
     */
    var mediaLoaded by mutableStateOf(false)
        private set

    var displayName by mutableStateOf("")
        private set
    var message by mutableStateOf("")
    var submitting by mutableStateOf(false)
    var drawingKey by mutableStateOf("")

    // 高级抽卡。本地开关立刻生效，同时写入设置；抽卡请求读这份，不等 DataStore 回写。
    var advOn by mutableStateOf<Boolean?>(null)
    var hidePromptPick by mutableStateOf<Boolean?>(null)
    var gachaModePick by mutableStateOf<String?>(null)
    var gachaMasterPick by mutableStateOf<String?>(null)
    var gachaOutfitPick by mutableStateOf<String?>(null)
    var gachaPoolPick by mutableStateOf<String?>(null)
    var gachaCategoryPick by mutableStateOf<String?>(null)
    var gachaMasters by mutableStateOf<List<GachaMaster>>(emptyList())
        private set
    /** 服端动态筛选项。null = 旧服没给 options（UI 回退写死列表）；非 null 时空数组 = 该框不画。 */
    var gachaOptions by mutableStateOf<GachaOptions?>(null)
        private set
    var gachaMastersError by mutableStateOf("")
        private set
    var gachaMastersLoaded by mutableStateOf(false)
        private set
    var gachaDrawing by mutableStateOf(false)
        private set

    /** 高级抽卡的临时提示词。非空时生成用这句，不关高级抽卡。换工作流就丢掉。 */
    var tempPrompt by mutableStateOf("")
        private set
    /** 临时词钉（r28.1）：钉住 = 生成成功后保留这句；不钉（默认）= 成功后自动清空。只在内存里，不进设置。 */
    var tempPromptPinned by mutableStateOf(false)
        private set

    fun advEnabled(saved: Boolean): Boolean = advOn ?: saved
    fun hidePromptOn(saved: Boolean): Boolean = hidePromptPick ?: saved

    fun gachaRequest(s: UserSettings, count: Int) = GachaRequest(
        base = s.advGachaUrl.trim(),
        mode = gachaModePick ?: s.advGachaMode.ifBlank { "wallpaper" },
        count = count,
        master = gachaMasterPick ?: s.advGachaMaster,
        category = gachaCategoryPick ?: s.advGachaCategory,
        outfit = gachaOutfitPick ?: s.advGachaOutfit.ifBlank { "random" },
        pool = gachaPoolPick ?: s.advGachaPool.ifBlank { "auto" },
    )
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

    // LoRA 开关 / 强度（round16）：运行时从 workflow.json 识别，开关与强度按工作流持久化
    var loraEntries by mutableStateOf(emptyList<LoraEntry>())
        private set
    var loraOn by mutableStateOf(emptyMap<String, Boolean>())
        private set
    var loraStrength by mutableStateOf(emptyMap<String, String>()) // strengthKey → 框文本
        private set

    /** 被自动强度框接管的旧暴露参数 spec key（设置页参数卡不再渲染） */
    var loraManaged by mutableStateOf(emptySet<String>())
        private set

    // 当前绑定工作流的 definition.json（restore / restoreDefaults 读 def 顶层 kwb_lora_* 用）
    private var defJson: JSONObject? = null

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

    // 选项拉取失败（401/超时/断网）的 spec key（round13 第 4 项）：这些槽位 UI 退化为
    // 可编辑输入框并提示，而不是只读文本；重拉成功后清空。
    var choiceLoadFailed by mutableStateOf(emptySet<String>())
        private set

    // r42：当前显示的选项来自本地缓存（还没从服务器拉到新的）。拉到新值或本机没缓存为 false。
    var choicesFromCache by mutableStateOf(false)
        private set

    // 「刷新」进行中（round13 第 7 项：模型采样 Tab 顶部按钮）
    var refreshing by mutableStateOf(false)
        private set

    // 抽卡共享筛选（决策 2）：所有 prompt_pool 抽卡共用，存 last_values 私有键 kwb_draw_filter
    var drawCat by mutableStateOf("")
    var drawInc by mutableStateOf("")
    var drawExc by mutableStateOf("")
    var drawCategories by mutableStateOf(listOf("" to ""))

    // file:image（本版最多一个 spec）
    var sourcePath by mutableStateOf<String?>(null)
    var sourceUri by mutableStateOf<String?>(null)
    var srcW by mutableStateOf(0)
    var srcH by mutableStateOf(0)

    // 参考视频（round17）：两个来源互斥（系统选择器 videoUri / 图库 videoPath），后选覆盖先选。
    // 全部不持久化，与参考图一致；bind() 换绑时清空。
    var videoUri by mutableStateOf<String?>(null)
        private set
    var videoPath by mutableStateOf<String?>(null)
        private set
    var videoName by mutableStateOf("")
        private set
    var videoSize by mutableStateOf(0L)
        private set
    var videoDurationMs by mutableStateOf(0L)
        private set
    var videoW by mutableStateOf(0)
        private set
    var videoH by mutableStateOf(0)
        private set
    var videoThumb by mutableStateOf<Bitmap?>(null)
        private set

    /** 单尺寸化（direct10c）：尺寸恒为一个，张数只由 batch 决定。 */
    fun sizeCount(): Int = 1

    fun totalCount(): Int = sizeCount() * batchSize

    /**
     * 该槽抽卡是否带共享筛选（r14fix3）：prompt_pool 槽一律带；主提示词槽即使是 text（如 Qwen
     * TextEncodeQwenImage21.prompt 被手动勾选、低置信度导入成 text）也带——筛选框挂在主提示词下，
     * 抽卡/备选数必须和它一致。其余 text 槽保持全库（round11 裁决 A）。
     */
    fun usesSharedFilter(spec: JSONObject?): Boolean {
        if (spec == null || spec.optBoolean("kwb_negative")) return false
        val type = Specs.type(spec)
        return type == "prompt_pool" || (type == "text" && Specs.key(spec) == mainPromptKey())
    }

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
        autoRefreshDone = false
        tempPrompt = ""
        tempPromptPinned = false
        // 换绑时清掉上一个工作流残留的参考图选择（图片字段不持久化）
        sourcePath = null
        sourceUri = null
        clearVideo()
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
                    c.engine.revision.update { it + 1 }
                }
            }
            // ③ 再加载新工作流：restore 读到的一定是已把挂起编辑落盘后的 last_values.json
            loadBound(id)
        }
    }

    /**
     * r38 E5：从 bind 抽出的加载尾（绑定与「编辑参数后同 id 重载」共用）：
     * 读工作流与定义 → 组 specs → restore → 拉模型选项。工作流已删 → 置空回空状态。
     */
    private suspend fun loadBound(id: String) {
        loading = true
        try {
            val pair = c.workflowStore.get(id)
            if (pair == null) {
                message = getApplication<Application>().str(R.string.err_workflow_deleted)
                c.settings.update { it.copy(currentWorkflow = "") }
                return
            }
            val (wf, def) = pair
            workflowJson = wf
            defJson = def
            displayName = c.workflowStore.metaOf(id)
                ?.let { getApplication<Application>().workflowLabelStr(it) } ?: id
            outputNode = def.optString("kwb_output_node").ifBlank { pickOutputNode(wf) }
            outputKind = outputKindOf(def)
            specs = buildList {
                val arr = def.optJSONArray("user_facing_inputs")
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(it) }
                }
            }.let { upgradeSamplerComboSpecs(it, wf) }
            loraEntries = loraEntriesOf(wf)
            restore(id)
            loraManaged = loraManagedSpecKeys(loraEntries, specs)
            // 换绑先清上一工作流的选项映射，避免同名 key（node|field 可能撞）串台；
            // 下面 loadChoices 失败的槽位就是「无选项 + 提示」，而不是残留旧值
            modelChoices = emptyMap()
            comboChoices = emptyMap()
            loadChoices()
            maybeAutoRefreshChoices()
        } catch (e: Exception) {
            message = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    /**
     * r38 E5：编辑参数页覆盖了当前绑定工作流的 definition 后由 definitionEdits 触发——
     * 同 id 强制重载（boundId 去重挡不住）。先刷盘挂起的表单编辑再重载；
     * 不换绑，不清文本卡/参考图。
     */
    fun onDefinitionEdited(id: String) {
        if (boundId != id) return
        viewModelScope.launch {
            persistJob?.cancel()
            try {
                withContext(Dispatchers.IO) { persistNow(id) }
            } catch (_: Exception) {
            }
            loadBound(id)
        }
    }

    private suspend fun restore(id: String) {
        val saved = c.workflowStore.lastValues(id)
        batchSize = saved.optInt("batch", c.settings.current().batch).coerceIn(1, 10)
        val values = saved.optJSONObject("values") ?: JSONObject()
        sizesSelected = saved.optJSONArray("sizes")?.let { arr ->
            buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { s -> s.isNotBlank() }?.let { add(it) } }
        }.orEmpty()
        // direct10c 起尺寸单选：旧数据存过多个时只取第一个，不崩、不空白
        if (sizesSelected.size > 1) sizesSelected = listOf(sizesSelected.first())
        val randoms = saved.optJSONObject("random") ?: JSONObject()
        // 抽卡筛选的私有键：不进 values、不参与填参，只在这里恢复
        val filter = saved.optJSONObject("kwb_draw_filter")
        drawCat = normalizeDrawCat(filter?.optString("cat"))
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
                    val raw: Any? = values.opt(key)
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
        // LoRA 开关 / 强度（round16）：saved.kwb_lora_on / kwb_lora_strength → 旧暴露参数的值 → def → 工作流原值
        val def = defJson
        loraOn = resolveLoraOn(loraEntries, saved, def)
        loraStrength = resolveLoraStrength(loraEntries, specs, saved, def)
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /**
     * model spec 的下拉选项 + kwb_combo select 的在线选项。
     * round13 第 4 项：单个槽位拉取失败（401/超时等）时保留该槽位的旧选项、不再写空列表
     * （写空会让下拉退化成只读文本且被记住），槽位 key 记入 choiceLoadFailed 供 UI 提示。
     * r42：开头先上本地缓存（filesDir/objinfo/<serverKey>.json，电脑关机/断线时立刻有上次的
     * 选项），然后照旧发请求；拉到非空新值覆盖显示并写缓存，拉取失败保留缓存显示（提示不吞）。
     */
    private suspend fun loadChoices() {
        val wf = workflowJson ?: return
        val dir = File(getApplication<Application>().filesDir, "objinfo")
        val serverUrl = c.settings.current().serverUrl
        val cached = withContext(Dispatchers.IO) { readCache(dir, serverUrl) }
        var fromCache = false
        if (cached.isNotEmpty()) {
            for (spec in specs) {
                val key = Specs.key(spec)
                when (Specs.type(spec)) {
                    "model" -> {
                        val cls = wf.optJSONObject(Specs.nodeId(spec))?.optString("class_type").orEmpty()
                        if (cls.isNotBlank()) {
                            cached["$cls|${Specs.field(spec)}"]?.let {
                                modelChoices = modelChoices + (key to it)
                                fromCache = true
                            }
                        }
                    }
                    "select" -> {
                        val combo = spec.optString("kwb_combo")
                        if (combo.isNotBlank()) {
                            val cls = combo.substringBeforeLast('|')
                            val field = combo.substringAfterLast('|', "")
                            if (cls.isNotBlank() && field.isNotBlank() && cls != combo) {
                                cached["$cls|$field"]?.let {
                                    comboChoices = comboChoices + (key to it)
                                    fromCache = true
                                }
                            }
                        }
                    }
                }
            }
        }
        choicesFromCache = fromCache

        val failed = mutableSetOf<String>()
        // r42：本轮成功拉到的选项（key = classType|field，与缓存文件同维度）
        val fresh = LinkedHashMap<String, List<String>>()
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
                        if (choices.isNotEmpty()) {
                            fresh["$cls|${Specs.field(spec)}"] = choices
                            choicesFromCache = false
                        }
                    } catch (_: Exception) {
                        failed.add(key)
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
                        val choices = c.api.comboChoices(cls, field)
                        comboChoices = comboChoices + (key to choices)
                        if (choices.isNotEmpty()) {
                            fresh["$cls|$field"] = choices
                            choicesFromCache = false
                        }
                    } catch (_: Exception) {
                        failed.add(key)
                    }
                }
            }
        }
        choiceLoadFailed = failed
        // r42：只有成功拉到非空列表才写缓存（merge 再挡一层：fresh 里的空 key 不覆盖旧值）
        if (fresh.isNotEmpty()) {
            withContext(Dispatchers.IO) { writeCache(dir, serverUrl, fresh) }
        }
    }

    /** 仅重拉当前工作流的选项（不动模型目录缓存）；err=null 成功。供参数区「重试」复用。 */
    fun refreshChoices(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                loadChoices()
                onDone(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * 「模型采样」Tab 顶部刷新（round13 第 7 项）：重拉本页全部下拉选项，相当于重新解析参数面板。
     * 已填的参数值（textValues）一律不动；中途任何一步失败都保留旧数据，err 回调给 UI 提示。
     * r42：原来借 models(refresh=true) 的失败上抛报告刷新失败，models() 已删（死缓存），
     * 改用本轮选项拉取失败的槽位判断——对外语义不变（拉不到就报「刷新失败」）。
     */
    fun refreshAll(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            refreshing = true
            try {
                loadChoices()
                if (choiceLoadFailed.isNotEmpty()) {
                    onDone(getApplication<Application>().str(R.string.err_cannot_connect))
                } else {
                    onDone(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(e.message ?: e.javaClass.simpleName)
            } finally {
                refreshing = false
            }
        }
    }

    private var persistJob: Job? = null

    /** r35.1：本轮绑定是否已自动补拉过选项（每次换绑重置，只补一次，不循环重试）。 */
    private var autoRefreshDone = false

    /** 模型/在线 select 槽是否还有没拿到选项的（空或拉取失败都算缺）。 */
    private fun choicesIncomplete(): Boolean {
        for (spec in specs) {
            val key = Specs.key(spec)
            when (Specs.type(spec)) {
                "model" -> if (modelChoices[key].isNullOrEmpty()) return true
                "select" ->
                    if (spec.optString("kwb_combo").isNotBlank() && comboChoices[key].isNullOrEmpty()) return true
            }
        }
        return false
    }

    /**
     * 打开工作流后选项仍缺，或当前显示来自本地缓存（r42）：后台自动补拉一次。
     * 等价于手点模型页的「刷新」。延迟一小段再发，避开冷启动连接建立期；成功则下拉就地就绪、
     * 「来自缓存」小字消失，失败保持既有提示（选项未加载/服务器未安装），用户仍可手动刷新。
     * 选项齐了、缓存也不用补、或未配服务器则不发。防重入标志保证每次换绑只补拉一次。
     */
    private fun maybeAutoRefreshChoices() {
        if (autoRefreshDone) return
        // r42：缓存有数据时 choicesIncomplete() 可能为假——「来自缓存」的显示也要刷一次，
        // 否则缓存永远不更新；autoRefreshDone 防止刷完又触发循环
        if (!choicesIncomplete() && !choicesFromCache) return
        autoRefreshDone = true
        val target = boundId
        viewModelScope.launch {
            delay(1_500)
            if (boundId != target) return@launch
            try {
                if (c.settings.current().serverUrl.isBlank()) return@launch
                loadChoices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 静默：界面已有「选项未加载/服务器未安装」提示兜底
            }
        }
    }

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
        // LoRA 私有键（round16）：不进 values（否则会被当成 spec 值）；强度只存合法有限的数字
        val loraOnDoc = JSONObject()
        loraOn.forEach { (k, v) -> loraOnDoc.put(k, v) }
        val loraStrengthDoc = JSONObject()
        loraStrength.forEach { (k, v) ->
            v.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { loraStrengthDoc.put(k, it) }
        }
        val doc = JSONObject()
            .put("values", values)
            .put("sizes", JSONArray(sizesSelected))
            .put("random", randoms)
            .put("batch", batchSize)
            .put(
                "kwb_draw_filter",
                JSONObject().put("cat", drawCat).put("inc", drawInc).put("exc", drawExc),
            )
            .put("kwb_lora_on", loraOnDoc)
            .put("kwb_lora_strength", loraStrengthDoc)
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
        batchSize = n.coerceIn(1, 10)
    }

    fun setIntText(key: String, raw: String) {
        textValues = textValues + (key to raw.filter { it.isDigit() || it == '-' }.take(20))
    }

    fun setFloatText(key: String, raw: String) {
        textValues = textValues + (key to raw.take(20))
    }

    // ---- LoRA 开关 / 强度（round16，设置页模型卡） ----

    /** 该 model spec 是否对应一个单加载器 entry（有 → SpecRow 的 model 分支带开关和强度框）。 */
    fun loraEntryForSpec(spec: JSONObject): LoraEntry? {
        if (Specs.type(spec) != "model") return null
        val nid = Specs.nodeId(spec)
        val field = Specs.field(spec)
        return loraEntries.firstOrNull { it.slot == null && it.nodeId == nid && it.fileField == field }
    }

    fun setLoraOn(key: String, on: Boolean) {
        loraOn = loraOn + (key to on)
        persist()
    }

    /** 照 setIntText 的风格过滤（数字 / . / -，take(20)）：非法文本留不到落盘，提交时用工作流原值兜底。 */
    fun setLoraStrength(strengthKey: String, raw: String) {
        val filtered = raw.filter { it.isDigit() || it == '.' || it == '-' }.take(20)
        loraStrength = loraStrength + (strengthKey to filtered)
        persist()
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
            val list = mutableListOf("" to "")
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

    fun clearSource() {
        sourcePath = null
        sourceUri = null
        srcW = 0
        srcH = 0
    }

    // ---- 视频选择（file:video，round17）：系统选择器 / 图库两个来源，互斥，后选覆盖先选 ----

    /** 系统选择器来源：content URI。元信息与首帧由 probeUri 读。 */
    fun useVideoUri(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val probe = withContext(Dispatchers.IO) { probeUri(app, uri) }
            // 超上限直接拒绝（不设 videoUri）；取不到大小（-1）放行，generate 时按实际复制字节数再拦
            if (probe.size > VIDEO_MAX_BYTES) {
                message = app.str(R.string.video_too_large, formatBytes(VIDEO_MAX_BYTES))
                return@launch
            }
            videoUri = uri.toString()
            videoPath = null
            videoName = probe.name.orEmpty()
            videoSize = probe.size.takeIf { it > 0 } ?: 0L
            videoDurationMs = probe.durationMs
            videoW = probe.width
            videoH = probe.height
            videoThumb = probe.frame
        }
    }

    /** 图库来源：本地文件路径。缩略图复用画廊的 `<视频>.jpg` 缓存（VideoThumb.thumbFile）。 */
    fun useVideoPath(path: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val probe = withContext(Dispatchers.IO) { probeFile(path) }
            // probe.size == -1 表示文件不存在（图库里已删除）：不选中
            if (probe.size < 0) {
                message = app.str(R.string.err_video_missing)
                return@launch
            }
            if (probe.size > VIDEO_MAX_BYTES) {
                message = app.str(R.string.video_too_large, formatBytes(VIDEO_MAX_BYTES))
                return@launch
            }
            videoPath = path
            videoUri = null
            videoName = probe.name.orEmpty()
            videoSize = probe.size
            videoDurationMs = probe.durationMs
            videoW = probe.width
            videoH = probe.height
            videoThumb = withContext(Dispatchers.IO) {
                VideoThumb.thumbFile(path)?.let { BitmapFactory.decodeFile(it.absolutePath) }
            } ?: probe.frame
        }
    }

    /** 只清状态，不删任何文件（图库原文件 / 暂存副本都不动）。 */
    fun clearVideo() {
        videoUri = null
        videoPath = null
        videoName = ""
        videoSize = 0L
        videoDurationMs = 0L
        videoW = 0
        videoH = 0
        videoThumb = null
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
                // 按槽型分流筛选参数（round11 裁决 A + r14fix3）：prompt_pool 槽与主提示词槽带共享筛选，其余 text 槽全库
                val spec = specs.firstOrNull { Specs.key(it) == key }
                val json = if (usesSharedFilter(spec)) {
                    c.library.draw(drawCat, drawInc, drawExc)
                } else {
                    c.library.draw("", "", "")
                }
                val maxChars = spec?.let { Specs.maxChars(it) } ?: 2000
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
                // LoRA 开关 / 强度（round16）同属设置页：一并恢复（def 顶层 → 工作流原值）
                loraOn = resolveLoraOn(loraEntries, null, def)
                loraStrength = resolveLoraStrength(loraEntries, specs, null, def)
                persistNow(boundId)
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun updateGacha(block: (UserSettings) -> UserSettings) {
        viewModelScope.launch { c.settings.update(block) }
    }

    fun setAdvGacha(on: Boolean) {
        advOn = on
        updateGacha { it.copy(advGacha = on) }
        if (on) autoFillGachaUrlIfNeeded()
    }

    /**
     * r40：开启高级抽卡且地址为空时，拿扫描记下的抽卡地址自动填并测一次；
     * 成功按原规则保存并刷新目录，失败什么都不存（抽卡区显示「还没找到抽卡服务」）。
     */
    private fun autoFillGachaUrlIfNeeded() {
        viewModelScope.launch {
            val cur = c.settings.current()
            if (cur.advGachaUrl.isNotBlank()) return@launch
            val candidate = c.settings.scannedGachaUrls().firstOrNull() ?: return@launch
            val target = normalizeServerUrl(candidate, ServerKind.GACHA) ?: return@launch
            try {
                c.gacha.catalog(target)
                c.settings.update {
                    it.copy(
                        advGachaUrl = target,
                        advGachaHistory = (listOf(target) + it.advGachaHistory.filter { h -> h != target }).take(8),
                    )
                }
                refreshGachaMasters(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 测试失败：不保存任何值，空状态引导用户去设置
            }
        }
    }

    fun dismissGachaGuide() {
        updateGacha { it.copy(advGachaGuideOff = true) }
    }

    fun setHideGachaPrompt(on: Boolean) {
        hidePromptPick = on
        updateGacha { it.copy(advGachaHidePrompt = on) }
    }

    fun pickGachaMode(value: String) {
        gachaModePick = value
        updateGacha { it.copy(advGachaMode = value) }
    }

    fun pickGachaMaster(value: String) {
        // r39：目录未就绪（空目录 / 刷新中）时不拿空列表跑校正——那会把选中校正成「不限」并落盘。
        // 只记母版并落盘，目录到了由 applyCorrectedGacha 统一校正五维。
        val catalogReady = gachaMasters.isNotEmpty() && gachaRefreshJob?.isActive != true
        if (!catalogReady) {
            gachaMasterPick = value
            updateGacha { it.copy(advGachaMaster = value) }
            return
        }
        // r32：选中母版立刻按它的 has_nude / has_signature 校正其它维，
        // 比如从有 nude 的母版切到没有的，mode 不能停在 nude。
        val after = correctGachaSelection(
            currentGachaSelection(settings.value).copy(master = value),
            gachaMasters,
            gachaOptions,
        )
        applyGachaSelection(after)
        updateGacha {
            it.copy(
                advGachaMaster = after.master,
                advGachaMode = after.mode,
                advGachaOutfit = after.outfit,
                advGachaCategory = after.category,
                advGachaPool = after.pool,
            )
        }
    }

    fun pickGachaOutfit(value: String) {
        gachaOutfitPick = value
        updateGacha { it.copy(advGachaOutfit = value) }
    }

    fun pickGachaPool(value: String) {
        gachaPoolPick = value
        updateGacha { it.copy(advGachaPool = value) }
    }

    fun pickGachaCategory(value: String) {
        gachaCategoryPick = value
        updateGacha { it.copy(advGachaCategory = value) }
    }

    fun editTempPrompt(text: String) {
        tempPrompt = text.take(GACHA_PROMPT_CAP)
    }

    /** 钉住 / 取消钉住。钉住后生成成功不清空，方便同一句连着出几张。 */
    fun toggleTempPin() {
        tempPromptPinned = !tempPromptPinned
    }

    /** 手动清空 = 回到默认（不钉）：清完下次点生成就照常去抽卡。 */
    fun clearTempPrompt() {
        tempPrompt = ""
        tempPromptPinned = false
    }

    // r39：目录刷新单飞——新请求取消旧的，旧结果按代号丢弃；同址 10s 内不重刷
    private var gachaRefreshJob: Job? = null
    private var gachaRefreshGen = 0
    /** 上次成功拿到目录的地址（null = 还没成功过）与时刻（elapsedRealtime）。 */
    private var gachaRefreshOkBase: String? = null
    private var gachaRefreshOkAt = 0L

    /**
     * 按指定地址刷新母版目录（r39 重写）：
     * - 刷新不再先清目录：保留上一份，成功整体替换，同服失败只报错不清、不重置任何选择；
     * - 结果写回前按代号校验，晚到的旧请求一律丢弃；
     * - 只有成功拿到目录才校正五维并落盘（校正基准用此刻的设置值，不是请求前快照）。
     */
    fun refreshGachaMasters(base: String) {
        val now = SystemClock.elapsedRealtime()
        if (
            base == gachaRefreshOkBase &&
            now - gachaRefreshOkAt < 10_000L &&
            gachaRefreshJob?.isActive != true
        ) {
            return
        }
        gachaRefreshJob?.cancel()
        val gen = ++gachaRefreshGen
        gachaRefreshJob = viewModelScope.launch {
            gachaMastersLoaded = false
            gachaMastersError = ""
            try {
                val catalog = c.gacha.catalog(base)
                if (gen != gachaRefreshGen) return@launch
                gachaMasters = catalog.masters
                gachaOptions = catalog.options
                gachaRefreshOkBase = base
                gachaRefreshOkAt = SystemClock.elapsedRealtime()
                applyCorrectedGacha(catalog)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != gachaRefreshGen) return@launch
                gachaMastersError = e.message ?: e.javaClass.simpleName
                // 同一台服（上次成功过的地址）：保留旧目录只报错，不动 pick、不落盘；
                // 换了地址（或从没成功过）才清空，不留上一台服的菜单（r32 语义保留在切服场景）
                if (gachaRefreshOkBase != base) {
                    gachaMasters = emptyList()
                    gachaOptions = null
                }
            } finally {
                if (gen == gachaRefreshGen) gachaMastersLoaded = true
            }
        }
    }

    /** 当前选中的五个 id（运行时 pick 优先，其次设置里持久化的值）。 */
    private fun currentGachaSelection(s: UserSettings): GachaSelection = GachaSelection(
        mode = gachaModePick ?: s.advGachaMode.ifBlank { "wallpaper" },
        master = gachaMasterPick ?: s.advGachaMaster,
        outfit = gachaOutfitPick ?: s.advGachaOutfit.ifBlank { "random" },
        category = gachaCategoryPick ?: s.advGachaCategory,
        pool = gachaPoolPick ?: s.advGachaPool.ifBlank { "auto" },
    )

    private fun applyGachaSelection(sel: GachaSelection) {
        gachaModePick = sel.mode
        gachaMasterPick = sel.master
        gachaOutfitPick = sel.outfit
        gachaCategoryPick = sel.category
        gachaPoolPick = sel.pool
    }

    /** 用新目录校正脏选中；值变了顺手写回设置，免得下次冷启动又拿旧 id 发请求。
     *  r39：只在成功拿到目录后调用；before 用此刻的设置值，不用请求前拍的快照。 */
    private suspend fun applyCorrectedGacha(catalog: GachaCatalog) {
        val before = currentGachaSelection(c.settings.current())
        val after = correctGachaSelection(before, catalog.masters, catalog.options)
        applyGachaSelection(after)
        if (after != before) {
            updateGacha {
                it.copy(
                    advGachaMode = after.mode,
                    advGachaMaster = after.master,
                    advGachaOutfit = after.outfit,
                    advGachaCategory = after.category,
                    advGachaPool = after.pool,
                )
            }
        }
    }

    // ---- 生成 ----

    fun generate() {
        if (submitting) return
        viewModelScope.launch {
            submitting = true
            message = ""
            try {
                enqueueCurrent(promptOverride = null, batchOverride = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                submitting = false
            }
        }
    }

    /** 底部生成按钮。高级抽卡开着且是文生图时，按当前张数去抽词再逐条入队。临时词优先，不关高级抽卡。 */
    fun generateFromButton() {
        val on = advEnabled(settings.value.advGacha) && isTextToImageWorkflow(outputKind, specs)
        val temp = tempPrompt.trim()
        if (on && temp.isNotBlank()) runTempPrompt(temp) else if (on) drawAdvanced(batchSize) else generate()
    }

    /** 用临时词跑当前张数。每张各排一个任务，和抽卡的张数一样，但不去抽卡服务要词。
     *  r28.1：全部排进队列后（= 这句确实用上了）没钉就清空，下次回到抽卡；钉住则保留。
     *  中途失败会提前 return，不会清空。 */
    private fun runTempPrompt(text: String) {
        if (gachaDrawing || submitting) return
        val n = batchSize.coerceIn(1, GACHA_MAX_COUNT)
        viewModelScope.launch {
            gachaDrawing = true
            submitting = true
            message = ""
            try {
                repeat(n) {
                    if (!enqueueCurrent(text, batchOverride = 1)) return@launch
                }
                if (!tempPromptPinned) clearTempPrompt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                gachaDrawing = false
                submitting = false
            }
        }
    }

    /** 高级抽卡：向电脑上的抽卡页要词，每条词各排一个出图任务。不出图请求不打到那个服务的 /api/comfy。
     *  r40：地址为空不发请求，提示去设置（不再回落到对别人连不上的假默认地址）。 */
    fun drawAdvanced(count: Int) {
        if (gachaDrawing || submitting) return
        val n = count.coerceIn(1, GACHA_MAX_COUNT)
        viewModelScope.launch {
            gachaDrawing = true
            submitting = true
            message = ""
            try {
                val req = gachaRequest(c.settings.current(), n)
                val base = trimBase(req.base)
                if (base.isBlank()) {
                    message = getApplication<Application>().str(R.string.gacha_no_server)
                    return@launch
                }
                val prompts = c.gacha.draw(req.copy(base = base, count = n))
                if (prompts.isEmpty()) {
                    message = getApplication<Application>().str(R.string.err_gacha_empty)
                    return@launch
                }
                for (prompt in prompts) {
                    if (!enqueueCurrent(prompt, batchOverride = 1)) return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                gachaDrawing = false
                submitting = false
            }
        }
    }

    /**
     * 排一个出图任务。promptOverride 非空时写入主提示词槽和任务上的 prompt（图片信息读这个）。
     * @return false = 已经写了 message，调用方不要再往下排。
     */
    private suspend fun enqueueCurrent(promptOverride: String?, batchOverride: Int?): Boolean {
        val id = boundId ?: return false
        if (workflowJson == null) return false
        val app = getApplication<Application>()
        // r35：模型槽为空（内置模板的空占位还没选）不排任务——空值会原样发给 ComfyUI，
        // 只能在服务器端报节点验证错误；这里就地拦下，去模型页选一个就行
        val missingModel = specs.firstOrNull { Specs.type(it) == "model" && textValues[Specs.key(it)].isNullOrBlank() }
        if (missingModel != null) {
            message = app.str(R.string.err_model_not_selected)
            return false
        }
        val batchForJob = batchOverride ?: batchSize
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
                    val payload = if (outputKind == "video" && selected == VIDEO_SIZE_SOURCE) {
                        JSONArray().put(VIDEO_SIZE_720P)
                    } else {
                        JSONArray(sizesSelected)
                    }
                    values.put(key, payload)
                }
            }
        }
        val shownPrompt = if (promptOverride != null) {
            val key = mainPromptKey()
            if (key == null) {
                message = app.str(R.string.err_gacha_no_prompt_slot)
                return false
            }
            val text = promptOverride.trim().take(GACHA_PROMPT_CAP)
            if (text.isBlank()) {
                message = app.str(R.string.err_gacha_empty)
                return false
            }
            values.put(key, text)
            text
        } else {
            mainPrompt()
        }
        val firstRandom = specs.firstOrNull { Specs.type(it) == "int_random" }
        val seed = firstRandom?.let { textValues[Specs.key(it)]?.toLongOrNull() ?: 0L } ?: 0L
        val seedRandom = firstRandom?.let { randomOn[Specs.key(it)] ?: Specs.randomDefault(it) } ?: true
        var loraStates: JSONObject? = null
        var loraStrengths: JSONObject? = null
        if (loraEntries.isNotEmpty()) {
            val keys = loraEntries.map { it.key }.toSet()
            val states = JSONObject()
            loraOn.forEach { (k, v) -> if (k in keys) states.put(k, v) }
            val strengths = JSONObject()
            for (e in loraEntries) {
                for (f in e.strengthFields) {
                    val sk = loraStrengthKey(e.key, f)
                    val v = loraStrength[sk]?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: e.strengthDefaults[f]
                        ?: continue
                    strengths.put(sk, v)
                }
            }
            if (states.length() > 0) loraStates = states
            if (strengths.length() > 0) loraStrengths = strengths
        }
        val body = buildCustomBody(
            clientId = jobClientId,
            workflowId = id,
            prompt = shownPrompt,
            values = values,
            batch = batchForJob,
            seed = seed,
            seedRandom = seedRandom,
            outputNode = outputNode,
            outputKind = outputKind,
            workflowName = displayName.takeIf { it.isNotBlank() && it != id }
                ?: c.workflowStore.metaOf(id)
                    ?.let { getApplication<Application>().workflowLabelStr(it) }.orEmpty(),
            loraStates = loraStates,
            loraStrengths = loraStrengths,
        )
        val hasImageSpec = specs.any { Specs.type(it) == "file:image" }
        val hasVideoSpec = specs.any { Specs.type(it) == "file:video" }
        if (hasVideoSpec) {
            if (videoUri == null && videoPath == null) {
                message = app.str(R.string.err_need_video)
                return false
            }
            val staged = withContext(Dispatchers.IO) { stageVideo(app, jobClientId) } ?: return false
            body.put("local_video", staged.absolutePath)
        }
        if (hasImageSpec) {
            if (sourcePath == null && sourceUri == null) {
                message = app.str(R.string.err_need_reference)
                return false
            }
            val original = withContext(Dispatchers.IO) { stageOriginal(app, jobClientId) }
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
                JobRow(
                    jobClientId,
                    "",
                    "custom",
                    if (hasImageSpec || hasVideoSpec) "uploading" else "pending",
                    body.toString(),
                    now,
                    now,
                    sizeCount() * batchForJob,
                    0,
                    "",
                ),
            )
        }
        persistNow(boundId)
        startGenerationService(app)
        return true
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

    /**
     * 视频暂存（round17）：系统选择器与图库两种来源都复制一份到 filesDir/uploads/<jobId>_video.<ext>。
     * local_video 只能指向这个副本——引擎上传成功 / 取消 / 清理会删它，绝不能指向图库原文件。
     * 返回 null = 已设置 message，调用方不提交。
     */
    private suspend fun stageVideo(app: Application, id: String): File? {
        val dir = File(app.filesDir, "uploads").apply { mkdirs() }
        return withContext(Dispatchers.IO) {
            var out: File? = null
            try {
                if (videoUri != null) {
                    val uri = Uri.parse(videoUri)
                    val ext = videoExtFor(queryDisplayName(app, uri), try {
                        app.contentResolver.getType(uri)
                    } catch (_: Exception) {
                        null
                    })
                    val dest = File(dir, "${id}_video.$ext")
                    out = dest
                    val input = app.contentResolver.openInputStream(uri)
                    if (input == null) {
                        message = app.str(R.string.err_video_missing)
                        return@withContext null
                    }
                    input.use { ins ->
                        FileOutputStream(dest).use { outs -> ins.copyTo(outs) }
                    }
                } else {
                    val src = File(videoPath.orEmpty())
                    if (!src.isFile) {
                        message = app.str(R.string.err_video_missing)
                        return@withContext null
                    }
                    val ext = videoExtFor(src.name, null)
                    out = File(dir, "${id}_video.$ext")
                    stageVideoFile(src, dir, id, ext)
                }
                // 选择时拿不到大小的情况：按实际复制字节数再拦一次
                if (out.length() > VIDEO_MAX_BYTES) {
                    out.delete()
                    message = app.str(R.string.video_too_large, formatBytes(VIDEO_MAX_BYTES))
                    return@withContext null
                }
                out
            } catch (e: FileNotFoundException) {
                out?.delete()
                message = app.str(R.string.err_video_missing)
                null
            } catch (e: Exception) {
                out?.delete()
                message = app.str(R.string.err_video_stage, e.message ?: e.javaClass.simpleName)
                null
            }
        }
    }

    private fun queryDisplayName(app: Application, uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
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
        // 用户手动点「继续」：允许提交恢复失败后重新提交（区别于自动 reconcile）
        c.engine.resumeManual(clientJobId)
        startGenerationService(getApplication())
    }

    /** 视频下载失败后的重试：重启轮询协程（jobId 已记录，不会重新提交）。 */
    fun retryDownload(clientJobId: String) {
        c.engine.retryDownload(clientJobId)
        startGenerationService(getApplication())
    }

    fun answerUploadAsk(clientId: String, compress: Boolean) = c.engine.answerUploadAsk(clientId, compress)

    /**
     * round19：收集器里的异常必须就地吃掉，不能让「读失败」升级成「闪退」。
     *
     * `viewModelScope` 没有 CoroutineExceptionHandler——上一轮真机崩溃正是发生在
     * `c.engine.revision.collect { reload() }` 这条路径上：`GalleryDb.kt:436` 的
     * `IllegalStateException: Couldn't read row N from CursorWindow` 一路冒到线程顶，
     * 直接终结进程（不是什么「图库崩了」，是整个 App 死）。
     */
    private suspend fun safeReload() {
        try {
            reload()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "reload failed", t)
        }
    }

    private suspend fun reload() {
        // 整个读取留在 IO 上（round18）：调用方是 revision 收集器，列表大时不能占着主线程
        val loaded = withContext(Dispatchers.IO) {
            // 文本结果没有本地文件（内容在行里），不能按文件存在过滤掉
            c.db.images().filter { it.kind == "text" || File(it.localPath).exists() }
        }
        gallery = loaded
        results = loaded.take(20)
        mediaLoaded = true
        backfillSeed(loaded)
    }

    /** 「从图库选择」参考图网格的数据源：只要图片行（文本行没有文件、视频不能当参考图）。 */
    fun pickerImages(): List<ImageRow> = gallery.filter { it.kind == "image" }

    /** 参考视频「从图库选择」的数据源（round17）：只列 localPath 非空且文件还在的视频行。 */
    fun pickerVideos(): List<ImageRow> =
        pickableVideos(gallery).filter { File(it.localPath).isFile }

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
            safeReload()
            c.engine.revision.collect { safeReload() }
        }
        // r38 E5：编辑参数页覆盖了某工作流的 definition——正是当前绑定时强制重载到新定义
        viewModelScope.launch {
            c.workflowStore.definitionEdits.collect { edit ->
                val edited = edit?.first ?: return@collect
                onDefinitionEdited(edited)
            }
        }
        // 当前提示词库变化（导入/切换/删除）时刷新抽卡筛选的分类列表（自 T2iModel 移植）
        viewModelScope.launch {
            c.library.currentId.collect {
                refreshDrawCategories()
            }
        }
    }
}

private const val TAG = "kwb-custom"
