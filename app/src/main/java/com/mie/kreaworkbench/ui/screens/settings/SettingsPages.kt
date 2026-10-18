package com.mie.kreaworkbench.ui.screens.settings

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
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
import com.mie.kreaworkbench.ui.components.PrimaryButton
import com.mie.kreaworkbench.ui.components.LargeBarScaffold
import com.mie.kreaworkbench.ui.components.glassChipColors
import com.mie.kreaworkbench.ui.components.glassSwitchColors
import com.mie.kreaworkbench.ui.components.glassTextFieldColors
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.theme.LocalIsDark
import com.mie.kreaworkbench.util.BatteryKeep
import kotlinx.coroutines.Dispatchers
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
    if (LocalIsDark.current) Color.Black.copy(alpha = 0.48f) else Color.Black.copy(alpha = 0.52f)

/** 设置二级页共用外壳：薄纱 + 透明沉浸顶栏 + 玻璃语境（列表/分组标题走白字）。 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(SettingsPageVeil()))
        CompositionLocalProvider(LocalGlassChrome provides true) {
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
fun ConnectionPage(onBack: () -> Unit, vm: AppSettingsModel = viewModel()) {
    val s by vm.settings.collectAsState()
    val app = LocalContext.current.applicationContext as KreaApp
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
                        onTest = { vm.test(onSuccess = { editing = false }) },
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
                } else {
                Text(stringResource(R.string.settings_current_server), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AddressMenuField(
                    value = vm.url.value,
                    onValueChange = { vm.url.value = it },
                    placeholder = stringResource(R.string.server_url_hint),
                    menuDescription = stringResource(R.string.action_pick_server),
                    entries = s.serverHistory.map { (it.name.ifBlank { it.url }) to it.url },
                    emptyText = stringResource(R.string.saved_servers_empty),
                    onPick = { picked ->
                        s.serverHistory.firstOrNull { it.url == picked }?.let { vm.applyHistory(it) }
                        editing = false
                    },
                    onDelete = { confirmDelete = it },
                    onFocusLost = { vm.save() },
                    debounceKey = listOf(vm.url.value, vm.user.value, vm.pass.value),
                    onDebounce = { vm.save() },
                )
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
                            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) vm.save() },
                            label = { Text(stringResource(R.string.field_user), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            colors = glassTextFieldColors(),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = vm.pass.value,
                            onValueChange = { vm.pass.value = it },
                            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) vm.save() },
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
                    vm.test(onSuccess = { editing = false })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.saveServer { editing = false } },
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
                }
            }

            if (showGacha) {
                Spacer(Modifier.height(20.dp))
                GachaServerCard(vm, s.advGachaHistory)
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
        GlassAlertDialog(
            onDismissRequest = { forceCancelAsk = false },
            title = { Text(stringResource(R.string.action_force_cancel_all)) },
            text = { Text(stringResource(R.string.force_cancel_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 全在本地收尾：DB / 界面落 cancelled、协程停掉；服务器端尽力撤销
                        app.container.engine.forceCancelAll()
                        forceCancelAsk = false
                    },
                ) { Text(stringResource(R.string.action_ok), color = GlassDanger) }
            },
            dismissButton = {
                TextButton(onClick = { forceCancelAsk = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun GachaServerCard(vm: AppSettingsModel, history: List<String>) {
    var editing by remember { mutableStateOf<Boolean?>(null) }
    val expanded = editing ?: vm.gachaUrl.value.isBlank()
    var touched by remember { mutableStateOf(false) }
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
                onTest = { vm.testGacha { editing = false } },
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
                onFocusGained = { touched = true },
                onFocusLost = { if (touched) vm.commitGachaUrl() },
            )
            Spacer(Modifier.height(12.dp))
            val testing = stringResource(R.string.settings_testing)
            val testLabel = stringResource(R.string.settings_test)
            PrimaryButton(if (vm.gachaBusy.value) testing else testLabel, enabled = !vm.gachaBusy.value) {
                vm.testGacha { editing = false }
            }
            if (vm.gachaPing.value.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(vm.gachaPing.value, color = ImmersiveInkMuted, fontSize = 13.sp)
            }
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
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
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
    val dynamic = Build.VERSION.SDK_INT >= 31
    val count = if (dynamic) 5 else 4
    val amoledIndex = if (dynamic) 3 else 2
    val blurIndex = amoledIndex + 1
    SettingsPageScaffold(title = stringResource(R.string.group_look), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            CardGroup {
                GroupedItem(
                    index = 0,
                    count = count,
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
                GroupedItem(
                    index = 1,
                    count = count,
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
                if (dynamic) {
                    GroupedItem(
                        index = 2,
                        count = count,
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
                }
                GroupedItem(
                    index = amoledIndex,
                    count = count,
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
                GroupedItem(
                    index = blurIndex,
                    count = count,
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
 * 空着时显示灰色占位。debounceKey 非空时，值停 500ms 再落库。
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
    debounceKey: Any? = null,
    onDebounce: (() -> Unit)? = null,
) {
    if (onDebounce != null) {
        androidx.compose.runtime.LaunchedEffect(debounceKey) {
            kotlinx.coroutines.delay(500)
            onDebounce()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (it.isFocused) onFocusGained() else onFocusLost()
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
