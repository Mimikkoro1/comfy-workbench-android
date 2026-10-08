package com.mie.kreaworkbench.data.workflows

import org.json.JSONArray
import org.json.JSONObject

/**
 * LoRA 开关与强度（round16）：从 API 工作流运行时识别单 LoRA 加载器与 rgthree Power Lora Loader
 * 槽位，提供开关（关 = 强度清 0 / 槽 on=false）与强度数字框。不改连线、不做节点 bypass。
 * 全部纯函数，只依赖 org.json，不引用 Android / KreaApp / R（单测直接调用）。
 */

/** 强度字段白名单：识别与清零都按这个顺序（JSONObject 是 HashMap 顺序，不能按 inputs.keys()）。 */
val LORA_STRENGTH_FIELDS = listOf(
    "strength_model", "strength_clip", "strength", "lora_strength", "model_strength", "clip_strength",
)

/** 一个 LoRA 开关。key：单加载器 = 节点号（"12"）；Power 槽位 = "节点号:槽名"（"5:lora_1"）。 */
data class LoraEntry(
    val key: String,
    val nodeId: String,
    val slot: String?,              // Power 槽名；单加载器为 null
    val classType: String,
    val nodeTitle: String,          // _meta.title，空则 classType
    val fileField: String?,         // 单加载器："lora_name" / "lora"；Power：null
    val fileName: String,           // 文件名；连线 / 拿不到为 ""
    val strengthFields: List<String>, // 单加载器：有数字值的强度字段，按 LORA_STRENGTH_FIELDS 顺序；
                                      // Power：["strength"]，槽里有数字 strengthTwo 时再加 "strengthTwo"
    val strengthDefaults: Map<String, Double>, // field → 工作流原值（同上字段）
    val defaultOn: Boolean,         // 单加载器 true；Power = 槽原 on
)

/** 强度值的键：entry.key + "|" + field，例如 "2|strength_model"、"5:lora_1|strength"。 */
fun loraStrengthKey(entryKey: String, field: String): String = "$entryKey|$field"

/** 连线引用 [字符串, 整数]（与 inferOne 规则 1 同形）。 */
private fun isLink(value: Any?): Boolean =
    value is JSONArray && value.length() == 2 && value.opt(0) is String && value.opt(1) is Number

/**
 * 单加载器识别：返回文件字段名（lora_name / lora），不是单加载器返回 null。规则：
 * ① baseClass 忽略大小写含 lora；② 有 lora_name 或 lora（按此顺序取第一个），值是 String 或连线数组；
 * ③ 至少一个白名单强度字段是数字（连线不算）；④ String 文件名非空且不是 "None"（空槽不给开关）。
 * 自然排除 Stack 类（lora_name_1 / lora_01 等字段名不满足②）和 Efficient Loader（类名不含 lora）。
 */
fun loraFileFieldOf(classType: String, inputs: JSONObject): String? {
    if (!baseClass(classType).contains("lora", ignoreCase = true)) return null
    val fileField = when {
        inputs.has("lora_name") -> "lora_name"
        inputs.has("lora") -> "lora"
        else -> return null
    }
    val value = inputs.opt(fileField)
    if (value is String) {
        if (value.isEmpty() || value.equals("None", ignoreCase = true)) return null
    } else if (!isLink(value)) {
        return null
    }
    if (LORA_STRENGTH_FIELDS.none { inputs.opt(it) is Number }) return null
    return fileField
}

private val POWER_SLOT_REGEX = Regex("^lora_(\\d+)$")

/**
 * 全图识别。节点按节点号数字升序；同一节点的 Power 槽按槽号数字升序（lora_2 在 lora_10 前）。
 * 单加载器走 loraFileFieldOf；Power 槽：input 名 ^lora_\d+$、值为 JSONObject、带非空非 None 的
 * String lora 和 Boolean on。同节点有有效 Power 槽时不再按单加载器识别。
 */
