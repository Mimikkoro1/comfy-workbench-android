package com.mie.kreaworkbench.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("krea_settings")

/** 服务器地址历史条目：地址 + 各自的账号密码（密码密文，只在 UI 填表单时解出来用）。 */
data class ServerHistoryEntry(
    val url: String,
    val user: String,
    val pass: String,
)

data class UserSettings(
    /** ComfyUI 地址（内网/外网共用一个框）；空 = 未配置。 */
    val serverUrl: String = "",
    /** Basic Auth 凭据（可空）。有值时所有请求/WS/图片加载都带头；加密落盘。 */
    val serverUser: String = "",
    val serverPass: String = "",
    /** 最近用过的地址（最多 5 条，各带自己的凭据），按 url 去重。 */
    val serverHistory: List<ServerHistoryEntry> = emptyList(),
    val themeMode: String = "system",
    val notifyEnabled: Boolean = true,
    val cacheLimitGb: Int = 10,
    val promptT2i: String = "",
    val promptI2i: String = "",
    val libCategory: String = "",
    val includeTags: String = "",
    val excludeTags: String = "",
    val width: Int = 1152,
    val height: Int = 1728,
    val batch: Int = 1,
    val denoise: Float = 0.55f,
    val unet: String = "",
    val clip: String = "",
    val vae: String = "",
    val seedText: String = "",
    val seedRandom: Boolean = true,
    val steps: Int = 8,
    val cfg: Float = 1f,
    val sampler: String = "euler",
    val scheduler: String = "simple",
    val shift: Float = 3f,
    val lastSeeds: String = "",
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false,
    val blurEnabled: Boolean = true,
    /** 当前工作流 id；"" = 未选中（合法状态，全 App 空状态）。 */
    val currentWorkflow: String = "",
    /** vivo 忽略电池优化的一次性引导只弹一次。 */
    val batteryHintShown: Boolean = false,
)

class SettingsStore(private val context: Context) {
    private val gate = Mutex()
    private val kUrl = stringPreferencesKey("server_url")
    private val kUser = stringPreferencesKey("server_user")
    private val kPass = stringPreferencesKey("server_pass")
    private val kHistory = stringPreferencesKey("server_history")
    private val kComfyClient = stringPreferencesKey("comfy_client_id")
    private val kTheme = stringPreferencesKey("theme")
    private val kNotify = booleanPreferencesKey("notify")
    private val kCache = intPreferencesKey("cache_gb")
    private val kPt = stringPreferencesKey("prompt_t2i")
    private val kPi = stringPreferencesKey("prompt_i2i")
    private val kCat = stringPreferencesKey("lib_cat")
    private val kInc = stringPreferencesKey("lib_inc")
    private val kExc = stringPreferencesKey("lib_exc")
    private val kW = intPreferencesKey("width")
    private val kH = intPreferencesKey("height")
    private val kBatch = intPreferencesKey("batch")
    private val kDenoise = floatPreferencesKey("denoise")
    private val kUnet = stringPreferencesKey("unet")
    private val kClip = stringPreferencesKey("clip")
    private val kVae = stringPreferencesKey("vae")
    private val kSeed = stringPreferencesKey("seed")
    private val kSeedRand = booleanPreferencesKey("seed_random")
    private val kSteps = intPreferencesKey("steps")
    private val kCfg = floatPreferencesKey("cfg")
    private val kSampler = stringPreferencesKey("sampler")
    private val kSched = stringPreferencesKey("scheduler")
    private val kShift = floatPreferencesKey("shift")
    private val kLast = stringPreferencesKey("last_seeds")
    private val kDynamic = booleanPreferencesKey("dynamic_color")
    private val kAmoled = booleanPreferencesKey("amoled")
    private val kBlur = booleanPreferencesKey("blur")
    private val kWorkflow = stringPreferencesKey("current_workflow")
    private val kBatteryHint = booleanPreferencesKey("battery_hint")
    // round14 更新检查：上次自动检查时间 + 用户忽略的 tag（不进 UserSettings，单独读写）
    private val kUpdateLast = longPreferencesKey("update_last_check")
    private val kUpdateIgnored = stringPreferencesKey("update_ignored_tag")
    // v0.5.0 双配置残留键，迁移后删除
    private val kHomeUrl = stringPreferencesKey("home_url")
    private val kHomeUser = stringPreferencesKey("home_user")
    private val kHomePass = stringPreferencesKey("home_pass")
    private val kExtUrl = stringPreferencesKey("ext_url")
    private val kExtUser = stringPreferencesKey("ext_user")
    private val kExtPass = stringPreferencesKey("ext_pass")
    private val kMode = stringPreferencesKey("server_mode")

