package com.mie.kreaworkbench.data.comfy

import android.content.Context
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.BUILTIN_SIZES
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.DEFAULT_CLIP
import com.mie.kreaworkbench.data.DEFAULT_UNET
import com.mie.kreaworkbench.data.DEFAULT_VAE
import com.mie.kreaworkbench.data.FALLBACK_SAMPLERS
import com.mie.kreaworkbench.data.FALLBACK_SCHEDULERS
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.data.api.ModelCatalog
import com.mie.kreaworkbench.data.api.PingResult
import com.mie.kreaworkbench.data.api.RemoteImage
import com.mie.kreaworkbench.data.api.RemoteJob
import com.mie.kreaworkbench.data.api.RemoteText
import com.mie.kreaworkbench.data.api.baseUrl
import com.mie.kreaworkbench.data.api.callJson
import com.mie.kreaworkbench.data.api.imageUrl
import com.mie.kreaworkbench.data.api.jsonBody
import com.mie.kreaworkbench.data.api.jsonClient
import com.mie.kreaworkbench.data.api.parseOutputEntries
import com.mie.kreaworkbench.data.api.strings
import com.mie.kreaworkbench.data.api.trimBase
import com.mie.kreaworkbench.data.db.GalleryDb
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.data.workflows.WorkflowStore
import com.mie.kreaworkbench.data.workflows.baseClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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

    @Volatile private var catalogCache: ModelCatalog? = null

    /** /queue 短缓存（round13 第 1 项）：多任务并发轮询在同一秒内共享一份队列快照。
     *  每个任务的 poll 每 1.5s 都要拉 /queue + n 个 /history，N 个任务并发时 /queue 请求量翻 N 倍，
     *  外网高延迟下会把 Caddy/ComfyUI 拖慢、轮询被节流到异常退避，卡片状态长时间停在旧值。 */
    @Volatile private var queueCache: Pair<Long, JSONObject>? = null

    private suspend fun queueCached(): JSONObject {
        queueCache?.let { (at, q) ->
            if (System.currentTimeMillis() - at < QUEUE_CACHE_MS) return q
        }
        val q = get("/queue")
        queueCache = System.currentTimeMillis() to q
        return q
    }

    private suspend fun get(path: String, attempts: Int = 4, base: String? = null): JSONObject {
        val b = base ?: settings.baseUrl()
        return http.callJson({ Request.Builder().url(b + path).get().build() }, idempotent = true, attempts = attempts)
    }

    private suspend fun post(path: String, body: JSONObject, idempotent: Boolean): JSONObject {
        val base = settings.baseUrl()
        return http.callJson({
            Request.Builder().url(base + path).post(jsonBody(body)).build()
        }, idempotent)
    }

    suspend fun ping(base: String, user: String = "", pass: String = ""): PingResult {
        val b = trimBase(base)
        if (b.isBlank()) throw ApiException(ctx.str(R.string.ping_need_server))
        val t = System.nanoTime()
        val json = http.callJson({
            val rb = Request.Builder().url(b + "/system_stats").get()
            if (user.isNotBlank()) rb.header("Authorization", Credentials.basic(user, pass))
            rb.build()
        }, idempotent = true, attempts = 1)
        val ms = (System.nanoTime() - t) / 1_000_000
        val version = json.optJSONObject("system")?.optString("comfyui_version").orEmpty()
        return PingResult(
            workbenchVersion = if (version.isBlank()) "ComfyUI" else "ComfyUI $version",
            comfyOk = true,
            comfyUrl = b,
            latencyMs = ms,
        )
    }

    suspend fun models(refresh: Boolean): ModelCatalog {
        if (!refresh) catalogCache?.let { return it }
        val defaults = JSONObject()
            .put("unet", DEFAULT_UNET)
            .put("clip", DEFAULT_CLIP)
            .put("vae", DEFAULT_VAE)
            .put("steps", 8)
            .put("cfg", 1)
            .put("sampler", "euler")
            .put("scheduler", "simple")
            .put("shift", 3)
            .put("denoise", 0.55)
        // 拉取过程一旦失败（401/超时/断网）直接上抛：绝不把空列表写进 catalogCache，
        // 否则 KSampler 选项会以「空下拉」的形态被缓存住（round13 第 4 项的根因之一）。
        // 失败时保留旧缓存（refresh 重试失败也退回旧值），调用方自行提示。
        val cat = ModelCatalog(
            // 任务书写的 source="comfy"，但 ModelsScreen 只认 "object_info"/"filesystem"，
            // 沿用 UI 已有约定值以正确显示「来源：ComfyUI」
            source = "object_info",
            unet = objectInfoList("UNETLoader", "unet_name"),
            clip = objectInfoList("CLIPLoader", "clip_name"),
            vae = objectInfoList("VAELoader", "vae_name"),
            samplers = objectInfoList("KSampler", "sampler_name").ifEmpty { FALLBACK_SAMPLERS },
            schedulers = objectInfoList("KSampler", "scheduler").ifEmpty { FALLBACK_SCHEDULERS },
            defaults = defaults,
            sizePresets = BUILTIN_SIZES.map { Triple(it.label, it.width, it.height) },
        )
        catalogCache = cat
        return cat
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
        return json.optJSONObject(cls)
            ?.optJSONObject("input")
            ?.optJSONObject("required")
            ?.optJSONArray(field)
            ?.optJSONArray(0)
            ?.strings()
            ?: emptyList()
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
                    pid in runningIds -> runningCount++
                    pendingIdx.containsKey(pid) -> {
                        val p = pendingIdx[pid]!!
                        if (minPending == null || p < minPending) minPending = p
                    }
                    else -> {
                        failCount++
                        if (firstError == null) firstError = ctx.str(R.string.err_job_lost)
                    }
                }
                continue
            }
            val status = h.optJSONObject("status") ?: JSONObject()
            when {
                status.optString("status_str") == "error" -> {
                    failCount++
                    if (firstError == null) {
                        firstError = executionMessage(status.optJSONArray("messages"))
                            ?: ctx.str(R.string.err_image_failed, index)
                    }
                }
                status.optBoolean("completed") && status.optString("status_str") == "success" -> {
                    doneCount++
                    val outputs = h.optJSONObject("outputs") ?: JSONObject()
                    if (isCustom) {
                        // round6：custom 不只信指定输出节点，遍历所有输出节点收集
                        // images/gifs/videos（视频在 gifs）与 text/string，图文视频混合全收
                        val (entries, outTexts) = parseOutputEntries(outputs)
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

    /** ComfyUI 没有删文件接口：远端删除是空操作，只删手机本地。 */
    suspend fun deleteRemoteImage(filename: String, subfolder: String, type: String = "output") {
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

    private companion object {
        const val SAVE_NODE = "16"

        /** /queue 共享缓存窗口：轮询周期 1.5s，窗口取 1s 保证每个周期至少刷新一次。 */
        const val QUEUE_CACHE_MS = 1000L
    }
}
