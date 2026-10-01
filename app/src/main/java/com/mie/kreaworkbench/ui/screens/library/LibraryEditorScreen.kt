package com.mie.kreaworkbench.ui.screens.library

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.library.LibraryEditorLoad
import com.mie.kreaworkbench.data.library.SaveTxtResult
import com.mie.kreaworkbench.ui.components.LargeBarScaffold
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.notify
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.launch

/**
 * txt 提示词库编辑器（round8 2.3）：顶部 取消（返回不落盘）/ 保存，正文可滚动大号文本框。
 * 预填 = 该库 entries 的 prompt 按 "\n\n" 拼接（与导出 txt 同一形态）；保存走 saveTxtLibrary，
 * 单条超 10000 字整体拒绝（行内红字提示，不关页），库被删则提示后关闭。
 */
@Composable
fun LibraryEditorScreen(
    libId: String,
    onBack: () -> Unit,
    vm: LibraryModel = viewModel(),
) {
    val scheme = MaterialTheme.colorScheme
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

    LargeBarScaffold(
        title = knownText(libName).ifBlank { editorTitle },
        onBack = onBack,
        actions = {
            TextButton(onClick = { save() }, enabled = !saving && !loading) {
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
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else {
                inlineError?.let {
                    Text(
                        it,
                        color = scheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val scroll = rememberScrollState()
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.editor_hint),
                            color = scheme.onSurfaceVariant,
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
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
