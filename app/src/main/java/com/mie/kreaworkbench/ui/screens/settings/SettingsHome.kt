package com.mie.kreaworkbench.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.BuildConfig
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.components.formatBytes
import com.mie.kreaworkbench.ui.locale.AppLocale
import com.mie.kreaworkbench.ui.components.CacheManagementSheet
import com.mie.kreaworkbench.ui.components.CardGroup
import com.mie.kreaworkbench.ui.components.GroupedItem
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.nav.NavModel
import com.mie.kreaworkbench.ui.nav.Overlay
import com.mie.kreaworkbench.util.BatteryKeep

/**
 * 设置 tab 主页（round13 5.2）：分组入口列表，条目进二级页——
 * ComfyUI 服务器（地址/凭据/测试连接/最近地址）、后台运行、外观与语言、存储与缓存、关于；
 * 每个入口副标题只写一行当前状态（r13fix 第 7 项），不写说明。原分组：
 * 连接（地址/凭据/测试连接/最近地址）、后台运行（5.1 合并后的一行入口，副标题只写状态）、
 * 外观与语言、存储与缓存（现成缓存管理弹层）、关于。各条目内容自旧首页原样搬入
 * SettingsPages.kt / CacheManagementSheet，未新增设置项。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsHome(onAbout: () -> Unit, vm: AppSettingsModel = viewModel(), nav: NavModel = viewModel()) {
    val context = LocalContext.current
    var showCache by remember { mutableStateOf(false) }
    val s by vm.settings.collectAsState()

    // 首页入口的「后台运行」副标题状态：从系统设置页返回后刷新
    var ignoring by remember { mutableStateOf(BatteryKeep.isIgnoring(context)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
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

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.title_settings))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            CardGroup {
                GroupedItem(
                    index = 0,
                    count = 5,
                    onClick = { nav.push(Overlay.SettingsConnection) },
                    headline = { Text(stringResource(R.string.group_connection)) },
                    supporting = { StatusText(serverStatus) },
                    trailing = { Chevron() },
                )
                GroupedItem(
                    index = 1,
                    count = 5,
                    onClick = { nav.push(Overlay.SettingsBackground) },
                    headline = { Text(stringResource(R.string.settings_battery)) },
                    supporting = {
                        Text(
                            stringResource(if (ignoring) R.string.battery_status_on else R.string.battery_status_off),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailing = { Chevron() },
                )
                GroupedItem(
                    index = 2,
                    count = 5,
                    onClick = { nav.push(Overlay.SettingsAppearance) },
                    headline = { Text(stringResource(R.string.group_look)) },
                    supporting = { StatusText("$themeLabel · $langLabel") },
                    trailing = { Chevron() },
                )
                GroupedItem(
                    index = 3,
                    count = 5,
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
                    count = 5,
                    onClick = onAbout,
                    headline = { Text(stringResource(R.string.title_about)) },
                    supporting = { StatusText("v${BuildConfig.VERSION_NAME}") },
                    trailing = { Chevron() },
                )
            }
            Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
        }
    }

    if (showCache) {
        CacheManagementSheet(onDismiss = { showCache = false })
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** 地址只留 host:port（去掉 scheme、路径与 user@）。 */
private fun hostPort(url: String): String =
    url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')

@Composable
private fun Chevron() {
    Icon(Lucide.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
