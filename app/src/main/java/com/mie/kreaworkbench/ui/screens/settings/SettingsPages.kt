package com.mie.kreaworkbench.ui.screens.settings

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.AppLocale
import com.mie.kreaworkbench.ui.components.CardGroup
import com.mie.kreaworkbench.ui.components.GroupedItem
import com.mie.kreaworkbench.ui.components.KreaCard
import com.mie.kreaworkbench.ui.components.PrimaryButton
import com.mie.kreaworkbench.ui.components.LargeBarScaffold
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.util.BatteryKeep
import com.mie.kreaworkbench.util.isWanAddress

/**
 * 设置页二级页（round13 5.2）：连接 / 后台运行 / 外观与语言。
 * 条目自旧 SettingsHome 原样搬入，只重组不改制：连接页 = 地址+凭据+测试连接+最近地址；
 * 后台页 = 简短说明 + 电池优化跳转（r13fix 去掉厂商专属入口）+ 出图通知；
 * 外观页 = 主题/语言/动态色/纯黑/模糊（语言切换的重启确认一并搬入）。
 */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionPage(onBack: () -> Unit, vm: AppSettingsModel = viewModel()) {
    val s by vm.settings.collectAsState()
    LargeBarScaffold(title = stringResource(R.string.group_connection), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            KreaCard {
                Text(stringResource(R.string.settings_server), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AddressField(vm, s.serverHistory)
                if (isWanAddress(vm.url.value) || vm.authNeeded.value) {
                    Spacer(Modifier.height(8.dp))
                    // r13fix3：两框都单行标签（省略号）+ 垂直居中，眼睛图标在时密码框不再因标签换行变高
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = vm.user.value,
                            onValueChange = { vm.user.value = it },
                            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) vm.save() },
                            label = { Text(stringResource(R.string.field_user), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = vm.pass.value,
                            onValueChange = { vm.pass.value = it },
                            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) vm.save() },
                            label = { Text(stringResource(R.string.field_pass), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            visualTransformation = if (vm.showPass.value) VisualTransformation.None else PasswordVisualTransformation(),
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
                }
                if (vm.authNeeded.value) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.auth_needed),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                val testing = stringResource(R.string.settings_testing)
                val testLabel = stringResource(R.string.settings_test)
                PrimaryButton(if (vm.busy.value) testing else testLabel, enabled = !vm.busy.value) {
                    vm.test()
                }
                if (vm.ping.value.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(vm.ping.value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
        }
    }
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
    LargeBarScaffold(title = stringResource(R.string.settings_battery), onBack = onBack) { padding ->
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Switch(checked = s.notifyEnabled, onCheckedChange = { vm.notify(it) })
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
    LargeBarScaffold(title = stringResource(R.string.group_look), onBack = onBack) { padding ->
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
                            Switch(checked = s.dynamicColor, onCheckedChange = { vm.dynamicColor(it) })
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
                        Switch(checked = s.amoled, onCheckedChange = { vm.amoled(it) })
                    },
                )
                GroupedItem(
                    index = blurIndex,
                    count = count,
                    onClick = { vm.blur(!s.blurEnabled) },
                    headline = { Text(stringResource(R.string.settings_blur)) },
                    supporting = { Text(stringResource(R.string.settings_blur_desc)) },
                    trailing = {
                        Switch(checked = s.blurEnabled, onCheckedChange = { vm.blur(it) })
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
        AlertDialog(
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
 * 地址输入框：500ms 防抖自动落库 + 失焦兜底；历史非空时右侧有「最近地址」下拉，
 * 点选一条回填地址和它自己的账号密码。（自旧 SettingsHome 搬入，仅连接页使用。）
 */
@Composable
private fun AddressField(vm: AppSettingsModel, history: List<com.mie.kreaworkbench.data.settings.ServerHistoryEntry>) {
    var historyOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(vm.url.value, vm.user.value, vm.pass.value) {
        kotlinx.coroutines.delay(500)
        vm.save()
    }
    OutlinedTextField(
        value = vm.url.value,
        onValueChange = { vm.url.value = it },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) vm.save() },
        singleLine = true,
        placeholder = { Text("http://192.168.1.100:8188") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        trailingIcon = {
            if (history.isNotEmpty()) {
                IconButton(onClick = { historyOpen = !historyOpen }) {
                    Icon(Lucide.History, contentDescription = stringResource(R.string.server_history))
                }
                DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                    history.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(entry.url, fontSize = 14.sp)
                                    if (entry.user.isNotBlank()) {
                                        Text(entry.user, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            onClick = {
                                historyOpen = false
                                vm.applyHistory(entry)
                            },
                        )
                    }
                }
            }
        },
    )
}
