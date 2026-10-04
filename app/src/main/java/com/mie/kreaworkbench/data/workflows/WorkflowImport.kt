package com.mie.kreaworkbench.data.workflows

import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.known
import com.mie.kreaworkbench.ui.locale.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作流导入的数据层：文件判型、spec 读取、校验（对应 PC workflow_validator 规则）、
 * 路 B 的 infer_spec 移植。全部纯函数，不碰网络不碰存储。
 */

enum class FileKind { DEFINITION, API_WORKFLOW, UI_WORKFLOW, UNKNOWN }

class ImportParseException(message: String) : Exception(message)

private val KNOWN_TYPES = setOf(
    "text", "prompt_pool", "select", "model", "int", "int_random",
    "float", "bool", "image_sizes", "file:image",
)

/** 按内容判断文件类型（PC 上工作流和定义同名，不能按文件名）。 */
fun classifyFile(name: String, text: String): Pair<FileKind, JSONObject> {
    val json = try {
        JSONObject(text)
    } catch (e: Exception) {
        val app = KreaApp.instance
        throw ImportParseException(app.str(R.string.err_bad_json, name, e.message ?: app.str(R.string.err_parse)))
    }
    return when {
        json.has("user_facing_inputs") && json.optJSONArray("user_facing_inputs") != null -> FileKind.DEFINITION to json
        json.has("nodes") && json.has("links") -> FileKind.UI_WORKFLOW to json
        isApiWorkflow(json) -> FileKind.API_WORKFLOW to json
        else -> FileKind.UNKNOWN to json
    }
}

/** API 格式工作流：顶层对象，至少一个值是带 class_type 和 inputs 的对象。 */
fun isApiWorkflow(json: JSONObject): Boolean {
    val keys = json.keys()
    while (keys.hasNext()) {
        val node = json.optJSONObject(keys.next()) ?: continue
        if (node.has("class_type") && node.has("inputs")) return true
    }
    return false
}

// ---------- spec 读取助手（未知键原样保留在 JSONObject 里，只按已知键取值） ----------

object Specs {
    fun nodeId(o: JSONObject): String = o.optString("node_id")
    fun field(o: JSONObject): String = o.optString("field")
    fun key(o: JSONObject): String = "${nodeId(o)}|${field(o)}"
    fun type(o: JSONObject): String = o.optString("type", "text")
    fun label(o: JSONObject): String = o.optString("label").ifBlank { field(o) }
    fun tier(o: JSONObject): String = o.optString("tier", "basic")
    fun help(o: JSONObject): String = o.optString("help")
    fun exampleKey(o: JSONObject): String = o.optString("example_key")
    fun maxChars(o: JSONObject): Int = o.optInt("max_chars", 2000)
    fun randomDefault(o: JSONObject): Boolean = o.optBoolean("random_default", false)
    fun allowCustom(o: JSONObject): Boolean = o.optBoolean("allow_custom", false)

    fun min(o: JSONObject): Double? = if (o.has("min")) o.optDouble("min") else null
    fun max(o: JSONObject): Double? = if (o.has("max")) o.optDouble("max") else null
    fun step(o: JSONObject): Double? = if (o.has("step")) o.optDouble("step") else null

    fun defaultText(o: JSONObject): String = when (val d = o.opt("default")) {
        null -> ""
        is String -> d
        is JSONArray -> buildString {
            for (i in 0 until d.length()) {
                if (i > 0) append("\n")
                append(d.optString(i))
            }
        }
        else -> d.toString()
    }

