package com.mie.kreaworkbench.ui.screens.settings

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.discovery.LanHit
import com.mie.kreaworkbench.data.discovery.LanScanner
import com.mie.kreaworkbench.data.net.isLocalNetworkGranted
import com.mie.kreaworkbench.data.net.needsLocalNetworkPermission
import com.mie.kreaworkbench.data.net.urlTargetsLocalNetwork
import com.mie.kreaworkbench.data.workflows.isTextToImageWorkflow
import com.mie.kreaworkbench.data.workflows.outputKindOf
import com.mie.kreaworkbench.ui.locale.AppLocale
import com.mie.kreaworkbench.ui.components.CardGroup
import com.mie.kreaworkbench.ui.components.GlassAlertDialog
import com.mie.kreaworkbench.ui.components.GlassCard
import com.mie.kreaworkbench.ui.components.GlassDanger
import com.mie.kreaworkbench.ui.components.GlassDropdownMenu
import com.mie.kreaworkbench.ui.components.GlassMenuItem
import com.mie.kreaworkbench.ui.components.GroupedItem
import com.mie.kreaworkbench.ui.components.ImmersiveHairline
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveInkMuted
import com.mie.kreaworkbench.ui.components.LocalGlassChrome
import com.mie.kreaworkbench.ui.components.LocalNetworkGuard
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.PrimaryButton
import com.mie.kreaworkbench.ui.components.LargeBarScaffold
import com.mie.kreaworkbench.ui.components.notify
import com.mie.kreaworkbench.service.ForceClearOutcome
import com.mie.kreaworkbench.ui.components.glassCheckboxColors
import com.mie.kreaworkbench.ui.components.glassChipColors
import com.mie.kreaworkbench.ui.components.glassSwitchColors
import com.mie.kreaworkbench.ui.components.glassTextFieldColors
import com.mie.kreaworkbench.ui.components.rememberLocalNetworkGuard
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.theme.AppearanceRow
import com.mie.kreaworkbench.ui.theme.LocalIsDark
import com.mie.kreaworkbench.ui.theme.ThemeKind
import com.mie.kreaworkbench.ui.theme.appearanceRows
import com.mie.kreaworkbench.ui.theme.isClassic
import com.mie.kreaworkbench.ui.theme.pageBackground
import com.mie.kreaworkbench.ui.theme.resolveThemeKind
import com.mie.kreaworkbench.util.BatteryKeep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页二级页（round13 5.2）：连接 / 后台运行 / 外观与语言。
 * 连接页连上之后收成一行，长按或点编辑再展开；抽卡地址同样是下拉。
 * 后台页 = 简短说明 + 电池优化跳转（r13fix 去掉厂商专属入口）+ 出图通知；
 * 外观页 = 主题/语言/动态色/纯黑/模糊（语言切换的重启确认一并搬入）。
 *
 * r29：这几页压在共享背景上，铺一层和设置 tab 同档的薄纱（不是实心深底），
 * 顶栏走沉浸白字，卡片/列表换玻璃。主题、语言、开关的业务逻辑一行没动。
 */

/**
 * 设置二级页的薄纱：与设置 tab 的薄遮罩同一档（深色 0.48 / 浅色 0.52）。
 * 这一层**故意不是 hazeSource**——玻璃卡要模糊 AppNav 根那张共享背景，才看得出通透；
 * 若把深色底注册成模糊源，卡里只会糊出一块均匀的暗色，看着又变回素皮。
 */
@Composable
internal fun SettingsPageVeil(): Color =
    if (isClassic()) {
        pageBackground()
    } else if (LocalIsDark.current) Color.Black.copy(alpha = 0.48f) else Color.Black.copy(alpha = 0.52f)

