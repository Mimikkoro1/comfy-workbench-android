package com.mie.kreaworkbench.ui.screens.settings

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.data.api.ConnErrorKind
import com.mie.kreaworkbench.data.api.ConnectionReport
import com.mie.kreaworkbench.data.api.ServerKind
import com.mie.kreaworkbench.data.api.normalizeServerUrl
import com.mie.kreaworkbench.data.api.pingLineError
import com.mie.kreaworkbench.data.api.serverUrlCandidates
import com.mie.kreaworkbench.data.api.trimBase
import com.mie.kreaworkbench.data.discovery.LanHit
import com.mie.kreaworkbench.data.discovery.LanScanner
import com.mie.kreaworkbench.data.discovery.ScanEvent
import com.mie.kreaworkbench.data.discovery.hostsToScan
import com.mie.kreaworkbench.data.discovery.intToIpv4
import com.mie.kreaworkbench.data.discovery.lanHitIpKey
import com.mie.kreaworkbench.data.net.isLocalNetworkGranted
import com.mie.kreaworkbench.data.net.needsLocalNetworkPermission
import com.mie.kreaworkbench.data.net.urlTargetsLocalNetwork
import com.mie.kreaworkbench.data.settings.ServerHistoryEntry
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.ui.theme.ThemeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AppSettingsModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KreaApp).container
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    var url = mutableStateOf("")
    var gachaUrl = mutableStateOf("")
    var user = mutableStateOf("")
    var pass = mutableStateOf("")
    /** 当前表单正在编辑的配置显示名（可选，保存进配置列表时用）。 */
    var name = mutableStateOf("")
    var showPass = mutableStateOf(false)
    var ping = mutableStateOf("")
    /** 最近一次连上时的版本和延迟。还没测过是空串 / -1，收起的那一行就不写。 */
    var serverVersion = mutableStateOf("")
    var serverLatency = mutableStateOf(-1L)
    var busy = mutableStateOf(false)
    /** 抽卡服务测试，和 Comfy 的 ping 分开记。 */
    var gachaPing = mutableStateOf("")
    var gachaLatency = mutableStateOf(-1L)
    var gachaBusy = mutableStateOf(false)
    /** 测试连接遇 401 → 展开账号密码框并提示「需要账号密码」。 */
    var authNeeded = mutableStateOf(false)
    /** r41：最近一次测试失败的分类（LOCAL_NET_BLOCKED 时界面给「去系统设置」出口）；成功/未测为 null。 */
    var pingKind = mutableStateOf<ConnErrorKind?>(null)
    /** r41：给开发者看的详细信息（失败或 WS 警告时非空；不含密码，URL 里的账号密码已脱敏）。 */
    var pingDetail = mutableStateOf("")
    /**
     * r43：本进程内最近一次手动「测试连接」的结果（null = 还没测过）。
     * 只放内存：冷启动一律 null（灰）；成功 true（绿）、失败 false（红）。
     * 不能拿 serverLatency 推断——失败分支不清版本/延迟，会残留上一次的绿。
     */
    var connOk = mutableStateOf<Boolean?>(null)
    var usage = mutableStateOf(0L)
    /** usage 是否已算出（设置首页「存储与缓存」状态行算出前不显示）。 */
    var usageLoaded = mutableStateOf(false)
    private var seeded = false

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            if (!seeded) {
                url.value = s.serverUrl
                user.value = s.serverUser
                pass.value = s.serverPass
                gachaUrl.value = s.advGachaUrl
                name.value = s.serverHistory.firstOrNull { it.url.trimEnd('/') == s.serverUrl.trimEnd('/') }?.name.orEmpty()
                seeded = true
            }
            usage.value = withContext(Dispatchers.IO) { c.cache.usedBytes() }
            usageLoaded.value = true
        }
    }

    private suspend fun saveNow() = c.settings.update {
        val (u, p) = normalizeCredentials(user.value, pass.value)
        it.copy(
            serverUrl = url.value.trim(),
            serverUser = u,
            serverPass = p,
        )
    }

    fun editGachaUrl(raw: String) {
        gachaUrl.value = raw.take(200)
        gachaLatency.value = -1L
        gachaPing.value = ""
    }

    /** 抽卡服务地址单独落库，并记进最近用过的地址。r40：空 = 未设置，清空地址、不动历史。 */
    fun commitGachaUrl() {
        // r41：先按抽卡服务规范化（补 8787、截接口路径），解析不过退回 trimBase（旧行为）
        val clean = normalizeServerUrl(gachaUrl.value, ServerKind.GACHA) ?: trimBase(gachaUrl.value)
        gachaUrl.value = clean
        viewModelScope.launch {
            c.settings.update { cur ->
                if (clean.isBlank()) {
                    cur.copy(advGachaUrl = "")
                } else {
                    val hist = listOf(clean) + cur.advGachaHistory.filter { it != clean }
                    cur.copy(advGachaUrl = clean, advGachaHistory = hist.take(8))
                }
            }
        }
    }

    fun pickGachaHistory(url: String) {
        gachaUrl.value = url
        gachaLatency.value = -1L
        gachaPing.value = ""
        commitGachaUrl()
    }

    /** 测抽卡服务：请求人物列表，记下延迟。成功后收起由调用方决定。
     *  r41：没写协议的域名先试 https 再试 http，第一个成功的就是最终地址。 */
    fun testGacha(onSuccess: () -> Unit = {}) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            // 只在点「测试连接」时才碰网络。先规范化出候选，格式不对就地提示、不动已输入内容。
            val candidates = serverUrlCandidates(gachaUrl.value, ServerKind.GACHA)
            if (candidates.isEmpty()) {
                gachaPing.value = app.str(R.string.err_gacha_bad_url)
                return@launch
            }
            gachaBusy.value = true
            gachaLatency.value = -1L
            gachaPing.value = app.str(R.string.ping_connecting)
            try {
                var lastError: Exception? = null
                var lastTried = candidates.last()
                var target: String? = null
                var ms = -1L
                withTimeout(20_000L) {
                    for (cand in candidates) {
                        lastTried = cand
                        try {
                            val t = System.nanoTime()
                            c.gacha.catalog(cand)
                            ms = (System.nanoTime() - t) / 1_000_000
                            target = cand
                            return@withTimeout
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }
                }
                val ok = target
                if (ok != null) {
                    // 连上了才回填、落库并记进最近地址
                    gachaUrl.value = ok
                    c.settings.update { cur ->
                        val hist = listOf(ok) + cur.advGachaHistory.filter { it != ok }
                        cur.copy(advGachaUrl = ok, advGachaHistory = hist.take(8))
                    }
                    gachaLatency.value = ms
                    gachaPing.value = app.str(R.string.gacha_latency, ms.toInt())
                    onSuccess()
                } else {
                    // 全部失败：r41 目标在局域网且安卓 17 没给权限时，八成是被系统拦了，直接说这条；
                    // 其余报最后一次的错误
                    val e = lastError
                    val lanBlocked = needsLocalNetworkPermission(
                        Build.VERSION.SDK_INT,
                        isLocalNetworkGranted(app),
                        urlTargetsLocalNetwork(lastTried),
                    )
                    gachaPing.value = when {
                        lanBlocked -> app.str(R.string.conn_err_local_net_blocked)
                        e != null -> e.message ?: e.javaClass.simpleName
                        else -> app.str(R.string.err_gacha_unreachable)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                gachaPing.value = app.str(R.string.err_gacha_unreachable)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                gachaPing.value = e.message ?: e.javaClass.simpleName
            } finally {
                gachaBusy.value = false
            }
        }
    }

    /** r41：地址框失焦时把显示回填成规范化形式（只改输入框，不落库、不发请求）。 */
    fun normalizeUrlField() {
        val target = normalizeServerUrl(url.value) ?: return
        if (target != url.value) url.value = target
    }

    /** 同上，抽卡地址框（按 8787 补端口）；延迟/提示不动。 */
    fun normalizeGachaField() {
        val target = normalizeServerUrl(gachaUrl.value, ServerKind.GACHA) ?: return
        if (target != gachaUrl.value) gachaUrl.value = target
    }

    /** 从「用过的地址」里删一条。当前输入框里的地址不动。 */
    fun deleteGachaHistory(url: String) {
        viewModelScope.launch {
            c.settings.update { cur ->
                cur.copy(advGachaHistory = cur.advGachaHistory.filter { it != url })
            }
        }
    }

    /** 从已保存配置列表回填：地址 + 显示名 + 各自凭据一起填，立即落库并设为当前连接。 */
    fun applyHistory(entry: ServerHistoryEntry) {
        url.value = entry.url
        user.value = entry.user
        pass.value = entry.pass
        name.value = entry.name
        ping.value = ""
        serverVersion.value = ""
        serverLatency.value = -1L
        authNeeded.value = false
        // r43：换了地址，上一次的测试结论作废（状态点回灰）
        connOk.value = null
        viewModelScope.launch {
            val previous = c.settings.current().serverUrl
            saveNow()
            cancelFinishedOnServerSwitch(previous)
        }
    }

    /** 把当前表单存成一套服务器配置（名称可选；同地址覆盖凭据，空名称保留旧名）。 */
    fun saveServer(onDone: () -> Unit = {}) = viewModelScope.launch {
        val previous = c.settings.current().serverUrl
        // 存配置也走规范化；解析不过保留原文，行为与旧版一致
        val normalized = normalizeServerUrl(url.value, ServerKind.COMFY) ?: url.value.trim()
        url.value = normalized
        c.settings.saveServer(
            ServerHistoryEntry(normalized, user.value.trim(), pass.value, name.value.trim()),
        )
        saveNow()
        cancelFinishedOnServerSwitch(previous)
        onDone()
    }

    /**
     * r31：切换到另一台 ComfyUI 之后，旧任务手里的 jobId 在新服务器上不存在。
     * 留着它们只会被 reconcile 反复踢、对着新地址查一个不存在的任务——
     * 所以确认地址真的变了，就把未完成的本地任务全部收掉（服务器端尽力撤销）。
     */
    private suspend fun cancelFinishedOnServerSwitch(previous: String) {
        if (trimBase(previous) == trimBase(c.settings.current().serverUrl)) return
        // paused 也算未完成：它的 jobId 同样属于旧服务器
        if (c.engine.hasAnyUnfinished()) c.engine.forceCancelAll()
    }

    /** 删除一套已保存配置（只从列表移除，不影响当前连接）。 */
    fun deleteServer(url: String) = viewModelScope.launch {
        c.settings.deleteServer(url)
    }

    /** r41：分类 → 用户文案（不出现异常类名）。 */
    private fun connKindText(app: Application, kind: ConnErrorKind, httpCode: Int?): String = when (kind) {
        ConnErrorKind.BAD_URL -> app.str(R.string.conn_err_bad_url)
        ConnErrorKind.LOCAL_NET_BLOCKED -> app.str(R.string.conn_err_local_net_blocked)
        ConnErrorKind.TIMEOUT -> app.str(R.string.conn_err_timeout)
        ConnErrorKind.REFUSED -> app.str(R.string.conn_err_refused)
        ConnErrorKind.NO_ROUTE -> app.str(R.string.conn_err_no_route)
        ConnErrorKind.UNKNOWN_HOST -> app.str(R.string.conn_err_unknown_host)
        ConnErrorKind.TLS -> app.str(R.string.conn_err_tls)
        ConnErrorKind.AUTH_REQUIRED -> app.str(R.string.auth_needed)
        ConnErrorKind.NOT_FOUND -> app.str(R.string.conn_err_not_found)
        ConnErrorKind.HTTP_OTHER -> app.str(R.string.conn_err_http_other, httpCode ?: 0)
        ConnErrorKind.NOT_COMFY -> app.str(R.string.conn_err_not_comfy)
        ConnErrorKind.WS_REJECTED -> app.str(R.string.conn_err_ws_rejected)
        ConnErrorKind.WS_AUTH -> app.str(R.string.conn_err_ws_auth)
        ConnErrorKind.WS_FAILED -> app.str(R.string.conn_err_ws_failed)
        ConnErrorKind.OTHER -> app.str(R.string.err_cannot_connect)
    }

    /** r41：给开发者看的详细信息（给用户截图/复制用；不含密码）。 */
    private fun buildPingDetail(app: Application, target: String, report: ConnectionReport?): String {
        val lines = buildDiagnosticsHeader(target).lines().toCollection(ArrayList())
        report?.error?.let { e ->
            lines.add("stage: ${stageName(e.kind)}, kind: ${e.kind}")
            e.httpCode?.let { lines.add("http code: $it") }
            lines.add("error: ${e.tech}")
        }
        report?.wsError?.let { e ->
            lines.add("ws kind: ${e.kind}")
            e.httpCode?.let { lines.add("ws http code: $it") }
            lines.add("ws error: ${e.tech}")
        }
        return lines.joinToString("\n")
    }

    /**
     * r42：诊断信息头部——版本、系统、局域网权限、当前网络、脱敏地址。
     * 没点过测试连接也能用（关于页「复制诊断信息」）；[serverUrl] 默认取表单当前值。
     * 不含账号密码（地址里的 user:pass@ 已由 [sanitizeUrl] 去掉）。
     */
    internal fun buildDiagnosticsHeader(serverUrl: String = url.value.trim()): String {
        val app = getApplication<Application>()
        val lines = ArrayList<String>()
        lines.add("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        lines.add("android: SDK ${Build.VERSION.SDK_INT}, targetSdk ${app.applicationInfo.targetSdkVersion}")
        lines.add("lan permission: " + if (isLocalNetworkGranted(app)) "granted" else "denied")
        lines.add("network: " + currentNetworkSummary(app))
        lines.add("url: " + sanitizeUrl(serverUrl))
        return lines.joinToString("\n")
    }

    /** r42：一键复制诊断信息的完整文本 = 头部 + 最近一次连接测试（测过才追加）。不含账号密码。 */
    fun buildFeedbackDiagnostics(): String {
        val app = getApplication<Application>()
        val sb = StringBuilder(buildDiagnosticsHeader())
        val kind = pingKind.value
        val detail = pingDetail.value
        if (kind != null || detail.isNotBlank()) {
            sb.append("\n\n").append(app.str(R.string.feedback_last_test)).append('\n')
            if (kind != null) sb.append("kind: ").append(kind.name).append('\n')
            if (detail.isNotBlank()) sb.append(detail)
        }
        return sb.toString()
    }

    private fun stageName(kind: ConnErrorKind): String = when (kind) {
        ConnErrorKind.WS_REJECTED, ConnErrorKind.WS_AUTH, ConnErrorKind.WS_FAILED -> "WS"
        ConnErrorKind.NOT_COMFY -> "CONTENT"
        else -> "HTTP"
    }

    /** 当前网络概览（Wi-Fi / 蜂窝 / 以太网 / VPN，给详细信息用）。 */
    private fun currentNetworkSummary(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val parts = ArrayList<String>()
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) parts.add("wifi")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) parts.add("cellular")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) parts.add("ethernet")
        if (LanScanner.isVpnActive(context)) parts.add("vpn")
        return if (parts.isEmpty()) "none" else parts.joinToString("+")
    }

    fun test(onSuccess: () -> Unit = {}) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (url.value.isBlank()) {
                ping.value = app.str(R.string.ping_need_server)
                return@launch
            }
            // r41：先规范化出候选（没写协议的域名先 https 后 http），逐个分步探测，
            // 第一个 HTTP 通的就是最终地址；全部失败报最后一次的分类文案 + 可复制详细信息。
            val candidates = serverUrlCandidates(url.value)
            if (candidates.isEmpty()) {
                ping.value = app.str(R.string.err_bad_server_url)
                return@launch
            }
            val previous = c.settings.current().serverUrl
            busy.value = true
            ping.value = app.str(R.string.ping_connecting)
            try {
                var lastReport: ConnectionReport? = null
                var lastTarget = candidates.last()
                var connected = false
                withTimeout(65_000L) {
                    for (target in candidates) {
                        val report = c.api.diagnose(target, user.value.trim(), pass.value)
                        if (report.httpOk) {
                            url.value = target
                            // 账号密码与地址一起在测试成功时保存；失焦不保存（输一半不会触发重连）
                            saveNow()
                            // r41.1：ping 只放 WS 提示那一行（没有就留空），失败路径不变；
                            // 版本/延迟由 serverVersion/serverLatency 显示（收起态摘要行）——
                            // ping 里再拼一遍会让「ping != 摘要」的去重判断失效，版本延迟显示两遍
                            ping.value = pingLineError(report)?.let { ws -> connKindText(app, ws.kind, ws.httpCode) } ?: ""
                            serverVersion.value = report.version
                            serverLatency.value = report.latencyMs
                            authNeeded.value = false
                            pingKind.value = null
                            pingDetail.value = if (report.wsError != null) buildPingDetail(app, target, report) else ""
                            onSuccess()
                            // 连通过才算「最近用过的地址」，进历史（防抖输入打到一半的值不会被记）
                            c.settings.rememberServer(target, user.value.trim(), pass.value)
                            cancelFinishedOnServerSwitch(previous)
                            connected = true
                            // r43：这次测试成功（绿）
                            connOk.value = true
                            return@withTimeout
                        }
                        lastReport = report
                        lastTarget = target
                    }
                }
                if (!connected) {
                    val err = lastReport?.error
                    if (err != null && err.kind == ConnErrorKind.AUTH_REQUIRED) {
                        ping.value = app.str(R.string.auth_needed)
                        authNeeded.value = true
                    } else {
                        ping.value = err?.let { connKindText(app, it.kind, it.httpCode) }
                            ?: app.str(R.string.err_cannot_connect)
                    }
                    pingKind.value = err?.kind
                    pingDetail.value = buildPingDetail(app, lastTarget, lastReport)
                    // r43：这次测试失败了（红），不沿用上次成功的结论
                    connOk.value = false
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ping.value = app.str(R.string.ping_timeout)
                connOk.value = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ping.value = e.message ?: e.javaClass.simpleName
                connOk.value = false
            } finally {
                busy.value = false
            }
        }
    }

    fun theme(mode: String) = viewModelScope.launch { c.settings.update { it.copy(themeMode = mode) } }
    fun dynamicColor(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(dynamicColor = on) } }
    fun amoled(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(amoled = on) } }
    fun uiStyle(kind: ThemeKind) = viewModelScope.launch { c.settings.update { it.copy(uiStyle = kind.id) } }
    fun notify(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(notifyEnabled = on) } }
    fun blur(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(blurEnabled = on) } }
    fun cache(gb: Int) = viewModelScope.launch {
        c.settings.update { it.copy(cacheLimitGb = gb) }
        usage.value = withContext(Dispatchers.IO) {
            c.cache.enforce()
            c.cache.usedBytes()
        }
    }

    fun clear(onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.cache.clearAll() }
        c.engine.revision.update { it + 1 }
        usage.value = 0
        onDone()
    }

    /** 当前收藏数量（清缓存确认文案用，round34）。 */
    suspend fun favoriteCount(): Int = withContext(Dispatchers.IO) { c.db.favoriteCount() }

    fun refreshUsage() {
        viewModelScope.launch {
            usage.value = withContext(Dispatchers.IO) { c.cache.usedBytes() }
            usageLoaded.value = true
        }
    }

    // ---- r39：局域网自动查找（扫描协程与弹窗状态都在这里，离开页面由 ConnectionPage 取消） ----

    /** 扫描弹窗状态；null = 弹窗关着。 */
    var scan = mutableStateOf<ScanUi?>(null)
        private set

    /** 一次扫描会话的 UI 状态。hits 按 IP 数值排序、按 url 去重。 */
    data class ScanUi(
        val kind: LanHit.Kind,
        val subnet: String,
        val total: Int,
        val done: Int = 0,
        val hits: List<LanHit> = emptyList(),
        val finished: Boolean = false,
        /** r41：手机自己的 IP、是否有 VPN、以及是否只能回退到 active 网络（提示网段可能不对用）。 */
        val selfIp: String = "",
        val vpnActive: Boolean = false,
        val viaFallback: Boolean = false,
    )

    private var scanJob: Job? = null
    private val scanner = LanScanner()

    /** 开始扫描本机网段。不在 Wi-Fi/以太网上时（按钮置灰的清形）直接不开弹窗。 */
    fun startScan(kind: LanHit.Kind) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val info = LanScanner.lanSegmentInfo(app) ?: return@launch
            val selfIp = info.selfIp
            val prefix = info.prefix
            val p = prefix.coerceIn(24, 32)
            val mask = -1 shl (32 - p)
            val subnet = intToIpv4(selfIp and mask).substringBeforeLast('.')
            val s = c.settings.current()
            // 探测端口：默认端口 + 当前配置里的自定义端口（最多各加 1 个）
            val comfyPorts = buildList {
                add(8188)
                LanScanner.customPort(s.serverUrl, 8188)?.let { add(it) }
            }
            val gachaPorts = buildList {
                add(8787)
                LanScanner.customPort(s.advGachaUrl, 8787)?.let { add(it) }
            }
            val total = hostsToScan(selfIp, prefix).size * (comfyPorts.size + gachaPorts.size)
            scan.value = ScanUi(
                kind = kind,
                subnet = subnet,
                total = total,
                selfIp = intToIpv4(selfIp),
                vpnActive = LanScanner.isVpnActive(app),
                viaFallback = info.viaFallback,
            )
            // r40：这一轮扫到的抽卡地址（会话内累积，随时覆盖写盘；新扫描整体替换旧值）
            val gachaHits = LinkedHashSet<String>()
            try {
                // r42：探测绑定到挑中的那张网卡（Wi-Fi/以太网），不走 VPN 虚拟网卡
                scanner.scan(app, selfIp, prefix, comfyPorts, gachaPorts, info.network).collect { ev ->
                    when (ev) {
                        is ScanEvent.Progress -> scan.value = scan.value?.copy(done = ev.done)
                        is ScanEvent.Found -> {
                            // 弹窗只显示自己对应的服务；抽卡服务的命中即使当前找的是 ComfyUI 也记下来
                            if (ev.hit.kind == LanHit.Kind.GACHA) {
                                gachaHits.add(ev.hit.url)
                                val sorted = gachaHits.sortedWith(compareBy({ lanHitIpKey(it) }, { it }))
                                runCatching { c.settings.setScannedGachaUrls(sorted) }
                            }
                            if (ev.hit.kind != kind) return@collect
                            scan.value = scan.value?.copy(
                                hits = (scan.value?.hits.orEmpty() + ev.hit)
                                    .distinctBy { it.url }
                                    .sortedWith(compareBy({ lanHitIpKey(it.url) }, { it.url })),
                            )
                        }
                        ScanEvent.Finished -> scan.value = scan.value?.copy(finished = true)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    /** 关弹窗并停扫描。 */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        scan.value = null
    }

    /** r41：扫描弹窗点「手动填地址」→ 对应地址卡片展开编辑；卡片消费后清空。 */
    var manualEdit = mutableStateOf<LanHit.Kind?>(null)

    /** 点一条扫描结果：只填输入框并提示，不自动测试、不保存（D3：测试连接成功才保存）。 */
    fun pickScanHit(hit: LanHit) {
        val app = getApplication<Application>()
        if (hit.kind == LanHit.Kind.COMFY) {
            if (url.value != hit.url) connOk.value = null
            url.value = hit.url
            ping.value = app.str(R.string.scan_filled_hint)
        } else {
            editGachaUrl(hit.url)
            gachaPing.value = app.str(R.string.scan_filled_hint)
        }
        cancelScan()
    }
}

/** 账号存前去首尾空白；密码原样保存（密码里可以有空格）。 */
internal fun normalizeCredentials(user: String, pass: String): Pair<String, String> = user.trim() to pass

/** 去掉 URL 里的 user:pass@，诊断信息不落密码（r42 抽成顶层纯函数，可单测）。 */
internal fun sanitizeUrl(url: String): String {
    val idx = url.indexOf("://")
    if (idx < 0) return url
    val rest = url.substring(idx + 3)
    val at = rest.lastIndexOf('@')
    return if (at >= 0) url.substring(0, idx + 3) + rest.substring(at + 1) else url
}
