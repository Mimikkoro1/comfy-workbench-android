package com.mie.kreaworkbench.ui.screens.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.data.api.trimBase
import com.mie.kreaworkbench.data.gacha.DEFAULT_ADV_GACHA_URL
import com.mie.kreaworkbench.data.settings.ServerHistoryEntry
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.Dispatchers
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
    var gachaUrl = mutableStateOf(DEFAULT_ADV_GACHA_URL)
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
                gachaUrl.value = s.advGachaUrl.ifBlank { DEFAULT_ADV_GACHA_URL }
                name.value = s.serverHistory.firstOrNull { it.url.trimEnd('/') == s.serverUrl.trimEnd('/') }?.name.orEmpty()
                seeded = true
            }
            usage.value = withContext(Dispatchers.IO) { c.cache.usedBytes() }
            usageLoaded.value = true
        }
    }

    private suspend fun saveNow() = c.settings.update {
        it.copy(
            serverUrl = url.value.trim(),
            serverUser = user.value.trim(),
            serverPass = pass.value,
        )
    }

    fun save() = viewModelScope.launch { saveNow() }

    fun editGachaUrl(raw: String) {
        gachaUrl.value = raw.take(200)
        gachaLatency.value = -1L
        gachaPing.value = ""
    }

    /** 抽卡服务地址单独落库，并记进最近用过的地址。空的话回到默认地址。 */
    fun commitGachaUrl() {
        val clean = trimBase(gachaUrl.value).ifBlank { DEFAULT_ADV_GACHA_URL }
        gachaUrl.value = clean
        viewModelScope.launch {
            c.settings.update { cur ->
                val hist = listOf(clean) + cur.advGachaHistory.filter { it != clean }
                cur.copy(advGachaUrl = clean, advGachaHistory = hist.take(8))
            }
        }
    }

    fun pickGachaHistory(url: String) {
        gachaUrl.value = url
        gachaLatency.value = -1L
        gachaPing.value = ""
        commitGachaUrl()
    }

    /** 测抽卡服务：请求人物列表，记下延迟。成功后收起由调用方决定。 */
    fun testGacha(onSuccess: () -> Unit = {}) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val target = trimBase(gachaUrl.value).ifBlank { DEFAULT_ADV_GACHA_URL }
            gachaUrl.value = target
            gachaBusy.value = true
            gachaLatency.value = -1L
            gachaPing.value = app.str(R.string.ping_connecting)
            try {
                val ms = withTimeout(20_000L) {
                    c.settings.update { cur ->
                        val hist = listOf(target) + cur.advGachaHistory.filter { it != target }
                        cur.copy(advGachaUrl = target, advGachaHistory = hist.take(8))
                    }
                    val t = System.nanoTime()
                    c.gacha.catalog(target)
                    (System.nanoTime() - t) / 1_000_000
                }
                gachaLatency.value = ms
                gachaPing.value = app.str(R.string.gacha_latency, ms.toInt())
                onSuccess()
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
        viewModelScope.launch {
            val previous = c.settings.current().serverUrl
            saveNow()
            cancelFinishedOnServerSwitch(previous)
        }
    }

    /** 把当前表单存成一套服务器配置（名称可选；同地址覆盖凭据，空名称保留旧名）。 */
    fun saveServer(onDone: () -> Unit = {}) = viewModelScope.launch {
        val previous = c.settings.current().serverUrl
        c.settings.saveServer(
            ServerHistoryEntry(url.value.trim(), user.value.trim(), pass.value, name.value.trim()),
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

    fun test(onSuccess: () -> Unit = {}) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val target = url.value.trim()
            if (target.isBlank()) {
                ping.value = app.str(R.string.ping_need_server)
                return@launch
            }
            val previous = c.settings.current().serverUrl
            busy.value = true
            ping.value = app.str(R.string.ping_connecting)
            try {
                val r = withTimeout(65_000L) {
                    saveNow()
                    c.api.ping(target, user.value.trim(), pass.value)
                }
                ping.value = app.str(R.string.ping_latency, r.workbenchVersion, r.latencyMs)
                serverVersion.value = r.workbenchVersion
                serverLatency.value = r.latencyMs
                authNeeded.value = false
                onSuccess()
                // 连通过才算「最近用过的地址」，进历史（防抖输入打到一半的值不会被记）
                c.settings.rememberServer(target, user.value.trim(), pass.value)
                cancelFinishedOnServerSwitch(previous)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ping.value = app.str(R.string.ping_timeout)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.auth) {
                    ping.value = app.str(R.string.auth_needed)
                    authNeeded.value = true
                } else {
                    ping.value = e.message ?: e.javaClass.simpleName
                }
            } catch (e: Exception) {
                ping.value = e.message ?: e.javaClass.simpleName
            } finally {
                busy.value = false
            }
        }
    }

    fun theme(mode: String) = viewModelScope.launch { c.settings.update { it.copy(themeMode = mode) } }
    fun dynamicColor(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(dynamicColor = on) } }
    fun amoled(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(amoled = on) } }
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

    fun refreshUsage() {
        viewModelScope.launch {
            usage.value = withContext(Dispatchers.IO) { c.cache.usedBytes() }
            usageLoaded.value = true
        }
    }
}
