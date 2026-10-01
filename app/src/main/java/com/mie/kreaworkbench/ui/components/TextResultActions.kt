package com.mie.kreaworkbench.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ClipboardCopy
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文本结果的操作（round6）：复制 / 存入提示词库。「用作提示词」已于 round7 整体移除。
 * 本组件不删数据：文本卡保留到切换工作流，由 CustomModel.bind 换绑时统一清空（round7 fix2）。
 * 生成页结果卡与查看页文本页共用；存库弹窗在本组件内。
 */
@Composable
fun TextResultActions(
    text: String,
    app: KreaApp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val libraries by app.container.library.libraries.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("caption", text))
                notify(toaster, context, context.str(R.string.copied))
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Lucide.ClipboardCopy, null, Modifier.size(16.dp))
            Text(stringResource(R.string.action_copy))
        }
        OutlinedButton(
            onClick = { showSheet = true },
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.action_to_library))
        }
    }
    if (showSheet) {
        SaveToLibrarySheet(
            libraries = libraries,
            onDismiss = { showSheet = false },
        ) { libId, newName ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val id = libId ?: app.container.library.createLibrary(newName)
                        app.container.library.addEntry(id, text)
                    } catch (_: Exception) {
                        false
                    }
                }
                notify(toaster, context, if (ok) context.str(R.string.saved_to_library) else context.str(R.string.err_save_to_library))
            }
        }
    }
}
