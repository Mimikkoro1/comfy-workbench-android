package com.mie.kreaworkbench.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.theme.isClassic
import com.mie.kreaworkbench.ui.components.formatBytes
import com.mie.kreaworkbench.ui.locale.AppLocale
import com.mie.kreaworkbench.ui.components.CacheManagementSheet
import com.mie.kreaworkbench.ui.components.CardGroup
import com.mie.kreaworkbench.ui.components.GlassDanger
import com.mie.kreaworkbench.ui.components.GroupedItem
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveInkMuted
import com.mie.kreaworkbench.ui.components.LocalGlassChrome
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.nav.NavModel
import com.mie.kreaworkbench.ui.nav.Overlay
import com.mie.kreaworkbench.util.BatteryKeep

/** r43：连接状态点的绿。只在本文件用，不写进 Glass* 组件（毛玻璃配色锁定）。 */
private val StatusGreen = Color(0xFF4CAF50)

/**
 * 设置 tab 主页（round13 5.2）：分组入口列表，条目进二级页——
 * ComfyUI 服务器（地址/凭据/测试连接/最近地址）、后台运行、外观与语言、存储与缓存、关于；
 * 每个入口副标题只写一行当前状态（r13fix 第 7 项），不写说明。
 * r38：快速开始与「工作流与模板」两个一级入口移除——前者在服务器未设置时降级为行下小字入口，
 * 也可从关于页复看；后者整个二级页删除（内置模板管理并入库页）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsHome(onAbout: () -> Unit, vm: AppSettingsModel = viewModel(), nav: NavModel = viewModel()) {
    val context = LocalContext.current
    var showCache by remember { mutableStateOf(false) }
    val s by vm.settings.collectAsState()

    // 首页入口的「后台运行」副标题状态：从系统设置页返回后刷新
    var ignoring by remember { mutableStateOf(BatteryKeep.isIgnoring(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoring = BatteryKeep.isIgnoring(context)
                vm.refreshUsage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 各入口的一行状态
    val notSet = stringResource(R.string.status_not_set)
    val serverStatus = hostPort(s.serverUrl).ifBlank { notSet }
    val themeLabel = when (s.themeMode) {
        "light" -> stringResource(R.string.theme_light)
        "dark" -> stringResource(R.string.theme_dark)
        else -> stringResource(R.string.follow_system)
    }
    val tags = AppLocale.languageTagOrNull(context)
        ?: AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val langLabel = when {
        tags.startsWith("zh") -> "中文"
        tags.startsWith("en") -> "English"
        else -> stringResource(R.string.follow_system)
    }
    val cacheStatus = if (vm.usageLoaded.value) stringResource(R.string.status_cache, formatBytes(vm.usage.value)) else ""

    // r29：整页进玻璃语境——分组入口画成半透明玻璃条、标题/副文/箭头走白字系。
    CompositionLocalProvider(LocalGlassChrome provides !isClassic()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.title_settings), ink = ImmersiveInk)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                CardGroup {
                    GroupedItem(
                        index = 0,
                        count = 6,
                        onClick = { nav.push(Overlay.SettingsConnection) },
                        headline = { Text(stringResource(R.string.group_connection)) },
                        // r43：地址左侧加连接状态点（绿=最近一次测试成功 / 红=失败 / 灰=未测试），只读内存状态
                        supporting = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ServerStatusDot(vm)
                                Spacer(Modifier.width(6.dp))
                                StatusText(serverStatus)
                            }
                        },
                        trailing = { Chevron() },
                    )
                    // r38：服务器未设置时（与该行显示「未设置」同一判定），行下小字快速开始入口；配好后消失。
                    if (s.serverUrl.isBlank()) {
                        Text(
                            stringResource(R.string.settings_quick_start_hint),
                            fontSize = 13.sp,
                            color = ImmersiveInkMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = nav::openOnboarding)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    GroupedItem(
                        index = 1,
                        count = 6,
                        onClick = { nav.push(Overlay.SettingsBackground) },
                        headline = { Text(stringResource(R.string.settings_battery)) },
                        supporting = {
                            Text(
                                stringResource(if (ignoring) R.string.battery_status_on else R.string.battery_status_off),
                                color = ImmersiveInkMuted,
                            )
                        },
                        trailing = { Chevron() },
                    )
                    GroupedItem(
                        index = 2,
                        count = 6,
                        onClick = { nav.push(Overlay.SettingsAppearance) },
                        headline = { Text(stringResource(R.string.group_look)) },
                        supporting = { StatusText("$themeLabel · $langLabel") },
                        trailing = { Chevron() },
                    )
                    GroupedItem(
                        index = 3,
                        count = 6,
                        onClick = { showCache = true },
                        headline = { Text(stringResource(R.string.group_storage)) },
                        supporting = if (cacheStatus.isNotEmpty()) {
                            { StatusText(cacheStatus) }
                        } else {
                            null
                        },
                        trailing = { Chevron() },
                    )
                    GroupedItem(
                        index = 4,
                        count = 6,
                        onClick = { nav.push(Overlay.SettingsFeedback) },
                        headline = { Text(stringResource(R.string.settings_feedback)) },
                        supporting = { StatusText(stringResource(R.string.settings_feedback_sub)) },
                        trailing = { Chevron() },
                    )
                    GroupedItem(
                        index = 5,
                        count = 6,
                        onClick = onAbout,
                        headline = { Text(stringResource(R.string.title_about)) },
                        supporting = { StatusText("v${BuildConfig.VERSION_NAME}") },
                        trailing = { Chevron() },
                    )
                }
                Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
            }
        }
    }

    if (showCache) {
        CacheManagementSheet(onDismiss = { showCache = false })
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, color = ImmersiveInkMuted, maxLines = 1)
}

/** r43：服务器行的连接状态点。只读会话内的 connOk/busy，不发任何网络请求；冷启动一律灰。 */
@Composable
private fun ServerStatusDot(vm: AppSettingsModel) {
    val dot = connDotState(vm.connOk.value, vm.busy.value)
    val color = when (dot) {
        ConnDot.OK -> StatusGreen
        ConnDot.FAILED -> GlassDanger
        ConnDot.UNKNOWN -> ImmersiveInkMuted
    }
    val desc = stringResource(
        when (dot) {
            ConnDot.OK -> R.string.status_connected
            ConnDot.FAILED -> R.string.status_failed
            ConnDot.UNKNOWN -> R.string.status_unknown
        },
    )
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = desc },
    )
}

@Composable
private fun Chevron() {
    Icon(Lucide.ChevronRight, contentDescription = null, tint = ImmersiveInkMuted)
}