    fun defaultNumber(o: JSONObject): Double = when (val d = o.opt("default")) {
        is Number -> d.toDouble()
        is String -> d.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    fun defaultBool(o: JSONObject): Boolean = o.optBoolean("default", false)

    /** image_sizes 的 default 可能是 "WxH" 字符串或 ["WxH", ...] 数组。 */
    fun defaultSizes(o: JSONObject): List<String> = when (val d = o.opt("default")) {
        is String -> if (d.isBlank()) emptyList() else listOf(d.trim())
        is JSONArray -> buildList {
            for (i in 0 until d.length()) {
                val s = d.optString(i).trim()
                if (s.isNotBlank()) add(s)
            }
        }
        else -> emptyList()
    }

    /** select 的 choices：字符串数组或 {label,value} 数组，统一成 label→value。 */
    fun choices(o: JSONObject): List<Pair<String, String>> {
        val arr = o.optJSONArray("choices") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is String -> add(v to v)
                    is JSONObject -> {
                        val label = v.optString("label").ifBlank { v.optString("value") }
                        add(label to v.optString("value"))
                    }
                }
            }
        }
    }

    /** image_sizes 的 presets：{label, value:"WxH"} 数组。 */
    fun sizePresets(o: JSONObject): List<Pair<String, String>> {
        val arr = o.optJSONArray("presets") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val value = p.optString("value")
                if (value.isNotBlank()) add(p.optString("label").ifBlank { value } to value)
            }
        }
    }

    /** 自定义宽高的夹紧范围：min/max_width/height，缺省 256..2560。 */
    fun widthRange(o: JSONObject): IntRange {
        val lo = (if (o.has("min_width")) o.optInt("min_width", 256) else o.optInt("min", 256)).coerceAtLeast(16)
        val hi = (if (o.has("max_width")) o.optInt("max_width", 2560) else o.optInt("max", 2560))
        return lo..hi.coerceAtLeast(lo)
    }

    fun heightRange(o: JSONObject): IntRange {
        val lo = (if (o.has("min_height")) o.optInt("min_height", 256) else o.optInt("min", 256)).coerceAtLeast(16)
        val hi = (if (o.has("max_height")) o.optInt("max_height", 2560) else o.optInt("max", 2560))
        return lo..hi.coerceAtLeast(lo)
    }

    /** mirror_to / also_patch 的附加写入目标（统一按 [{node_id, field}] 读）。 */
    fun extraTargets(o: JSONObject): List<Pair<String, String>> {
        val arr = o.optJSONArray("mirror_to") ?: o.optJSONArray("also_patch") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val nid = t.optString("node_id")
                val f = t.optString("field")
                if (nid.isNotBlank() && f.isNotBlank()) add(nid to f)
            }
        }
    }

    /** fixed_patches：{node_id: {field: value}}。 */
    fun fixedPatches(wf: JSONObject): List<Triple<String, String, Any?>> = buildList {
        val fp = wf.optJSONObject("fixed_patches") ?: return@buildList
        val keys = fp.keys()
        while (keys.hasNext()) {
            val nid = keys.next()
            val fields = fp.optJSONObject(nid) ?: continue
            val fk = fields.keys()
            while (fk.hasNext()) {
                val f = fk.next()
                add(Triple(nid, f, fields.opt(f)))
            }
        }
    }
}

// ---------- 输出类型（round6：image | text | video） ----------

/** class_type 去掉「|」及其后面的部分（ShowAnything|Mie → ShowAnything）。 */
fun baseClass(classType: String): String = classType.substringBefore('|').trim()

private val TEXT_OUTPUT_CLASSES = setOf("ShowAnything", "ShowText", "PreviewAny", "ShowString")
private val VIDEO_OUTPUT_CLASSES = setOf("VHS_VideoCombine", "SaveVideo", "SaveWEBM")

/** 文本输出节点 id 列表（class 基名匹配）。 */
fun textOutputNodes(wf: JSONObject): List<String> = outputNodesOf(wf, TEXT_OUTPUT_CLASSES)

/** 视频输出节点 id 列表（class 基名匹配）。 */
fun videoOutputNodes(wf: JSONObject): List<String> = outputNodesOf(wf, VIDEO_OUTPUT_CLASSES)

private fun outputNodesOf(wf: JSONObject, classes: Set<String>): List<String> = buildList {
    val keys = wf.keys()
    while (keys.hasNext()) {
        val nid = keys.next()
        val node = wf.optJSONObject(nid) ?: continue
        if (baseClass(node.optString("class_type")) in classes) add(nid)
    }
}

/** 按输出节点推断类型：video > text > image。 */
fun inferOutputKind(wf: JSONObject): String = when {
    videoOutputNodes(wf).isNotEmpty() -> "video"
    textOutputNodes(wf).isNotEmpty() -> "text"
    else -> "image"
}

