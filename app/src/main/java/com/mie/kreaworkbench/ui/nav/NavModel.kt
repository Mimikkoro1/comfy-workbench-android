package com.mie.kreaworkbench.ui.nav

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.data.workflows.BuiltinTemplates
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

    /** r38 E4/E5：库内工作流的参数配置页（copy = 复制并自定义；edit = 编辑参数），与导入页同屏。 */
    data class WorkflowCustomize(val sourceId: String, val mode: String) : Overlay

    /** txt 提示词库编辑器（round8 2.3，全屏）。 */
    data class LibraryEditor(val libId: String) : Overlay

    /** 设置页「关于」二级页。 */
    data object About : Overlay

    /** 全新安装「快速开始」引导页（r36）：没点过开始且没配地址时冷启动自动压栈。 */
    data object Onboarding : Overlay

    /** 设置页二级页（round13 5.2）：连接 / 后台运行 / 外观与语言。 */
    data object SettingsConnection : Overlay
    data object SettingsBackground : Overlay
    data object SettingsAppearance : Overlay

    /** 设置页「反馈与建议」二级页（r43，入口在设置首页「关于」上面）。 */
    data object SettingsFeedback : Overlay
}

class NavModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KreaApp).container
    var tab by mutableStateOf(AppTab.T2I)
    val stack = mutableStateListOf<Overlay>()
    var pendingI2iPath by mutableStateOf<String?>(null)

    /** 与 [pendingI2iPath] 成对：参考图要等这个工作流绑上再填，避免 bind() 清槽。 */
    var pendingI2iWorkflowId by mutableStateOf<String?>(null)

    /** r39：引导页①「自动查找」一次性标志：进连接页后自动开扫描弹窗；r40 改成可指定服务类型
     *  （引导页传 COMFY，抽卡空状态「去设置」传 GACHA），ConnectionPage 消费后置 null。 */
    var pendingAutoScan by mutableStateOf<com.mie.kreaworkbench.data.discovery.LanHit.Kind?>(null)

    /** 多个带参考图工作流时的「用于图生图」选择弹窗；null = 不弹。 */
    var i2iChooser by mutableStateOf<List<ImportedWorkflowMeta>?>(null)
        private set
    private var i2iChooserPath: String? = null

    /** 当前工作流（DataStore 持久化）："" = 未选中（空状态），非空 = 导入工作流 id。 */
    val workflow: StateFlow<String> = c.settings.flow
        .map { it.currentWorkflow }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** r36.1 first onboarding step: a server is configured (a direct URL or, since v0.5.2, an SSH tunnel). */
    val serverReady: StateFlow<Boolean> = c.settings.flow
        .map { it.hasServer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** r38 E6：生成页空状态里「当前存在的内置模板」（随删除 / 恢复自动刷新）。 */
    val builtins: StateFlow<List<ImportedWorkflowMeta>> = c.workflowStore.workflows
        .map { list -> list.filter { it.source == BuiltinTemplates.SOURCE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** r36.1 引导页②步：挑工作流的玻璃弹窗；null = 不弹。与 i2iChooser 同构。 */
    var wfChooser by mutableStateOf<List<ImportedWorkflowMeta>?>(null)
        private set

    /** r36.2 引导页逃生窗（「还没完成」）：返回手势与「开始生成」共用，状态在 NavModel 供返回拦截触发。 */
    var showOnboardingSkip by mutableStateOf(false)
        private set

    init {
        // r36.1 首启判定：异步读盘不阻塞首帧。没跳过（onboarded=false）且还有步骤没做完
        // （地址或工作流任一为空）就弹；老用户两者都齐则无感。配好地址只是①完成，
        // 绝不替用户置位，也不把引导从栈里踢掉——从连接页返回仍落回引导页。
        viewModelScope.launch {
            val s = c.settings.current()
            if (!s.onboarded && (!s.hasServer || s.currentWorkflow.isBlank())) {
                push(Overlay.Onboarding)
            }
        }
    }

    fun push(overlay: Overlay) {
        stack.add(overlay)
    }

    /**
     * r38：打开引导页——栈里已有就 pop 回到它（引导页「帮助」进关于后再点复看，不叠两层），
     * 没有才 push。设置页小入口与关于页「重新查看快速开始」都走这里。
     */
    fun openOnboarding() {
        val i = stack.indexOf(Overlay.Onboarding)
        if (i < 0) {
            push(Overlay.Onboarding)
            return
        }
        while (stack.lastIndex > i) {
            stack.removeAt(stack.lastIndex)
        }
    }

    /** 引导页「开始使用」（r36）：记住已完成并收起引导，不要求先配地址。 */
    fun finishOnboarding() {
        viewModelScope.launch { c.settings.update { it.copy(onboarded = true) } }
        if (stack.lastOrNull() == Overlay.Onboarding) pop()
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

    /** r36.1 引导页②步：打开挑工作流弹窗。内置模板排前，其余按原序；列表空时弹窗只显示「还没就绪」。 */
    fun openWfChooser() {
        viewModelScope.launch {
            val list = c.workflowStore.list()
            wfChooser = list.sortedByDescending { it.source == BuiltinTemplates.SOURCE }
        }
    }

    /** r36.1 引导页②步选中：写 currentWorkflow（LibraryModel.setWorkflow 同款纯写值），关弹窗。 */
    fun chooseWf(id: String) {
        wfChooser = null
        viewModelScope.launch { c.settings.update { it.copy(currentWorkflow = id) } }
    }

    fun dismissWfChooser() {
        wfChooser = null
    }

    /** r38 E6：生成页空状态点一个模板 = 直接设为当前工作流（与 [chooseWf] 同款纯写值）。 */
    fun pickWorkflow(id: String) {
        viewModelScope.launch { c.settings.update { it.copy(currentWorkflow = id) } }
    }

    /**
     * r36.2 引导页统一出口：返回手势与「开始生成」都走这里。
     * 两步都完成 → 正常收尾落主界面；没做完 → 弹逃生窗（返回不再直接跳出）。
     */
    fun requestOnboardingExit() {
        if (serverReady.value && workflow.value.isNotBlank()) {
            tab = AppTab.T2I
            finishOnboarding()
        } else {
            showOnboardingSkip = true
        }
    }

    /** 逃生窗「仍要进入」：置位 onboarded 并收引导，此后不再自动弹（设置可复看）。 */
    fun confirmOnboardingSkip() {
        showOnboardingSkip = false
        tab = AppTab.T2I
        finishOnboarding()
    }

    /** 逃生窗「继续配置」：留在引导页。 */
    fun dismissOnboardingSkip() {
        showOnboardingSkip = false
    }
}
