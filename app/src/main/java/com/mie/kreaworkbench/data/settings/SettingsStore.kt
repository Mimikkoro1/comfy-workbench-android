package com.mie.kreaworkbench.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mie.kreaworkbench.data.DEFAULT_URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.dataStore by preferencesDataStore("krea_settings")

data class UserSettings(
    val serverUrl: String = DEFAULT_URL,
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
)

class SettingsStore(private val context: Context) {
    private val gate = Mutex()
    private val kUrl = stringPreferencesKey("server_url")
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

    val flow: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            serverUrl = p[kUrl] ?: DEFAULT_URL,
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
        )
    }

    suspend fun current(): UserSettings = flow.first()

    /** App 安装级固定 UUID，首次生成后持久化，提交 /prompt 时作为 client_id 带上。 */
    suspend fun comfyClientId(): String = gate.withLock {
        val existing = context.dataStore.data.first()[kComfyClient]
        if (!existing.isNullOrBlank()) return@withLock existing
        val id = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[kComfyClient] = id }
        id
    }

    /** 一次性迁移：老版本存的工作台地址（:8199）换成 ComfyUI 直连端口（:8188）。 */
    suspend fun migrateServerUrl() = gate.withLock {
        val raw = context.dataStore.data.first()[kUrl] ?: return@withLock
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.endsWith(":8199")) {
            val next = trimmed.removeSuffix(":8199") + ":8188"
            context.dataStore.edit { it[kUrl] = next }
        }
    }

    suspend fun update(block: (UserSettings) -> UserSettings) = gate.withLock {
        val next = block(current())
        context.dataStore.edit { p ->
            p[kUrl] = next.serverUrl
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
        }
    }
}
