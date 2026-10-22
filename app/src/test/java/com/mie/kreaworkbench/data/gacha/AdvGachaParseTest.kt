package com.mie.kreaworkbench.data.gacha

import com.mie.kreaworkbench.data.api.ApiException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvGachaParseTest {
    @Test
    fun drawBodyOmitsBlankMasterAndPose() {
        val body = buildAdvDrawBody(
            GachaRequest("http://127.0.0.1:8787", "gongsu", 10, "", "", "signature", "lingerie"),
        )
        assertEquals("gongsu", body.getString("mode"))
        assertEquals(10, body.getInt("count"))
        assertEquals("signature", body.getString("outfit"))
        assertEquals("lingerie", body.getString("pool"))
        assertFalse(body.has("master"))
        assertFalse(body.has("masters"))
        assertFalse(body.has("category"))
        assertEquals(true, body.getBoolean("allow_dup"))
    }

    @Test
    fun drawBodySendsMasterBothWaysAndClampsCount() {
        val body = buildAdvDrawBody(
            GachaRequest("http://127.0.0.1:8787", "wallpaper", 99, "mika", "sitting", "random", "auto"),
        )
        assertEquals(GACHA_MAX_COUNT, body.getInt("count"))
        assertEquals("mika", body.getString("master"))
        assertEquals("mika", body.getJSONArray("masters").getString(0))
        assertEquals("sitting", body.getString("category"))
    }

    @Test
    fun parsesPromptItemsAndMasterShapes() {
        val prompts = parseDrawPrompts(
            JSONObject().put(
                "items",
                JSONArray()
                    .put(JSONObject().put("prompt", " one "))
                    .put("two")
                    .put(JSONObject().put("text", "three")),
            ),
        )
        assertEquals(listOf("one", "two", "three"), prompts)

        val fromArray = parseMasters(JSONObject().put("items", JSONArray().put("a").put("b")))
        assertEquals(listOf("a", "b"), fromArray.map { it.id })

        val fromMap = parseMasters(
            JSONObject().put("masters", JSONObject().put("id1", "角色")),
        )
        assertEquals("id1", fromMap.single().id)
        assertEquals("角色", fromMap.single().label)

        val fromObj = parseMasters(
            JSONObject().put(
                "masters",
                JSONArray().put(JSONObject().put("id", "k").put("name", "K")),
            ),
        )
        assertEquals("k", fromObj.single().id)
        assertEquals("K", fromObj.single().label)
    }

    // ---- r32：动态筛选项 ----

    private fun option(id: String, label: String) = JSONObject().put("id", id).put("label", label)

    private fun fullResponse(): JSONObject = JSONObject()
        .put("count", 2)
        .put(
            "masters",
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "mika")
                        .put("name", "米卡")
                        .put("has_nude", true)
                        .put("has_signature", true),
                )
                .put(JSONObject().put("id", "lena").put("name", "蕾娜")),
        )
        .put(
            "options",
            JSONObject()
                .put("modes", JSONArray().put(option("wallpaper", "壁纸")).put(option("nude", "NSFW")))
                .put("outfits", JSONArray().put(option("random", "随机")).put(option("signature", "招牌")))
                .put("poses", JSONArray().put(option("standing", "站立")))
                .put("pools", JSONArray().put(option("auto", "自动")).put(option("casual", "日常"))),
        )

    @Test
    fun catalogParsesMastersAndOptionsInOneResponse() {
        val catalog = parseCatalog(fullResponse())
        assertEquals(listOf("mika", "lena"), catalog.masters.map { it.id })
        val options = catalog.options
        assertTrue(options != null)
        assertEquals(listOf("wallpaper", "nude"), options!!.modes.map { it.id })
        assertEquals(listOf("壁纸", "NSFW"), options.modes.map { it.label })
        assertEquals(listOf("random", "signature"), options.outfits.map { it.id })
        assertEquals(listOf("standing"), options.poses.map { it.id })
        assertEquals(listOf("auto", "casual"), options.pools.map { it.id })
    }

    @Test
    fun masterCarriesHasNudeAndHasSignature() {
        val masters = parseMasters(fullResponse())
        assertTrue(masters.single { it.id == "mika" }.hasNude)
        assertTrue(masters.single { it.id == "mika" }.hasSignature)
        // 缺字段按 false，不能因为老服没这两项就崩
        assertFalse(masters.single { it.id == "lena" }.hasNude)
        assertFalse(masters.single { it.id == "lena" }.hasSignature)
    }

    @Test
    fun emptyDimensionIsKeptEmptyAndNeverFilledFromFallback() {
        val root = JSONObject()
            .put("masters", JSONArray().put(JSONObject().put("id", "k").put("name", "K")))
            .put(
                "options",
                JSONObject()
                    .put("modes", JSONArray().put(option("gongsu", "攻速")))
                    .put("outfits", JSONArray())
                    .put("poses", JSONArray())
                    .put("pools", JSONArray()),
            )
        val options = parseOptions(root)
        assertTrue(options != null)
        assertEquals(listOf("gongsu"), options!!.modes.map { it.id })
        assertTrue(options.outfits.isEmpty())
        assertTrue(options.poses.isEmpty())
        assertTrue(options.pools.isEmpty())
        // 整响应里 masters 照常能读到
        assertEquals(listOf("k"), parseCatalog(root).masters.map { it.id })
    }

    @Test
    fun missingOptionsMeansLegacyServer() {
        val root = JSONObject()
            .put("masters", JSONArray().put(JSONObject().put("id", "k").put("name", "K")))
        assertNull(parseOptions(root))
        assertNull(parseCatalog(root).options)

        // options 不是对象（null / 数组）也当旧服，别抛
        assertNull(parseOptions(JSONObject().put("options", JSONObject.NULL)))
        assertNull(parseOptions(JSONObject().put("options", JSONArray().put("x"))))
    }

    @Test
    fun optionEntryFallsBackToIdWhenLabelMissing() {
        val options = parseOptions(
            JSONObject().put(
                "options",
                JSONObject().put("modes", JSONArray().put(JSONObject().put("id", "wallpaper"))),
            ),
        )
        assertEquals("wallpaper", options!!.modes.single().label)
    }

    @Test
    fun straySelectionIsCorrectedAgainstNewOptions() {
        val masters = listOf(
            GachaMaster("mika", "米卡", hasNude = false, hasSignature = false),
            GachaMaster("lena", "蕾娜", hasNude = true, hasSignature = true),
        )
        val options = GachaOptions(
            modes = listOf(GachaOption("wallpaper", "壁纸"), GachaOption("nude", "NSFW")),
            outfits = listOf(GachaOption("random", "随机"), GachaOption("signature", "招牌")),
            poses = listOf(GachaOption("standing", "站立")),
            pools = listOf(GachaOption("auto", "自动")),
        )
        val fixed = correctGachaSelection(
            GachaSelection(mode = "nude", master = "mika", outfit = "signature", category = "lying", pool = "casual"),
            masters,
            options,
        )
        // 母版没有 nude / 招牌：退回第一项；姿势不在列表 → 不限；池不在 → auto
        assertEquals("wallpaper", fixed.mode)
        assertEquals("mika", fixed.master)
        assertEquals("random", fixed.outfit)
        assertEquals("", fixed.category)
        assertEquals("auto", fixed.pool)
    }

    @Test
    fun anyMasterKeepsNudeAndSignature() {
        val masters = listOf(GachaMaster("mika", "米卡"))
        val options = GachaOptions(
            modes = listOf(GachaOption("wallpaper", "壁纸"), GachaOption("nude", "NSFW")),
            outfits = listOf(GachaOption("random", "随机"), GachaOption("signature", "招牌")),
            poses = listOf(GachaOption("standing", "站立")),
            pools = listOf(GachaOption("auto", "自动")),
        )
        val kept = correctGachaSelection(
            GachaSelection(mode = "nude", master = "", outfit = "signature", category = "standing", pool = "auto"),
            masters,
            options,
        )
        assertEquals(GachaSelection("nude", "", "signature", "standing", "auto"), kept)
    }

    @Test
    fun legacyServerKeepsSelectionExceptUnknownMaster() {
        val masters = listOf(GachaMaster("mika", "米卡"))
        val sel = GachaSelection("nude", "gone", "signature", "lying", "lingerie")
        val fixed = correctGachaSelection(sel, masters, null)
        assertEquals(sel.copy(master = ""), fixed)
    }

    @Test
    fun modeGateKeepsListWhenOnlyNudeExists() {
        val modes = listOf(GachaOption("nude", "NSFW"))
        // 闸完一项不剩时不要把整框弄没
        assertEquals(listOf("nude"), visibleModes(modes, GachaMaster("mika", "米卡")).map { it.id })
        assertEquals(listOf("nude"), visibleModes(modes, null).map { it.id })
    }

    // ---- r33：服端 label_en（英文界面显示用），传参仍只用 id ----

    @Test
    fun optionParsesLabelEnAndLeavesItBlankWhenMissing() {
        val options = parseOptions(
            JSONObject().put(
                "options",
                JSONObject().put(
                    "modes",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "nude")
                                .put("label", "私房艺术")
                                .put("label_en", "Boudoir Art"),
                        )
                        .put(JSONObject().put("id", "gongsu").put("label", "攻速")),
                ),
            ),
        )
        val nude = options!!.modes.first { it.id == "nude" }
        assertEquals("私房艺术", nude.label)
        assertEquals("Boudoir Art", nude.labelEn)
        // 没给 label_en 就留空，显示层回退 label；id 始终原样
        val gongsu = options.modes.first { it.id == "gongsu" }
        assertEquals("攻速", gongsu.label)
        assertEquals("", gongsu.labelEn)
    }

    // ---- r39：服务端错误文本与连接失败分类 ----

    @Test
    fun serverErrorTextPrefersErrorMessageThenErrorThenDetail() {
        assertEquals("m", gachaServerErrorText(JSONObject().put("error", JSONObject().put("message", "m"))))
        assertEquals("e", gachaServerErrorText(JSONObject().put("error", "e")))
        assertEquals("d", gachaServerErrorText(JSONObject().put("detail", "d")))
        // error 是对象且 message 为空时，不能把对象 toString 当文案，要落到 detail
        assertEquals(
            "d",
            gachaServerErrorText(
                JSONObject().put("error", JSONObject().put("message", "")).put("detail", "d"),
            ),
        )
        assertEquals("", gachaServerErrorText(JSONObject()))
    }

    @Test
    fun httpErrorsAreNotTransportFailures() {
        // HTTP 5xx / 4xx：服务端错误，保留 HTTP 文案，不再误报「连不上」
        assertFalse(isGachaTransportFailure(ApiException("x", network = true, code = 500)))
        assertFalse(isGachaTransportFailure(ApiException("x", network = false, code = 404)))
        // 连接层失败（code==0）才按「连不上」报
        assertTrue(isGachaTransportFailure(ApiException("x", network = true)))
        assertTrue(isGachaTransportFailure(ApiException("x")))
    }
}
