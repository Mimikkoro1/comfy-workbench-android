package com.mie.kreaworkbench.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 更新包下载状态。挂在 [UpdateDownloader] 单例上，弹窗关了下载照跑。 */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** total <= 0 = 总大小未知（只显示已下载）。 */
    data class Running(val done: Long, val total: Long) : DownloadState
    data class Done(val file: File) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/**
 * r40（G3）：更新包应用内下载 + 调起系统安装。
 * - 独立 OkHttpClient（connect 15s / read 30s，不设 callTimeout），流式写 `.part`，完成后改名；
 * - 候选地址：当前 Release 的 apkUrl 优先，非 GitHub 源再从 GitHub 补一个直链，任一失败换下一个；
 * - 安装前校验包名与版本号，不对就删包报「安装包不对」；不做断点续传、不做签名比对。
 */
object UpdateDownloader {
    val state = MutableStateFlow<DownloadState>(DownloadState.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 取消下载：断开在途连接，`.part` 由协程 finally 删掉。已下完的包保留。 */
    fun cancel() {
        job?.cancel()
        job = null
        client.dispatcher.cancelAll()
        if (state.value is DownloadState.Running) state.value = DownloadState.Idle
    }

    /** 开始下载。已在下就忽略（防重复点）。 */
    fun start(info: ReleaseInfo, context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            state.value = DownloadState.Running(0, info.apkSize)
            try {
                val candidates = ArrayList<String>()
                if (!info.apkUrl.isNullOrBlank()) candidates.add(info.apkUrl)
                if (info.source != SettingsStore.UPDATE_SOURCE_GITHUB) {
                    runCatching { UpdateChecker.fetchApkFromGithub() }.getOrNull()?.let { (url, _) ->
                        if (url !in candidates) candidates.add(url)
                    }
                }
                if (candidates.isEmpty()) {
                    state.value = DownloadState.Failed(app.str(R.string.update_no_apk))
                    return@launch
                }

                val dir = File(app.cacheDir, "update")
                dir.mkdirs()
                val fileName = "ComfyWorkbench_${info.tag}.apk"
                // 下载前清掉其他 tag 的旧包
                for (old in dir.listFiles().orEmpty()) {
                    if (old.name != fileName) old.delete()
                }
                val part = File(dir, "$fileName.part")
                val target = File(dir, fileName)

                var lastError: Throwable? = null
                for (url in candidates) {
                    try {
                        part.delete()
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "ComfyWorkbench-Android/${BuildConfig.VERSION_NAME}")
                            .get()
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body ?: throw IOException("empty body")
                            val total = body.contentLength()
                            var done = 0L
                            body.byteStream().use { input ->
                                FileOutputStream(part).use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        if (!isActive) throw CancellationException("download cancelled")
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        done += n
                                        state.value = DownloadState.Running(done, if (total > 0) total else -1L)
                                    }
                                    out.flush()
                                }
                            }
                        }
                        if (!part.renameTo(target)) throw IOException("rename failed")
                        state.value = DownloadState.Done(target)
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = e
                    } finally {
                        part.delete()
                    }
                }
                state.value = DownloadState.Failed(
                    lastError?.message?.takeIf { it.isNotBlank() } ?: lastError?.javaClass?.simpleName ?: "download failed",
                )
            } catch (e: CancellationException) {
                // 用户取消：回到空闲；.part 已在上面各候选的 finally 里删除
                if (state.value is DownloadState.Running) state.value = DownloadState.Idle
            }
        }
    }

    /**
     * 校验已下好的包并调起系统安装。
     * 包名 / 版本号不对 → 删包、置 Failed（文案「安装包不对」）；未授权「未知来源」→ 返回 false，由调用方弹引导。
     */
    fun install(context: Context, info: ReleaseInfo): Boolean {
        val file = (state.value as? DownloadState.Done)?.file
        if (file == null || !file.exists()) {
            state.value = DownloadState.Failed(context.str(R.string.update_no_apk))
            return false
        }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, 0)
        if (archive == null || archive.packageName != context.packageName || archive.versionName != info.version) {
            file.delete()
            state.value = DownloadState.Failed(context.str(R.string.update_apk_invalid))
            return false
        }
        if (!pm.canRequestPackageInstalls()) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            return false
        }
        return true
    }
}
