package com.mie.kreaworkbench.data.comfy

import android.content.Context
import android.os.Build
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.data.api.ConnError
import com.mie.kreaworkbench.data.api.ConnErrorKind
import com.mie.kreaworkbench.data.api.ConnectionReport
import com.mie.kreaworkbench.data.api.ConnStage
import com.mie.kreaworkbench.data.api.RemoteImage
import com.mie.kreaworkbench.data.api.RemoteJob
import com.mie.kreaworkbench.data.api.RemoteText
import com.mie.kreaworkbench.data.api.baseUrl
import com.mie.kreaworkbench.data.api.callBodyText
import com.mie.kreaworkbench.data.api.callJson
import com.mie.kreaworkbench.data.api.classifyConnectionError
import com.mie.kreaworkbench.data.api.imageUrl
import com.mie.kreaworkbench.data.api.isComfyStats
import com.mie.kreaworkbench.data.api.jsonBody
import com.mie.kreaworkbench.data.api.jsonClient
import com.mie.kreaworkbench.data.api.parseOutputEntries
import com.mie.kreaworkbench.data.api.strings
import com.mie.kreaworkbench.data.api.techSummary
import com.mie.kreaworkbench.data.api.trimBase
import com.mie.kreaworkbench.data.api.WsHandshakeOutcome
import com.mie.kreaworkbench.data.api.wsProbeError
import com.mie.kreaworkbench.data.db.GalleryDb
import com.mie.kreaworkbench.data.net.isLocalNetworkGranted
import com.mie.kreaworkbench.data.net.urlTargetsLocalNetwork
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.data.workflows.WorkflowStore
import com.mie.kreaworkbench.data.workflows.baseClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 导入时按 class 查 /object_info 的结果。failed 是网络失败，不是「节点不存在」。 */
data class NodeLookup(val node: JSONObject?, val failed: Boolean)

/**
 * ComfyUI 直连客户端：在 App 内部完成原来 8199 工作台代做的拼工作流、提交、轮询、下图。
 * 任务 id 形如 "cq_<client_job_id>"，提交记录（prompt_id/种子）存在任务 requestJson 的 comfy_prompts 里。
 */