fun loraEntries(wf: JSONObject): List<LoraEntry> = buildList {
    val nodeIds = wf.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    for (nid in nodeIds) {
        val node = wf.optJSONObject(nid) ?: continue
        val classType = node.optString("class_type")
        val inputs = node.optJSONObject("inputs") ?: continue
        val nodeTitle = node.optJSONObject("_meta")?.optString("title").orEmpty()
            .ifBlank { classType }

        val slots = ArrayList<Pair<Int, String>>()
        val keys = inputs.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val m = POWER_SLOT_REGEX.find(name) ?: continue
            slots.add((m.groupValues[1].toInt()) to name)
        }
        var hasSlot = false
        for ((_, slot) in slots.sortedBy { it.first }) {
            val slotObj = inputs.optJSONObject(slot) ?: continue
            val lora = slotObj.opt("lora") as? String ?: continue
            if (lora.isEmpty() || lora.equals("None", ignoreCase = true)) continue
            if (slotObj.opt("on") !is Boolean) continue
            hasSlot = true
            val fields = ArrayList<String>(2)
            val defaults = LinkedHashMap<String, Double>()
            if (slotObj.opt("strength") is Number) {
                fields.add("strength")
                defaults["strength"] = slotObj.optDouble("strength")
            }
            if (slotObj.opt("strengthTwo") is Number) {
                fields.add("strengthTwo")
                defaults["strengthTwo"] = slotObj.optDouble("strengthTwo")
            }
            add(
                LoraEntry(
                    key = "$nid:$slot", nodeId = nid, slot = slot, classType = classType,
                    nodeTitle = nodeTitle, fileField = null, fileName = lora,
                    strengthFields = fields, strengthDefaults = defaults,
                    defaultOn = slotObj.optBoolean("on"),
                ),
            )
        }
        if (hasSlot) continue

        val fileField = loraFileFieldOf(classType, inputs) ?: continue
        val fields = LORA_STRENGTH_FIELDS.filter { inputs.opt(it) is Number }
        val defaults = LinkedHashMap<String, Double>()
        for (f in fields) defaults[f] = inputs.optDouble(f)
        val fileValue = inputs.opt(fileField)
        add(
            LoraEntry(
                key = nid, nodeId = nid, slot = null, classType = classType, nodeTitle = nodeTitle,
                fileField = fileField,
                fileName = if (fileValue is String) fileValue else "",
                strengthFields = fields, strengthDefaults = defaults, defaultOn = true,
            ),
        )
    }
}

/** strengths 里的值：Number 或能 toDouble 的 String；NaN/Infinity/不可解析 → null。 */
private fun numberValue(raw: Any?): Double? = when (raw) {
    is Number -> raw.toDouble().takeIf { it.isFinite() }
    is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    else -> null
}

private fun numberAt(obj: JSONObject, key: String): Double? = numberValue(obj.opt(key))

/** 原值是整数类 Number（Int/Long）且新值是整数 → 写 Long（含 0）；否则写 Double（0 写 0.0）。 */
private fun putNumber(parent: JSONObject, field: String, value: Double, original: Any?) {
    val v: Any = if ((original is Int || original is Long) && value == kotlin.math.floor(value)) {
        value.toLong()
    } else {
        value
    }
    parent.put(field, v)
}

/**
 * 提交时应用开关和强度（就地修改 wf）。states = {entryKey: Boolean}，strengths = {strengthKey: Number|String}。
 * 两者都可为 null（老任务 body）。基于传进来的 wf 重新识别 entry；wf 里找不到对应 entry 的 key 忽略；
 * 非 Boolean 状态当没有；连线数组永不写。返回实际写入的字段数。
 * 单加载器：关 → 每个强度字段写 0（不看 strengths）；开 / 无状态 → strengths 里有合法值才写。
 * Power 槽：有状态写 on；最终 on=true 才写 strength/strengthTwo，on=false 强度不动。
 * 优先级：关闭（0）> 自动强度框 > 旧暴露参数 / mirror_to 写入的值 > 工作流原值（调用方保证顺序）。
 */
fun applyLoraStates(wf: JSONObject, states: JSONObject?, strengths: JSONObject?): Int {
    var written = 0
    for (entry in loraEntries(wf)) {
        val node = wf.optJSONObject(entry.nodeId) ?: continue
        val inputs = node.optJSONObject("inputs") ?: continue
        val stateRaw = states?.opt(entry.key)
        val hasState = stateRaw is Boolean
        val stateOn = stateRaw == true
        if (entry.slot == null) {
            val on = if (hasState) stateOn else true
            if (!on) {
                for (f in entry.strengthFields) {
                    putNumber(inputs, f, 0.0, inputs.opt(f))
                    written++
                }
            } else {
                for (f in entry.strengthFields) {
                    val v = numberValue(strengths?.opt(loraStrengthKey(entry.key, f))) ?: continue
                    putNumber(inputs, f, v, inputs.opt(f))
                    written++
                }
            }
        } else {
            val slotObj = inputs.optJSONObject(entry.slot) ?: continue
            if (hasState) {
                slotObj.put("on", stateOn)
                written++
            }
            val finalOn = if (hasState) stateOn else entry.defaultOn
            if (finalOn) {
                for (f in entry.strengthFields) {
                    val v = numberValue(strengths?.opt(loraStrengthKey(entry.key, f))) ?: continue
                    putNumber(slotObj, f, v, slotObj.opt(f))
                    written++
                }
            }
        }
    }
    return written
}

