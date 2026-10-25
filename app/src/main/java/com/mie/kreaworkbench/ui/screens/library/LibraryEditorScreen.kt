package com.mie.kreaworkbench.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.library.LibraryEditorLoad
import com.mie.kreaworkbench.data.library.SaveTxtResult
import com.mie.kreaworkbench.ui.components.GlassDanger
import com.mie.kreaworkbench.ui.components.ImmersiveHairline
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveInkMuted
import com.mie.kreaworkbench.ui.components.LargeBarScaffold
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.hazeSourceIfEnabled
import com.mie.kreaworkbench.ui.components.notify
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.ui.theme.isClassic
import com.mie.kreaworkbench.ui.theme.pageBackground
import kotlinx.coroutines.launch

/** 独立场景（不在 HomeLayer 里，没有共享背景）的底色：白字体系要有深底才站得住，
 *  同时充当这一页的毛玻璃取样源。 */
private val EditorPageBase = Color.Black.copy(alpha = 0.58f)

/** 编辑区面板：比普通玻璃卡更实一点，长文本、光标、选中态才看得清。 */
private val EditorPanel = Color.Black.copy(alpha = 0.72f)

/**
 * txt 提示词库编辑器（round8 2.3）：顶部 取消（返回不落盘）/ 保存，正文可滚动大号文本框。
 * 预填 = 该库 entries 的 prompt 按 "\n\n" 拼接（与导出 txt 同一形态）；保存走 saveTxtLibrary，
 * 单条超 10000 字整体拒绝（行内红字提示，不关页），库被删则提示后关闭。
 * r28：换玻璃外观（白字 + 深色面板），保存/返回逻辑一行没动。
 */
@Composable
fun LibraryEditorScreen(
    libId: String,
    onBack: () -> Unit,
    vm: LibraryModel = viewModel(),
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var libName by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    val editorTitle = stringResource(R.string.editor_title)

    LaunchedEffect(libId) {
        when (val r = vm.loadEditor(libId)) {
            is LibraryEditorLoad.Ready -> {
                libName = r.name
                text = r.text
            }
            LibraryEditorLoad.Missing -> {
                notify(toaster, context, context.str(R.string.editor_missing))
                onBack()
            }
            LibraryEditorLoad.TooBig -> {
                notify(toaster, context, context.str(R.string.editor_too_big))
                onBack()
            }
        }
        loading = false
    }

    fun save() {
        if (saving || loading) return
        scope.launch {
            saving = true
            val r = try {
                vm.saveTxtLibrary(libId, text)
            } catch (e: Exception) {
                SaveTxtResult.Error(e.message ?: e.javaClass.simpleName)
            }
            saving = false
            when (r) {
                SaveTxtResult.Ok -> {
                    notify(toaster, context, context.str(R.string.saved))
                    onBack()
                }
                is SaveTxtResult.TooLong -> inlineError = context.str(R.string.entry_too_long, r.index)
                SaveTxtResult.Missing -> {
                    notify(toaster, context, context.str(R.string.lib_deleted))
                    onBack()
                }
                is SaveTxtResult.Error -> inlineError = context.str(R.string.save_failed, r.message)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(if (isClassic()) pageBackground() else EditorPageBase).hazeSourceIfEnabled())
        LargeBarScaffold(
            title = knownText(libName).ifBlank { editorTitle },
            onBack = onBack,
            blur = true,
            immersive = true,
            actions = {
                TextButton(
                    onClick = { save() },
                    enabled = !saving && !loading,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = ImmersiveInk,
                        disabledContentColor = ImmersiveInkMuted,
                    ),
                ) {
                    Text(if (saving) stringResource(R.string.state_saving) else stringResource(R.string.action_save))
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding(),
            ) {
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = ImmersiveInk)
                    }
                } else {
                    inlineError?.let {
                        Text(
                            it,
                            color = GlassDanger,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    val scroll = rememberScrollState()
                    val panelShape = MaterialTheme.shapes.large
                    // r46：经典主题面板换 surfaceBright 实心，光标/选区跟主题主色。
                    val panelColor = if (isClassic()) MaterialTheme.colorScheme.surfaceBright else EditorPanel
                    val selectionBg = if (isClassic()) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    else Color.White.copy(alpha = 0.30f)
                    CompositionLocalProvider(
                        // 深底上的选中态：默认的蓝色高亮几乎看不见，换成白色半透明 + 白把手
                        LocalTextSelectionColors provides TextSelectionColors(
                            handleColor = ImmersiveInk,
                            backgroundColor = selectionBg,
                        ),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .clip(panelShape)
                                .background(panelColor)
                                .border(1.dp, ImmersiveHairline, panelShape)
                                .verticalScroll(scroll)
                                .navigationBarsPadding()
                                .padding(16.dp),
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(R.string.editor_hint),
                                    color = ImmersiveInkMuted,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    inlineError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ImmersiveInk),
                                cursorBrush = SolidColor(if (isClassic()) MaterialTheme.colorScheme.primary else Color.White),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
