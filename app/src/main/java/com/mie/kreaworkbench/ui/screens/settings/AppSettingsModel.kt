package com.mie.kreaworkbench.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.api.ApiException
import com.mie.kreaworkbench.data.settings.UserSettings
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AppSettingsModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KreaApp).container
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())
    var url = androidx.compose.runtime.mutableStateOf("")
    var ping = androidx.compose.runtime.mutableStateOf("")
    var busy = androidx.compose.runtime.mutableStateOf(false)
    var usage = androidx.compose.runtime.mutableLongStateOf(0L)
    private var seeded = false

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            if (!seeded) {
                url.value = s.serverUrl
                seeded = true
            }
            usage.longValue = withContext(Dispatchers.IO) { c.cache.usedBytes() }
        }
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
                    c.settings.update { it.copy(serverUrl = target) }
                    c.api.ping(target)
                }
                ping.value = app.str(R.string.ping_latency, r.workbenchVersion, r.latencyMs)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ping.value = app.str(R.string.ping_timeout)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                ping.value = e.message ?: e.javaClass.simpleName
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
        usage.longValue = withContext(Dispatchers.IO) {
            c.cache.enforce()
            c.cache.usedBytes()
        }
    }

    fun clear(onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.cache.clearAll() }
        c.engine.revision.value = c.engine.revision.value + 1
        usage.longValue = 0
        onDone()
    }

    fun refreshUsage() {
        viewModelScope.launch {
            usage.longValue = withContext(Dispatchers.IO) { c.cache.usedBytes() }
        }
    }
}
