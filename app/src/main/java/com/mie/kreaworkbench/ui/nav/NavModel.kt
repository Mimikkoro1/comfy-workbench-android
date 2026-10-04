package com.mie.kreaworkbench.ui.nav

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.data.workflows.ImportedWorkflowMeta
import com.mie.kreaworkbench.ui.components.AppTab
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Overlay {
    data object Gallery : Overlay
    data class Viewer(val ids: List<Long>, val index: Int, val origin: String = "home") : Overlay

    /** 路 B：裸 API 工作流导入后的配置页（全屏）。 */
    data class WorkflowImport(val uris: List<android.net.Uri>) : Overlay

    /** txt 提示词库编辑器（round8 2.3，全屏）。 */
    data class LibraryEditor(val libId: String) : Overlay

    /** 设置页「关于」二级页。 */
    data object About : Overlay

    /** 设置页二级页（round13 5.2）：连接 / 后台运行 / 外观与语言。 */
    data object SettingsConnection : Overlay
    data object SettingsBackground : Overlay
    data object SettingsAppearance : Overlay
}

class NavModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KreaApp).container
    var tab by mutableStateOf(AppTab.T2I)
    val stack = mutableStateListOf<Overlay>()
    var pendingI2iPath by mutableStateOf<String?>(null)

    /** 与 [pendingI2iPath] 成对：参考图要等这个工作流绑上再填，避免 bind() 清槽。 */
    var pendingI2iWorkflowId by mutableStateOf<String?>(null)

    /** 多个带参考图工作流时的「用于图生图」选择弹窗；null = 不弹。 */
    var i2iChooser by mutableStateOf<List<ImportedWorkflowMeta>?>(null)
        private set
    private var i2iChooserPath: String? = null

    /** 当前工作流（DataStore 持久化）："" = 未选中（空状态），非空 = 导入工作流 id。 */
    val workflow: StateFlow<String> = c.settings.flow
        .map { it.currentWorkflow }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun push(overlay: Overlay) {
        stack.add(overlay)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** [origin] namespaces the hero shared-element key ("home" thumbnails vs "gal" grid) — E4. */
    fun openViewer(ids: List<Long>, index: Int, origin: String = "home") {
        if (ids.isEmpty()) return
        push(Overlay.Viewer(ids, index.coerceIn(0, ids.lastIndex), origin))
    }

    /**
     * 看图页「用于图生图」：目标 = 含 file:image spec 的导入工作流。
     * 0 个时按钮根本不渲染（ViewerScreen 侧判定）；1 个直接切；多个弹列表选择。
     * 选中后：currentWorkflow=它 + 暂存图片路径 + 跳「生成」tab。
     */
    fun useForI2i(path: String) {
        viewModelScope.launch {
            val targets = c.workflowStore.imageWorkflows()
            when {
                targets.isEmpty() -> return@launch
                targets.size == 1 -> chooseForI2i(targets.first().id, path)
                else -> {
                    i2iChooserPath = path
                    i2iChooser = targets
                }
            }
        }
    }

    fun chooseForI2i(id: String) {
        val path = i2iChooserPath
        i2iChooser = null
        i2iChooserPath = null
        if (path == null) return
        chooseForI2i(id, path)
    }

    private fun chooseForI2i(id: String, path: String) {
        pendingI2iWorkflowId = id
        pendingI2iPath = path
        tab = AppTab.T2I
        stack.clear()
        viewModelScope.launch { c.settings.update { it.copy(currentWorkflow = id) } }
    }

    fun dismissI2iChooser() {
        i2iChooser = null
        i2iChooserPath = null
    }
}
