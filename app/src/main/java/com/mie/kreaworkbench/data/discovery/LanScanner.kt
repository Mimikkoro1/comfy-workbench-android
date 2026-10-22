package com.mie.kreaworkbench.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mie.kreaworkbench.data.api.trimBase
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一条扫描结果：服务的可直连地址（http://IP:端口）+ 小字详情（GPU 名 / 抽卡服务版本）。 */
data class LanHit(
    val kind: Kind,
    val url: String,
    val detail: String,
) {
    enum class Kind { COMFY, GACHA }
}

/** 扫描事件：进度（已完成/总数）、发现一条、扫完。 */
sealed interface ScanEvent {
    data class Progress(val done: Int, val total: Int) : ScanEvent
    data class Found(val hit: LanHit) : ScanEvent
    data object Finished : ScanEvent
}

/**
 * 局域网服务器自动查找（r39）。只在 Wi-Fi/以太网上工作；权限只有清单里已有的
 * INTERNET + ACCESS_NETWORK_STATE，不新增任何权限。明文 HTTP 已被 network_security_config 放行。
 */
class LanScanner {

    // 探测专用客户端：扫一遍网段要打几百个不可能有响应的地址，每个必须快速失败
    private val probe = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .callTimeout(1_200, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 扫描 [selfIp]/[prefix] 网段。端口列表由调用方按服务给（默认端口 + 当前配置里的自定义端口）。
     * 实时输出 [ScanEvent]；取消收集即取消扫描（探测请求一并取消）。
     * 结果按发现顺序即为 IP 升序（主机列表升序枚举），去重由收集方按 url 做。
     */
    fun scan(selfIp: Int, prefix: Int, comfyPorts: List<Int>, gachaPorts: List<Int>): Flow<ScanEvent> = channelFlow {
        val hosts = hostsToScan(selfIp, prefix, selfIp)
        val comfy = comfyPorts.distinct()
        val gacha = gachaPorts.distinct()
        val total = hosts.size * (comfy.size + gacha.size)
        if (total == 0) {
            send(ScanEvent.Finished)
            return@channelFlow
        }
        val done = AtomicInteger(0)
        val gate = Semaphore(PROBE_CONCURRENCY)
        val jobs = ArrayList<Job>()
        for (host in hosts) {
            for (port in comfy) {
                jobs += launch {
                    val hit = gate.withPermit { probeComfy(host, port) }
                    hit?.let { send(ScanEvent.Found(it)) }
                    send(ScanEvent.Progress(done.incrementAndGet(), total))
                }
            }
            for (port in gacha) {
                jobs += launch {
                    val hit = gate.withPermit { probeGacha(host, port) }
                    hit?.let { send(ScanEvent.Found(it)) }
                    send(ScanEvent.Progress(done.incrementAndGet(), total))
                }
            }
        }
        jobs.forEach { it.join() }
        send(ScanEvent.Finished)
    }

    private suspend fun probeComfy(host: String, port: Int): LanHit? {
        val (code, text) = probeText("http://$host:$port/system_stats") ?: return null
        if (code != 200) return null
        val hit = parseComfyStats(text) ?: return null
        val detail = listOf(hit.gpu, hit.version).filter { it.isNotBlank() }.joinToString(" · ")
        return LanHit(LanHit.Kind.COMFY, "http://$host:$port", detail)
    }

    private suspend fun probeGacha(host: String, port: Int): LanHit? {
        val (code, text) = probeText("http://$host:$port/api/health") ?: return null
        if (code != 200) return null
        val hit = parseGachaHealth(text) ?: return null
        return LanHit(LanHit.Kind.GACHA, "http://$host:$port", hit.version)
    }

    /** 探测一个 GET；任何失败（超时/拒连/格式错/取消）都返回 null，不抛。 */
    private suspend fun probeText(url: String): Pair<Int, String>? = try {
        probe.newCall(Request.Builder().url(url).build()).await()
    } catch (_: Exception) {
        null
    }

    /** enqueue + 可取消挂起：收集侧取消时立刻 cancel 底层请求（同 data/api/Http.kt 的做法）。 */
    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val text = try {
                    response.body?.string().orEmpty()
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                if (cont.isActive) cont.resume(Pair(response.code, text))
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    companion object {
        /** 探测并发上限（r39 定值）：/24 × 两服务 ≈ 500 个探测，48 并发下全程约几秒。 */
        private const val PROBE_CONCURRENCY = 48

        /**
         * 本机网段：活跃网络必须是 Wi-Fi 或以太网，取第一个非 loopback/link-local 的 IPv4 与前缀。
         * 不满足返回 null（按钮置灰、扫描拒绝启动）。
         */
        fun lanSegment(context: Context): Pair<Int, Int>? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            val onLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLan) return null
            val lp = cm.getLinkProperties(network) ?: return null
            for (la in lp.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return Pair(ipv4BytesToInt(addr.address), la.prefixLength)
                }
            }
            return null
        }

        /** 是否具备扫描前提（Wi-Fi/以太网 + 有 IPv4 网段）。按钮置灰判断用。 */
        fun available(context: Context): Boolean = lanSegment(context) != null

        /** 从地址里取端口；与默认相同或解析不出返回 null（不用加进探测列表）。 */
        fun customPort(url: String, defaultPort: Int): Int? {
            val clean = trimBase(url)
            if (clean.isBlank()) return null
            val httpUrl = clean.toHttpUrlOrNull() ?: return null
            return if (httpUrl.port == defaultPort) null else httpUrl.port
        }
    }
}