/** definition 的输出类型：kwb_output 优先 → output_kind → 默认 image（旧定义）。 */
fun outputKindOf(defJson: JSONObject): String {
    val k = defJson.optString("kwb_output")
    if (k in setOf("image", "text", "video")) return k
    val kind = defJson.optString("output_kind")
    if (kind in setOf("image", "text", "video")) return kind
    return "image"
}

/**
 * 抽卡只服务文生图：输出是图片，且没有任何参考图槽（file:image）。
 * 图生图、视频、打标（文本输出）以及其它输出类型一律 false。抽卡控件只许问这一个函数。
 */
fun isTextToImageWorkflow(outputKind: String, specs: Iterable<JSONObject>): Boolean {
    if (outputKind != "image") return false
    for (spec in specs) {
        if (Specs.type(spec) == "file:image") return false
    }
    return true
}

/** 视频尺寸「保持参考图原始分辨率」。不是 WxH，parseSizes 会跳过。 */
const val VIDEO_SIZE_SOURCE = "source"

/** 视频「自定义尺寸」的默认值：720P。 */
const val VIDEO_SIZE_720P = "1280x720"

/** 生成页输出尺寸说明（图片：预设或自定义）。结尾不加标点。 */
const val HELP_OUTPUT_SIZE = "可选预设尺寸，或填入自定义尺寸"

/** 视频没有预设尺寸，和图片共用 image_sizes 的说明位时用这句。 */
const val HELP_OUTPUT_SIZE_VIDEO = "填入自定义尺寸，或保持参考图原始分辨率。"

/** 生成页种子说明，对应「随机 / 固定」两个按钮。 */
const val HELP_SEED = "选「随机」每张图自动换种子；选「固定」使用上面的数值。"

private const val HELP_OUTPUT_SIZE_OLD = "勾选多个预设尺寸，或填自定义宽×高（每个尺寸出一张图）。"
/** 上一版输出尺寸说明，末尾多了句号。已导入的定义里可能还是这句。 */
private const val HELP_OUTPUT_SIZE_DOT = "可选预设尺寸，或填入自定义尺寸。"
private const val HELP_SEED_OLD = "勾上「随机」则每张图各自随机；不勾则用固定数值。"

private fun isImageOutputSizeHelp(help: String): Boolean =
    help == HELP_OUTPUT_SIZE || help == HELP_OUTPUT_SIZE_OLD || help == HELP_OUTPUT_SIZE_DOT

/**
 * 生成页展示用的说明。已导入的定义里若还是旧句，这里换成新句，不改存盘内容。
 * 视频的尺寸说明若仍是图片那句（旧句、带句号的上一版，或新句），换成没有预设的说法。
 */
fun shownHelp(spec: JSONObject, outputKind: String): String {
    val help = Specs.help(spec)
    if (Specs.type(spec) == "image_sizes" && outputKind == "video" && isImageOutputSizeHelp(help)) {
        return HELP_OUTPUT_SIZE_VIDEO
    }
    return when (help) {
        HELP_OUTPUT_SIZE_OLD, HELP_OUTPUT_SIZE_DOT -> HELP_OUTPUT_SIZE
        HELP_SEED_OLD -> HELP_SEED
        else -> help
    }
}

/** 原始分辨率的提醒线：spec 写了 max_width/max_height 就用它，否则 1280（720P 长边）。 */
fun videoSourceWarnLimit(spec: JSONObject): Pair<Int, Int> {
    val w = if (spec.has("max_width")) spec.optInt("max_width", 1280).coerceAtLeast(16) else 1280
    val h = if (spec.has("max_height")) spec.optInt("max_height", 1280).coerceAtLeast(16) else 1280
    return w to h
}

// ---------- 校验（PC workflow_validator 规则，列出全部问题） ----------