class ComfyApi(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val db: GalleryDb,
    auth: Interceptor? = null,
) {
    private val http = jsonClient(auth)
    private val progress = ComfyProgress(settings, auth)

    // r41 测试连接专用探测客户端：更短的超时、不重试；HTTP 与 WS 探测共用
    private val diagClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // r42：原 catalogCache / models() 已删——没有读者（UI 选项走 modelChoices/comboChoices，
    // 本地持久缓存见 data/comfy/ChoiceCache.kt）。

    /** /queue 短缓存（round13 第 1 项）：多任务并发轮询在同一秒内共享一份队列快照。
     *  每个任务的 poll 每 1.5s 都要拉 /queue + n 个 /history，N 个任务并发时 /queue 请求量翻 N 倍，
     *  外网高延迟下会把 Caddy/ComfyUI 拖慢、轮询被节流到异常退避，卡片状态长时间停在旧值。
     *  r22：POST /prompt、删队列、interrupt 会作废这份缓存。不作废的话，刚入队的 prompt
     *  既不在旧快照里、history 也还没有，会被立刻判成「任务丢失」。 */
    private val queueSnaps = QueueSnapshotCache(QUEUE_CACHE_MS)

    /** prompt_id → 连续没在队列/历史里出现。宽限期过了才算丢失。 */
    private val promptMisses = java.util.concurrent.ConcurrentHashMap<String, PromptMiss>()

    private suspend fun queueCached(): JSONObject {
        val now = System.currentTimeMillis()
        queueSnaps.getIfFresh(now)?.let { return it }
        val seen = queueSnaps.currentEpoch()
        val q = get("/queue")
        queueSnaps.store(seen, System.currentTimeMillis(), q)
        return q
    }

    private fun seenPrompt(pid: String) {
        if (pid.isNotBlank()) promptMisses.remove(pid)
    }

    private suspend fun get(path: String, attempts: Int = 4, base: String? = null): JSONObject {
        val b = base ?: settings.baseUrl()
        return http.callJson({ Request.Builder().url(b + path).get().build() }, idempotent = true, attempts = attempts)
    }

    private suspend fun post(path: String, body: JSONObject, idempotent: Boolean): JSONObject {
        val base = settings.baseUrl()
        // 改队列的请求：发出前先作废，回来再作废一次。
        // 中间若有人正在拉 /queue，那份旧响应按代次对不上，不会写回缓存。
        val touchesQueue = path == "/prompt" || path == "/interrupt" || path == "/queue"
        if (touchesQueue) queueSnaps.invalidate()
        val resp = http.callJson({
            Request.Builder().url(base + path).post(jsonBody(body)).build()
        }, idempotent)
        if (touchesQueue) queueSnaps.invalidate()
        return resp
    }

    /**
     * r41：分步探测，测试连接用（r45 起探测入口只此一处）。
     * ①地址格式 → ②GET /system_stats（带 Basic Auth，同 ping）→ ③isComfyStats 内容校验 →
     * ④仅当②③都过，再做 WS 握手探测（6 秒内 onOpen 即算通，立刻 close）。
     * WS 失败不阻止保存——出图只靠 HTTP，缺的只是步数进度。
     */
    suspend fun diagnose(base: String, user: String = "", pass: String = ""): ConnectionReport {
        val b = trimBase(base)
        if (b.isBlank()) {
            return ConnectionReport(
                httpOk = false, version = "", latencyMs = 0,
                error = ConnError(ConnErrorKind.BAD_URL, null, "unparseable base url: " + base.take(120)),
                wsError = null, detail = "",
            )
        }
        val sdk = Build.VERSION.SDK_INT
        val granted = isLocalNetworkGranted(ctx)
        val hostIsLocal = urlTargetsLocalNetwork(b)
        val auth = if (user.isNotBlank()) Credentials.basic(user, pass) else null
        val t = System.nanoTime()
        val (code, text) = try {
            diagClient.callBodyText {
                val rb = Request.Builder().url(b + "/system_stats").get()
                if (auth != null) rb.header("Authorization", auth)
                rb.build()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t) / 1_000_000
            return ConnectionReport(
                httpOk = false, version = "", latencyMs = ms,
                error = ConnError(
                    classifyConnectionError(ConnStage.HTTP, e, null, sdk, granted, hostIsLocal),
                    null,
                    techSummary(e),
                ),
                wsError = null, detail = "",
            )
        }
        val ms = (System.nanoTime() - t) / 1_000_000
        if (code !in 200..299 || !isComfyStats(code, text)) {
            return ConnectionReport(
                httpOk = false, version = "", latencyMs = ms,
                error = ConnError(
                    classifyConnectionError(ConnStage.CONTENT, null, code, sdk, granted, hostIsLocal),
                    code,
                    "HTTP $code, body head: " + text.take(160),
                ),
                wsError = null, detail = "",
            )
        }
        val version = try {
            JSONObject(text).optJSONObject("system")?.optString("comfyui_version").orEmpty()
        } catch (_: Exception) {
            ""
        }
        val wsError = probeWebSocket(b, auth, sdk, granted, hostIsLocal)
        return ConnectionReport(
            httpOk = true,
            version = if (version.isBlank()) "ComfyUI" else "ComfyUI $version",
            latencyMs = ms,
            error = null,
            wsError = wsError,
            detail = "",
        )
    }

    /**
     * WS 握手探测：不用真实 comfyClientId（探测连接绝不能顶掉正在跑任务的进度连接），
     * 用一次性 kwb-probe-<uuid>。6 秒内 onOpen 即算通并立刻 close(1000)。
     * r41.1：结局用 WsHandshakeOutcome（Opened/Failed）区分，只有等不到结局（超时）才落到 null——
     * 之前成功也 resume(null)，和超时的 null 混在一起，连上了也被当成超时报 WS 失败。
     */
    private suspend fun probeWebSocket(base: String, auth: String?, sdk: Int, granted: Boolean, hostIsLocal: Boolean): ConnError? {
        val httpish = base.toHttpUrlOrNull()
            ?: return ConnError(ConnErrorKind.WS_FAILED, null, "unparseable base for ws probe")
        // OkHttp 的 HttpUrl 不认 ws/wss，先用 http(s) 建请求，newWebSocket 自己升级协议
        val scheme = if (httpish.isHttps) "https" else "http"
        val probeUrl = "$scheme://${httpish.host}:${httpish.port}/ws?clientId=kwb-probe-${UUID.randomUUID()}"
        val rb = Request.Builder().url(probeUrl)
        if (auth != null) rb.header("Authorization", auth)
        val outcome = withTimeoutOrNull(6_000L) {
            suspendCancellableCoroutine<WsHandshakeOutcome> { cont ->
                val ws = diagClient.newWebSocket(rb.build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1000, "probe done")
                        if (cont.isActive) cont.resume(WsHandshakeOutcome.Opened)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) cont.resume(WsHandshakeOutcome.Opened)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resume(WsHandshakeOutcome.Failed(t, response?.code))
                    }
                })
                cont.invokeOnCancellation { runCatching { ws.cancel() } }
            }
        }
        return wsProbeError(outcome, sdk, granted, hostIsLocal)
    }

    /**
     * 取 <Class>.input.required.<field>[0] 的字符串数组。
     * 404（本机没有这个节点）返回空列表属正常；其他失败（401/5xx/超时/断网）一律上抛，
     * 绝不静默变成空列表——调用方要么保留旧值要么提示，界面不再退化成只读文本。
     */
    private suspend fun objectInfoList(cls: String, field: String): List<String> {
        val seg = URLEncoder.encode(cls, "UTF-8").replace("+", "%20")
        val json = try {
            get("/object_info/$seg")
        } catch (e: ApiException) {
            if (e.code == 404) return emptyList()
            throw e
        }
        val declared = json.optJSONObject(cls)
            ?.optJSONObject("input")
            ?.optJSONObject("required")
            ?.optJSONArray(field)
            ?: return emptyList()
        // r44：combo 声明兼容两种格式——老 [[...]] 与新 ["COMBO",{"options":[...]}]
        return when (val first = declared.opt(0)) {
            is JSONArray -> first.strings()
            is String ->
                if (first == "COMBO") {
                    declared.optJSONObject(1)?.optJSONArray("options")?.strings().orEmpty()
                } else {
                    emptyList()
                }
            else -> emptyList()
        }
    }

    /** 导入工作流的 model spec 下拉选项：/object_info/<该节点 class_type> 的同名字段列表（只读 GET）。 */
    suspend fun modelChoices(classType: String, field: String): List<String> = objectInfoList(classType, field)

    /** kwb_combo select 行的选项（/object_info/<class_type> 的字段枚举，只读 GET，在线实时）。 */
    suspend fun comboChoices(classType: String, field: String): List<String> = objectInfoList(classType, field)

    /**
     * 导入用的单节点查询：GET /object_info/{class_type}，只试一次。
     * 不再拉全量 /object_info（那份数据在弱网上会把导入卡住，而且 combo 升级只用得到个别 class）。
     * failed=true 是网络失败，调用方应静默跳过且不要重试；failed=false 且 node=null 才是本机没有这个节点。
     */
    suspend fun objectInfoClass(classType: String): NodeLookup {
        if (classType.isBlank()) return NodeLookup(node = null, failed = false)
        val seg = URLEncoder.encode(classType, "UTF-8").replace("+", "%20")
        return try {
            val json = get("/object_info/$seg", attempts = 1)
            NodeLookup(node = json.optJSONObject(classType), failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NodeLookup(node = null, failed = true)
        }
    }

    suspend fun submitJob(body: JSONObject): RemoteJob {
        progress.ensure()
        // manual_retry 只在引擎收到「用户手动重试」时注入，这里立刻取走，避免它被 recordPrompts 持久化，
        // 之后自动 reconcile 再进来时误当手动放行
        val manualRetry = body.optBoolean("manual_retry")
        body.remove("manual_retry")
        val clientId = body.optString("client_job_id")
        val mode = body.optString("mode", "t2i")
        val flows = buildCustomWorkflows(ctx, body)
        val comfyClientId = settings.comfyClientId()
        // 断点续交：读已提交过的 index，沿用其记录（prompt_id + seed），只补缺的那几张，
        // 避免 GenerationEngine 网络错误重试或 App 被杀重进后重复往 ComfyUI 塞 prompt
        val existing = existingPrompts(clientId)
        val prompts = JSONArray()
        for (i in 0 until flows.size) {
            existing[i + 1]?.let { prompts.put(it) }
        }
        // 幂等补洞：submit_attempted 标记说明此任务之前提交过、记录里却还缺 index——
        // 先按 extra_data 从 /queue + /history 找回 prompt_id。找回不全且不是用户手动重试时，
        // 绝不自动补交（POST 可能其实已到达、只是响应丢失/服务器重启丢了 /history，
        // 补交会重复出图）：抛非网络错误让任务标记失败，等用户手动点重试（manual_retry 放行）。
        val recovered = if (submitAttempted(clientId)) {
            val missing = (1..flows.size).filter { !existing.containsKey(it) }
            if (missing.isNotEmpty()) {
                val found = recoverPrompts(clientId, missing, flows, body)
                val stillMissing = missing.filter { it !in found }
                if (stillMissing.isNotEmpty() && !manualRetry) {
                    throw ApiException(ctx.str(R.string.err_recover_stopped), network = false)
                }
                found
            } else {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        for ((index, rec) in recovered) prompts.put(rec)
        try {
            var flagged = false
            for ((i, flow) in flows.withIndex()) {
                val index = i + 1
                if (existing.containsKey(index) || recovered.containsKey(index)) continue
                val seed = flow.seed
                val wf = flow.wf
                if (!flagged) {
                    // 第一次 POST /prompt 之前先把「已尝试提交」标记落库（App 被杀内存会丢，必须持久化），
                    // 之后任何一次续交都据此先找回再补交
                    markSubmitAttempted(body, clientId)
                    flagged = true
                }
                val payload = JSONObject()
                    .put("prompt", wf)
                    .put("client_id", comfyClientId)
                    .put(
                        "extra_data",
                        JSONObject()
                            .put("kwb_job", clientId)
                            .put("kwb_index", index),
                    )
                val resp = post("/prompt", payload, idempotent = false)
                val pid = resp.optString("prompt_id")
                if (pid.isBlank()) throw ApiException(ctx.str(R.string.err_submit_no_pid))
                prompts.put(
                    JSONObject()
                        .put("prompt_id", pid)
                        .put("seed", seed)
                        .put("index", index)
                        .put("width", flow.width)
                        .put("height", flow.height),
                )
                // 每成功一张立刻持久化，进程被杀也能从记录续交
                recordPrompts(body, clientId, prompts)
            }
        } catch (e: ApiException) {
            if (!e.network && prompts.length() > 0) {
                // 400 等确定性失败：撤掉已入队的 prompt（跑着的 interrupt），别留孤儿任务
                discardPrompts(prompts)
                recordPrompts(body, clientId, JSONArray())
            }
            throw e
        }
        recordPrompts(body, clientId, prompts)
        val params = JSONObject(body.toString())
        params.remove("comfy_prompts")
        params.remove("submit_attempted")
        params.remove("manual_retry")
        return RemoteJob(
            jobId = "cq_$clientId",
            clientJobId = clientId,
            mode = mode,
            state = "queued",
            prompt = body.optString("prompt"),
            params = params,
            total = flows.size,
            done = 0,
            queuePosition = null,
            progressValue = 0,
            progressMax = 0,
            imageIndex = 0,
            images = emptyList(),
            error = null,
        )
    }

    private suspend fun recordPrompts(body: JSONObject, clientId: String, prompts: JSONArray) {
        val full = JSONObject(body.toString())
        full.put("comfy_prompts", prompts)
        db.updateJob(clientId) { it.copy(requestJson = full.toString()) }
    }

    suspend fun getJob(jobId: String): RemoteJob {
        progress.ensure()
        val clientId = jobId.removePrefix("cq_")
        val row = db.job(clientId) ?: throw ApiException(ctx.str(R.string.err_job_missing), network = false)
        val body = try {
            JSONObject(row.requestJson)
        } catch (_: Exception) {
            JSONObject()
        }
        val prompts = body.optJSONArray("comfy_prompts")
        val n = prompts?.length() ?: 0
        if (n == 0) throw ApiException(ctx.str(R.string.err_job_no_submit), network = false)

        val q = queueCached()
        val runningIds = HashSet<String>()
        val runningArr = q.optJSONArray("queue_running") ?: JSONArray()
        for (i in 0 until runningArr.length()) {
            val pid = runningArr.optJSONArray(i)?.optString(1).orEmpty()
            if (pid.isNotBlank()) runningIds.add(pid)
        }
        // pending 按执行顺序排列，下标 = 前面排队的个数
        val pendingIdx = HashMap<String, Int>()
        val pendingArr = q.optJSONArray("queue_pending") ?: JSONArray()
        for (i in 0 until pendingArr.length()) {
            val pid = pendingArr.optJSONArray(i)?.optString(1).orEmpty()
            if (pid.isNotBlank()) pendingIdx[pid] = i
        }

        // 正在跑的那张：用它在 comfy_prompts 里的序号填 imageIndex，步数从 ws 记录里取（没有就 0，UI 自动退回）
        var runningPid: String? = null
        var runningIndex = 0
        for (i in 0 until n) {
            val o = prompts?.optJSONObject(i) ?: continue
            val pid = o.optString("prompt_id")
            if (pid in runningIds) {
                val idx = o.optInt("index", i + 1)
                if (runningIndex == 0 || idx < runningIndex) {
                    runningIndex = idx
                    runningPid = pid
                }
            }
        }
        val step = runningPid?.let { progress.stepsFor(it) }

        val width = body.optInt("width")
        val height = body.optInt("height")
        // custom 任务从 body 指定的出图节点读结果；内置缺省还是 SAVE_NODE
        val outputNode = body.optString("output_node").ifBlank { SAVE_NODE }
        var doneCount = 0
        var failCount = 0
        var runningCount = 0
        var minPending: Int? = null
        var firstError: String? = null
        // r42：失败详情（WS 缓存优先、history 的 execution_error 兜底；按 prompt 去重只取第一条）
        var errorDetail: String? = null
        val images = ArrayList<RemoteImage>(n)
        val videos = ArrayList<RemoteImage>()
        val texts = ArrayList<RemoteText>()
        val isCustom = body.optString("mode") == "custom"
        // custom 任务按节点分阶段（采样 1/2 / 合成视频）；null = 拿不到就退回普通步数显示
        val stageLabels: Map<String, String>? = if (isCustom) stageLabelsFor(body) else null

        // history 并行预取（round13 第 1 项）：外网 RTT 高时串行 n 个请求会把单次 getJob 拖到
        // n×RTT 以上，状态刷新随之变慢；并行后降到一次 RTT。任一网络失败整体上抛（同旧行为）。
        val hists = coroutineScope {
            (0 until n).map { i ->
                async {
                    val pid = prompts?.optJSONObject(i)?.optString("prompt_id").orEmpty()
                    try {
                        get("/history/$pid")
                    } catch (e: ApiException) {
                        if (e.network) throw e
                        JSONObject()
                    }
                }
            }.map { it.await() }
        }

        for (i in 0 until n) {
            val o = prompts?.optJSONObject(i) ?: continue
            val pid = o.optString("prompt_id")
            val seed = o.optLong("seed")
            val index = o.optInt("index", i + 1)
            val h = hists[i].optJSONObject(pid)
            if (h == null) {
                when {
                    pid in runningIds -> {
                        seenPrompt(pid)
                        runningCount++
                    }
                    pendingIdx.containsKey(pid) -> {
                        seenPrompt(pid)
                        val p = pendingIdx[pid]!!
                        if (minPending == null || p < minPending) minPending = p
                    }
                    else -> {
                        // 队列快照可能是旧的。WebSocket 已经有步数就仍当它在跑，别把进度盖成丢失。
                        val known = progress.stepsFor(pid)
                        if (known != null && (known.first > 0 || known.second > 0)) {
                            seenPrompt(pid)
                            runningCount++
                            if (runningPid == null) {
                                runningPid = pid
                                runningIndex = index
                            }
                        } else {
                            val nowMs = System.currentTimeMillis()
                            if (promptMisses.size > 100) {
                                val cutoff = nowMs - 60_000L
                                promptMisses.entries.removeIf { it.value.firstAt < cutoff }
                            }
                            val miss = promptMisses.compute(pid) { _, old ->
                                if (old == null) PromptMiss(nowMs, 1) else old.copy(count = old.count + 1)
                            } ?: PromptMiss(nowMs, 1)
                            if (PromptLostPolicy.isLost(miss.count, miss.firstAt, nowMs)) {
                                promptMisses.remove(pid)
                                failCount++
                                if (firstError == null) firstError = ctx.str(R.string.err_job_lost)
                            }
                            // 宽限内：不当失败，轮询继续。state 落在下面的 queued/running。
                        }
                    }
                }
                continue
            }
            seenPrompt(pid)
            val status = h.optJSONObject("status") ?: JSONObject()
            when {
                status.optString("status_str") == "error" -> {
                    failCount++
                    if (firstError == null) {
                        firstError = executionMessage(status.optJSONArray("messages"))
                            ?: ctx.str(R.string.err_image_failed, index)
                    }
                    // r42：WS 的 execution_error 可能先于/晚于 history 到达，这里 WS 优先、history 补缺
                    if (errorDetail == null) {
                        val e = progress.errorOf(pid)?.let { runCatching { formatExecError(it) }.getOrNull() }
                        errorDetail = e ?: executionErrorDetail(status.optJSONArray("messages"), pid)
                    }
                }
                status.optBoolean("completed") && status.optString("status_str") == "success" -> {
                    doneCount++
                    val outputs = h.optJSONObject("outputs") ?: JSONObject()
                    if (isCustom) {
                        // round6：custom 不只信指定输出节点，遍历所有输出节点收集
                        // images/gifs/videos（视频在 gifs）与 text/string，图文视频混合全收。
                        // r44：有 SaveImage 类产物时丢弃 PreviewImage 等 temp 预览；
                        // 定义里指定了出图节点（kwb_output_node → body.output_node）的排最前。
                        val (entries, outTexts) = parseOutputEntries(
                            outputs,
                            body.optString("output_node").ifBlank { null },
                        )
                        for (t in outTexts) texts.add(RemoteText(index = index, text = t))
                        for ((j, e) in entries.withIndex()) {
                            if (e.video) {
                                videos.add(
                                    RemoteImage(
                                        index = index,
                                        seed = seed,
                                        filename = e.filename,
                                        subfolder = e.subfolder,
                                        type = e.type,
                                        width = 0,
                                        height = 0,
                                        size = 0,
                                        url = imageUrl(e.filename, e.subfolder, e.type),
                                    ),
                                )
                            } else {
                                images.add(
                                    RemoteImage(
                                        index = index,
                                        seed = seed,
                                        filename = e.filename,
                                        subfolder = e.subfolder,
                                        type = e.type,
                                        width = 0,
                                        height = 0,
                                        size = 0,
                                        url = imageUrl(e.filename, e.subfolder, e.type),
                                    ),
                                )
                            }
                            if (j > 64) break // 同一 prompt 异常多产物时兜底，防列表爆掉
                        }
                    } else {
                        // 内置任务保持旧行为：先读指定出图节点；该节点没有图时退回 outputs 里
                        // 第一个带 images 的节点。type 照原样透传（PreviewImage 会是 temp）。
                        var im = outputs.optJSONObject(outputNode)?.optJSONArray("images")?.optJSONObject(0)
                        if (im == null) {
                            val keys = outputs.keys()
                            while (keys.hasNext() && im == null) {
                                val anyNode = outputs.optJSONObject(keys.next()) ?: continue
                                im = anyNode.optJSONArray("images")?.optJSONObject(0)
                            }
                        }
                        if (im != null) {
                            val filename = im.optString("filename")
                            val subfolder = im.optString("subfolder")
                            val type = im.optString("type", "output")
                            // 每张图自己的宽高（custom 按尺寸×批次记录在 comfy_prompts 里）；
                            // 旧记录/内置任务没有这两个键时退回 body 的 width/height
                            val recW = o.optInt("width", 0)
                            val recH = o.optInt("height", 0)
                            images.add(
                                RemoteImage(
                                    index = index,
                                    seed = seed,
                                    filename = filename,
                                    subfolder = subfolder,
                                    type = type,
                                    width = if (recW > 0) recW else width,
                                    height = if (recH > 0) recH else height,
                                    size = 0,
                                    url = imageUrl(filename, subfolder, type),
                                ),
                            )
                        }
                    }
                }
                else -> runningCount++
            }
        }

        // 阶段文案：正在跑的 prompt 对应 executing 节点 → 「采样 1/2」「合成视频」
        val stage = runningPid?.let { sid -> stageLabels?.get(progress.currentNodeFor(sid)) } ?: ""

        val state = when {
            doneCount + failCount == n && failCount == 0 -> "success"
            doneCount + failCount == n && doneCount == 0 -> "failed"
            doneCount + failCount == n -> "partial"
            runningCount > 0 -> "running"
            else -> "queued"
        }
        val params = JSONObject(body.toString())
        params.remove("comfy_prompts")
        params.remove("submit_attempted")
        return RemoteJob(
            jobId = jobId,
            clientJobId = clientId,
            mode = body.optString("mode", "t2i"),
            state = state,
            prompt = body.optString("prompt"),
            params = params,
            total = n,
            done = doneCount,
            queuePosition = minPending,
            progressValue = step?.first ?: 0,
            progressMax = step?.second ?: 0,
            imageIndex = runningIndex,
            images = images,
            videos = videos,
            texts = texts,
            stage = stage,
            error = firstError,
            errorDetail = errorDetail,
        )
    }

    /** 阶段映射表：节点 id → 「采样 k/n」/「合成视频」。读不到工作流返回 null。 */
    private suspend fun stageLabelsFor(body: JSONObject): Map<String, String>? {
        val wfId = body.optString("workflow_id")
        if (wfId.isBlank()) return null
        val wf = try {
            WorkflowStore(ctx).get(wfId)?.first ?: return null
        } catch (_: Exception) {
            return null
        }
        val samplers = ArrayList<String>()
        val videoNodes = ArrayList<String>()
        val keys = wf.keys()
        while (keys.hasNext()) {
            val nid = keys.next()
            val node = wf.optJSONObject(nid) ?: continue
            when (baseClass(node.optString("class_type"))) {
                "KSampler", "KSamplerAdvanced" -> samplers.add(nid)
                "VHS_VideoCombine", "SaveVideo", "SaveWEBM" -> videoNodes.add(nid)
            }
        }
        samplers.sortBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val map = HashMap<String, String>()
        samplers.forEachIndexed { i, nid -> map[nid] = "采样 ${i + 1}/${samplers.size}" }
        videoNodes.forEach { nid -> map[nid] = "合成视频" }
        return map
    }

    /** 读任务记录里已提交过的 comfy_prompts，按 index（1..N）索引。 */
    private fun existingPrompts(clientId: String): Map<Int, JSONObject> {
        val row = db.job(clientId) ?: return emptyMap()
        val arr = try {
            JSONObject(row.requestJson).optJSONArray("comfy_prompts")
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val map = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val index = o.optInt("index", 0)
            if (index > 0 && o.optString("prompt_id").isNotBlank()) map[index] = o
        }
        return map
    }

    /** 此任务是否已发起过 POST /prompt（标记持久化在 requestJson，App 被杀不丢）。 */
    private fun submitAttempted(clientId: String): Boolean {
        val row = db.job(clientId) ?: return false
        return try {
            JSONObject(row.requestJson).optBoolean("submit_attempted", false)
        } catch (_: Exception) {
            false
        }
    }

    /** 第一次 POST /prompt 之前把「已尝试提交」标记写进 body 并落库，后续 recordPrompts 会带着它持久化。 */
    private suspend fun markSubmitAttempted(body: JSONObject, clientId: String) {
        body.put("submit_attempted", true)
        db.updateJob(clientId) { row ->
            val json = try {
                JSONObject(row.requestJson)
            } catch (_: Exception) {
                JSONObject()
            }
            json.put("submit_attempted", true)
            row.copy(requestJson = json.toString())
        }
    }

    /**
     * 按 extra_data（kwb_job + kwb_index）从 /queue（running+pending）和 /history?max_items=64
     * 找回「已到达 ComfyUI 但 prompt_id 没记录下来」的提交。返回 index → 提交记录。
     * 种子优先取工作流图里的真实值，取不到用本次计算的兜底；
     * 宽高不用图里的值，直接按本次 flows（选中尺寸 × batch_count 展开顺序）算。
     */
    private suspend fun recoverPrompts(
        clientId: String,
        missing: List<Int>,
        flows: List<BuiltFlow>,
        body: JSONObject,
    ): Map<Int, JSONObject> {
        val found = HashMap<Int, JSONObject>()
        try {
            val q = get("/queue")
            // /queue 条目 = [number, prompt_id, 工作流图, extra_data, outputs_to_execute]
            for (key in listOf("queue_running", "queue_pending")) {
                val arr = q.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONArray(i) ?: continue
                    val pid = entry.optString(1)
                    val extra = entry.optJSONObject(3)
                    val index = extra?.optInt("kwb_index", 0) ?: 0
                    if (pid.isNotBlank() && extra?.optString("kwb_job") == clientId && index in missing) {
                        found[index] = recoveredRecord(entry.optJSONObject(2), pid, index, flows, body)
                    }
                }
            }
            val hist = get("/history?max_items=64")
            val keys = hist.keys()
            while (keys.hasNext()) {
                val pid = keys.next()
                val entry = hist.optJSONObject(pid)?.optJSONArray("prompt") ?: continue
                val extra = entry.optJSONObject(3)
                val index = extra?.optInt("kwb_index", 0) ?: 0
                if (pid.isNotBlank() && extra?.optString("kwb_job") == clientId &&
                    index in missing && !found.containsKey(index)
                ) {
                    found[index] = recoveredRecord(entry.optJSONObject(2), pid, index, flows, body)
                }
            }
        } catch (e: ApiException) {
            if (e.network) throw e
            // 响应形状异常就当没找到；是否允许补交由调用方的 manual_retry 守卫决定
        }
        return found
    }

    private suspend fun recoveredRecord(
        graph: JSONObject?,
        pid: String,
        index: Int,
        flows: List<BuiltFlow>,
        body: JSONObject,
    ): JSONObject {
        val flow = flows.getOrNull(index - 1)
        val fallback = flow?.seed ?: 0L
        val seed = seedFromGraph(graph, body) ?: fallback
        return JSONObject()
            .put("prompt_id", pid)
            .put("seed", seed)
            .put("index", index)
            .put("width", flow?.width ?: 0)
            .put("height", flow?.height ?: 0)
    }

    /** custom：按定义里第一个 int_random 的 node/field 从图里取种子；取不到返回 null 走 flows 兜底。 */
    private suspend fun seedFromGraph(graph: JSONObject?, body: JSONObject): Long? {
        if (graph == null || body.optString("mode") != "custom") return null
        val def = try {
            WorkflowStore(ctx).definition(body.optString("workflow_id"))
        } catch (_: ApiException) {
            null
        } ?: return null
        val specs = def.optJSONArray("user_facing_inputs") ?: return null
        for (i in 0 until specs.length()) {
            val spec = specs.optJSONObject(i) ?: continue
            if (spec.optString("type") != "int_random") continue
            val nid = spec.optString("node_id")
            val field = spec.optString("field")
            val seed = graph.optJSONObject(nid)?.optJSONObject("inputs")?.optLong(field, 0L) ?: 0L
            return if (seed > 0) seed else null
        }
        return null
    }

    /** 从 ComfyUI 队列撤掉这些 prompt：排队的删除，还在跑的是我们的就 interrupt。失败静默（清理不能盖过原始错误）。 */
    private suspend fun discardPrompts(prompts: JSONArray) {
        val ids = ArrayList<String>(prompts.length())
        for (i in 0 until prompts.length()) {
            val pid = prompts.optJSONObject(i)?.optString("prompt_id").orEmpty()
            if (pid.isNotBlank()) ids.add(pid)
        }
        discardIds(ids)
    }

    private suspend fun discardIds(ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            post("/queue", JSONObject().put("delete", JSONArray(ids)), idempotent = true)
            val q = get("/queue")
            val runningArr = q.optJSONArray("queue_running") ?: JSONArray()
            for (i in 0 until runningArr.length()) {
                val pid = runningArr.optJSONArray(i)?.optString(1)
                if (pid in ids) {
                    // 新版 ComfyUI 可只中断指定任务（带 prompt_id）；带不上就退回 {} 中断当前
                    val body = if (pid.isNullOrBlank()) JSONObject() else JSONObject().put("prompt_id", pid)
                    post("/interrupt", body, idempotent = false)
                    break
                }
            }
        } catch (_: ApiException) {
        }
    }

    /** r44 强制结束的服务器清空结果：reachable=false 表示连不上（本地仍会重置）。 */
    data class ServerQueueClear(val reachable: Boolean, val runningLeft: Int, val pendingLeft: Int)

    /** r44 冷启动对账的只读探测结果：该任务已提交的 prompt 是否还有任何一个在队列 / 历史里。 */
    data class ServerProbe(val reachable: Boolean, val inQueue: Boolean, val inHistory: Boolean)

    /**
     * 只读探测（对账专用，不改任何状态）：一次 GET /queue + 逐个 GET /history/{pid}。
     * 在队列里就不再查历史（KEEP 的判定用不到）；返回 null = 服务器不可达，调用方一律不改本地状态。
     */
    suspend fun probeServer(clientId: String): ServerProbe? {
        val row = db.job(clientId) ?: return null
        val pids = ArrayList<String>()
        try {
            val arr = JSONObject(row.requestJson).optJSONArray("comfy_prompts") ?: return null
            for (i in 0 until arr.length()) {
                val pid = arr.optJSONObject(i)?.optString("prompt_id").orEmpty()
                if (pid.isNotBlank()) pids.add(pid)
            }
        } catch (_: Exception) {
            return null
        }
        if (pids.isEmpty()) return null
        return try {
            val q = get("/queue", attempts = 1)
            val inQueue = queueContains(q, pids)
            var inHistory = false
            if (!inQueue) {
                for (pid in pids) {
                    if (get("/history/$pid", attempts = 1).has(pid)) {
                        inHistory = true
                        break
                    }
                }
            }
            ServerProbe(reachable = true, inQueue = inQueue, inHistory = inHistory)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun queueContains(q: JSONObject, pids: List<String>): Boolean {
        val want = pids.toHashSet()
        for (key in listOf("queue_running", "queue_pending")) {
            val arr = q.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val pid = arr.optJSONArray(i)?.optString(1).orEmpty()
                if (pid in want) return true
            }
        }
        return false
    }

    /**
     * r44：把当前配置的这台服务器的队列**整个**清掉——先 POST /queue {"clear":true} 清 pending，
     * 再 POST /interrupt 中断正在跑的（顺序不能反，反过来 interrupt 后下一条 pending 会立刻顶上来开跑），
     * 最后 GET /queue 确认还剩几条（给 Comfy 500ms 反应时间，最多复查一次）。
     *
     * 不按 prompt_id 过滤：这是「把这台 Comfy 的队列全清掉」，包括电脑网页端用户提交的任务
     * （调用方的弹窗必须已写明）。任何失败都不抛，返回 reachable=false 交给调用方提示。
     */
    suspend fun clearServerQueue(): ServerQueueClear {
        val unreachable = ServerQueueClear(reachable = false, runningLeft = 0, pendingLeft = 0)
        return try {
            withTimeoutOrNull(SERVER_CLEAR_BUDGET_MS) {
                post("/queue", JSONObject().put("clear", true), idempotent = false)
                post("/interrupt", JSONObject(), idempotent = false)
                delay(500)
                var left = queueLeftCounts(get("/queue", attempts = 1))
                if (left.first > 0 || left.second > 0) {
                    delay(700)
                    left = queueLeftCounts(get("/queue", attempts = 1))
                }
                ServerQueueClear(reachable = true, runningLeft = left.first, pendingLeft = left.second)
            } ?: unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unreachable
        }
    }

    private fun queueLeftCounts(q: JSONObject): Pair<Int, Int> =
        (q.optJSONArray("queue_running")?.length() ?: 0) to (q.optJSONArray("queue_pending")?.length() ?: 0)

    /** r44：强制结束后清掉「任务丢失」计数与 /queue 缓存，幽灵排队不再靠轮询自己恢复。 */
    fun resetLocalTracking() {
        promptMisses.clear()
        queueSnaps.invalidate()
    }

    suspend fun cancelJob(jobId: String) {
        val clientId = jobId.removePrefix("cq_")
        val ids = ArrayList<String>()
        db.job(clientId)?.let { row ->
            val prompts = try {
                JSONObject(row.requestJson).optJSONArray("comfy_prompts")
            } catch (_: Exception) {
                null
            }
            if (prompts != null) {
                for (i in 0 until prompts.length()) {
                    val pid = prompts.optJSONObject(i)?.optString("prompt_id").orEmpty()
                    if (pid.isNotBlank() && pid !in ids) ids.add(pid)
                }
            }
        }
        // 兜底：取消瞬间 POST 可能刚到达 ComfyUI、prompt_id 还没写进本地记录——
        // 按 extra_data.kwb_job 把队列里属于本任务的 prompt 一并撤掉（去重）。
        // 出网络错误静默跳过，不能影响取消本身。
        try {
            val q = get("/queue")
            for (key in listOf("queue_running", "queue_pending")) {
                val arr = q.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONArray(i) ?: continue
                    val pid = entry.optString(1)
                    if (pid.isNotBlank() && pid !in ids &&
                        entry.optJSONObject(3)?.optString("kwb_job") == clientId
                    ) {
                        ids.add(pid)
                    }
                }
            }
        } catch (_: ApiException) {
        }
        discardIds(ids)
    }

    private fun executionMessage(messages: JSONArray?): String? {
        if (messages == null) return null
        for (i in 0 until messages.length()) {
            val m = messages.optJSONArray(i) ?: continue
            if (m.optString(0) != "execution_error") continue
            val data = m.optJSONObject(1) ?: return null
            val nodeType = data.optString("node_type")
            val msg = data.optString("exception_message")
            return when {
                nodeType.isNotBlank() && msg.isNotBlank() -> ctx.str(R.string.err_node, nodeType, msg)
                msg.isNotBlank() -> msg
                nodeType.isNotBlank() -> ctx.str(R.string.err_node_only, nodeType)
                else -> null
            }
        }
        return null
    }

    /** r42：history status.messages 里的 execution_error 详情（持久、不依赖 WS 时序）；没有返回 null。
     *  消息里缺 prompt_id 时用 [promptId]（history 实际 pid）补。 */
    private fun executionErrorDetail(messages: JSONArray?, promptId: String): String? {
        if (messages == null) return null
        for (i in 0 until messages.length()) {
            val m = messages.optJSONArray(i) ?: continue
            if (m.optString(0) != "execution_error") continue
            val data = m.optJSONObject(1) ?: return null
            if (data.optString("prompt_id").isBlank() && promptId.isNotBlank()) {
                data.put("prompt_id", promptId)
            }
            val e = runCatching { parseExecutionError(data) }.getOrNull() ?: return null
            return runCatching { formatExecError(e) }.getOrNull()
        }
        return null
    }

    /**
     * r42：poll 发现失败后再取一次 WS 缓存的详情（WS 消息可能晚于 history 到达）。
     * 按 clientId 对应的 comfy_prompts 里的 prompt_id 找，不按「最近一条」；取不到返回 null，不阻塞。
     */
    suspend fun lateErrorDetail(jobId: String): String? {
        val clientId = jobId.removePrefix("cq_")
        val row = db.job(clientId) ?: return null
        val prompts = try {
            JSONObject(row.requestJson).optJSONArray("comfy_prompts")
        } catch (_: Exception) {
            null
        } ?: return null
        for (i in 0 until prompts.length()) {
            val pid = prompts.optJSONObject(i)?.optString("prompt_id") ?: continue
            val e = progress.errorOf(pid) ?: continue
            return runCatching { formatExecError(e) }.getOrNull()
        }
        return null
    }

    private companion object {
        const val SAVE_NODE = "16"

        /** /queue 共享缓存窗口：轮询周期 1.5s，窗口取 1s 保证每个周期至少刷新一次。 */
        const val QUEUE_CACHE_MS = 1000L

        /** r44 清空服务器队列的总时长上限：连不上时不能把「强制结束」一直挂着。 */
        const val SERVER_CLEAR_BUDGET_MS = 8_000L
    }
}
