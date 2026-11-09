package com.mie.kreaworkbench.data.comfy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mie.kreaworkbench.data.api.baseUrl
import com.mie.kreaworkbench.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ComfyUI /ws 进度监听：只服务于「步数 x/y」显示，任务状态一律仍以 /queue + /history 为准，ws 挂了不影响出图。
 * 文本帧维护 prompt_id → (value, max)；r50 起二进制帧在「即时预览」开关打开时解码成 Bitmap 按 prompt_id 缓存
 * （仅内存，绝不落盘/入图库），开关关时与旧行为一致直接丢弃。
 * 0.29.0 源码确认：progress/executing/execution_* 只发给「正在执行的 prompt 的提交 client_id」，
 * 所以这里的 clientId 必须和提交 /prompt 时用的 client_id 相同（都用 settings.comfyClientId()）。
 */
class ComfyProgress(private val settings: SettingsStore, auth: okhttp3.Interceptor? = null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply { if (auth != null) addInterceptor(auth) }
        .build()

    private val lock = Any()
    private val steps = HashMap<String, Pair<Int, Int>>()
    private val currentNodes = HashMap<String, String>()
    /** r42：execution_error 详情，按 prompt_id 独立缓存（不被下面的 clear 清掉），最多 20 条。 */
    private val errors = BoundedCache<String, ExecError>(ERROR_CACHE_MAX)
    /** r50：即时预览开关最新值；init 里的 settings.flow 收集器维护，关时顺手清缓存。 */
    private val previewOn = AtomicBoolean(false)
    /** r50：prompt_id → 最近一帧预览（每 prompt 最多 1 张，覆盖写）。仅内存，不 recycle
     *  （旧图可能正被 Compose 画着，recycle 会崩；换新引用让旧图交 GC）。 */
    private val previews = HashMap<String, Bitmap>()
    /** r50：二进制帧归属的 prompt——二进制帧不带 prompt_id，按「正在 executing 的 prompt」记账。 */
    @Volatile private var executingPid: String? = null

    init {
        // r50：观察即时预览开关。关 → 停解码 + 清已有帧；开 → 下一帧起解码（不补历史帧）。
        // 收集失败（磁盘读坏等罕见情况）就让预览停在上一个状态，不影响步数主功能。
        scope.launch {
            runCatching {
                settings.flow.collect { s ->
                    previewOn.set(s.livePreviewEnabled)
                    if (!s.livePreviewEnabled) clearPreviews()
                }
            }
        }
    }

    private val lastTouch = AtomicLong(0L)
    private val socketGone = AtomicBoolean(true)
    private val backoffIdx = AtomicInteger(0)
    // 连接代数：每创建一个 socket（或主动关旧 socket）自增。回调持有自己那一代的编号，
    // 比对代数而不是比对 socket 引用——新 socket 在 `socket = newWebSocket(...)` 赋值前
    // 就回调 onFailure 时，socket 还指向 null/旧值，按引用比对会把它误当旧回调忽略掉。
    private val generation = AtomicLong(0L)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var activeUrl: String? = null
    @Volatile private var monitor: Job? = null

    /** 懒连接入口：ComfyApi 每次 submitJob/getJob 调用；连接已在则只刷新活跃时间。 */
    fun ensure() {
        lastTouch.set(System.currentTimeMillis())
        synchronized(this) {
            if (monitor?.isActive != true) monitor = scope.launch { loop() }
        }
    }

    /** 某 prompt 当前的 (步数, 总步数)；没有数据返回 null（UI 退回不显示步数）。 */
    fun stepsFor(promptId: String): Pair<Int, Int>? = synchronized(lock) { steps[promptId] }

    /** 某 prompt 当前正在执行的节点 id（round6 阶段文案用）；没有数据返回 null。 */
    fun currentNodeFor(promptId: String): String? = synchronized(lock) { currentNodes[promptId] }

    /** r42：某 prompt 的 execution_error 详情（WS 收到过才有）；没有返回 null，不阻塞等待。 */
    fun errorOf(promptId: String): ExecError? = errors[promptId]

    /** r50：某 prompt 最近一帧预览；没有返回 null。仅内存，随任务终结被 clear 清掉。 */
    fun previewFor(promptId: String): Bitmap? = synchronized(lock) { previews[promptId] }

    /** r50：清掉全部预览帧（关开关用）。不 recycle：旧图可能正被 Compose 画着。 */
    fun clearPreviews() {
        synchronized(lock) { previews.clear() }
    }

    private suspend fun loop() {
        val backoff = longArrayOf(1000L, 3000L, 6000L, 10000L)
        var retryAt = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (now - lastTouch.get() > IDLE_MS) {
                closeSocket()
                activeUrl = null
                // r50：连接已闲置（没有进行中的任务），预览缓存一并清掉，不攒内存
                clearPreviews()
                return
            }
            val want = try {
                wsUrl()
            } catch (_: Exception) {
                null
            }
            if (want != activeUrl) {
                // 首次连接或服务器地址变了：断开旧连接，下一分支立即用新地址重连
                closeSocket()
                activeUrl = want
                backoffIdx.set(0)
                retryAt = 0L
            }
            if (want == null) {
                // 地址为空：退出监听，下次 getJob 再拉起
                return
            }
            if (socketGone.get()) {
                if (socket != null) {
                    // 刚发现连接死亡：按当前退避档安排下次尝试
                    socket = null
                    retryAt = now + backoff[backoffIdx.get().coerceAtMost(backoff.size - 1)]
                    backoffIdx.incrementAndGet()
                } else if (now >= retryAt) {
                    socketGone.set(false)
                    val gen = generation.incrementAndGet()
                    socket = client.newWebSocket(Request.Builder().url(want).build(), makeListener(gen))
                }
            }
            delay(1000L)
        }
    }

    private fun closeSocket() {
        socket?.let { ws ->
            // 让这个 socket 之后的任何回调都视为过期
            generation.incrementAndGet()
            try {
                ws.close(1000, "kwb")
            } catch (_: Exception) {
            }
        }
        socket = null
        socketGone.set(true)
    }

    private fun makeListener(gen: Long): WebSocketListener = object : WebSocketListener() {
        private fun stale(): Boolean = gen != generation.get()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            android.util.Log.i("KreaWs", "ws open gen=$gen")
            if (stale()) return
            backoffIdx.set(0)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!stale()) handleText(text)
        }

        // r50：二进制帧 = 采样过程预览图（服务端 --preview-method ≠ none 才有）。开关关时立即丢弃。
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!stale()) handleBinary(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 旧连接也要完成 close 握手；但状态只许当前连接改
            try {
                webSocket.close(1000, null)
            } catch (_: Exception) {
            }
            if (stale()) return
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stale()) return
            socketGone.set(true)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 连接失败留一条日志（401/断网排障用）；重连节奏由 loop 的退避表管
            android.util.Log.w("KreaWs", "ws fail gen=$gen code=${response?.code}", t)
            if (stale()) return
            socketGone.set(true)
        }
    }

    private fun handleText(text: String) {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val data = o.optJSONObject("data") ?: return
        when (o.optString("type")) {
            "progress" -> {
                val pid = data.optString("prompt_id")
                val value = data.optInt("value", -1)
                val max = data.optInt("max", -1)
                if (pid.isNotBlank() && value >= 0 && max > 0) {
                    synchronized(lock) { steps[pid] = value to max }
                }
                data.optString("node").takeIf { it.isNotBlank() }?.let { node ->
                    if (pid.isNotBlank()) synchronized(lock) { currentNodes[pid] = node }
                }
                // r50：progress 事件证明该 prompt 正在执行，二进制预览帧记账到它头上
                if (pid.isNotBlank()) executingPid = pid
            }
            "executing" ->
                // node=null 表示该 prompt 执行结束（0.29.0 main.py），清掉进度；node 有值的是节点开始
                if (data.isNull("node") || data.optString("node").isBlank()) {
                    data.optString("prompt_id").takeIf { it.isNotBlank() }?.let {
                        if (executingPid == it) executingPid = null
                        clear(it)
                    }
                } else {
                    val pid = data.optString("prompt_id")
                    val node = data.optString("node")
                    if (pid.isNotBlank() && node.isNotBlank()) {
                        synchronized(lock) { currentNodes[pid] = node }
                        executingPid = pid
                    }
                }
            "execution_success", "execution_interrupted" ->
                data.optString("prompt_id").takeIf { it.isNotBlank() }?.let {
                    if (executingPid == it) executingPid = null
                    clear(it)
                }
            "execution_error" -> {
                val pid = data.optString("prompt_id")
                if (pid.isNotBlank()) {
                    // 错误缓存独立于 steps/currentNodes；坏消息直接忽略，不影响进度显示
                    runCatching { parseExecutionError(data) }.getOrNull()?.let { errors.put(pid, it) }
                    if (executingPid == pid) executingPid = null
                    clear(pid)
                }
            }
        }
    }

    /**
     * r50：二进制预览帧。事件类型只认 1（PREVIEW_IMAGE）；格式不严格校验（BitmapFactory 自己嗅探，
     * 解不出就丢）。归属「正在 executing 的 prompt」。开关关、无归属、解不出图都直接丢，
     * 不分配、不落盘。每步多帧时只保留最后一帧（覆盖写）。
     */
    private fun handleBinary(frame: ByteArray) {
        if (!previewOn.get()) return
        val pid = executingPid ?: return
        val img = PreviewFrame.imageBytes(frame) ?: return
        val bmp = try {
            BitmapFactory.decodeByteArray(img, 0, img.size)
        } catch (_: Exception) {
            null
        } ?: return
        synchronized(lock) { previews[pid] = bmp }
    }

    private fun clear(promptId: String) {
        synchronized(lock) {
            steps.remove(promptId)
            currentNodes.remove(promptId)
            previews.remove(promptId)
        }
    }

    /** 返回 null = 地址未配置或非法（loop 会安静退出/断开，绝不因畸形 URL 炸掉 scope）。
     *  注意校验只能对 http(s) 做：OkHttp 的 HttpUrl 不认 ws/wss scheme，直接对完整 ws://
     *  地址 toHttpUrlOrNull() 恒为 null——旧代码正是这么写的，导致 WS 从未真正连接、
     *  步数进度（progress 消息）一直为空（round13 第 1 项的隐藏根因）。 */
    private suspend fun wsUrl(): String? {
        val base = try {
            settings.baseUrl()
        } catch (_: Exception) {
            return null
        }
        val httpish = base.toHttpUrlOrNull() ?: return null
        val scheme = if (httpish.isHttps) "wss" else "ws"
        return "$scheme://${httpish.host}:${httpish.port}/ws?clientId=${settings.comfyClientId()}"
    }

    private companion object {
        /** 连续这么久没有 getJob 触碰就主动断开（没有进行中的任务不挂连接）。 */
        const val IDLE_MS = 60_000L

        /** r42：execution_error 详情缓存的条数上限。 */
        const val ERROR_CACHE_MAX = 20
    }
}