/**
 * 开关初值：saved（last_values.json）的 kwb_lora_on → def（definition.json）顶层 kwb_lora_on →
 * entry.defaultOn。只返回 entries 里存在的 key（过期 key 丢掉）。
 */
fun resolveLoraOn(
    entries: List<LoraEntry>,
    saved: JSONObject?,
    def: JSONObject?,
): Map<String, Boolean> {
    val savedOn = saved?.optJSONObject("kwb_lora_on")
    val defOn = def?.optJSONObject("kwb_lora_on")
    return buildMap {
        for (e in entries) {
            put(
                e.key,
                when {
                    savedOn != null && savedOn.opt(e.key) is Boolean -> savedOn.optBoolean(e.key)
                    defOn != null && defOn.opt(e.key) is Boolean -> defOn.optBoolean(e.key)
                    else -> e.defaultOn
                },
            )
        }
    }
}

/** 1.0 → "1"、0.75 → "0.75"（数字框显示风格，与表单 trimNum 一致）。 */
private fun trimNumStr(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/**
 * 强度初值（strengthKey → 显示文本）。优先级从高到低：
 * ① saved.kwb_lora_strength[strengthKey]（合法数值）；② 旧的已暴露参数：specs 里 type=float/int 且
 * (node_id, field) 命中该单加载器 entry 的强度字段（或 mirror_to 指向它），取 saved.values[spec key]
 * ——升级前用户在参数区调过的强度由自动框继承（只对单加载器）；③ def 顶层 kwb_lora_strength；
 * ④ entry.strengthDefaults（工作流原值）。saved 传 null = 恢复导入时默认值，只看 ③④。
 */
fun resolveLoraStrength(
    entries: List<LoraEntry>,
    specs: List<JSONObject>,
    saved: JSONObject?,
    def: JSONObject?,
): Map<String, String> {
    val savedStrengths = saved?.optJSONObject("kwb_lora_strength")
    val savedValues = saved?.optJSONObject("values")
    val defStrengths = def?.optJSONObject("kwb_lora_strength")
    val out = LinkedHashMap<String, String>()
    for (e in entries) {
        for (f in e.strengthFields) {
            val sk = loraStrengthKey(e.key, f)
            var v: Double? = savedStrengths?.let { numberAt(it, sk) }
            if (v == null && savedValues != null && e.slot == null) {
                for (spec in specs) {
                    val type = Specs.type(spec)
                    if (type != "float" && type != "int") continue
                    val mainHit = Specs.nodeId(spec) == e.nodeId && Specs.field(spec) == f
                    val mirrorHit = Specs.extraTargets(spec).any { it.first == e.nodeId && it.second == f }
                    if (!mainHit && !mirrorHit) continue
                    v = numberAt(savedValues, Specs.key(spec))
                    if (v != null) break
                }
            }
            if (v == null && defStrengths != null) v = numberAt(defStrengths, sk)
            if (v == null) v = e.strengthDefaults[f]
            out[sk] = trimNumStr(v ?: 0.0)
        }
    }
    return out
}

/**
 * 被自动强度框接管的已暴露参数 spec key 集合（设置页参数卡不再渲染这些行，避免重复）：
 * type=float/int、非 kwb_gen、(node_id, field) 命中某个单加载器 entry 的强度字段，
 * 或其 mirror_to 里任一目标命中。
 */
fun loraManagedSpecKeys(entries: List<LoraEntry>, specs: List<JSONObject>): Set<String> {
    val singles = entries.filter { it.slot == null }
    if (singles.isEmpty()) return emptySet()
    val out = HashSet<String>()
    for (spec in specs) {
        val type = Specs.type(spec)
        if (type != "float" && type != "int") continue
        if (spec.optBoolean("kwb_gen")) continue
        val nid = Specs.nodeId(spec)
        val field = Specs.field(spec)
        val targets = Specs.extraTargets(spec)
        val hit = singles.any { e ->
            (e.nodeId == nid && field in e.strengthFields) ||
                targets.any { (tn, tf) -> tn == e.nodeId && tf in e.strengthFields }
        }
        if (hit) out.add(Specs.key(spec))
    }
    return out
}
