@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mie.kreaworkbench.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.library.LibraryMeta

/**
 * 「存入提示词库」选择弹窗（round6）：点已有库直接写入；或新建库（默认名「反推」）。
 * onSave(libId=null, newName) = 新建并写入；onSave(libId, "") = 写入已有库。异步与提示由调用方处理。
 */
@Composable
fun SaveToLibrarySheet(
    libraries: List<LibraryMeta>,
    onDismiss: () -> Unit,
    onSave: (libId: String?, newLibName: String) -> Unit,
) {
    val defaultName = stringResource(R.string.default_library_name)
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(defaultName) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.save_library_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.save_library_body),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (creating) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(24) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.new_library_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton(onClick = { creating = false }) { Text(stringResource(R.string.action_back)) }
                    TextButton(
                        onClick = {
                            val name = newName.trim().ifBlank { defaultName }
                            onDismiss()
                            onSave(null, name)
                        },
                        enabled = true,
                    ) { Text(stringResource(R.string.action_create)) }
                }
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.new_library)) }
                    libraries.forEach { lib ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onSave(lib.id, "")
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(lib.name, Modifier.weight(1f), maxLines = 1)
                            Text(
                                pluralStringResource(R.plurals.entry_count, lib.count, lib.count),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
