package com.mie.kreaworkbench.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.update.DownloadState
import com.mie.kreaworkbench.data.update.NoteBlock
import com.mie.kreaworkbench.data.update.NoteSpan
import com.mie.kreaworkbench.data.update.ReleaseInfo
import com.mie.kreaworkbench.data.update.UpdateChecker
import com.mie.kreaworkbench.data.update.UpdateDownloader
import com.mie.kreaworkbench.data.update.parseBlocks
import com.mie.kreaworkbench.data.update.pickSection
import java.util.Locale

/** 新版本对话框（round14）：标题 + 可滚动更新说明 + 去下载 / 忽略此版本 / 稍后。
 *  r29：换成玻璃弹层；挂在 AppNav 的 GlassHostProvider 里（不要再放回 MainActivity 顶层）。
 *  r40：说明只显示当前语言那段，按 ReleaseNotes 渲染；应用内下载（G3）——
 *  下载状态挂 UpdateDownloader 单例，「稍后」关弹窗下载照跑。 */
@Composable
fun UpdateDialog() {
    val info by UpdateChecker.pending.collectAsState()
    val rel = info ?: return
    val context = LocalContext.current
    val dismiss = { UpdateChecker.pending.value = null }
    val downloadState by UpdateDownloader.state.collectAsState()
    // 当前语言：跟随系统时按系统语言（与 AdvGachaSection 的 optionText 同一判断方式）
    val locales = LocalConfiguration.current.locales
    val zh = !locales.isEmpty && locales[0].language == "zh"
    // 「未知来源」权限说明弹窗；从系统页回来（ON_RESUME）已授权就自动继续安装
    var askInstallPerm by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && askInstallPerm) {
                if (context.packageManager.canRequestPackageInstalls()) {
                    askInstallPerm = false
                    UpdateDownloader.install(context, rel)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 下载完成自动走安装流程：已授权直接调起系统安装器，没授权先弹说明
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Done) {
            if (context.packageManager.canRequestPackageInstalls()) {
                UpdateDownloader.install(context, rel)
            } else {
                askInstallPerm = true
            }
        }
    }
    GlassAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.update_title, rel.version)) },
        text = {
            if (rel.notes.isBlank() && downloadState !is DownloadState.Running && downloadState !is DownloadState.Failed) {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.update_no_notes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val section = pickSection(rel.notes, zh)
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (block in parseBlocks(section)) {
                        when (block) {
                            is NoteBlock.Heading -> Text(
                                block.spans.toAnnotatedString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            is NoteBlock.Bullet -> Row {
                                Text("•  ", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    block.spans.toAnnotatedString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            is NoteBlock.Paragraph -> Text(
                                block.spans.toAnnotatedString(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    when (val ds = downloadState) {
                        is DownloadState.Running -> {
                            Spacer(Modifier.height(4.dp))
                            if (ds.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (ds.done.toFloat() / ds.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                if (ds.total > 0) {
                                    stringResource(
                                        R.string.update_download_progress,
                                        mbText(ds.done),
                                        mbText(ds.total),
                                    )
                                } else {
                                    stringResource(R.string.update_download_progress_no_total, mbText(ds.done))
                                },
                                fontSize = 12.sp,
                            )
                        }
                        is DownloadState.Failed -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.update_download_failed, ds.reason),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {}
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is DownloadState.Running -> TextButton(onClick = { UpdateDownloader.cancel() }) {
                    Text(stringResource(R.string.update_download_cancel))
                }
                is DownloadState.Done -> TextButton(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) {
                        UpdateDownloader.install(context, rel)
                    } else {
                        askInstallPerm = true
                    }
                }) { Text(stringResource(R.string.update_install)) }
                is DownloadState.Failed -> TextButton(onClick = { UpdateDownloader.start(rel, context) }) {
                    Text(stringResource(R.string.update_retry))
                }
                else -> if (rel.apkUrl != null) {
                    TextButton(onClick = { UpdateDownloader.start(rel, context) }) {
                        Text(stringResource(R.string.update_download_install))
                    }
                } else {
                    TextButton(onClick = { openReleasePage(context, rel) }) {
                        Text(stringResource(R.string.update_download))
                    }
                }
            }
        },
        dismissButton = {
            when (downloadState) {
                is DownloadState.Failed -> Row {
                    TextButton(onClick = { openReleasePage(context, rel) }) {
                        Text(stringResource(R.string.update_download_browser))
                    }
                }
                is DownloadState.Running -> TextButton(onClick = dismiss) {
                    Text(stringResource(R.string.update_later))
                }
                else -> Row {
                    TextButton(onClick = {
                        dismiss()
                        UpdateChecker.ignore((context.applicationContext as KreaApp).container.settings, rel.tag)
                    }) { Text(stringResource(R.string.update_ignore)) }
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.update_later)) }
                }
            }
        },
    )

    if (askInstallPerm) {
        GlassAlertDialog(
            onDismissRequest = { askInstallPerm = false },
            title = { Text(stringResource(R.string.update_install_title)) },
            text = { Text(stringResource(R.string.update_install_permission)) },
            confirmButton = {
                TextButton(onClick = {
                    askInstallPerm = false
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                    }
                }) { Text(stringResource(R.string.update_install_go)) }
            },
            dismissButton = {
                TextButton(onClick = { askInstallPerm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun openReleasePage(context: android.content.Context, rel: ReleaseInfo) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(rel.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}

/** 字节 → MB 文本：≥100 MB 取整，否则 1 位小数。 */
private fun mbText(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 100) String.format(Locale.US, "%.0f", mb) else String.format(Locale.US, "%.1f", mb)
}

/** 行内 span 拼成 AnnotatedString；`**粗体**` 段加粗。 */
private fun List<NoteSpan>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (span in this@toAnnotatedString) {
        if (span.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) } else append(span.text)
    }
}
