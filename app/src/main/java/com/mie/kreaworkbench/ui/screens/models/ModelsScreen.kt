package com.mie.kreaworkbench.ui.screens.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.components.AppTab
import com.mie.kreaworkbench.ui.components.KreaCard
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.notify
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.nav.NavModel
import com.mie.kreaworkbench.ui.screens.custom.CustomModel
import com.mie.kreaworkbench.ui.screens.custom.LoraToggleRow
import com.mie.kreaworkbench.ui.screens.custom.SpecRow
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.ui.screens.custom.WorkflowEmptyState
import com.mie.kreaworkbench.data.workflows.Specs

/**
 * 「模型和采样」页签 = 当前工作流的设置页：渲染 model/select/int/float/bool 五类 spec，
 * 与生成页（CustomScreen）共用同一个 CustomModel 实例和数据（viewModel() Activity 作用域）。
 * 没有保存按钮：改动即 300ms 防抖自动落盘 last_values.json（复用表单 VM 的 persist/persistNow）。
 * 顶部「刷新」（round13 第 7 项）：清模型目录缓存重拉 /object_info + 本页全部下拉选项，
 * 等价于 PC 浏览器对 ComfyUI 按 Ctrl+R 后重开参数面板；已填参数值保留，失败保留旧数据。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelsScreen(vm: CustomModel = viewModel(), nav: NavModel = viewModel()) {
    val wf by nav.workflow.collectAsState()
    // 切换工作流时设置页跟着切（与生成页同款 bind，boundId 去重）
    LaunchedEffect(wf) { if (wf.isNotBlank()) vm.bind(wf) }
    var confirmRestore by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val toaster = LocalToaster.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val currentName = if (vm.displayName.isBlank()) "…" else knownText(vm.displayName)
    // r13fix2：与生成/工作流/设置三个 tab 一样用 ScreenHeader 渲染主标题（不再用 LargeBarScaffold，
    // 其 LargeFlexibleTopAppBar 顶部固有一行 64dp 小栏，会把大标题整体往下压），刷新按钮放在同一行右侧
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.title_models), actions = {
            IconButton(
                onClick = {
                    if (!vm.refreshing) {
                        vm.refreshAll { err ->
                            if (err == null) {
                                notify(toaster, context, context.getString(R.string.refresh_done))
                            } else {
                                notify(toaster, context, context.getString(R.string.refresh_failed, err))
                            }
                        }
                    }
                },
                enabled = !vm.refreshing,
            ) {
                if (vm.refreshing) {
                    ContainedLoadingIndicator(Modifier.size(24.dp))
                } else {
                    Icon(Lucide.RefreshCw, contentDescription = stringResource(R.string.action_refresh), tint = scheme.primary)
                }
            }
        })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            if (wf.isBlank()) {
                // 无选中工作流或没有任何导入工作流 → 空状态
                WorkflowEmptyState(onGoImport = { nav.tab = AppTab.WORKFLOWS })
            } else {
                // 顶部当前工作流名（只读，不加切换入口）
                Text(
                    stringResource(R.string.current_workflow, currentName),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (vm.loading) {
                    ContainedLoadingIndicator(
                        Modifier.padding(bottom = 8.dp).size(48.dp).align(Alignment.CenterHorizontally),
                    )
                }
                // 分工路由：模型（type==model）与参数（select/int/float/bool），平铺不折叠；
                // kwb_gen 的 int（帧数/帧率）在生成页，不进设置页
                val modelSpecs = vm.specs.filter { Specs.type(it) == "model" }
                // LoRA 开关行（round16）：没有对应 model spec 的单加载器（导入时没勾文件字段）+ 所有 Power 槽
                val loraExtra = vm.loraEntries.filter { entry -> vm.specs.none { vm.loraEntryForSpec(it) == entry } }
                // 被自动强度框接管的强度参数不再显示（值照旧写入，提交时被 LoRA 层覆盖）
                val paramSpecs = vm.specs.filter {
                    Specs.type(it) in setOf("select", "int", "float", "bool") && !it.optBoolean("kwb_gen") &&
                        Specs.key(it) !in vm.loraManaged
                }
                if (modelSpecs.isNotEmpty() || loraExtra.isNotEmpty()) {
                    KreaCard {
                        Text(stringResource(R.string.label_model), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        modelSpecs.forEach { spec -> SpecRow(vm, spec, {}, {}) }
                        loraExtra.forEach { entry -> LoraToggleRow(vm, entry) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (paramSpecs.isNotEmpty()) {
                    KreaCard {
                        Text(stringResource(R.string.label_params), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        paramSpecs.forEach { spec -> SpecRow(vm, spec, {}, {}) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { confirmRestore = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
                ) {
                    Text(stringResource(R.string.restore_defaults), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary, maxLines = 1)
                }
                if (vm.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(vm.message, color = scheme.error)
                }
                Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.restore_defaults)) },
            text = { Text(stringResource(R.string.restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    vm.restoreDefaults()
                }) { Text(stringResource(R.string.restore_defaults)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