/** 返回全部问题；空列表 = 通过。defJson 定义、wfJson API 工作流。 */
fun validateDefinition(defJson: JSONObject, wfJson: JSONObject): List<String> {
    val problems = mutableListOf<String>()
    val app = KreaApp.instance

    when (outputKindOf(defJson)) {
        "text" -> if (textOutputNodes(wfJson).isEmpty()) {
            problems.add(app.str(R.string.err_no_text_node))
        }
        "video" -> if (videoOutputNodes(wfJson).isEmpty()) {
            problems.add(app.str(R.string.err_no_video_node))
        }
        else -> if (imageSaverNodes(wfJson).isEmpty()) {
            // 出图节点：支持 SaveImage / SaveImageAdvanced / PreviewImage（含模糊兜底）
            problems.add(app.str(R.string.err_no_image_node))
        }
    }

    val specs = defJson.optJSONArray("user_facing_inputs")
    if (specs == null || specs.length() == 0) {
        problems.add(app.str(R.string.err_no_inputs))
        return problems
    }

    var fileImageCount = 0
    val unsupported = mutableListOf<String>()
    for (i in 0 until specs.length()) {
        val spec = specs.optJSONObject(i) ?: continue
        val label = app.known(Specs.label(spec))
        val type = Specs.type(spec)
        val nid = Specs.nodeId(spec)
        val field = Specs.field(spec)

        if (type !in KNOWN_TYPES) {
            unsupported.add(app.str(R.string.err_bad_type, label, type))
            continue
        }
        if (type == "file:image") fileImageCount++

        val node = wfJson.optJSONObject(nid)
        if (node == null) {
            problems.add(app.str(R.string.err_node_missing, label, nid))
            continue
        }
        val inputs = node.optJSONObject("inputs") ?: JSONObject()
        if (type == "image_sizes") {
            // field 固定叫 sizes，实际改的是 width/height/batch_size
            if (!inputs.has("width") || !inputs.has("height")) {
                problems.add(app.str(R.string.err_node_no_size, label, nid))
            }
        } else if (!inputs.has(field)) {
            problems.add(app.str(R.string.err_node_no_field, label, nid, field))
        }
        for ((tnid, tfield) in Specs.extraTargets(spec)) {
            val tnode = wfJson.optJSONObject(tnid)
            if (tnode == null) {
                problems.add(app.str(R.string.err_extra_missing, label, tnid))
                continue
            }
            if (tnode.optJSONObject("inputs")?.has(tfield) != true) {
                problems.add(app.str(R.string.err_extra_no_field, label, tnid, tfield))
            }
        }
    }
    if (fileImageCount > 1) problems.add(app.str(R.string.err_one_image, fileImageCount))
    if (unsupported.isNotEmpty()) {
        problems.add(app.str(R.string.err_unsupported_types, unsupported.joinToString(app.str(R.string.list_sep))))
    }

    // fixed_patches 的节点必须存在（fixed_patches 是定义的顶层键，PC runner 同位置）
    for ((nid, field, _) in Specs.fixedPatches(defJson)) {
        if (wfJson.optJSONObject(nid) == null) {
            problems.add(app.str(R.string.err_patch_missing, nid))
        } else if (wfJson.optJSONObject(nid)?.optJSONObject("inputs")?.has(field) != true) {
            problems.add(app.str(R.string.err_patch_no_field, nid, field))
        }
    }
    return problems
}

/**
 * 出图节点候选：class_type 精确匹配 SaveImage / SaveImageAdvanced / PreviewImage，
 * 外加模糊兜底（class_type 忽略大小写包含 saveimage / previewimage）；
 * 组内按节点号数字升序，跨组优先级 SaveImage > SaveImageAdvanced > PreviewImage > 其他模糊命中。
 */
fun imageSaverNodes(wf: JSONObject): List<String> {
    val byRank = Array(4) { ArrayList<String>() }
    val keys = wf.keys()
    while (keys.hasNext()) {
        val nid = keys.next()
        val node = wf.optJSONObject(nid) ?: continue
        val cls = baseClass(node.optString("class_type"))
        if (cls.isBlank()) continue
        val lower = cls.lowercase()
        val rank = when {
            cls == "SaveImage" -> 0
            cls == "SaveImageAdvanced" -> 1
            cls == "PreviewImage" -> 2
            "saveimage" in lower || "previewimage" in lower -> 3
            else -> -1
        }
        if (rank < 0) continue
        byRank[rank].add(nid)
    }
    return byRank.flatMap { group -> group.distinct().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE } }
}

/** 自动选输出节点：按 imageSaverNodes 的优先级取第一个。 */
fun pickOutputNode(wf: JSONObject): String = imageSaverNodes(wf).firstOrNull() ?: ""

// ---------- 路 B：infer_spec 移植 ----------

/** 候选字段：spec JSON 带 runtime 字段（confidence/current_value/node_title/enabled/_merge_key/class_type）。 */
data class Candidate(val spec: JSONObject, val nodeName: String)

