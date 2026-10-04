package com.mie.kreaworkbench.data.api

import com.mie.kreaworkbench.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

/**
 * Basic Auth 统一注入（v0.5.1 返工：自动内外网探测/切换已删，地址就是设置里那一个）。
 * 凭据从 settings 流同步进内存（拦截器是同步接口读不了 DataStore），
 * 对「请求 host:port:scheme == 配置地址」的请求加 Authorization 头；探测/测试连接这类
 * 带显式凭据的请求自己加头，不走这里。
 */
class RequestAuth(private val settings: SettingsStore, private val scope: CoroutineScope? = null) {
    @Volatile private var baseUrl: String = ""
    @Volatile private var user: String = ""
    @Volatile private var pass: String = ""

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val auth = if (user.isNotBlank() && sameAuthority(req.url.toString(), baseUrl)) {
            Credentials.basic(user, pass)
        } else {
            null
        }
        chain.proceed(if (auth != null) req.newBuilder().header("Authorization", auth).build() else req)
    }

    fun start() {
        (scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)).launch {
            settings.flow.collect { s ->
                baseUrl = trimBase(s.serverUrl)
                user = s.serverUser
                pass = s.serverPass
            }
        }
    }

    private fun sameAuthority(urlA: String, urlB: String): Boolean {
        val a = runCatching { urlA.toHttpUrlOrNull() }.getOrNull() ?: return false
        val b = runCatching { urlB.toHttpUrlOrNull() }.getOrNull() ?: return false
        return a.host == b.host && a.port == b.port && a.scheme == b.scheme
    }
}
