package com.mie.kreaworkbench.ui.screens.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.api.ApiException
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
    var user = mutableStateOf("")
    var pass = mutableStateOf("")
    var showPass = mutableStateOf(false)
    var ping = mutableStateOf("")
    var busy = mutableStateOf(false)
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

    /** 从「最近地址」历史回填：地址+各自凭据一起填，立即落库。 */
    fun applyHistory(entry: ServerHistoryEntry) {
        url.value = entry.url
        user.value = entry.user
        pass.value = entry.pass
        ping.value = ""
        authNeeded.value = false
        viewModelScope.launch { saveNow() }
    }

    fun test() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val target = url.value.trim()
            if (target.isBlank()) {
                ping.value = app.str(R.string.ping_need_server)
                return@launch
            }
            busy.value = true
            ping.value = app.str(R.string.ping_connecting)
            try {
                val r = withTimeout(65_000L) {
                    saveNow()
                    c.api.ping(target, user.value.trim(), pass.value)
                }
                ping.value = app.str(R.string.ping_latency, r.workbenchVersion, r.latencyMs)
                authNeeded.value = false
                // 连通过才算「最近用过的地址」，进历史（防抖输入打到一半的值不会被记）
                c.settings.rememberServer(target, user.value.trim(), pass.value)
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