/** 设置二级页共用外壳：薄纱 + 透明沉浸顶栏 + 玻璃语境（列表/分组标题走白字）。 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(SettingsPageVeil()))
        CompositionLocalProvider(LocalGlassChrome provides !isClassic()) {
            LargeBarScaffold(
                title = title,
                onBack = onBack,
                blur = true,
                immersive = true,
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionPage(
    onBack: () -> Unit,
    autoScan: LanHit.Kind? = null,
    onAutoScanConsumed: () -> Unit = {},
    vm: AppSettingsModel = viewModel(),
) {
    val s by vm.settings.collectAsState()
    val app = LocalContext.current.applicationContext as KreaApp
    // r39：本机是否在 Wi-Fi/以太网上（打开页面时查一次，按钮据此置灰）
    var lanOk by remember { mutableStateOf(LanScanner.available(app)) }
    // r41：局域网权限引导；从系统设置回来（ON_RESUME）后刷新授权状态
    val guard = rememberLocalNetworkGuard()
    var lanGranted by remember { mutableStateOf(isLocalNetworkGranted(app)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) lanGranted = isLocalNetworkGranted(app)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // r39：离开页面自动取消扫描（AppSettingsModel 是页面级单例，必须自己收）
    DisposableEffect(Unit) {
        onDispose { vm.cancelScan() }
    }
    // r41：扫描前清单弹窗；null = 关着。所有扫描入口统一走 requestScan。
    var checklistAsk by remember { mutableStateOf<LanHit.Kind?>(null) }
    val scope = rememberCoroutineScope()
    fun requestScan(kind: LanHit.Kind) {
        scope.launch {
            // 用 suspend 读（autoScan 触发时设置可能还没载入），不读缓存值
            if (app.container.settings.scanChecklistOff()) {
                guard.ensure(null, runEvenIfDenied = false) { vm.startScan(kind) }
            } else {
                checklistAsk = kind
            }
        }
    }
    // r39：引导页「自动查找」直达：首次组合时立即开扫描弹窗；r40 起可指定服务类型（引导页 COMFY / 抽卡空状态 GACHA）
    androidx.compose.runtime.LaunchedEffect(autoScan) {
        val kind = autoScan
        if (kind != null) {
            onAutoScanConsumed()
            if (lanOk) requestScan(kind)
        }
    }
    var textToImage by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(s.currentWorkflow) {
        val id = s.currentWorkflow
        textToImage = withContext(Dispatchers.IO) { currentWorkflowIsTextToImage(app, id) }
    }
    val showGacha = s.advGacha && textToImage
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    // r31：连接不上时任务会卡在提交/排队里，给一个「全部收掉」的出口
    var forceCancelAsk by remember { mutableStateOf(false) }
    // null = 还没手动开过：已经有地址就收起，没有地址就直接编辑。
    var editing by remember { mutableStateOf<Boolean?>(null) }
    val expanded = editing ?: s.serverUrl.isBlank()
    androidx.compose.runtime.LaunchedEffect(vm.authNeeded.value) {
        if (vm.authNeeded.value) editing = true
    }
    // r41：扫描弹窗点「手动填地址」→ ComfyUI 卡片展开编辑（消费后清空；抽卡卡片自己消费 GACHA）
    androidx.compose.runtime.LaunchedEffect(vm.manualEdit.value) {
        if (vm.manualEdit.value == LanHit.Kind.COMFY) {
            editing = true
            vm.manualEdit.value = null
        }
    }
    SettingsPageScaffold(title = stringResource(R.string.group_connection), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // —— 当前连接 ——
            GlassCard {
                if (!expanded) {
                    val serverTitle = vm.name.value.ifBlank { hostPort(vm.url.value) }
                        .ifBlank { stringResource(R.string.settings_current_server) }
                    val serverStatus = if (vm.serverLatency.value >= 0 && vm.serverVersion.value.isNotBlank()) {
                        stringResource(R.string.ping_latency, vm.serverVersion.value, vm.serverLatency.value.toInt())
                    } else {
                        null
                    }
                    ServerSummaryRow(
                        title = serverTitle,
                        status = serverStatus,
                        entries = s.serverHistory.map { (it.name.ifBlank { it.url }) to it.url },
                        emptyText = stringResource(R.string.saved_servers_empty),
                        testing = vm.busy.value,
                        onTest = { guard.ensure(vm.url.value, runEvenIfDenied = true) { vm.test(onSuccess = { editing = false }) } },
                        onEdit = { editing = true },
                        onPick = { picked ->
                            s.serverHistory.firstOrNull { it.url == picked }?.let { vm.applyHistory(it) }
                            editing = false
                        },
                        onDelete = { confirmDelete = it },
                    )
                    if (vm.ping.value.isNotBlank() && vm.ping.value != serverStatus) {
                        Text(vm.ping.value, color = ImmersiveInkMuted, fontSize = 13.sp)
                    }
                    LanBlockedRow(vm)
                    PingDetailBlock(vm)
                } else {
                Text(stringResource(R.string.settings_current_server), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AddressMenuField(
                    value = vm.url.value,
                    // r43：地址变了，上一次的测试结论作废（状态点回灰）；值没变不重置，避免无谓写状态
                    onValueChange = {
                        if (vm.url.value != it) {
                            vm.url.value = it
                            vm.connOk.value = null
                        }
                    },
                    placeholder = stringResource(R.string.server_url_hint),
                    menuDescription = stringResource(R.string.action_pick_server),
                    entries = s.serverHistory.map { (it.name.ifBlank { it.url }) to it.url },
                    emptyText = stringResource(R.string.saved_servers_empty),
                    onPick = { picked ->
                        s.serverHistory.firstOrNull { it.url == picked }?.let { vm.applyHistory(it) }
                        editing = false
                    },
                    onDelete = { confirmDelete = it },
                    onFocusGained = { editing = true },
                    onFocusLost = { vm.normalizeUrlField() },
                )
                // r41：安卓 17 且未授权、当前地址指向局域网时提示补授权
                if (needsLocalNetworkPermission(
                        Build.VERSION.SDK_INT,
                        lanGranted,
                        urlTargetsLocalNetwork(vm.url.value),
                    )
                ) {
                    Text(
                        stringResource(R.string.lan_perm_hint),
                        fontSize = 12.sp,
                        color = ImmersiveInkMuted,
                        modifier = Modifier.clickable { guard.ensure(vm.url.value, true) {} },
                    )
                }
                Spacer(Modifier.height(8.dp))
                AutoFindRow(lanOk) { requestScan(LanHit.Kind.COMFY) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vm.name.value,
                    onValueChange = { vm.name.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.field_server_name)) },
                    singleLine = true,
                    colors = glassTextFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                // r13fix3：两框都单行标签（省略号）+ 垂直居中，眼睛图标在时密码框不再因标签换行变高
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = vm.user.value,
                            onValueChange = { vm.user.value = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.field_user), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            colors = glassTextFieldColors(),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = vm.pass.value,
                            onValueChange = { vm.pass.value = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.field_pass), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            visualTransformation = if (vm.showPass.value) VisualTransformation.None else PasswordVisualTransformation(),
                            colors = glassTextFieldColors(),
                            trailingIcon = {
                                IconButton(onClick = { vm.showPass.value = !vm.showPass.value }) {
                                    Icon(
                                        if (vm.showPass.value) Lucide.EyeOff else Lucide.Eye,
                                        contentDescription = stringResource(if (vm.showPass.value) R.string.hide_password else R.string.show_password),
                                    )
                                }
                            },
                        )
                    }
                if (vm.authNeeded.value) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.auth_needed),
                        color = GlassDanger,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                val testing = stringResource(R.string.settings_testing)
                val testLabel = stringResource(R.string.settings_test)
                PrimaryButton(if (vm.busy.value) testing else testLabel, enabled = !vm.busy.value) {
                    guard.ensure(vm.url.value, runEvenIfDenied = true) { vm.test(onSuccess = { editing = false }) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { guard.ensure(vm.url.value, runEvenIfDenied = true) { vm.saveServer { editing = false } } },
                    enabled = vm.url.value.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ImmersiveInk),
                    border = BorderStroke(1.dp, ImmersiveHairline),
                ) {
                    Text(stringResource(R.string.action_save_server))
                }
                if (vm.ping.value.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(vm.ping.value, color = ImmersiveInkMuted, fontSize = 13.sp)
                }
                LanBlockedRow(vm)
                PingDetailBlock(vm)
                }
            }

            if (showGacha) {
                Spacer(Modifier.height(20.dp))
                GachaServerCard(vm, s.advGachaHistory, lanOk, guard, onScan = { requestScan(LanHit.Kind.GACHA) })
            }

            // r31：服务器连不上 / 地址填错时，任务可能一直挂在「生成中」。
            // 这一项把本地所有未完成任务一次性收掉，不必等服务器回应。
            Spacer(Modifier.height(20.dp))
            CardGroup {
                GroupedItem(
                    index = 0,
                    count = 1,
                    onClick = { forceCancelAsk = true },
                    headline = { Text(stringResource(R.string.action_force_cancel_all), color = GlassDanger) },
                )
            }

            Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
        }
    }

    confirmDelete?.let { url ->
        GlassAlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_server_title)) },
            text = { Text(stringResource(R.string.delete_server_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteServer(url)
                        confirmDelete = null
                    },
                ) { Text(stringResource(R.string.action_delete), color = GlassDanger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (forceCancelAsk) {
        val toaster = LocalToaster.current
        val ctx = LocalContext.current
        GlassAlertDialog(
            onDismissRequest = { forceCancelAsk = false },
            title = { Text(stringResource(R.string.action_force_cancel_all)) },
            text = { Text(stringResource(R.string.force_cancel_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // r44：本地全部收掉之外，连当前服务器的队列一起清（含电脑网页端排的任务）；
                        // 服务器连不上也能立刻恢复本地，结果用一句话提示
                        app.container.engine.forceCancelAll(clearServerQueue = true) { outcome ->
                            val msg = when (outcome) {
                                ForceClearOutcome.CLEARED -> ctx.getString(R.string.force_clear_done)
                                ForceClearOutcome.SERVER_UNREACHABLE -> ctx.getString(R.string.force_clear_unreachable)
                                ForceClearOutcome.SERVER_STILL_BUSY -> ctx.getString(R.string.force_clear_still_busy)
                            }
                            notify(toaster, ctx, msg)
                        }
                        forceCancelAsk = false
                    },
                ) { Text(stringResource(R.string.action_ok), color = GlassDanger) }
            },
            dismissButton = {
                TextButton(onClick = { forceCancelAsk = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // r39：局域网扫描弹窗（状态与扫描协程都在 AppSettingsModel）；r41：没扫到时给「手动填地址」出口
    vm.scan.value?.let { st ->
        LanScanDialog(
            state = st,
            onCancel = { vm.cancelScan() },
            onPick = { vm.pickScanHit(it) },
            onManual = {
                val kind = st.kind
                vm.cancelScan()
                vm.manualEdit.value = kind
            },
        )
    }

    // r41：扫描前清单弹窗（引导页 / 抽卡空状态都汇入 requestScan，只在这一处弹，不会弹两次）
    checklistAsk?.let { kind ->
        var dontAsk by remember { mutableStateOf(false) }
        GlassAlertDialog(
            onDismissRequest = { checklistAsk = null },
            title = { Text(stringResource(R.string.scan_checklist_title)) },
            text = {
                Column {
                    CheckLine(stringResource(R.string.scan_check_1))
                    CheckLine(stringResource(R.string.scan_check_2))
                    CheckLine(stringResource(R.string.scan_check_3))
                    CheckLine(stringResource(R.string.scan_check_4))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontAsk = !dontAsk },
                    ) {
                        Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it }, colors = glassCheckboxColors())
                        Text(stringResource(R.string.scan_check_off), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    checklistAsk = null
                    scope.launch {
                        // 取消不写设置；点开始才落「不再提示」
                        if (dontAsk) app.container.settings.setScanChecklistOff(true)
                        guard.ensure(null, runEvenIfDenied = false) { vm.startScan(kind) }
                    }
                }) { Text(stringResource(R.string.scan_check_start)) }
            },
            dismissButton = {
                TextButton(onClick = { checklistAsk = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // r41：局域网权限的说明弹窗与被拒弹窗（页面末尾放一次）
    guard.Dialogs()
}

@Composable
private fun GachaServerCard(
    vm: AppSettingsModel,
    history: List<String>,
    lanOk: Boolean,
    guard: LocalNetworkGuard,
    onScan: () -> Unit,
) {
    var editing by remember { mutableStateOf<Boolean?>(null) }
    // r41：扫描弹窗点「手动填地址」→ 抽卡卡片展开编辑（消费后清空）
    androidx.compose.runtime.LaunchedEffect(vm.manualEdit.value) {
        if (vm.manualEdit.value == LanHit.Kind.GACHA) {
            editing = true
            vm.manualEdit.value = null
        }
    }
    val expanded = editing ?: vm.gachaUrl.value.isBlank()
    val status = if (vm.gachaLatency.value >= 0) {
        stringResource(R.string.gacha_latency, vm.gachaLatency.value.toInt())
    } else {
        null
    }
    GlassCard {
        if (!expanded) {
            ServerSummaryRow(
                title = hostPort(vm.gachaUrl.value).ifBlank { stringResource(R.string.gacha_url) },
                status = status,
                entries = history.map { hostPort(it).ifBlank { it } to it },
                emptyText = stringResource(R.string.gacha_history_empty),
                testing = vm.gachaBusy.value,
                onTest = { guard.ensure(vm.gachaUrl.value, runEvenIfDenied = true) { vm.testGacha { editing = false } } },
                onEdit = { editing = true },
                onPick = { picked ->
                    vm.pickGachaHistory(picked)
                    editing = false
                },
                onDelete = { vm.deleteGachaHistory(it) },
                menuDescription = stringResource(R.string.action_pick_gacha),
            )
            if (vm.gachaPing.value.isNotBlank() && vm.gachaPing.value != status) {
                Text(vm.gachaPing.value, color = ImmersiveInkMuted, fontSize = 13.sp)
            }
        } else {
            Text(stringResource(R.string.gacha_url), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            AddressMenuField(
                value = vm.gachaUrl.value,
                onValueChange = { vm.editGachaUrl(it) },
                placeholder = stringResource(R.string.gacha_url_hint),
                menuDescription = stringResource(R.string.action_pick_gacha),
                entries = history.map { hostPort(it).ifBlank { it } to it },
                emptyText = stringResource(R.string.gacha_history_empty),
                onPick = { picked ->
                    vm.pickGachaHistory(picked)
                    editing = false
                },
                onDelete = { vm.deleteGachaHistory(it) },
                onFocusGained = { editing = true },
                onFocusLost = { vm.normalizeGachaField() },
            )
            Spacer(Modifier.height(12.dp))
            AutoFindRow(lanOk) { onScan() }
            Spacer(Modifier.height(4.dp))
            val testing = stringResource(R.string.settings_testing)
            val testLabel = stringResource(R.string.settings_test)
            PrimaryButton(if (vm.gachaBusy.value) testing else testLabel, enabled = !vm.gachaBusy.value) {
                guard.ensure(vm.gachaUrl.value, runEvenIfDenied = true) { vm.testGacha { editing = false } }
            }
            if (vm.gachaPing.value.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(vm.gachaPing.value, color = ImmersiveInkMuted, fontSize = 13.sp)
            }
        }
    }
}

/** r41：被系统局域网拦截（LOCAL_NET_BLOCKED）时，错误旁给「去系统设置」出口。 */
@Composable
private fun LanBlockedRow(vm: AppSettingsModel) {
    if (vm.pingKind.value != com.mie.kreaworkbench.data.api.ConnErrorKind.LOCAL_NET_BLOCKED) return
    val context = LocalContext.current
    TextButton(
        onClick = { com.mie.kreaworkbench.ui.components.openAppSettings(context) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInk),
    ) {
        Text(stringResource(R.string.lan_perm_open_settings), maxLines = 1)
    }
}

