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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.AppLocale
import com.mie.kreaworkbench.ui.components.CardGroup
import com.mie.kreaworkbench.ui.components.GroupedItem
import com.mie.kreaworkbench.ui.components.KreaCard
import com.mie.kreaworkbench.ui.components.PrimaryButton
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom

/** 设置 tab 主页面：原「App 设置」二级页平铺到这里（direct10b），功能与存储不动。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsHome(onAbout: () -> Unit, vm: AppSettingsModel = viewModel()) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    var pendingLang by remember { mutableStateOf<String?>(null) }
    val dynamic = Build.VERSION.SDK_INT >= 31
    val count = if (dynamic) 5 else 4
    val amoledIndex = if (dynamic) 3 else 2
    val blurIndex = amoledIndex + 1
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.title_settings))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            KreaCard {
                Text(stringResource(R.string.settings_server), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vm.url.value,
                    onValueChange = { vm.url.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("http://192.168.1.100:8188") },
                )
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
            Spacer(Modifier.height(16.dp))
            CardGroup(stringResource(R.string.settings_appearance)) {
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
            Spacer(Modifier.height(16.dp))
            CardGroup {
                GroupedItem(
                    index = 0,
                    count = 1,
                    onClick = onAbout,
                    headline = { Text(stringResource(R.string.title_about)) },
                    trailing = {
                        Icon(Lucide.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
