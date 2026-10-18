package com.mie.kreaworkbench.data.gacha

import android.content.Context
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.data.api.callBodyText
import com.mie.kreaworkbench.data.api.jsonBody
import com.mie.kreaworkbench.data.api.jsonClient
import com.mie.kreaworkbench.data.api.trimBase
import com.mie.kreaworkbench.ui.locale.str
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 电脑上文字抽卡页的默认地址（工作仓自用，和 Comfy 默认内网地址同一类）。 */
const val DEFAULT_ADV_GACHA_URL = "http://192.168.1.100:8787"

/** 抽卡服务仓库。空着时引导框不显示链接。 */
const val GACHA_SERVICE_REPO_URL = "https://github.com/Mimikkoro1/gacha-server-lite"

const val GACHA_MAX_COUNT = 10

/** 写进任务和图片信息的提示词上限。再长的抽卡词会截断，避免一条任务把库撑爆。 */
const val GACHA_PROMPT_CAP = 12000

/** 母版。has_nude / has_signature 由服端给（旧服没有就当 false），用来决定 nude / 招牌能不能选。 */
data class GachaMaster(
    val id: String,
    val label: String,
    val hasNude: Boolean = false,
    val hasSignature: Boolean = false,
)

/**
 * 服端 options 里的一项。id 原样进 draw 请求体（mode / outfit / pool / category）。
 * labelEn 只在服端给了 label_en 时才有值，英文界面优先用它显示；传参永远只用 id。
 */
data class GachaOption(val id: String, val label: String, val labelEn: String = "")

/**
 * 服端 /api/masters 一起给的筛选项。
 * 四个数组始终存在，空 = 服端没这类数据，UI 就别画那个框。
 */
data class GachaOptions(
    val modes: List<GachaOption>,
    val outfits: List<GachaOption>,
    val poses: List<GachaOption>,
    val pools: List<GachaOption>,
)

/** 一次 /api/masters 的完整结果：母版 + 筛选项（options 为 null 表示这台服没这字段，走写死列表）。 */
data class GachaCatalog(
    val masters: List<GachaMaster>,
    val options: GachaOptions?,
)

/** 当前选中的五个 id。master 空 = 不限；category 空 = 姿势不限。 */
data class GachaSelection(
    val mode: String,
    val master: String,
    val outfit: String,
    val category: String,
    val pool: String,
)

const val GACHA_MODE_NUDE = "nude"
const val GACHA_OUTFIT_SIGNATURE = "signature"
const val GACHA_OUTFIT_RANDOM = "random"
const val GACHA_POOL_AUTO = "auto"

data class GachaRequest(
    val base: String,
    val mode: String,
    val count: Int,
    val master: String,
    val category: String,
    val outfit: String,
    val pool: String,
)

fun buildAdvDrawBody(req: GachaRequest): JSONObject {
    val n = req.count.coerceIn(1, GACHA_MAX_COUNT)
    val body = JSONObject()
        .put("mode", req.mode.ifBlank { "wallpaper" })
        .put("count", n)
        .put("outfit", req.outfit.ifBlank { "random" })
        .put("pool", req.pool.ifBlank { "auto" })
        .put("allow_dup", true)
    if (req.master.isNotBlank()) {
        body.put("master", req.master)
        body.put("masters", JSONArray().put(req.master))
    }
    if (req.category.isNotBlank()) body.put("category", req.category)
    return body
}

fun parseDrawPrompts(root: JSONObject): List<String> {
    val items = root.optJSONArray("items")
        ?: root.optJSONArray("prompts")
        ?: root.optJSONArray("data")
    val out = ArrayList<String>()
    if (items != null) {
        for (i in 0 until items.length()) {
            val text = when (val el = items.opt(i)) {
                is String -> el
                is JSONObject -> el.optString("prompt").ifBlank { el.optString("text") }
                else -> ""
            }.trim()
            if (text.isNotBlank()) out.add(text)
        }
    }
    if (out.isEmpty()) {
        val one = root.optString("prompt").trim()
        if (one.isNotBlank()) out.add(one)
    }
    return out
}