/** r41：测试失败 / WS 警告时给「详细信息」（可展开、可选中、可复制），给用户截图或复制给开发者。 */
@Composable
private fun PingDetailBlock(vm: AppSettingsModel) {
    val detail = vm.pingDetail.value
    if (detail.isBlank()) return
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInkMuted),
        ) {
            Text(stringResource(R.string.ping_detail_show), maxLines = 1)
        }
        if (open) {
            TextButton(
                onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    runCatching { cm?.setPrimaryClip(android.content.ClipData.newPlainText("detail", detail)) }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInkMuted),
            ) {
                Text(stringResource(R.string.ping_detail_copy), maxLines = 1)
            }
        }
    }
    if (open) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(detail, fontSize = 11.sp, color = ImmersiveInkMuted)
        }
    }
}

private suspend fun currentWorkflowIsTextToImage(app: KreaApp, workflowId: String): Boolean {
    if (workflowId.isBlank()) return false
    val def = try {
        app.container.workflowStore.definition(workflowId)
    } catch (_: Exception) {
        null
    } ?: return false
    val specs = buildList {
        val arr = def.optJSONArray("user_facing_inputs") ?: return@buildList
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
    }
    return isTextToImageWorkflow(outputKindOf(def), specs)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackgroundPage(onBack: () -> Unit, vm: AppSettingsModel = viewModel()) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    // 忽略电池优化状态在从系统页返回后刷新
    var ignoring by remember { mutableStateOf(BatteryKeep.isIgnoring(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoring = BatteryKeep.isIgnoring(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    SettingsPageScaffold(title = stringResource(R.string.settings_battery), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // 点开后的简短说明（round13 5.1）：不再罗列前台服务/WakeLock 等技术词
            Text(
                stringResource(R.string.settings_battery_desc),
                fontSize = 13.sp,
                color = ImmersiveInkMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            CardGroup {
                GroupedItem(
                    index = 0,
                    count = 1,
                    onClick = { BatteryKeep.requestIgnore(context) },
                    headline = { Text(stringResource(R.string.action_battery_ignore)) },
                    supporting = {
                        Text(
                            stringResource(if (ignoring) R.string.battery_status_on else R.string.battery_status_off),
                            color = ImmersiveInkMuted,
                        )
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            CardGroup(stringResource(R.string.settings_notifications)) {
                GroupedItem(
                    index = 0,
                    count = 1,
                    onClick = { vm.notify(!s.notifyEnabled) },
                    headline = { Text(stringResource(R.string.settings_notify_done)) },
                    trailing = {
                        Switch(
                            checked = s.notifyEnabled,
                            onCheckedChange = { vm.notify(it) },
                            colors = glassSwitchColors(),
                        )
                    },
                )
            }
            Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppearancePage(onBack: () -> Unit, vm: AppSettingsModel = viewModel()) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    var pendingLang by remember { mutableStateOf<String?>(null) }
    val kind = resolveThemeKind(s.uiStyle)
    val rows = appearanceRows(kind, Build.VERSION.SDK_INT)
    SettingsPageScaffold(title = stringResource(R.string.group_look), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            CardGroup {
                rows.forEachIndexed { index, row ->
                    when (row) {
                        AppearanceRow.STYLE -> GroupedItem(
                            index = index,
                            count = rows.size,
                            onClick = null,
                            headline = { Text(stringResource(R.string.settings_style)) },
                            supporting = {
                                Row {
                                    listOf(
                                        ThemeKind.GLASS to stringResource(R.string.style_glass),
                                        ThemeKind.CLASSIC to stringResource(R.string.style_classic),
                                    ).forEach { (id, label) ->
                                        FilterChip(
                                            selected = kind == id,
                                            onClick = { vm.uiStyle(id) },
                                            label = { Text(label) },
                                            modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                                            colors = glassChipColors(),
                                        )
                                    }
                                }
                            },
                        )
                        AppearanceRow.THEME -> GroupedItem(
                            index = index,
                            count = rows.size,
                            onClick = null,
                            headline = { Text(stringResource(R.string.settings_theme)) },
                            supporting = {
                                Row {
                                    listOf(
                                        "light" to stringResource(R.string.theme_light),
                                        "dark" to stringResource(R.string.theme_dark),
                                        "system" to stringResource(R.string.follow_system),
                                    ).forEach { (id, label) ->
                                        FilterChip(
                                            selected = s.themeMode == id,
                                            onClick = { vm.theme(id) },
                                            label = { Text(label) },
                                            modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                                            colors = glassChipColors(),
                                        )
                                    }
                                }
                            },
                        )
                        AppearanceRow.LANGUAGE -> GroupedItem(
                            index = index,
                            count = rows.size,
                            onClick = null,
                            headline = { Text(stringResource(R.string.settings_language)) },
                            supporting = {
                                val tags = AppLocale.languageTagOrNull(context)
                                    ?: AppCompatDelegate.getApplicationLocales().toLanguageTags()
                                val selected = when {
                                    tags.startsWith("zh") -> "zh"
                                    tags.startsWith("en") -> "en"
                                    else -> "system"
                                }
                                Row {
                                    listOf(
                                        "system" to stringResource(R.string.follow_system),
                                        "zh" to "中文",
                                        "en" to "English",
                                    ).forEach { (id, label) ->
                                        FilterChip(
                                            selected = selected == id,
                                            onClick = { if (id != selected) pendingLang = id },
                                            label = { Text(label) },
                                            modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                                            colors = glassChipColors(),
                                        )
                                    }
                                }
                            },
                        )
                        AppearanceRow.DYNAMIC -> GroupedItem(
                            index = index,
                            count = rows.size,
                            onClick = { vm.dynamicColor(!s.dynamicColor) },
                            headline = { Text(stringResource(R.string.settings_dynamic)) },
                            supporting = { Text(stringResource(R.string.settings_dynamic_desc)) },
                            trailing = {
                                Switch(
                                    checked = s.dynamicColor,
                                    onCheckedChange = { vm.dynamicColor(it) },
                                    colors = glassSwitchColors(),
                                )
                            },
                        )
                        AppearanceRow.AMOLED -> GroupedItem(
                            index = index,
                            count = rows.size,
                            onClick = { vm.amoled(!s.amoled) },
                            headline = { Text(stringResource(R.string.settings_amoled)) },
                            supporting = { Text(stringResource(R.string.settings_amoled_desc)) },
                            trailing = {
                                Switch(
                                    checked = s.amoled,
                                    onCheckedChange = { vm.amoled(it) },
                                    colors = glassSwitchColors(),
                                )
                            },
                        )
                        AppearanceRow.BLUR -> GroupedItem(
                            index = index,
                            count = rows.size,
                            onClick = { vm.blur(!s.blurEnabled) },
                            headline = { Text(stringResource(R.string.settings_blur)) },
                            supporting = { Text(stringResource(R.string.settings_blur_desc)) },
                            trailing = {
                                Switch(
                                    checked = s.blurEnabled,
                                    onCheckedChange = { vm.blur(it) },
                                    colors = glassSwitchColors(),
                                )
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
        }
    }
    val chosen = pendingLang
    if (chosen != null) {
        val active = (context.applicationContext as KreaApp).container.engine.hasActive()
        val base = stringResource(R.string.language_restart_body)
        val tasks = stringResource(R.string.language_restart_tasks)
        val body = if (active) "$base\n$tasks" else base
        GlassAlertDialog(
            onDismissRequest = { pendingLang = null },
            title = { Text(stringResource(R.string.language_restart_title)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    AppLocale.persist(context, chosen)
                    AppLocale.restart(context)
                }) { Text(stringResource(R.string.language_restart_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLang = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * 地址栏。右侧箭头弹出已保存列表，点一项就切换，行尾可以删。
 * 空着时显示灰色占位。输入过程不落库不校验：只有测试连接成功才由调用方保存。
 */
@Composable
private fun AddressMenuField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    menuDescription: String,
    entries: List<Pair<String, String>>,
    emptyText: String,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onFocusLost: () -> Unit,
    onFocusGained: () -> Unit = {},
) {
    // onFocusChanged 在节点挂上时也会发一次「未聚焦」事件——只有真获得过焦点再失去才算失焦
    var sawFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (it.isFocused) {
                sawFocus = true
                onFocusGained()
            } else if (sawFocus) {
                sawFocus = false
                onFocusLost()
            }
        },
        singleLine = true,
        placeholder = { Text(placeholder, color = ImmersiveInkMuted) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        colors = glassTextFieldColors(),
        trailingIcon = {
            HistoryMenuButton(menuDescription, entries, emptyText, onPick, onDelete)
        },
    )
}

/** 收起后：第一行名称，第二行版本和延迟铺满。右侧只有测试连接和下拉。长按整行进入编辑。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerSummaryRow(
    title: String,
    status: String?,
    entries: List<Pair<String, String>>,
    emptyText: String,
    testing: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    menuDescription: String = "",
) {
    val menuLabel = menuDescription.ifBlank { stringResource(R.string.action_pick_server) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = {}, onLongClick = onEdit),
            )
            TextButton(
                onClick = onTest,
                enabled = !testing,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInk),
            ) {
                Text(
                    stringResource(if (testing) R.string.settings_testing else R.string.settings_test),
                    maxLines = 1,
                )
            }
            HistoryMenuButton(
                description = menuLabel,
                entries = entries,
                emptyText = emptyText,
                onPick = onPick,
                onDelete = onDelete,
            )
        }
        if (!status.isNullOrBlank()) {
            Text(
                status,
                fontSize = 12.sp,
                color = ImmersiveInkMuted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onEdit),
            )
        }
    }
}

/** 地址只留 host:port（去掉 scheme、路径与 user@）。 */
internal fun hostPort(url: String): String =
    url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')

/** r39：「自动查找」入口行。不在 Wi-Fi/以太网时按钮置灰并提示。 */
@Composable
private fun AutoFindRow(lanOk: Boolean, onStart: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onStart,
            enabled = lanOk,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInk),
        ) {
            Text(stringResource(R.string.scan_action), maxLines = 1)
        }
        if (!lanOk) {
            Text(stringResource(R.string.scan_need_wifi), fontSize = 12.sp, color = ImmersiveInkMuted)
        }
    }
}

/** r39：局域网扫描弹窗。进度实时更新、结果实时追加；点一行只填输入框（不自动测试、不保存）。
 *  r41：扫完没结果时改为排查要点列表，右下角给「手动填地址」。 */
@Composable
private fun LanScanDialog(
    state: AppSettingsModel.ScanUi,
    onCancel: () -> Unit,
    onPick: (LanHit) -> Unit,
    onManual: () -> Unit,
) {
    val noHits = state.finished && state.hits.isEmpty()
    GlassAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.scan_action)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (!state.finished) {
                    Text(
                        stringResource(R.string.scan_progress, state.selfIp, state.subnet, state.done, state.total),
                        fontSize = 13.sp,
                    )
                    if (state.viaFallback && state.vpnActive) {
                        Text(
                            stringResource(R.string.scan_vpn_hint),
                            fontSize = 12.sp,
                            color = ImmersiveInkMuted,
                        )
                    }
                } else if (state.hits.isEmpty()) {
                    ScanEmptyPoints(vpnActive = state.vpnActive)
                }
                state.hits.forEach { hit ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(hit) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                hostPort(hit.url),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = ImmersiveInk,
                            )
                            if (hit.detail.isNotBlank()) {
                                Text(
                                    hit.detail,
                                    fontSize = 12.sp,
                                    color = ImmersiveInkMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (noHits) {
                TextButton(onClick = onManual) { Text(stringResource(R.string.scan_fill_manually)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        dismissButton = {
            if (noHits) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.scan_close)) }
            }
        },
    )
}

/** r41：没扫到时的短要点。VPN 开着时把 VPN 那条挪到最前并加粗（带「检测到 VPN 开着」）。 */
@Composable
private fun ScanEmptyPoints(vpnActive: Boolean) {
    Column {
        Text(stringResource(R.string.scan_empty_lead), fontSize = 13.sp)
        if (vpnActive) {
            CheckLine(stringResource(R.string.scan_check_vpn_on), bold = true)
        }
        CheckLine(stringResource(R.string.scan_check_1))
        CheckLine(stringResource(R.string.scan_check_2))
        if (!vpnActive) {
            CheckLine(stringResource(R.string.scan_check_3))
        }
        CheckLine(stringResource(R.string.scan_check_4))
        Text(stringResource(R.string.scan_empty_tail), fontSize = 13.sp)
        Text(
            stringResource(R.string.scan_empty_extra),
            fontSize = 12.sp,
            color = ImmersiveInkMuted,
        )
    }
}

/** r41：清单/要点的一行：圆点 + 一句话。 */
@Composable
private fun CheckLine(text: String, bold: Boolean = false) {
    Row {
        Text("•", fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun HistoryMenuButton(
    description: String,
    entries: List<Pair<String, String>>,
    emptyText: String,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Lucide.ChevronDown, description)
        }
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (entries.isEmpty()) {
                GlassMenuItem(
                    text = { Text(emptyText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { open = false },
                    enabled = false,
                )
            }
            entries.forEach { (label, id) ->
                GlassMenuItem(
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        IconButton(onClick = {
                            open = false
                            onDelete(id)
                        }) {
                            Icon(Lucide.Trash2, stringResource(R.string.action_delete), tint = GlassDanger)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(id)
                    },
                )
            }
        }
    }
}
