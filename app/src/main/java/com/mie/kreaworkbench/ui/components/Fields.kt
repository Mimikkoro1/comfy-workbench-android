package com.mie.kreaworkbench.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuField(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = scheme.onSurfaceVariant) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            trailingIcon = { Icon(Lucide.ChevronDown, null, tint = scheme.onSurfaceVariant) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        ChoiceSheet(label, options, options.indexOf(value), onDismiss = { open = false }) { index ->
            open = false
            onPick(index)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactMenuField(
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(scheme.surfaceBright)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), shape)
            .clickable(enabled = options.isNotEmpty()) { open = true }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Lucide.ChevronDown, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
    if (open) {
        ChoiceSheet(stringResource(R.string.action_choose), options, options.indexOf(value), onDismiss = { open = false }) { index ->
            open = false
            onPick(index)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 长列表先半屏展开，可上拉到接近全屏；短列表内容多高就多高（不再硬卡 480dp）
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            itemsIndexed(options, key = { index, item -> "$index-$item" }) { index, item ->
                ListItem(
                    headlineContent = {
                        Text(item, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = if (index == selected) {
                        { Icon(Lucide.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable { onPick(index) },
                )
            }
        }
    }
}

/**
 * 与 CompactMenuField 同款外观的单行输入框（40dp 高、12dp 圆角、surfaceBright 底 + 细描边、labelMedium），
 * 设置页 int/float 及选项拉取失败时的退化输入框用，和下拉框视觉对齐。
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.height(40.dp).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(scheme.surfaceBright)
                    .border(
                        1.dp,
                        if (focused) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f),
                        shape,
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                inner()
            }
        },
    )
}

@Composable
fun MiniField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        modifier = modifier.height(40.dp),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(scheme.surface)
                    .border(1.dp, scheme.outline, shape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                inner()
            }
        },
    )
}

@Composable
fun ModelLine(path: String) {
    val file = path.substringAfterLast('\\').substringAfterLast('/')
    val folder = path.removeSuffix(file).trim('\\', '/')
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            file,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (folder.isNotBlank()) {
            Text(
                folder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1) return String.format("%.2f GB", gb)
    val mb = bytes / 1024.0 / 1024.0
    return String.format("%.1f MB", mb)
}
