package com.mie.kreaworkbench.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.mie.kreaworkbench.MainActivity
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.startGenerationService
import com.mie.kreaworkbench.data.CacheManager
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.data.api.ByteProgress
import com.mie.kreaworkbench.data.api.RemoteJob
import com.mie.kreaworkbench.data.api.Transfer
import com.mie.kreaworkbench.data.comfy.ComfyApi
import com.mie.kreaworkbench.data.api.imageUrl
import com.mie.kreaworkbench.data.db.GalleryDb
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.data.workflows.WorkflowStore
import com.mie.kreaworkbench.ui.locale.qty
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.util.extFromContentType
import com.mie.kreaworkbench.util.prepareUploadJpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class GenerationEngine(
    private val appContext: Context,
    private val db: GalleryDb,
    private val api: ComfyApi,
    private val transfer: Transfer,
    private val settings: SettingsStore,
    private val cache: CacheManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobWake: PowerManager.WakeLock =
        (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krea:job")
            .apply { setReferenceCounted(false) }
    private val running = ConcurrentHashMap<String, Job>()
    private val live = ConcurrentHashMap<String, LiveJob>()
    private val stopFlag = ConcurrentHashMap.newKeySet<String>()
    /** 用户手动点的重试/继续：允许 ComfyApi 在恢复失败后重新 POST /prompt（自动 reconcile 绝不放行）。 */
    private val manualKicks = ConcurrentHashMap.newKeySet<String>()
    /** 满 10 秒后界面在问，原图上传仍在跑。进度回调据此保持 upload_ask，避免把对话框盖掉。 */
    private val uploadAskHold = ConcurrentHashMap.newKeySet<String>()
    private val originalUploads = ConcurrentHashMap<String, Job>()
    private val compressGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val compressRequested = ConcurrentHashMap.newKeySet<String>()
    private val uploadUiLock = Any()
    val snapshot = MutableStateFlow<List<LiveJob>>(emptyList())
    val revision = MutableStateFlow(0L)

    init {
        // 启动兜底清理（round17）：视频暂存大，扫一次 uploads/ 里过期又无任务引用的文件
        scope.launch { cleanupStaleUploads() }
    }

    fun reconcile() {
        scope.launch {
            db.needsWork().forEach { kick(it.clientJobId) }
            publish()
        }
    }

    fun watchNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // needsWork() 会读库，不能在网络回调线程上跑
                scope.launch { resumePaused() }
            }
        })
    }

    suspend fun resumePaused() {
        val pending = db.needsWork().isNotEmpty() || live.values.any { it.phase !in TERMINAL || it.phase == "paused" }
        if (!pending) return
        startGenerationService(appContext)
    }

    /** Re-queue unfinished jobs whose coroutine died. Skips paused jobs so an offline phone does not spin forever. */
    fun reconcileActive() {
        scope.launch {
            db.needsWork().filter { it.state != "paused" }.forEach { kick(it.clientJobId) }
        }
    }

    suspend fun hasUnfinished(): Boolean = try {
        db.needsWork().any { it.state != "paused" }
    } catch (_: Exception) {
        false
    }

    fun kick(clientId: String, manual: Boolean = false) {
        if (clientId.isBlank()) return
        if (running[clientId]?.isActive == true) return
        if (manual) manualKicks.add(clientId) else manualKicks.remove(clientId)
        stopFlag.remove(clientId)
        running[clientId] = scope.launch { runJob(clientId) }
    }

    /** 用户手动点「继续/重试」：和自动 kick 的区别是提交恢复失败后允许重新 POST /prompt。 */
    fun resumeManual(clientId: String) = kick(clientId, manual = true)

    fun cancel(clientId: String) {
        scope.launch {
            stopFlag.add(clientId)
            // 先停掉提交/轮询协程再撤销：提交阶段 jobId 还没写回，旧写法（先 cancelJob 后 cancel）
            // 会跳过撤销、且提交协程可能在撤销之后又交一张
            running[clientId]?.cancelAndJoin()
            // round17：取消时删暂存视频（失败不删，可能手动重试；参考图沿用原行为不删）
            try {
                db.job(clientId)?.requestJson?.let { raw ->
                    val lv = runCatching { JSONObject(raw).optString("local_video") }.getOrNull()
                    if (!lv.isNullOrBlank()) File(lv).delete()
                }
            } catch (_: Exception) {
            }
            // 先落「已取消」再撤服务器：断网时撤销请求会拖满超时，UI 不能跟着等
            db.updateJob(clientId) { it.copy(state = "cancelled", error = "") }
            put(liveOf(clientId, "cancelled"))
            try {
                api.cancelJob("cq_$clientId")
            } catch (_: Exception) {
                // 断网时撤销必然失败、静默丢弃会在服务端留幽灵任务（恢复网络后原任务照跑）：
                // 定期重试清理；若任务被重新发起（state 离开 cancelled）就放弃，避免误删新任务
                retryServerCleanup(clientId)
            }
        }
    }

    /** 服务端残留清理重试：30s 一次、至多 10 次；仅当任务仍处于 cancelled（未被重新发起）时执行。 */
    private fun retryServerCleanup(clientId: String) {
        scope.launch {
            repeat(10) {
                delay(30_000)
                if (db.job(clientId)?.state != "cancelled") return@launch
                try {
                    api.cancelJob("cq_$clientId")
                    return@launch
                } catch (_: Exception) {
                }
            }
        }
    }

    fun hasActive(): Boolean = running.values.any { it.isActive }

    private suspend fun runJob(clientId: String) {
        // 任务 deadline：视频（Wan 81 帧 × 2 采样器）可能远超 30 分钟；到点走 pause（可恢复）而不是 failed。
        // 提交和上传的网络失败重试另计，最多 NETWORK_RETRY_CAP_MS，不跟这个 deadline 一样长。
        val deadline = System.currentTimeMillis() + deadlineMs(clientId)
        holdWake()
        try {
            val row = db.job(clientId) ?: return
            val ready = prepareBody(clientId, row.requestJson, deadline) ?: return
            put(liveOf(clientId, "submitting", prompt = promptOf(db.job(clientId) ?: row)))
            var serverId = db.job(clientId)?.jobId.orEmpty().ifBlank { row.jobId }
            if (serverId.isBlank()) {
                serverId = submit(ready, clientId, deadline, manualKicks.remove(clientId)) ?: return
            }
            poll(clientId, serverId, deadline)
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            finishLocal(clientId, "failed", e.message ?: appContext.str(R.string.err_generation_failed))
        } finally {
            running.remove(clientId)
            dropWake()
        }
    }

    /** 任务总 deadline：custom 的 text/video 工作流放宽到 2 小时，其余 30 分钟。轮询用它；网络重试不用。 */
    private fun deadlineMs(clientId: String): Long {
        val long = try {
            val row = db.job(clientId) ?: return 30 * 60 * 1000L
            val body = JSONObject(row.requestJson)
            body.optString("mode") == "custom" && body.optString("kwb_output").let { it == "video" || it == "text" }
        } catch (_: Exception) {
            false
        }
        return if (long) 120 * 60 * 1000L else 30 * 60 * 1000L
    }

    /** 「视频下载失败，点击重试」：重启轮询协程（jobId 已记录，不会重新提交）。用户手动点，算 manual。 */
    fun retryDownload(clientId: String) {
        if (clientId.isBlank()) return
        scope.launch {
            running[clientId]?.cancelAndJoin()
            kick(clientId, manual = true)
        }
    }

    private fun holdWake() {
        // 10 分钟超时（v0.5 §3）：轮询循环和下载进度回调会不断续期；任务结束 finally 立刻释放
        try {
            jobWake.acquire(10 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    private fun dropWake() {
        if (running.isNotEmpty()) return
        try {
            if (jobWake.isHeld) jobWake.release()
        } catch (_: RuntimeException) {
        }
    }

    private suspend fun submit(body: String, clientId: String, deadline: Long, manual: Boolean): String? {
        // 网络失败最多重试 NETWORK_RETRY_CAP_MS，且不超过任务 deadline。到点 pause，可恢复，不改 failed。
        val submitDeadline = minOf(deadline, System.currentTimeMillis() + NETWORK_RETRY_CAP_MS)
        while (System.currentTimeMillis() < submitDeadline) {
            if (clientId in stopFlag) return null
            try {
                // manual=true 表示这次提交是用户手动触发的：ComfyApi 据此允许在恢复失败后重新 POST /prompt
                val payload = JSONObject(body)
                if (manual) payload.put("manual_retry", true)
                val job = api.submitJob(payload)
                db.updateJob(clientId) {
                    it.copy(jobId = job.jobId, state = job.state, total = job.total, done = job.done)
                }
                return job.jobId
            } catch (e: ApiException) {
                // 400 等确定性失败（缺模型）：network=false，直接失败，不进网络重试。
                // 摘要来自 Http.errorText（error/node_errors），由任务卡片显示。
                if (!e.network) {
                    finishLocal(clientId, "failed", e.message ?: appContext.str(R.string.err_submit_failed))
                    return null
                }
                put(liveOf(clientId, "submitting", error = e.message ?: ""))
                delay(5000)
            }
        }
        pause(clientId)
        return null
    }

    private suspend fun poll(clientId: String, serverId: String, deadline: Long) {
        while (System.currentTimeMillis() < deadline) {
            if (clientId in stopFlag) return
            holdWake()
            try {
                val job = api.getJob(serverId)
                if (!saveResults(clientId, job)) {
                    delay(5000)
                    continue
                }
                val phase = if (job.state in setOf("success", "partial", "failed", "cancelled")) job.state else job.state
                db.updateJob(clientId) {
                    it.copy(state = job.state, done = job.done, total = job.total, error = job.error.orEmpty(), jobId = serverId)
                }
                put(fromRemote(clientId, job, phase))
                if (job.state in setOf("success", "partial", "failed", "cancelled")) {
                    rememberSeeds(job)
                    emitDone(clientId, job.state, job.error)
                    return
                }
                delay(1500)
            } catch (e: ApiException) {
                if (!e.network) {
                    finishLocal(clientId, "failed", e.message ?: appContext.str(R.string.err_query_failed))
                    return
                }
                delay(5000)
            }
        }
        pause(clientId)
    }

    /** 图片/视频下载 + 文本落库；false = 有产物没拿到（轮询稍后自动重试），任务不算失败。 */
    private suspend fun saveResults(clientId: String, job: RemoteJob): Boolean {
        val textsOk = saveTexts(job)
        val videosOk = saveVideos(clientId, job)
        val imagesOk = saveImages(clientId, job)
        return textsOk && videosOk && imagesOk
    }

    /** 文本结果直接写进任务记录（重启 App 后仍在）；已存在的行跳过。 */
    private fun saveTexts(job: RemoteJob): Boolean {
        for (t in job.texts) {
            if (t.text.isBlank()) continue
            if (db.hasLocal(job.jobId, t.index, "text")) continue
            if (db.isTombstoned(job.jobId, t.index, "text")) continue
            db.insertImage(
                ImageRow(
                    id = 0,
                    jobId = job.jobId,
                    clientJobId = job.clientJobId,
                    idx = t.index,
                    mode = job.mode,
                    prompt = job.prompt,
                    paramsJson = job.params.toString(),
                    seed = 0,
                    width = 0,
                    height = 0,
                    remoteFilename = "",
                    remoteSubfolder = "",
                    localPath = "",
                    sizeBytes = t.text.length.toLong(),
                    createdAt = System.currentTimeMillis(),
                    savedToAlbum = false,
                    albumUri = "",
                    kind = "text",
                    text = t.text,
                ),
            )
            revision.update { it + 1 }
        }
        return true
    }

    /** 视频：流式写 .part 再重命名（Transfer 内置 Range 续传 + 退避重试）；失败返回 false，不置 failed。 */
    private suspend fun saveVideos(clientId: String, job: RemoteJob): Boolean {
        for (v in job.videos) {
            if (db.hasLocal(job.jobId, v.index, "video")) continue
            if (db.isTombstoned(job.jobId, v.index, "video", v.filename, v.subfolder)) continue
            publishDownload(clientId, job, ByteProgress(0, 0, 0.0))
            val ext = v.filename.substringAfterLast('.', "mp4").lowercase().take(8)
            val dest = File(cache.imagesDir(), "${job.jobId}_v${v.index}.${ext}")
            val url = v.url.ifBlank { imageUrl(v.filename, v.subfolder, v.type) }
            try {
                var lastUi = 0L
                transfer.download(url, dest) { prog ->
                    val now = System.currentTimeMillis()
                    val finished = prog.total > 0 && prog.done >= prog.total
                    if (now - lastUi >= 250 || finished) {
                        lastUi = now
                        try {
                            holdWake()
                            publishDownload(clientId, job, prog)
                        } catch (_: Exception) {
                        }
                    }
                }
                // 护栏（direct12）：0 字节文件=下载失败，走既有 downloadFailed 路径（轮询稍后重试）
                if (dest.length() == 0L) error(appContext.str(R.string.err_empty_file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                put((live[clientId] ?: fromRemote(clientId, job, "downloading")).copy(phase = "downloading", downloadFailed = true, error = appContext.str(R.string.err_video_download, e.message ?: appContext.str(R.string.err_net))))
                return false
            }
            // 宽高用真实容器尺寸；取不到就 0（UI 退回 16:9）
            var w = 0
            var h = 0
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(dest.absolutePath)
                w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
            val videoRow = ImageRow(
                id = 0,
                jobId = job.jobId,
                clientJobId = clientId,
                idx = v.index,
                mode = job.mode,
                prompt = job.prompt,
                paramsJson = job.params.toString(),
                seed = v.seed,
                width = w,
                height = h,
                remoteFilename = v.filename,
                remoteSubfolder = v.subfolder,
                localPath = dest.absolutePath,
                sizeBytes = dest.length(),
                createdAt = System.currentTimeMillis(),
                savedToAlbum = false,
                albumUri = "",
                kind = "video",
            )
            db.insertImage(videoRow)
            cache.enforce()
            revision.update { it + 1 }
        }
        return true
    }

    private suspend fun saveImages(clientId: String, job: RemoteJob): Boolean {
        for (img in job.images) {
            if (db.hasLocal(job.jobId, img.index)) continue
            if (db.isTombstoned(job.jobId, img.index, "image", img.filename, img.subfolder)) continue
            publishDownload(clientId, job, ByteProgress(0, 0, 0.0))
            // 扩展名优先取 ComfyUI 返回的 filename 后缀（bug 修复：以前写死 .png）；
            // 文件名没带后缀时先落占位名，下载完按响应 Content-Type 决定扩展名并改名
            val extFromName = img.filename.substringAfterLast('.', "").lowercase()
                .takeIf { it in IMAGE_EXTS }
            var file = File(cache.imagesDir(), "${job.jobId}_${img.index}.${extFromName ?: "bin"}")
            val url = img.url.ifBlank { imageUrl(img.filename, img.subfolder, img.type) }
            try {
                var lastUi = 0L
                val ctype = transfer.download(url, file) { prog ->
                    val now = System.currentTimeMillis()
                    val finished = prog.total > 0 && prog.done >= prog.total
                    if (now - lastUi >= 250 || finished) {
                        lastUi = now
                        try {
                            holdWake()
                            publishDownload(clientId, job, prog)
                        } catch (_: Exception) {
                        }
                    }
                }
                val ext = extFromName ?: extFromContentType(ctype) ?: "png"
                if (ext != file.extension) {
                    val renamed = File(cache.imagesDir(), "${job.jobId}_${img.index}.$ext")
                    renamed.delete()
                    if (!file.renameTo(renamed)) {
                        file.copyTo(renamed, overwrite = true)
                        file.delete()
                    }
                    file = renamed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            // 护栏（direct12）：下载完成却解不出像素（0×0，文件损坏/半截）→ 删文件、不插行、跳过；
            // 不算失败，saveResults 不因此返回 false，轮询不会为此重试。
            var outW = 0
            var outH = 0
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                outW = opts.outWidth
                outH = opts.outHeight
            } catch (_: Exception) {
            }
            if (outW <= 0 || outH <= 0) {
                file.delete()
                // round18：只删文件不记账会让 needsWork 的「done > 图片数 + 墓碑数」永远为真，
                // 任务被判成「还有产物没拿全」，每次冷启动都重新下载同一个坏文件。补一条墓碑封口。
                db.addTombstone(
                    ImageRow(
                        id = 0,
                        jobId = job.jobId,
                        clientJobId = clientId,
                        idx = img.index,
                        mode = job.mode,
                        prompt = job.prompt,
                        paramsJson = "",
                        seed = img.seed,
                        width = 0,
                        height = 0,
                        remoteFilename = img.filename,
                        remoteSubfolder = img.subfolder,
                        localPath = "",
                        sizeBytes = 0,
                        createdAt = System.currentTimeMillis(),
                        savedToAlbum = false,
                        albumUri = "",
                    ),
                )
                continue
            }
            val imgRow = ImageRow(
                id = 0,
                jobId = job.jobId,
                clientJobId = clientId,
                idx = img.index,
                mode = job.mode,
                prompt = job.prompt,
                paramsJson = job.params.toString(),
                seed = img.seed,
                width = outW,
                height = outH,
                remoteFilename = img.filename,
                remoteSubfolder = img.subfolder,
                localPath = file.absolutePath,
                sizeBytes = file.length(),
                createdAt = System.currentTimeMillis(),
                savedToAlbum = false,
                albumUri = "",
            )
            db.insertImage(imgRow)
            cache.enforce()
            revision.update { it + 1 }
        }
        return true
    }

    private fun publishDownload(clientId: String, job: RemoteJob, prog: ByteProgress) {
        val cur = live[clientId] ?: fromRemote(clientId, job, "downloading")
        put(
            cur.copy(
                phase = "downloading",
                mode = job.mode.ifBlank { cur.mode },
                queuePosition = job.queuePosition,
                step = job.progressValue,
                stepMax = job.progressMax,
                imageIndex = job.imageIndex,
                done = job.done,
                total = job.total.coerceAtLeast(1),
                download = if (prog.total > 0) prog.fraction else -1f,
                bytesDone = prog.done.coerceAtLeast(0),
                bytesTotal = prog.total.coerceAtLeast(0),
                bytesPerSec = prog.bytesPerSec.coerceAtLeast(0.0),
                downloadFailed = false,
                error = "",
                prompt = job.prompt.ifBlank { cur.prompt },
            ),
        )
    }

    private suspend fun rememberSeeds(job: RemoteJob) {
        val seeds = (job.images + job.videos).joinToString(",") { it.seed.toString() }
        if (seeds.isNotBlank()) settings.update { it.copy(lastSeeds = seeds) }
    }

    private suspend fun prepareBody(clientId: String, raw: String, deadline: Long): String? {
        val json = JSONObject(raw)
        val mode = json.optString("mode")
        if (mode != "custom") return raw

        // 图片槽位：定义里第一个 file:image spec 的 values 键。
        // 定义/工作流已被删除属不可重试失败，直接报错，不能在这里无限重试。
        val def: JSONObject? = try {
            WorkflowStore(appContext).definition(json.optString("workflow_id"))
        } catch (e: ApiException) {
            if (!e.network) {
                finishLocal(clientId, "failed", e.message ?: appContext.str(R.string.err_workflow_deleted))
                return null
            }
            null
        }
        if (def == null) {
            finishLocal(clientId, "failed", appContext.str(R.string.err_workflow_deleted))
            return null
        }
        var values = json.optJSONObject("values")

        // 视频槽位（round17）：定义里第一个 file:video spec。先传视频再走图片流程；
        // 视频不弹「是否压缩」，网络失败重试到 cap 后 pause。
        var videoSlot: String? = null
        val specs = def.optJSONArray("user_facing_inputs")
        if (specs != null) {
            for (i in 0 until specs.length()) {
                val spec = specs.optJSONObject(i) ?: continue
                if (spec.optString("type") == "file:video") {
                    videoSlot = "${spec.optString("node_id")}|${spec.optString("field")}"
                    break
                }
            }
        }
        if (videoSlot != null && values?.optString(videoSlot).orEmpty().isBlank()) {
            val slot: String = videoSlot
            val path = json.optString("local_video")
            val file = File(path)
            if (path.isBlank() || !file.exists()) {
                finishLocal(clientId, "failed", appContext.str(R.string.err_video_missing))
                return null
            }
            db.updateJob(clientId) { it.copy(state = "uploading") }
            put(liveOf(clientId, "uploading").copy(download = 0f))
            val uploadDeadline = minOf(deadline, System.currentTimeMillis() + NETWORK_RETRY_CAP_MS)
            var uploadedPair: Pair<String, String>? = null
            while (System.currentTimeMillis() < uploadDeadline) {
                if (clientId in stopFlag) return null
                try {
                    val uploaded = transfer.uploadVideo(file) { frac ->
                        put(liveOf(clientId, "uploading").copy(download = frac))
                    }
                    val vals = values ?: JSONObject().also { values = it; json.put("values", it) }
                    vals.put(slot, "${uploaded.second}/${uploaded.first}")
                    json.remove("local_video")
                    // 先落库再删文件：续传 / 重启后不会重复上传
                    db.updateJob(clientId) { it.copy(requestJson = json.toString()) }
                    file.delete()
                    uploadedPair = uploaded
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (!e.network) {
                        finishLocal(clientId, "failed", e.message ?: appContext.str(R.string.err_upload_failed))
                        return null
                    }
                    put(liveOf(clientId, "uploading", error = e.message ?: ""))
                    delay(5000)
                } catch (_: Exception) {
                    delay(5000)
                }
            }
            if (uploadedPair == null) {
                pause(clientId)
                return null
            }
        }

        // 图片槽位：定义里第一个 file:image spec 的 values 键。
        // 定义/工作流已被删除属不可重试失败，直接报错，不能在这里无限重试。
        var slotKey: String? = null
        if (specs != null) {
            for (i in 0 until specs.length()) {
                val spec = specs.optJSONObject(i) ?: continue
                if (spec.optString("type") == "file:image") {
                    slotKey = "${spec.optString("node_id")}|${spec.optString("field")}"
                    break
                }
            }
        }
        // 视频步骤可能已改过 json（values/local_video），早退必须返回新串
        if (slotKey == null || values?.optString(slotKey).orEmpty().isNotBlank()) return json.toString()
        val slot: String = slotKey

        val path = json.optString("local_jpeg")
        val file = File(path)
        if (path.isBlank() || !file.exists()) {
            finishLocal(clientId, "failed", appContext.str(R.string.err_reference_missing))
            return null
        }
        db.updateJob(clientId) { it.copy(state = "uploading") }
        if (json.optBoolean("upload_compressed")) {
            // 用户已确认的压缩重传：网络失败最多重试 NETWORK_RETRY_CAP_MS。到点 pause，不再弹询问。
            val uploadDeadline = minOf(deadline, System.currentTimeMillis() + NETWORK_RETRY_CAP_MS)
            while (System.currentTimeMillis() < uploadDeadline) {
                if (clientId in stopFlag) return null
                try {
                    put(liveOf(clientId, "uploading").copy(download = 0f))
                    val uploaded = transfer.upload(file) { frac ->
                        put(liveOf(clientId, "uploading").copy(download = frac))
                    }
                    finishUploadJson(json, uploaded, slot)
                    val body = json.toString()
                    db.updateJob(clientId) { it.copy(state = "pending", requestJson = body) }
                    file.delete()
                    return body
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (!e.network) {
                        finishLocal(clientId, "failed", e.message ?: appContext.str(R.string.err_upload_failed))
                        return null
                    }
                    delay(5000)
                } catch (_: Exception) {
                    delay(5000)
                }
            }
            pause(clientId)
            return null
        }
        // 原图直传不设 10 秒 callTimeout。满 10 秒只弹询问，请求继续；用户选压缩才取消这一支。
        put(liveOf(clientId, "uploading").copy(download = 0f))
        when (val outcome = uploadOriginalWithAsk(clientId, file)) {
            is OriginalUpload.Ok -> {
                compressRequested.remove(clientId)
                if (clientId in stopFlag) return null
                finishUploadJson(json, outcome.pair, slot)
                val body = json.toString()
                db.updateJob(clientId) { it.copy(state = "pending", requestJson = body) }
                file.delete()
                return body
            }
            is OriginalUpload.Failed -> {
                if (clientId in stopFlag) return null
                if (compressRequested.remove(clientId)) {
                    if (!beginCompressedRetry(clientId)) return null
                    val refreshed = db.job(clientId)?.requestJson ?: return null
                    return prepareBody(clientId, refreshed, deadline)
                }
                val ask = appContext.str(R.string.upload_fail_ask, outcome.message)
                db.updateJob(clientId) { it.copy(state = "paused", error = ask) }
                put(liveOf(clientId, "upload_fail_ask", error = ask))
                return null
            }
            OriginalUpload.Compress -> {
                compressRequested.remove(clientId)
                if (clientId in stopFlag) return null
                if (!beginCompressedRetry(clientId)) return null
                val refreshed = db.job(clientId)?.requestJson ?: return null
                return prepareBody(clientId, refreshed, deadline)
            }
        }
    }

    /** 上传成功后把 subfolder/文件名 写回 custom body 的 values[<node>|<field>]。 */
    private fun finishUploadJson(json: JSONObject, uploaded: Pair<String, String>, slotKey: String) {
        val values = json.optJSONObject("values") ?: JSONObject()
        values.put(slotKey, "${uploaded.second}/${uploaded.first}")
        json.put("values", values)
        json.remove("local_jpeg")
        json.remove("upload_compressed")
    }

    /**
     * 原图上传询问。compress=false：上传还在跑则继续（不取消请求）；失败对话框上则取消任务。
     * 上传已经成功、相位离开询问后，残影点击不能把任务改成失败。
     * compress=true：还在传就只通知父协程取消这一支；已经结束则等 runJob 退出后再压缩并 kick。
     * 父协程若已拿走 compressRequested，这边 join 完看见标记没了就返回，避免压缩两次。
     */
    fun answerUploadAsk(clientId: String, compress: Boolean) {
        if (!compress) {
            val upload = originalUploads[clientId]
            if (upload != null && upload.isActive) {
                synchronized(uploadUiLock) {
                    uploadAskHold.remove(clientId)
                    val frac = live[clientId]?.download ?: 0f
                    put(liveOf(clientId, "uploading").copy(download = frac))
                }
                return
            }
            if (live[clientId]?.phase == "upload_fail_ask") {
                scope.launch { finishLocal(clientId, "failed", appContext.str(R.string.upload_cancelled)) }
            }
            return
        }
        val inFlight = originalUploads[clientId]?.isActive == true
        if (inFlight) {
            if (compressRequested.add(clientId)) {
                compressGates[clientId]?.complete(Unit)
            }
            return
        }
        if (!compressRequested.add(clientId)) return
        scope.launch {
            try {
                val job = running[clientId]
                val stateNow = db.job(clientId)?.state
                if (job != null && stateNow in setOf("uploading", "paused")) job.join()
                if (!compressRequested.remove(clientId)) return@launch
                val state = db.job(clientId)?.state
                if (state != "paused" && state != "uploading") return@launch
                if (!beginCompressedRetry(clientId)) return@launch
                kick(clientId)
            } catch (e: CancellationException) {
                compressRequested.remove(clientId)
                throw e
            }
        }
    }

    /** 满 10 秒弹出询问，但不取消正在进行的原图上传。 */
    private suspend fun uploadOriginalWithAsk(clientId: String, file: File): OriginalUpload {
        val acceptProgress = AtomicBoolean(true)
        val gate = CompletableDeferred<Unit>()
        compressGates[clientId] = gate
        try {
            return supervisorScope {
                val deferred = async {
                    transfer.uploadOriginal(file) { frac ->
                        publishOriginalProgress(clientId, frac, acceptProgress)
                    }
                }
                originalUploads[clientId] = deferred
                val timer = launch {
                    delay(ORIGINAL_UPLOAD_TIMEOUT_MS)
                    ensureActive()
                    synchronized(uploadUiLock) {
                        if (!acceptProgress.get()) return@synchronized
                        uploadAskHold.add(clientId)
                        val frac = live[clientId]?.download ?: 0f
                        put(liveOf(clientId, "upload_ask", error = appContext.str(R.string.upload_slow_msg)).copy(download = frac))
                    }
                }
                try {
                    val selected = select<OriginalUpload> {
                        deferred.onAwait { pair -> OriginalUpload.Ok(pair) }
                        gate.onAwait { _ -> OriginalUpload.Compress }
                    }
                    // 上传已经成功、压缩询问同时到达：按成功提交，不再取消这次传输。
                    val outcome = if (selected is OriginalUpload.Compress && deferred.isCompleted) {
                        try {
                            OriginalUpload.Ok(deferred.await())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            selected
                        }
                    } else {
                        selected
                    }
                    timer.cancel()
                    endOriginalProgress(clientId, acceptProgress)
                    if (outcome is OriginalUpload.Compress) {
                        deferred.cancel()
                        deferred.join()
                    } else {
                        put(liveOf(clientId, "uploading").copy(download = 1f))
                    }
                    outcome
                } catch (e: CancellationException) {
                    timer.cancel()
                    endOriginalProgress(clientId, acceptProgress)
                    throw e
                } catch (e: Exception) {
                    timer.cancel()
                    endOriginalProgress(clientId, acceptProgress)
                    if (clientId in stopFlag) throw CancellationException("cancelled", e)
                    OriginalUpload.Failed(e.message?.takeIf { it.isNotBlank() } ?: appContext.str(R.string.err_upload_failed))
                }
            }
        } finally {
            originalUploads.remove(clientId)
            compressGates.remove(clientId)
            synchronized(uploadUiLock) { uploadAskHold.remove(clientId) }
        }
    }

    /** 停掉进度/计时器对相位的写入。必须和 publish 用同一把锁，避免失败对话框被后到的进度盖掉。 */
    private fun endOriginalProgress(clientId: String, acceptProgress: AtomicBoolean) {
        synchronized(uploadUiLock) {
            acceptProgress.set(false)
            uploadAskHold.remove(clientId)
        }
    }

    private fun publishOriginalProgress(clientId: String, frac: Float, acceptProgress: AtomicBoolean) {
        synchronized(uploadUiLock) {
            if (!acceptProgress.get()) return
            val hold = clientId in uploadAskHold
            put(
                liveOf(clientId, if (hold) "upload_ask" else "uploading", error = if (hold) appContext.str(R.string.upload_slow_msg) else "")
                    .copy(download = frac),
            )
        }
    }

    /** 把 local_jpeg 换成压缩图并打上 upload_compressed，供压缩上传分支接着跑。 */
    private suspend fun beginCompressedRetry(clientId: String): Boolean {
        val row = db.job(clientId)
        val json = row?.let { runCatching { JSONObject(it.requestJson) }.getOrNull() }
        val src = json?.optString("local_jpeg")?.takeIf { it.isNotBlank() }?.let(::File)
        if (row == null || src == null || !src.exists()) {
            finishLocal(clientId, "failed", appContext.str(R.string.err_reference_missing))
            return false
        }
        stopFlag.remove(clientId)
        put(liveOf(clientId, "uploading").copy(download = 0f))
        return try {
            val compressed = withContext(Dispatchers.IO) { prepareUploadJpeg(appContext, src) }
            val dest = withContext(Dispatchers.IO) {
                val out = File(src.parentFile, "${clientId}_compressed.jpg")
                compressed.copyTo(out, overwrite = true)
                if (compressed.absolutePath != out.absolutePath) compressed.delete()
                out
            }
            src.delete()
            json.put("local_jpeg", dest.absolutePath)
            json.put("upload_compressed", true)
            db.updateJob(clientId) { it.copy(state = "uploading", requestJson = json.toString(), error = "") }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishLocal(clientId, "failed", appContext.str(R.string.err_compress, e.message ?: appContext.str(R.string.err_unknown)))
            false
        }
    }

    private fun pause(clientId: String) {
        val msg = appContext.str(R.string.paused_net)
        db.updateJob(clientId) { it.copy(state = "paused", error = msg) }
        put(liveOf(clientId, "paused", error = msg))
    }

    private fun emitDone(clientId: String, state: String, error: String?) {
        if (state == "cancelled") return
        val count = db.imagePathsForClient(clientId).size
        val title = when (state) {
            "failed" -> appContext.str(R.string.err_generation_failed)
            else -> appContext.str(R.string.notify_done)
        }
        val text = when (state) {
            "failed" -> error ?: appContext.str(R.string.err_generation_failed)
            "partial" -> appContext.qty(R.plurals.partial_done, count, count)
            else -> appContext.qty(R.plurals.image_total, count, count)
        }
        scope.launch {
            if (!settings.current().notifyEnabled) return@launch
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }
            ensureNotifyChannels(appContext)
            val open = PendingIntent.getActivity(
                appContext,
                3,
                Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val b = Notification.Builder(appContext, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
            appContext.getSystemService(NotificationManager::class.java)
                .notify((System.currentTimeMillis() % 100000).toInt() + 100, b.build())
        }
    }

    private fun finishLocal(clientId: String, state: String, error: String) {
        db.updateJob(clientId) { it.copy(state = state, error = error) }
        put(liveOf(clientId, state, error = error))
        emitDone(clientId, state, error)
    }

    /**
     * 暂存文件兜底清理（round17）：删 filesDir/uploads/ 里修改时间早于 72 小时、且没有被任何
     * 进行中任务（needsWork 覆盖 uploading/paused/pending 等全部非终态）的 requestJson
     * （local_video / local_jpeg）引用的文件。只扫 uploads/，永不碰图库目录。
     * 失败任务的暂存文件 72 小时内仍可手动重试，之后由这里回收。
     */
    private suspend fun cleanupStaleUploads() {
        try {
            val dir = File(appContext.filesDir, "uploads")
            if (!dir.isDirectory) return
            val referenced = HashSet<String>()
            for (row in db.needsWork()) {
                val json = runCatching { JSONObject(row.requestJson) }.getOrNull() ?: continue
                for (key in listOf("local_video", "local_jpeg")) {
                    val p = json.optString(key)
                    if (p.isNotBlank()) referenced.add(File(p).absolutePath)
                }
            }
            val cutoff = System.currentTimeMillis() - STALE_UPLOAD_MS
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff && f.absolutePath !in referenced) {
                    runCatching { f.delete() }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun fromRemote(clientId: String, job: RemoteJob, phase: String): LiveJob {
        val paths = db.imagePathsForClient(clientId)
        val cur = live[clientId]
        return LiveJob(
            clientJobId = clientId,
            mode = job.mode.ifBlank { cur?.mode ?: "" },
            phase = phase,
            queuePosition = job.queuePosition,
            step = job.progressValue,
            stepMax = job.progressMax,
            imageIndex = job.imageIndex,
            done = job.done,
            total = job.total,
            error = job.error.orEmpty(),
            paths = paths,
            prompt = job.prompt,
            stage = job.stage.ifBlank { cur?.stage ?: "" },
            createdAt = cur?.createdAt ?: System.currentTimeMillis(),
        )
    }

    private fun liveOf(clientId: String, phase: String, prompt: String = "", error: String = ""): LiveJob {
        val row = db.job(clientId)
        val cur = live[clientId]
        val paths = db.imagePathsForClient(clientId)
        return LiveJob(
            clientJobId = clientId,
            mode = row?.mode ?: cur?.mode ?: "",
            phase = phase,
            done = row?.done ?: 0,
            total = row?.total ?: 1,
            error = error,
            paths = paths,
            prompt = prompt.ifBlank { cur?.prompt ?: "" },
            createdAt = row?.createdAt ?: cur?.createdAt ?: System.currentTimeMillis(),
        )
    }

    private fun put(job: LiveJob) {
        live[job.clientJobId] = job
        publish()
    }

    private fun publish() {
        snapshot.value = live.values.sortedByDescending { it.createdAt }
    }
}

/** 提交、上传参考图的网络失败自动重试上限。排队和生成中的轮询不走这条，仍用任务 deadline。 */
private const val NETWORK_RETRY_CAP_MS = 5 * 60 * 1000L

/** 图片产物允许的扩展名（按 ComfyUI 返回的 filename 判断）；不在此列的走 Content-Type。 */
private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")

/** 原图上传满这段时间就弹出「继续 / 压缩」。只是界面计时，不取消请求。 */
private const val ORIGINAL_UPLOAD_TIMEOUT_MS = 10_000L

/** uploads/ 暂存文件的保留期（round17）：修改时间早于该值且无任务引用的文件启动时删除。 */
private const val STALE_UPLOAD_MS = 72 * 60 * 60 * 1000L

private sealed class OriginalUpload {
    class Ok(val pair: Pair<String, String>) : OriginalUpload()
    object Compress : OriginalUpload()
    class Failed(val message: String) : OriginalUpload()
}

private fun promptOf(row: com.mie.kreaworkbench.data.db.JobRow): String = try {
    JSONObject(row.requestJson).optString("prompt")
} catch (_: Exception) {
    ""
}
