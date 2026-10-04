package com.mie.kreaworkbench.data.comfy

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
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ComfyUI /ws 进度监听：只服务于「步数 x/y」显示，任务状态一律仍以 /queue + /history 为准，ws 挂了不影响出图。
 * 文本帧维护 prompt_id → (value, max)；二进制帧（预览图）不重载 onMessage(ByteString) 即被丢弃。
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

    private suspend fun loop() {
        val backoff = longArrayOf(1000L, 3000L, 6000L, 10000L)
        var retryAt = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (now - lastTouch.get() > IDLE_MS) {
                closeSocket()
                activeUrl = null
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
            }
            "executing" ->
                // node=null 表示该 prompt 执行结束（0.29.0 main.py），清掉进度；node 有值的是节点开始
                if (data.isNull("node") || data.optString("node").isBlank()) {
                    data.optString("prompt_id").takeIf { it.isNotBlank() }?.let { clear(it) }
                } else {
                    val pid = data.optString("prompt_id")
                    val node = data.optString("node")
                    if (pid.isNotBlank() && node.isNotBlank()) {
                        synchronized(lock) { currentNodes[pid] = node }
                    }
                }
            "execution_success", "execution_error", "execution_interrupted" ->
                data.optString("prompt_id").takeIf { it.isNotBlank() }?.let { clear(it) }
        }
    }

    private fun clear(promptId: String) {
        synchronized(lock) {
            steps.remove(promptId)
            currentNodes.remove(promptId)
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
    }
}
