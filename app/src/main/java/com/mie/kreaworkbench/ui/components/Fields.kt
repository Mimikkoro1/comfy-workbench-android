package com.mie.kreaworkbench.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
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

/** 玻璃底上的下拉：和强度输入框（`CompactTextField` 玻璃态）同一支半透明底，绝不用实心白。
 *  改这里前先看 `GlassControls.kt` 里的 `GlassFieldFill`——两处要一起变，卡里才像同一族控件。 */
private val GlassMenuFieldFill = Color.White.copy(alpha = 0.08f)

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

/**
 * 紧凑下拉框（40dp / 12dp 圆角）。
 * 默认是主题素色实心胶囊（`surfaceBright`），生成页参数抽屉等已验收的地方保持这个样子；
 * 在 [LocalGlassFields] = true 的玻璃卡里（模型采样页、导入配置页）换成
 * 「半透明玻璃底 + Hairline 细白边 + 白字」，和强度输入框 `CompactTextField` 玻璃态同款；
 * 弹出层仍走 [ChoiceSheet]，它自己会按 [LocalGlassChrome] 决定用玻璃弹层还是 Material 弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactMenuField(
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    // r43：只给字段当前显示的值文字上色（模型缺失整段标红用）；默认 null = 原行为。
    // 只影响收起态的值文字，展开菜单里的各项颜色不动。
    // 注意：不能放到参数表末尾——现有调用点全是尾随 lambda 写法，尾随 lambda 永远绑最后一个参数。
    valueColor: Color? = null,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val glassy = LocalGlassFields.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(if (glassy) GlassMenuFieldFill else scheme.surfaceBright)
            .border(
                1.dp,
                if (glassy) ImmersiveHairline else scheme.outlineVariant.copy(alpha = 0.5f),
                shape,
            )
            .clickable(enabled = options.isNotEmpty()) { open = true }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor ?: if (glassy) ImmersiveInk else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Lucide.ChevronDown,
            null,
            tint = if (glassy) ImmersiveInkMuted else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
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
    if (LocalGlassChrome.current) {
        GlassBottomSheet(onDismissRequest = onDismiss) {
            ChoiceRows(title, options, selected, onPick, glass = true)
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            // 长列表先半屏展开，可上拉到接近全屏；短列表内容多高就多高（不再硬卡 480dp）
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            ChoiceRows(title, options, selected, onPick, glass = false)
        }
    }
}

@Composable
private fun ChoiceRows(
    title: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    glass: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = scheme.onSurface,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (glass) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEachIndexed { index, item ->
                ChoiceLine(index, item, selected, scheme, glass = true, onPick)
            }
        }
    } else {
        LazyColumn(Modifier.navigationBarsPadding()) {
            itemsIndexed(options, key = { index, item -> "$index-$item" }) { index, item ->
                ChoiceLine(index, item, selected, scheme, glass = false, onPick)
            }
        }
    }
}

@Composable
private fun ChoiceLine(
    index: Int,
    item: String,
    selected: Int,
    scheme: androidx.compose.material3.ColorScheme,
    glass: Boolean,
    onPick: (Int) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(item, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = if (index == selected) {
            { Icon(Lucide.Check, null, tint = if (glass) ImmersiveInk else scheme.primary) }
        } else {
            null
        },
        colors = if (glass) {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = ImmersiveInk,
                trailingIconColor = ImmersiveInk,
            )
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable { onPick(index) },
    )
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
    val glass = LocalGlassChrome.current
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    val textColor = if (glass) {
        if (enabled) ImmersiveInk else ImmersiveInkMuted
    } else {
        scheme.onSurface
    }
    val cursor = if (glass) Color.White else scheme.primary
    val fill = when {
        glass && !enabled -> Color.White.copy(alpha = 0.04f)
        glass -> Color.White.copy(alpha = 0.08f)
        else -> scheme.surfaceBright
    }
    val stroke = when {
        glass && !enabled -> Color.White.copy(alpha = 0.12f)
        glass && focused -> Color.White.copy(alpha = 0.70f)
        glass -> ImmersiveHairline
        focused -> scheme.primary
        else -> scheme.outlineVariant.copy(alpha = 0.5f)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelMedium.copy(color = textColor),
        cursorBrush = SolidColor(cursor),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.height(40.dp).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(fill)
                    .border(1.dp, stroke, shape)
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
    val glass = LocalGlassChrome.current
    val shape = RoundedCornerShape(10.dp)
    val textColor = if (glass) ImmersiveInk else scheme.onSurface
    val cursor = if (glass) Color.White else scheme.primary
    val fill = if (glass) Color.White.copy(alpha = 0.08f) else scheme.surface
    val stroke = if (glass) ImmersiveHairline else scheme.outline
    val hint = if (glass) ImmersiveInkMuted else scheme.onSurfaceVariant
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelSmall.copy(color = textColor),
        cursorBrush = SolidColor(cursor),
        modifier = modifier.height(40.dp),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(fill)
                    .border(1.dp, stroke, shape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.labelSmall, color = hint, maxLines = 1)
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
