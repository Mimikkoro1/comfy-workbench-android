package com.mie.kreaworkbench.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.update.UpdateChecker

/** 新版本对话框（round14）：标题 + 可滚动更新说明 + 去下载 / 忽略此版本 / 稍后。 */
@Composable
fun UpdateDialog() {
    val info by UpdateChecker.pending.collectAsState()
    val rel = info ?: return
    val context = LocalContext.current
    val dismiss = { UpdateChecker.pending.value = null }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.update_title, rel.version)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    rel.notes.ifBlank { stringResource(R.string.update_no_notes) },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    dismiss()
                    UpdateChecker.ignore((context.applicationContext as KreaApp).container.settings, rel.tag)
                }) { Text(stringResource(R.string.update_ignore)) }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.update_later)) }
            }
        },
    )
}

