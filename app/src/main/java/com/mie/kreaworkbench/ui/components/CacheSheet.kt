package com.mie.kreaworkbench.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.screens.settings.AppSettingsModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CacheManagementSheet(onDismiss: () -> Unit, vm: AppSettingsModel = viewModel()) {
    val s by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshUsage() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.cache_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            val unlimited = stringResource(R.string.cache_unlimited)
            FlowRow(Modifier.padding(top = 12.dp)) {
                listOf(5 to "5GB", 10 to "10GB", 20 to "20GB", 50 to "50GB", 0 to unlimited).forEach { (gb, label) ->
                    FilterChip(
                        selected = s.cacheLimitGb == gb,
                        onClick = { vm.cache(gb) },
                        label = { Text(label, maxLines = 1, softWrap = false) },
                        modifier = Modifier.padding(end = 6.dp, top = 8.dp),
                    )
                }
            }
            val cap = if (s.cacheLimitGb <= 0) unlimited else "${s.cacheLimitGb} GB"
            Text(
                stringResource(R.string.cache_used, formatBytes(vm.usage.value), cap),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = { confirm = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.cache_clear))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.cache_clear)) },
            text = { Text(stringResource(R.string.cache_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.clear {}
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