private val SIZE_LATENT_CLASSES = setOf(
    "EmptySD3LatentImage", "EmptyFlux2LatentImage", "EmptyLatentImage", "EmptyLatentImageCustomPresets",
)

private val LOADER_MAP = mapOf(
    "CheckpointLoaderSimple" to ("ckpt_name" to "大模型"),
    "UNETLoader" to ("unet_name" to "扩散模型"),
    "UnetLoaderGGUF" to ("unet_name" to "扩散模型"),
    "VAELoader" to ("vae_name" to "VAE"),
    "CLIPLoader" to ("clip_name" to "文本编码器"),
    "CLIPVisionLoader" to ("clip_name" to "视觉编码器"),
    "LoraLoader" to ("lora_name" to "LoRA"),
    "LoraLoaderModelOnly" to ("lora_name" to "LoRA"),
    "LatentUpscaleModelLoader" to ("model_name" to "放大模型"),
)

/** 对 API 工作流的每个节点每个 input 按顺序推断，命中即停；返回按节点号+字段顺序的候选列表。 */
fun inferSpecs(wf: JSONObject): List<Candidate> {
    val out = ArrayList<Candidate>()
    val nodeIds = wf.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    for (nid in nodeIds) {
        val node = wf.optJSONObject(nid) ?: continue
        val classType = node.optString("class_type")
        val inputs = node.optJSONObject("inputs") ?: continue
        val nodeTitle = node.optJSONObject("_meta")?.optString("title").orEmpty()
            .ifBlank { classType }
        val fieldKeys = inputs.keys().asSequence().toList()
        for (field in fieldKeys) {
            val value = inputs.opt(field) ?: continue
            val spec = inferOne(nid, field, value, classType, nodeTitle) ?: continue
            out.add(Candidate(spec, nodeTitle))
        }
    }
    return mergeImageSizes(mergeSameNameFields(out))
}

/**
 * 同名字段合并（round6）：同一 class 基名、同字段、同类型、同当前值的候选（例如 Wan 工作流
 * 两个 KSamplerAdvanced 的 noise_seed/steps/cfg/sampler_name/scheduler）合并成一个 spec，
 * 其余节点写进 mirror_to，运行时一个控件同时写多个节点。值不同（如 start_at_step）不合并。
 */
private fun mergeSameNameFields(candidates: List<Candidate>): List<Candidate> {
    data class Ref(val nid: String, val field: String)
    val consumed = HashSet<Candidate>()
    val out = ArrayList<Candidate>(candidates.size)
    for (c in candidates) {
        if (c in consumed) continue
        val spec = c.spec
        val field = Specs.field(spec)
        val type = spec.optString("type")
        val cls = baseClass(spec.optString("class_type"))
        val value = spec.opt("current_value")
        if (field.isBlank() || type == "image_sizes" || cls.isBlank()) {
            out.add(c)
            continue
        }
        val same = candidates.filter {
            it !== c && it !in consumed &&
                Specs.field(it.spec) == field &&
                it.spec.optString("type") == type &&
                baseClass(it.spec.optString("class_type")) == cls &&
                sameValue(it.spec.opt("current_value"), value)
        }
        if (same.isEmpty()) {
            out.add(c)
            continue
        }
        same.forEach { consumed.add(it) }
        val targets = JSONArray()
        for (s in same) targets.put(JSONObject().put("node_id", Specs.nodeId(s.spec)).put("field", field))
        val merged = JSONObject(spec.toString())
        val existing = merged.optJSONArray("mirror_to") ?: JSONArray()
        for (i in 0 until targets.length()) existing.put(targets.opt(i))
        merged.put("mirror_to", existing)
        out.add(Candidate(merged, c.nodeName))
    }
    return out
}

private fun sameValue(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    else -> a?.toString() == b?.toString() && a != null
}

/** 推断单个 input；null = 不该暴露给用户。规则顺序见 PC wizard.infer_spec。
 *  附带运行时键 class_type（buildDefinition 保存时照旧剥离），供路 B combo 升级查 /object_info 用。 */