fun parseMasters(root: JSONObject): List<GachaMaster> {
    val direct = when {
        root.has("masters") -> root.opt("masters")
        root.has("items") -> root.opt("items")
        root.has("data") -> root.opt("data")
        else -> null
    }
    val out = ArrayList<GachaMaster>()
    when (direct) {
        is JSONArray -> {
            for (i in 0 until direct.length()) parseMasterEntry(direct.opt(i))?.let { out.add(it) }
        }
        is JSONObject -> {
            val keys = direct.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = direct.opt(key)) {
                    is String -> if (key.isNotBlank()) out.add(GachaMaster(key, value.ifBlank { key }))
                    is JSONObject -> parseMasterEntry(value)?.let { out.add(it) }
                    null -> {}
                    else -> if (key.isNotBlank()) out.add(GachaMaster(key, key))
                }
            }
        }
        else -> {}
    }
    return out.distinctBy { it.id }
}

private fun parseMasterEntry(el: Any?): GachaMaster? = when (el) {
    is String -> el.trim().ifBlank { null }?.let { GachaMaster(it, it) }
    is JSONObject -> {
        val id = el.optString("id").ifBlank {
            el.optString("key")
        }.ifBlank {
            el.optString("name")
        }.ifBlank {
            el.optString("master")
        }.trim()
        if (id.isBlank()) {
            null
        } else {
            val label = el.optString("label").ifBlank {
                el.optString("title")
            }.ifBlank {
                el.optString("name")
            }.ifBlank { id }
            GachaMaster(
                id = id,
                label = label,
                hasNude = el.optBoolean("has_nude", false),
                hasSignature = el.optBoolean("has_signature", false),
            )
        }
    }
    else -> null
}

/**
 * 解析 /api/masters 里的 options。
 * 没有 options 键（或不是对象）= 旧服 → null，调用方回退写死列表。
 * 有键但某维缺省/不是数组 = 该维空列表（服端没这类数据，别回退写死）。
 */
fun parseOptions(root: JSONObject): GachaOptions? {
    val obj = root.opt("options") as? JSONObject ?: return null
    return GachaOptions(
        modes = parseOptionList(obj.opt("modes")),
        outfits = parseOptionList(obj.opt("outfits")),
        poses = parseOptionList(obj.opt("poses")),
        pools = parseOptionList(obj.opt("pools")),
    )
}

/** 一维选项。每项至少要 id；label 空就用 id，照样显示。 */
fun parseOptionList(raw: Any?): List<GachaOption> {
    val arr = raw as? JSONArray ?: return emptyList()
    val out = ArrayList<GachaOption>()
    for (i in 0 until arr.length()) {
        when (val el = arr.opt(i)) {
            is String -> el.trim().ifBlank { null }?.let { out.add(GachaOption(it, it)) }
            is JSONObject -> {
                val id = el.optString("id").ifBlank {
                    el.optString("key")
                }.ifBlank {
                    el.optString("name")
                }.trim()
                if (id.isNotBlank()) {
                    val label = el.optString("label").ifBlank {
                        el.optString("title")
                    }.ifBlank {
                        el.optString("name")
                    }.ifBlank { id }
                    val labelEn = el.optString("label_en").trim()
                    out.add(GachaOption(id, label, labelEn))
                }
            }
            else -> {}
        }
    }
    return out.distinctBy { it.id }
}

/** 一次请求同时拿母版和筛选项。禁止为了 options 再打第二个 HTTP。 */
fun parseCatalog(root: JSONObject): GachaCatalog =
    GachaCatalog(masters = parseMasters(root), options = parseOptions(root))

/**
 * 能真正选的 mode：选中了具体母版、而它没有 nude 时把 nude 藏掉（别让用户白跑）。
 * 「不限」母版（owner = null）不闸，交给服端裁决。
 * 闸完一项不剩时保留原列表，否则整个框会凭空消失。
 */
fun visibleModes(modes: List<GachaOption>, owner: GachaMaster?): List<GachaOption> =
    if (owner == null || owner.hasNude) {
        modes
    } else {
        modes.filter { it.id != GACHA_MODE_NUDE }.ifEmpty { modes }
    }

/** 能真正选的 outfit：招牌同理，母版没有签名就把 signature 藏掉。 */
fun visibleOutfits(outfits: List<GachaOption>, owner: GachaMaster?): List<GachaOption> =
    if (owner == null || owner.hasSignature) {
        outfits
    } else {
        outfits.filter { it.id != GACHA_OUTFIT_SIGNATURE }.ifEmpty { outfits }
    }

