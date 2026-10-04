package com.mie.kreaworkbench.data.update

import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** GitHub 最新 Release 的必要字段。version = tag 去掉前导 v。 */
data class ReleaseInfo(val tag: String, val version: String, val notes: String, val url: String)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val info: ReleaseInfo) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 轻量更新检查（round14）：只读 GitHub releases/latest，比较 tag 与 BuildConfig.VERSION_NAME，
 * 有新版就弹窗给下载页链接（浏览器打开），不下载、不应用内安装。
 * 启动自动检查最多 24h 一次、尊重「忽略此版本」，任何失败静默；关于页手动检查无视两者。
 * 用独立 OkHttpClient：不挂 ComfyUI 的 Basic Auth 拦截器，走系统默认代理（不像 ComfyUI 客户端强制直连）。
 */
object UpdateChecker {
    const val LATEST_URL = "https://api.github.com/repos/Mimikkoro1/comfy-workbench-android/releases/latest"
    const val RELEASES_PAGE = "https://github.com/Mimikkoro1/comfy-workbench-android/releases"
    private const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

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

    /** 启动时调用：未到 24h 或失败返回 null；已忽略的 tag 也返回 null。 */
    suspend fun autoCheck(settings: SettingsStore): ReleaseInfo? = try {
        val now = System.currentTimeMillis()
        val last = settings.updateLastCheck()
        if (last in 1..now && now - last < AUTO_INTERVAL_MS) {
            null
        } else {
            settings.setUpdateLastCheck(now)
            val info = fetchLatest()
            if (isNewer(info.version, BuildConfig.VERSION_NAME) && info.tag != settings.updateIgnoredTag()) info else null
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** 关于页「检查更新」：无视 24h 与忽略记录。 */
    suspend fun manualCheck(settings: SettingsStore): UpdateResult = try {
        val info = fetchLatest()
        runCatching { settings.setUpdateLastCheck(System.currentTimeMillis()) }
        if (isNewer(info.version, BuildConfig.VERSION_NAME)) UpdateResult.Available(info) else UpdateResult.UpToDate
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        UpdateResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    private suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ComfyWorkbench-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            val tag = o.optString("tag_name").trim()
            if (tag.isBlank()) throw IOException("no tag_name")
            val url = o.optString("html_url").trim().takeIf { it.startsWith("https://") } ?: RELEASES_PAGE
            ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                notes = o.optString("body").replace("\r\n", "\n").trim(),
                url = url,
            )
        }
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