    val flow: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            serverUrl = p[kUrl] ?: "",
            serverUser = p[kUser] ?: "",
            serverPass = ServerCrypto.decrypt(p[kPass] ?: ""),
            serverHistory = parseHistory(p[kHistory].orEmpty()),
            themeMode = p[kTheme] ?: "system",
            notifyEnabled = p[kNotify] ?: true,
            cacheLimitGb = p[kCache] ?: 10,
            promptT2i = p[kPt] ?: "",
            promptI2i = p[kPi] ?: "",
            libCategory = p[kCat] ?: "",
            includeTags = p[kInc] ?: "",
            excludeTags = p[kExc] ?: "",
            width = p[kW] ?: 1152,
            height = p[kH] ?: 1728,
            batch = p[kBatch] ?: 1,
            denoise = p[kDenoise] ?: 0.55f,
            unet = p[kUnet] ?: "",
            clip = p[kClip] ?: "",
            vae = p[kVae] ?: "",
            seedText = p[kSeed] ?: "",
            seedRandom = p[kSeedRand] ?: true,
            steps = p[kSteps] ?: 8,
            cfg = p[kCfg] ?: 1f,
            sampler = p[kSampler] ?: "euler",
            scheduler = p[kSched] ?: "simple",
            shift = p[kShift] ?: 3f,
            lastSeeds = p[kLast] ?: "",
            dynamicColor = p[kDynamic] ?: false,
            amoled = p[kAmoled] ?: false,
            blurEnabled = p[kBlur] ?: true,
            currentWorkflow = p[kWorkflow] ?: "",
            batteryHintShown = p[kBatteryHint] ?: false,
        )
    }

    suspend fun current(): UserSettings = flow.first()

    suspend fun updateLastCheck(): Long = context.dataStore.data.first()[kUpdateLast] ?: 0L
    suspend fun setUpdateLastCheck(time: Long) { context.dataStore.edit { it[kUpdateLast] = time } }
    suspend fun updateIgnoredTag(): String = context.dataStore.data.first()[kUpdateIgnored].orEmpty()
    suspend fun setUpdateIgnoredTag(tag: String) { context.dataStore.edit { it[kUpdateIgnored] = tag } }

    /** App 安装级固定 UUID，首次生成后持久化，提交 /prompt 时作为 client_id 带上。 */
    suspend fun comfyClientId(): String = gate.withLock {
        val existing = context.dataStore.data.first()[kComfyClient]
        if (!existing.isNullOrBlank()) return@withLock existing
        val id = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[kComfyClient] = id }
        id
    }

    /**
     * 迁移（v0.5.1）：v0.5.0 的家里/外面两套配置合回单地址。
     * 地址取家里（为空取外面）；凭据取家里（为空取外面）；外面地址进历史，
     * 当前地址（带凭据）置历史首位。老版本 v0.4 只有 server_url，直接沿用。
     * 做完删掉全部 v0.5.0 残留键，因此幂等。
     */
    suspend fun migrateLegacyServerUrl() = gate.withLock {
        val p = context.dataStore.data.first()
        val hasLegacyDual = p[kHomeUrl] != null || p[kExtUrl] != null || p[kMode] != null
        if (!hasLegacyDual && p[kUrl] != null) return@withLock
        val old8199fix: (String) -> String = { it.trim().trimEnd('/').let { t -> if (t.endsWith(":8199")) t.removeSuffix(":8199") + ":8188" else t } }
        val homeUrl = p[kHomeUrl]?.let(old8199fix).orEmpty()
        val extUrl = p[kExtUrl]?.trim().orEmpty()
        val homeUser = p[kHomeUser].orEmpty()
        val extUser = p[kExtUser].orEmpty()
        val homePass = p[kHomePass].orEmpty()
        val extPass = p[kExtPass].orEmpty()
        // 当前地址取家里（为空取外面）；凭据跟地址走，不串配——家里配外面的账号没有意义
        val fromHome = homeUrl.isNotBlank()
        val url = if (fromHome) homeUrl else extUrl.trim()
        val user = if (fromHome) homeUser else extUser
        val pass = if (fromHome) homePass else extPass
        val history = ArrayList<ServerHistoryEntry>()
        if (url.isNotBlank()) history.add(ServerHistoryEntry(url, user, ServerCrypto.decrypt(pass)))
        if (extUrl.isNotBlank() && extUrl != url) {
            history.add(ServerHistoryEntry(extUrl, extUser, ServerCrypto.decrypt(extPass)))
        }
        context.dataStore.edit { e ->
            if (url.isNotBlank()) e[kUrl] = url
            if (user.isNotBlank() || pass.isNotBlank()) {
                e[kUser] = user
                e[kPass] = pass
            }
            if (history.isNotEmpty()) e[kHistory] = historyToJson(history)
            e.remove(kHomeUrl); e.remove(kHomeUser); e.remove(kHomePass)
            e.remove(kExtUrl); e.remove(kExtUser); e.remove(kExtPass); e.remove(kMode)
        }
    }

    /** 测试连接成功后记录「最近用过的地址」：按 url 去重置顶，最多 5 条。 */
    suspend fun rememberServer(url: String, user: String, pass: String) = gate.withLock {
        val clean = url.trim().trimEnd('/')
        if (clean.isBlank()) return@withLock
        val list = ArrayList<ServerHistoryEntry>()
        list.add(ServerHistoryEntry(clean, user.trim(), pass))
        parseHistory(context.dataStore.data.first()[kHistory].orEmpty()).forEach { old ->
            if (old.url != clean && list.size < 5) list.add(old)
        }
        context.dataStore.edit { it[kHistory] = historyToJson(list) }
    }

    suspend fun update(block: (UserSettings) -> UserSettings) = gate.withLock {
        val next = block(current())
        context.dataStore.edit { p ->
            p[kUrl] = next.serverUrl
            p[kUser] = next.serverUser
            p[kPass] = ServerCrypto.encrypt(next.serverPass)
            p[kTheme] = next.themeMode
            p[kNotify] = next.notifyEnabled
            p[kCache] = next.cacheLimitGb
            p[kPt] = next.promptT2i
            p[kPi] = next.promptI2i
            p[kCat] = next.libCategory
            p[kInc] = next.includeTags
            p[kExc] = next.excludeTags
            p[kW] = next.width
            p[kH] = next.height
            p[kBatch] = next.batch
            p[kDenoise] = next.denoise
            p[kUnet] = next.unet
            p[kClip] = next.clip
            p[kVae] = next.vae
            p[kSeed] = next.seedText
            p[kSeedRand] = next.seedRandom
            p[kSteps] = next.steps
            p[kCfg] = next.cfg
            p[kSampler] = next.sampler
            p[kSched] = next.scheduler
            p[kShift] = next.shift
            p[kLast] = next.lastSeeds
            p[kDynamic] = next.dynamicColor
            p[kAmoled] = next.amoled
            p[kBlur] = next.blurEnabled
            p[kWorkflow] = next.currentWorkflow
            p[kBatteryHint] = next.batteryHintShown
        }
    }

    companion object {
        const val HISTORY_MAX = 5

        fun parseHistory(raw: String): List<ServerHistoryEntry> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url").trim()
                    if (url.isBlank()) return@mapNotNull null
                    ServerHistoryEntry(url, o.optString("user"), ServerCrypto.decrypt(o.optString("pass")))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun historyToJson(list: List<ServerHistoryEntry>): String {
            val arr = JSONArray()
            for (e in list.take(HISTORY_MAX)) {
                arr.put(
                    JSONObject()
                        .put("url", e.url)
                        .put("user", e.user)
                        .put("pass", ServerCrypto.encrypt(e.pass)),
                )
            }
            return arr.toString()
        }
    }
}
