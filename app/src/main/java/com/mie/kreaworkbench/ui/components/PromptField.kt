package com.mie.kreaworkbench.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.launch

@Composable
fun PromptField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.prompt_placeholder),
    minLines: Int = 5,
) {
    var expanded by remember { mutableStateOf(false) }
    // r21：展开入口从「装饰盒内的 48dp 大 IconButton」改为「框内右上角一颗小图标」——
    // 不再占横向空间、不把输入框撑高，多行时贴顶右，文本右侧留 34dp 避免遮字。
    Box {
        FollowingTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            placeholder = placeholder,
            minLines = minLines,
            // 固定高度：行数封顶在 minLines，超出在框内滚动，不随打字撑高整页
            maxLines = minLines,
            innerEndPadding = 34.dp,
        )
        Icon(
            Lucide.Maximize2,
            contentDescription = stringResource(R.string.prompt_expand),
            tint = if (LocalGlassChrome.current) ImmersiveInk else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(8.dp)
                .size(18.dp),
        )
    }
    if (expanded) {
        FullPromptDialog(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            onClose = { expanded = false },
        )
    }
}

@Composable
private fun FullPromptDialog(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClose: () -> Unit,
) {
    // 存入提示词库（round8 2.2）：所有 PromptField（text/prompt_pool，含负向词）共用本对话框。
    // r27：弹层改挂同窗口的 GlassHost 叠层，sheet 后开在上层，保存后全屏编辑态原样保留
    // （不清空、不改动输入内容）；不再需要「独立窗口」那套降级。
    val app = LocalContext.current.applicationContext as KreaApp
    val libraries by app.container.library.libraries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSaveSheet by remember { mutableStateOf(false) }
    GlassDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // r27：全屏面板吃掉整屏，不露共享背景；底用 GlassPanelFallback（0.86 黑）保证长文本可读，
        // 不走真模糊——整屏模糊既看不出背景也白费一次大图模糊。
        Column(
            Modifier
                .fillMaxSize()
                .background(GlassPanelFallback)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.prompt_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = ImmersiveInk,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showSaveSheet = true }, enabled = value.isNotBlank()) {
                    Text(stringResource(R.string.action_to_library))
                }
                // 走弹层自己的关闭流程（先锁输入法再收），别直接改状态把面板硬拆掉
                val close = LocalGlassDismiss.current
                TextButton(onClick = { close() }) { Text(stringResource(R.string.action_done)) }
            }
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FollowingTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = placeholder,
                    minLines = 12,
                    maxLines = Int.MAX_VALUE,
                    requestFocus = true,
                    // 全屏编辑没有固定高度，光标跟随靠祖先滚动
                    followCursor = true,
                )
            }
        }
    }
    if (showSaveSheet) {
        SaveToLibrarySheet(
            libraries = libraries,
            onDismiss = { showSaveSheet = false },
        ) { libId, newName ->
            // SaveToLibrarySheet 内部已先 onDismiss 再回调；这里再置一次兜底
            showSaveSheet = false
            scope.launch {
                // 不查重：连存两条相同文字各算一条；入库不消费，value 原样保留
                val ok = try {
                    val id = libId ?: app.container.library.createLibrary(newName)
                    app.container.library.addEntry(id, value)
                } catch (_: Exception) {
                    false
                }
                // 用系统 Toast：不依赖弹层窗口层级，键盘开着时也一定看得见（行为同 r26）
                Toast.makeText(
                    context,
                    if (ok) context.str(R.string.saved_to_library) else context.str(R.string.err_save_to_library),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String,
    minLines: Int,
    maxLines: Int,
    requestFocus: Boolean = false,
    followCursor: Boolean = false,
    // r21：框内右上角叠展开小图标时给文本右侧留白，避免第一行正文被图标遮住
    innerEndPadding: Dp = 0.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = LocalGlassChrome.current
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(0))) }
    LaunchedEffect(value) {
        if (value != tfv.text) {
            // 外部替换文本（抽卡/恢复默认/换绑）：光标与框内滚动都回开头，先看到内容再编辑。
            // 打字被 maxChars 截断时 value 是现文本的前缀，保持原光标位置，别打断输入；
            // 手动打字走 onValueChange 回路 value==tfv.text，不进这个分支。
            tfv = if (value.isNotEmpty() && tfv.text.startsWith(value)) {
                TextFieldValue(value, TextRange(tfv.selection.end.coerceIn(0, value.length)))
            } else {
                TextFieldValue(value, TextRange(0))
            }
        }
    }
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val extraPx = with(density) { 72.dp.toPx() }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val colors = if (glass) {
        glassTextFieldColors()
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface,
            focusedContainerColor = scheme.surfaceBright,
            unfocusedContainerColor = scheme.surfaceBright,
            cursorColor = scheme.primary,
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline,
            focusedPlaceholderColor = scheme.onSurfaceVariant,
            unfocusedPlaceholderColor = scheme.onSurfaceVariant,
        )
    }
    val ink = if (glass) ImmersiveInk else scheme.onSurface
    val cursor = if (glass) Color.White else scheme.primary
    val hint = if (glass) ImmersiveInkMuted else scheme.onSurfaceVariant
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }
    // 只在键盘弹出/收起时把整个框滚入视野。不能拿 tfv.selection 当 key：
    // 那会每敲一个字就发一次「全框入视野」的滚动请求，和 onTextLayout 里
    // 「光标矩形入视野」的目标不同，两个请求一上一下互相拉扯，整页随打字跳动。
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) requester.bringIntoView()
    }
    // 光标跟随只在需要祖先滚动才能看到光标的场景启用（全屏编辑）。
    // 固定高度的内联框由 BasicTextField 自身在框内滚动追光标，
    // 再发祖先滚动请求会和框内滚动打架。
    val cursorLayout: ((TextLayoutResult) -> Unit)? = if (followCursor) {
        { layout ->
            val offset = tfv.selection.end.coerceIn(0, tfv.text.length)
            val rect = layout.getCursorRect(offset)
            val target = rect.copy(bottom = rect.bottom + extraPx)
            scope.launch { requester.bringIntoView(target) }
        }
    } else {
        null
    }
    BasicTextField(
        value = tfv,
        onValueChange = {
            tfv = it
            if (it.text != value) onValueChange(it.text)
        },
        modifier = modifier
            .focusRequester(focus)
            .bringIntoViewRequester(requester)
            .onFocusChanged { state ->
                if (state.isFocused) scope.launch { requester.bringIntoView() }
            },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
        cursorBrush = SolidColor(cursor),
        keyboardOptions = KeyboardOptions.Default,
        keyboardActions = KeyboardActions.Default,
        singleLine = false,
        minLines = minLines,
        maxLines = maxLines,
        interactionSource = interaction,
        onTextLayout = cursorLayout ?: {},
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = tfv.text,
                visualTransformation = VisualTransformation.None,
                innerTextField = {
                    Box(Modifier.padding(end = innerEndPadding)) { inner() }
                },
                placeholder = {
                    Text(placeholder, color = hint, style = MaterialTheme.typography.bodyLarge)
                },
                singleLine = false,
                enabled = true,
                isError = false,
                interactionSource = interaction,
                colors = colors,
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interaction,
                        colors = colors,
                    )
                },
            )
        },
    )
}