private fun inferOne(nid: String, field: String, value: Any?, classType: String, nodeTitle: String): JSONObject? {
    // 1. 连线引用 [字符串, 整数]
    if (value is JSONArray && value.length() == 2 &&
        value.opt(0) is String && value.opt(1) is Number
    ) {
        return null
    }
    val base = JSONObject()
        .put("node_id", nid)
        .put("field", field)
        .put("class_type", classType)

    // 2/3. 文件槽（高）
    if (classType == "LoadImage" && field == "image") {
        return base.put("type", "file:image").put("default", value ?: "")
            .put("confidence", "high").put("current_value", value ?: "")
            .put("node_title", nodeTitle).put("enabled", true)
            .put("help", "上传本次生成使用的参考图片。")
    }
    if (classType == "VHS_LoadVideo" && field == "video") {
        return base.put("type", "file:video").put("default", value ?: "")
            .put("confidence", "high").put("current_value", value ?: "")
            .put("node_title", nodeTitle).put("enabled", false)
            .put("help", "本版不支持视频输入。")
    }

    // 4. 加载器模型字段（高，tier=model）
    LOADER_MAP[classType]?.let { (loaderField, label) ->
        if (field == loaderField) {
            return base.put("type", "model").put("default", value ?: "")
                .put("tier", "model").put("confidence", "high")
                .put("current_value", value ?: "").put("node_title", nodeTitle)
                .put("enabled", true).put("label", label)
        }
    }

    // 5. seed / noise_seed → int_random（高）
    if (field == "seed" || field == "noise_seed") {
        return base.put("type", "int_random").put("default", (value as? Number)?.toLong() ?: 0L)
            .put("random_default", true).put("confidence", "high")
            .put("current_value", value ?: 0).put("node_title", nodeTitle).put("enabled", true)
            .put("min", 0).put("max", 9223372036854775807L).put("step", 1)
            .put("label", "种子值")
            .put("help", HELP_SEED)
    }

    // 6. 尺寸 latent 的 width/height → image_sizes（合并，高）；field 保持 width/height，
    //    由 mergeImageSizes 配对后统一改成 sizes
    //    round6：ImageResizeKJv2 的 width/height 同样进「尺寸与张数」卡片
    if ((classType in SIZE_LATENT_CLASSES || baseClass(classType) == "ImageResizeKJv2") &&
        (field == "width" || field == "height")
    ) {
        return base.put("type", "image_sizes")
            .put("default", 0) // 占位，mergeImageSizes 里重写为 "WxH"
            .put("confidence", "high").put("current_value", value ?: 0)
            .put("node_title", nodeTitle).put("enabled", true)
            .put("_merge_key", nid).put("label", "输出尺寸")
            .put("help", HELP_OUTPUT_SIZE)
    }

    // 6b. 视频参数：WanImageToVideo.length → 帧数、VHS_VideoCombine.frame_rate → 帧率，
    //     放生成页（kwb_gen 标记，不进设置页）
    if (value is Number && field == "length" && baseClass(classType) == "WanImageToVideo") {
        return base.put("type", "int").put("default", value.toLong()).put("kwb_gen", true)
            .put("confidence", "high").put("current_value", value)
            .put("node_title", nodeTitle).put("enabled", true)
            .put("min", 5).put("max", 241).put("step", 1)
            .put("label", "帧数")
            .put("help", "总帧数；Wan 建议 4n+1，帧越多越慢越吃显存。")
    }
    if (value is Number && field == "frame_rate" && baseClass(classType) == "VHS_VideoCombine") {
        return base.put("type", "int").put("default", value.toLong()).put("kwb_gen", true)
            .put("confidence", "high").put("current_value", value)
            .put("node_title", nodeTitle).put("enabled", true)
            .put("min", 1).put("max", 60).put("step", 1)
            .put("label", "帧率")
            .put("help", "合成视频的播放帧率（fps）。")
    }

    // 7. bool（低）
    if (value is Boolean) {
        return base.put("type", "bool").put("default", value)
            .put("confidence", "low").put("current_value", value)
            .put("node_title", nodeTitle).put("enabled", false)
            .put("label", "${nodeTitle} · $field")
    }

    // 8. 数字（低）：整数 int，小数 float
    if (value is Number) {
        val isFloat = value is Double || value is Float
        val typ = if (isFloat) "float" else "int"
        val def: Any = if (isFloat) value.toDouble() else value.toLong()
        return base.put("type", typ).put("default", def)
            .put("confidence", "low").put("current_value", value)
            .put("node_title", nodeTitle).put("enabled", false)
            .put("label", "${nodeTitle} · $field")
    }

    // 9/10. 字符串
    if (value is String) {
        // 9a. 负向提示词：节点标题带 Negative 的 CLIPTextEncode → 独立折叠卡（不能当第二个正向提示词）
        if (baseClass(classType) == "CLIPTextEncode" && field == "text" &&
            nodeTitle.contains("negative", ignoreCase = true)
        ) {
            return base.put("type", "text").put("default", value)
                .put("confidence", "high").put("current_value", value)
                .put("node_title", nodeTitle).put("enabled", true)
                .put("kwb_negative", true)
                .put("max_chars", 2000).put("label", "负向提示词")
                .put("help", "不希望出现的内容；留空则保持工作流原值。")
        }
        if (classType == "CLIPTextEncode" && field == "text" && value.length > 20) {
            return base.put("type", "prompt_pool").put("default", value)
                .put("confidence", "medium").put("current_value", value)
                .put("node_title", nodeTitle).put("enabled", true)
                .put("max_chars", 2000).put("label", "提示词")
                .put("help", "描述希望生成的内容、风格与构图。")
        }
        return base.put("type", "text").put("default", value)
            .put("confidence", "low").put("current_value", value)
            .put("node_title", nodeTitle).put("enabled", false)
            .put("max_chars", 2000).put("label", "${nodeTitle} · $field")
    }
    return null
}