/**
 * 拿新目录校正选中的 id（切服 / 换母版后都走这里）：
 * 母版不在新列表 → 不限；某维 id 不在（或被闸掉）→ 该维第一项，姿势回「不限」。
 * options 为 null（旧服）时只校母版，其它维保持原样，跟 r31 前一致。
 */
fun correctGachaSelection(
    selection: GachaSelection,
    masters: List<GachaMaster>,
    options: GachaOptions?,
): GachaSelection {
    val master = if (masters.any { it.id == selection.master }) selection.master else ""
    if (options == null) return selection.copy(master = master)

    val owner = masters.firstOrNull { it.id == master }
    val modes = visibleModes(options.modes, owner)
    val outfits = visibleOutfits(options.outfits, owner)
    val mode = when {
        modes.isEmpty() -> ""
        modes.any { it.id == selection.mode } -> selection.mode
        else -> modes.first().id
    }
    val outfit = when {
        outfits.isEmpty() -> ""
        outfits.any { it.id == selection.outfit } -> selection.outfit
        outfits.any { it.id == GACHA_OUTFIT_RANDOM } -> GACHA_OUTFIT_RANDOM
        else -> outfits.first().id
    }
    val category = when {
        options.poses.isEmpty() -> ""
        options.poses.any { it.id == selection.category } -> selection.category
        else -> ""
    }
    val pool = when {
        options.pools.isEmpty() -> ""
        options.pools.any { it.id == selection.pool } -> selection.pool
        options.pools.any { it.id == GACHA_POOL_AUTO } -> GACHA_POOL_AUTO
        else -> options.pools.first().id
    }
    return GachaSelection(mode = mode, master = master, outfit = outfit, category = category, pool = pool)
}

/** 不走 Comfy 的 Basic Auth。连不上就抛错，调用方提示，不往 Comfy 重复提交。 */
class AdvGachaClient(private val ctx: Context) {
    private val http = jsonClient(null)

    /** 一次请求拿全套：母版 + 动态筛选项。别再为 options 单独发请求。 */
    suspend fun catalog(base: String): GachaCatalog =
        parseCatalog(request(endpoint(base, "/api/masters"), null))

    suspend fun draw(req: GachaRequest): List<String> {
        val json = request(endpoint(req.base, "/api/draw"), buildAdvDrawBody(req))
        return parseDrawPrompts(json).map { it.take(GACHA_PROMPT_CAP) }
    }

    private fun endpoint(base: String, path: String): String {
        val clean = trimBase(base)
        if (clean.isBlank()) throw ApiException(ctx.str(R.string.err_gacha_bad_url), network = false)
        return clean + path
    }

    private suspend fun request(url: String, body: JSONObject?): JSONObject {
        try {
            val (code, text) = http.callBodyText {
                val b = Request.Builder().url(url)
                if (body == null) b.get() else b.post(jsonBody(body))
                b.build()
            }
            return interpret(code, text)
        } catch (e: ApiException) {
            if (e.network || e.code == 0) {
                throw ApiException(ctx.str(R.string.err_gacha_unreachable), network = true, code = e.code)
            }
            throw e
        } catch (_: IOException) {
            throw ApiException(ctx.str(R.string.err_gacha_unreachable), network = true)
        } catch (_: IllegalArgumentException) {
            throw ApiException(ctx.str(R.string.err_gacha_bad_url), network = false)
        }
    }

    private fun interpret(code: Int, text: String): JSONObject {
        val trimmed = text.trim()
        val json = when {
            trimmed.startsWith("[") -> JSONObject().put("items", JSONArray(trimmed))
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> JSONObject()
        }
        if (code !in 200..299) {
            val obj = json.optJSONObject("error")
            val err = obj?.optString("message").orEmpty().ifBlank { json.optString("error") }
            throw ApiException(
                err.ifBlank { ctx.str(R.string.err_gacha_http, code) },
                network = code >= 500,
                code = code,
            )
        }
        if (json.has("ok") && !json.optBoolean("ok")) {
            throw ApiException(json.optString("error").ifBlank { ctx.str(R.string.err_gacha_empty) })
        }
        return json
    }
}
