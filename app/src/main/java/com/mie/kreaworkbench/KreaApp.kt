package com.mie.kreaworkbench

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mie.kreaworkbench.data.CacheManager
import com.mie.kreaworkbench.data.api.RequestAuth
import com.mie.kreaworkbench.data.api.Transfer
import com.mie.kreaworkbench.data.api.fileClient
import com.mie.kreaworkbench.data.comfy.ComfyApi
import com.mie.kreaworkbench.data.db.GalleryDb
import com.mie.kreaworkbench.data.library.PromptLibraryRepo
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.data.workflows.WorkflowStore
import com.mie.kreaworkbench.service.GenerationEngine
import com.mie.kreaworkbench.service.GenerationService
import com.mie.kreaworkbench.service.ensureNotifyChannels
import com.mie.kreaworkbench.service.scheduleResumeWork
import com.mie.kreaworkbench.ui.locale.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KreaApp : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: KreaApp
            private set
    }

    /** 全局 Coil 加载器也挂 Auth 拦截器：任何经 /view 的远程图片拉取都不会 401（本地缩略图不受影响）。 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .okHttpClient { fileClient(container.auth.interceptor) }
            .build()

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        // 还没有 Activity，这里应用语言不会把当前界面重建掉。
        AppLocale.applyAtStartup(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        instance = this
        super.onCreate()
        container = AppContainer(this)
        ensureNotifyChannels(this)
        container.engine.watchNetwork()
        container.auth.start()
        scheduleResumeWork(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                container.settings.migrateLegacyServerUrl()
            } catch (_: Exception) {
            }
        }
        scope.launch {
            // 启动兜底：currentWorkflow 非空但 store 里查不到（direct9 升级用户存的内置 id、
            // 或指向已删除的导入工作流）→ 写回 "" 进空状态。这就是全部数据迁移，不做专门迁移。
            try {
                val cur = container.settings.current().currentWorkflow
                if (cur.isNotBlank() && container.workflowStore.labelOf(cur) == null) {
                    container.settings.update { it.copy(currentWorkflow = "") }
                }
            } catch (_: Exception) {
            }
        }
        scope.launch {
            try {
                container.cache.enforce()
            } catch (_: Exception) {
            }
        }
        scope.launch {
            // 启动自愈（direct12）：清历史遗留的 0×0 图片行——能解出像素的回填宽高，解不出的删文件+墓碑；
            // 有变更就 bump revision 让生成页/画廊立刻重读。无坏行时近乎零成本。
            try {
                if (container.db.healZeroSizedImages() > 0) {
                    container.engine.revision.value = container.engine.revision.value + 1
                }
            } catch (_: Exception) {
            }
        }
    }
}

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val settings = SettingsStore(app)
    val db = GalleryDb(app)
    // auth 先建：api/transfer/progress 的 OkHttp 客户端都要挂它的 Basic Auth 拦截器
    val auth = RequestAuth(settings)
    val api = ComfyApi(app, settings, db, auth.interceptor)
    val library = PromptLibraryRepo(app)
    val workflowStore = WorkflowStore(app)
    val transfer = Transfer(settings, auth.interceptor)
    val cache = CacheManager(app, db, settings)
    val engine = GenerationEngine(app, db, api, transfer, settings, cache)
}

fun startGenerationService(context: Context) {
    val app = context.applicationContext as KreaApp
    app.container.engine.reconcile()
    try {
        val intent = Intent(app, GenerationService::class.java)
        ContextCompat.startForegroundService(app, intent)
    } catch (_: Exception) {
    }
}