/** 同一节点的 width/height 候选合并成一个 image_sizes spec（field=sizes，default="WxH"）。 */
private fun mergeImageSizes(candidates: List<Candidate>): List<Candidate> {
    val merged = ArrayList<Candidate>()
    val consumed = HashSet<Candidate>()
    for (c in candidates) {
        if (c in consumed) continue
        if (c.spec.optString("type") != "image_sizes") {
            merged.add(c)
            continue
        }
        val nid = c.spec.optString("node_id")
        val otherField = if (c.spec.optString("field") == "width") "height" else "width"
        val other = candidates.firstOrNull {
            it !== c && it !in consumed && it.spec.optString("type") == "image_sizes" &&
                it.spec.optString("node_id") == nid && it.spec.optString("field") == otherField
        }
        consumed.add(c)
        other?.let { consumed.add(it) }
        val widthC = if (c.spec.optString("field") == "width") c else other
        val heightC = if (widthC === c) other else c
        val w = (widthC?.spec?.opt("current_value") as? Number)?.toInt() ?: 0
        val h = (heightC?.spec?.opt("current_value") as? Number)?.toInt() ?: 0
        val spec = c.spec
        spec.put("field", "sizes")
        spec.put("default", "${w}x${h}")
        merged.add(Candidate(spec, c.nodeName))
    }
    return merged
}

/**
 * 路 B 组合升级（inferSpecs 之后调用）：object_info 查一次，对每个 type=="text" 的候选查
 * object_info[class_type].input.required[field][0]，是字符串数组 → 改 type="select"、
 * 写私有键 kwb_combo = "<class_type>|<field>"。置信度/默认勾选不变（低、不勾）。
 * objectInfo 为 null（网络不通等）静默跳过。路 A 不做升级。
 */
fun upgradeCombos(candidates: List<Candidate>, objectInfo: JSONObject?): List<Candidate> {
    if (objectInfo == null) return candidates
    return candidates.map { cand ->
        val spec = cand.spec
        if (spec.optString("type") != "text") return@map cand
        val cls = spec.optString("class_type")
        val field = Specs.field(spec)
        if (cls.isBlank() || field.isBlank()) return@map cand
        val arr = objectInfo.optJSONObject(cls)?.optJSONObject("input")
            ?.optJSONObject("required")?.optJSONArray(field)?.optJSONArray(0)
            ?: return@map cand
        var count = 0
        for (i in 0 until arr.length()) {
            if (arr.optString(i).isNotBlank()) count++
        }
        if (count == 0) return@map cand
        spec.put("type", "select")
        spec.put("kwb_combo", "$cls|$field")
        cand
    }
}

