package com.mie.kreaworkbench.data.update

import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 最新 Release 的必要字段。version = tag 去掉前导 v。source = gitee / github。 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    /** 第一个 .apk 附件的下载地址；两边都没有附件时为 null（弹窗退回打开 Release 网页）。 */
    val apkUrl: String? = null,
    /** APK 附件大小（字节）；源没给就 -1。 */
    val apkSize: Long = -1L,
    val source: String = SettingsStore.UPDATE_SOURCE_GITHUB,
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val info: ReleaseInfo) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 轻量更新检查（round14）：比较 tag 与 BuildConfig.VERSION_NAME，有新版就弹窗（r40 起支持应用内下载）。
 * 启动自动检查最多 24h 一次、尊重「忽略此版本」，任何失败静默；关于页手动检查无视两者。
 * r40：国内优先 Gitee、失败换 GitHub；国外只 GitHub；设置里可手动固定源（Gitee 的 Release/附件
 * 由 GitHub Action 同步，可能暂时缺失，缺失即当失败回落）。
 * 用独立 OkHttpClient：不挂 ComfyUI 的 Basic Auth 拦截器，走系统默认代理（不像 ComfyUI 客户端强制直连）。
 */
object UpdateChecker {
    const val GITHUB_LATEST_URL = "https://api.github.com/repos/Mimikkoro1/comfy-workbench-android/releases/latest"
    const val GITEE_LATEST_URL = "https://gitee.com/api/v5/repos/Mimikkoro1/comfy-workbench-android/releases/latest"
    const val RELEASES_PAGE = "https://github.com/Mimikkoro1/comfy-workbench-android/releases"
    private const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** 「是不是国内」按这两个时区/地区判，不申请任何权限。 */
    private val CHINA_TIME_ZONES = setOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "Asia/Urumqi", "PRC")

    /** 待弹的新版本；MainActivity 观察它显示对话框，置 null 即关闭。 */
    val pending = MutableStateFlow<ReleaseInfo?>(null)

    /** 对话框关闭后组合即销毁，落盘不能挂在组合作用域上。 */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ignore(settings: SettingsStore, tag: String) {
        io.launch { runCatching { settings.setUpdateIgnoredTag(tag) } }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 国内判定：中国标准时区之一，或系统地区是 CN。 */
    fun isInChina(): Boolean =
        TimeZone.getDefault().id in CHINA_TIME_ZONES || Locale.getDefault().country == "CN"

    /** 按设置展开实际尝试顺序（r40）。 */
    internal fun sourceOrder(updateSource: String): List<String> = when (updateSource) {
        SettingsStore.UPDATE_SOURCE_GITEE -> listOf(
            SettingsStore.UPDATE_SOURCE_GITEE,
            SettingsStore.UPDATE_SOURCE_GITHUB,
        )
        SettingsStore.UPDATE_SOURCE_GITHUB -> listOf(SettingsStore.UPDATE_SOURCE_GITHUB)
        else -> if (isInChina()) {
            listOf(SettingsStore.UPDATE_SOURCE_GITEE, SettingsStore.UPDATE_SOURCE_GITHUB)
        } else {
            listOf(SettingsStore.UPDATE_SOURCE_GITHUB)
        }
    }

    /** 启动时调用：未到 24h 或失败返回 null；已忽略的 tag 也返回 null。 */
    suspend fun autoCheck(settings: SettingsStore): ReleaseInfo? = try {
        val now = System.currentTimeMillis()
        val last = settings.updateLastCheck()
        if (last in 1..now && now - last < AUTO_INTERVAL_MS) {
            null
        } else {
            settings.setUpdateLastCheck(now)
            val info = fetchLatest(settings.updateSource())
            if (isNewer(info.version, BuildConfig.VERSION_NAME) && info.tag != settings.updateIgnoredTag()) info else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** 关于页「检查更新」：无视 24h 与忽略记录。 */
    suspend fun manualCheck(settings: SettingsStore): UpdateResult = try {
        val info = fetchLatest(settings.updateSource())
        runCatching { settings.setUpdateLastCheck(System.currentTimeMillis()) }
        if (isNewer(info.version, BuildConfig.VERSION_NAME)) UpdateResult.Available(info) else UpdateResult.UpToDate
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        UpdateResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    /** 按顺序试各源；前一个源失败（HTTP 非 2xx / 无 tag_name / JSON 解析失败）自动换下一个。 */
    private suspend fun fetchLatest(updateSource: String): ReleaseInfo = withContext(Dispatchers.IO) {
        val order = sourceOrder(updateSource)
        var lastError: Throwable? = null
        for (source in order) {
            try {
                val info = fetchFrom(source)
                // Gitee 能查到 Release 但没有 APK 附件：版本信息用 Gitee 的，apkUrl 再去 GitHub 拿一次
                if (source == SettingsStore.UPDATE_SOURCE_GITEE && info.apkUrl == null) {
                    runCatching { fetchApkFromGithub() }.getOrNull()?.let { (url, size) ->
                        return@withContext info.copy(apkUrl = url, apkSize = size)
                    }
                }
                return@withContext info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IOException("no update source")
    }

    private suspend fun fetchFrom(source: String): ReleaseInfo = when (source) {
        SettingsStore.UPDATE_SOURCE_GITEE -> fetchGitee()
        else -> fetchGithub()
    }

    /** 只从 GitHub 拿最新 Release 的 APK 附件（Gitee 缺附件 / 下载兜底候选用），失败抛错由调用方吞掉。 */
    internal suspend fun fetchApkFromGithub(): Pair<String, Long> {
        val info = fetchGithub()
        val url = info.apkUrl ?: throw IOException("no apk asset")
        return url to info.apkSize
    }

    private suspend fun fetchGithub(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(GITHUB_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ComfyWorkbench-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub HTTP ${resp.code}")
            parseRelease(resp.body?.string().orEmpty(), SettingsStore.UPDATE_SOURCE_GITHUB, RELEASES_PAGE)
        }
    }

    private suspend fun fetchGitee(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(GITEE_LATEST_URL)
            .header("User-Agent", "ComfyWorkbench-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Gitee HTTP ${resp.code}")
            parseRelease(resp.body?.string().orEmpty(), SettingsStore.UPDATE_SOURCE_GITEE, RELEASES_PAGE)
        }
    }

    /** GitHub / Gitee 的 latest 响应字段同名，一套解析共用。缺 tag_name 或 JSON 坏了都算失败。 */
    private fun parseRelease(text: String, source: String, fallbackPage: String): ReleaseInfo {
        val o = JSONObject(text)
        val tag = o.optString("tag_name").trim()
        if (tag.isBlank()) throw IOException("no tag_name")
        val pageUrl = o.optString("html_url").trim().takeIf { it.startsWith("https://") } ?: fallbackPage
        var apkUrl: String? = null
        var apkSize = -1L
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").trim().lowercase().endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url").trim()
                        .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    apkSize = a.optLong("size", -1L)
                    break
                }
            }
        }
        return ReleaseInfo(
            tag = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            notes = o.optString("body").replace("\r\n", "\n").trim(),
            pageUrl = pageUrl,
            apkUrl = apkUrl,
            apkSize = apkSize,
            source = source,
        )
    }

    /** 按数字逐段比较（0.2.10 > 0.2.9）；每段取开头的数字，缺段按 0。 */
    fun isNewer(remote: String, local: String): Boolean = compareVersions(remote, local) > 0

    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