/**
 * 路 B 保存：把勾选并排好序的候选 spec 转成 PC 同格式定义。
 * 去掉运行时字段（class_type/confidence/current_value/node_title/enabled/_merge_key），
 * combo 升级写入的 kwb_combo 不在剥离名单里、保留在 definition（运行时实时拉选项的依据），
 * default 已在 UI 层从工作流当前值取好。
 */
fun buildDefinition(
    displayName: String,
    workflowFileName: String,
    outputNode: String,
    orderedSpecs: List<JSONObject>,
    outputKind: String = "image",
): JSONObject {
    val kind = if (outputKind in setOf("image", "text", "video")) outputKind else "image"
    val specs = JSONArray()
    for (raw in orderedSpecs) {
        val spec = JSONObject(raw.toString())
        for (k in listOf("class_type", "confidence", "current_value", "node_title", "enabled", "_merge_key")) {
            spec.remove(k)
        }
        if (spec.optString("type") == "image_sizes") {
            spec.put("field", "sizes")
            spec.put("allow_custom", true)
            // 视频和图片共用这一字段；保存时把图片那句说明换成没有预设的说法
            if (kind == "video" && isImageOutputSizeHelp(spec.optString("help"))) {
                spec.put("help", HELP_OUTPUT_SIZE_VIDEO)
            }
        }
        specs.put(spec)
    }
    return JSONObject()
        .put("workflow_file", workflowFileName)
        .put("display_name", displayName)
        .put("category", kind)
        .put("group", "图像")
        .put("output_kind", kind)
        .put("kwb_output", kind)
        .put("kwb_output_node", outputNode)
        .put("user_facing_inputs", specs)
}

/** 路 A 导入：定义缺 kwb_output_node 时按规则自动定并写回（返回新对象，不动原定义）。 */
fun withAutoOutputNode(defJson: JSONObject, wfJson: JSONObject): JSONObject {
    if (defJson.optString("kwb_output_node").isNotBlank()) return defJson
    val out = JSONObject(defJson.toString())
    val node = pickOutputNode(wfJson)
    if (node.isNotBlank()) out.put("kwb_output_node", node)
    return out
}

/**
 * 导出用：深拷贝 definition，按 last_values.json 覆盖每个 spec 的 default——
 * 即「带当前保存后的参数」的 definition 快照（text/prompt_pool/select/model→values[key]；
 * int/float→当前值；int_random→当前值且 random_default=当前开关；bool→当前值；
 * image_sizes→当前选中，1 个写 "WxH" 字符串、多个写数组、空则保留原 default）。
 * fixed_patches 不动；workflow.json 原样导出（运行时本来就会被 spec 覆盖）。
 */
fun exportDefinition(defJson: JSONObject, saved: JSONObject): JSONObject {
    val out = JSONObject(defJson.toString())
    val values = saved.optJSONObject("values") ?: JSONObject()
    val randoms = saved.optJSONObject("random") ?: JSONObject()
    val sizes = saved.optJSONArray("sizes")
    val specs = out.optJSONArray("user_facing_inputs") ?: return out
    for (i in 0 until specs.length()) {
        val spec = specs.optJSONObject(i) ?: continue
        val key = Specs.key(spec)
        when (Specs.type(spec)) {
            "text", "prompt_pool", "select", "model" ->
                if (values.has(key)) spec.put("default", values.optString(key))
            "int", "int_random" -> {
                if (values.has(key)) spec.put("default", values.optLong(key))
                if (Specs.type(spec) == "int_random" && randoms.has(key)) {
                    spec.put("random_default", randoms.optBoolean(key))
                }
            }
            "float" -> if (values.has(key)) spec.put("default", values.optDouble(key))
            "bool" -> if (values.has(key)) spec.put("default", values.optBoolean(key))
            "image_sizes" -> {
                if (sizes != null && sizes.length() > 0) {
                    if (sizes.length() == 1) {
                        spec.put("default", sizes.optString(0))
                    } else {
                        spec.put("default", JSONArray(sizes.toString()))
                    }
                }
            }
        }
    }
    return out
}

/** 导出文件名：display_name 过滤非法字符（\ / : * ? " < > | 及控制符），去结尾点和空格，空则回退。 */
fun safeExportName(display: String): String {
    val cleaned = display
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trimEnd('.', ' ')
    return cleaned.ifBlank { "workflow" }
}
