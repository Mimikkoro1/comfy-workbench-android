package com.mie.kreaworkbench.ui.screens.viewer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.TextResultActions
import com.mie.kreaworkbench.ui.components.glass
import com.mie.kreaworkbench.ui.components.hazeSourceIfEnabled
import com.mie.kreaworkbench.ui.components.notify
import com.mie.kreaworkbench.ui.theme.PhotoBackdrop
import com.mie.kreaworkbench.ui.theme.PhotoOnBackdrop
import com.mie.kreaworkbench.ui.theme.SystemBarAppearance
import com.mie.kreaworkbench.util.SaveOutcome
import com.mie.kreaworkbench.util.albumUriExists
import com.mie.kreaworkbench.util.persistDelete
import com.mie.kreaworkbench.util.saveImageIfNeeded
import com.mie.kreaworkbench.util.shareImage
import com.mie.kreaworkbench.util.shareVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    heroPrefix: String = "home",
    ids: List<Long>,
    start: Int,
    onBack: () -> Unit,
    onUseForI2i: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as KreaApp
    val toaster = LocalToaster.current
    // 「用于图生图」按钮只在本机有带参考图（file:image spec）的导入工作流时渲染（决策 5）
    val canI2i by produceState(initialValue = false, ids) {
        value = try {
            app.container.workflowStore.imageWorkflows().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
    var ready by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<ImageRow>>(emptyList()) }
    var originId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(ids) {
        rows = withContext(Dispatchers.IO) { ids.mapNotNull { app.container.db.image(it) } }
        if (originId < 0 && rows.isNotEmpty()) {
            originId = rows[start.coerceIn(0, rows.lastIndex)].id
        }
        ready = true
        if (rows.isEmpty()) onBack()
    }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var alsoServer by remember { mutableStateOf(false) }
    var pull by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissAt = with(density) { 160.dp.toPx() }
    val back by rememberUpdatedState(onBack)
    SystemBarAppearance(dark = true)
    HideSystemBars(hide = !chrome)
    if (!ready || rows.isEmpty()) return
    val pager = rememberPagerState(start.coerceIn(0, rows.lastIndex)) { rows.size }
    val current = rows[pager.currentPage.coerceIn(0, rows.lastIndex)]
    val saved = current.savedToAlbum && albumUriExists(context, current.albumUri)
    val bgAlpha = (1f - abs(pull) / 600f).coerceIn(0f, 1f)
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    CompositionLocalProvider(LocalContentColor provides PhotoOnBackdrop) {
        Box(Modifier.fillMaxSize().background(PhotoBackdrop.copy(alpha = bgAlpha))) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed && pull == 0f,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSourceIfEnabled()
                    .graphicsLayer { translationY = pull },
            ) { page ->
                val row = rows[page]
                when (row.kind) {
                    "video" -> VideoPlayer(
                        path = row.localPath,
                        active = page == pager.currentPage,
                        chromeVisible = chrome,
                        onToggleChrome = { chrome = !chrome },
                    )
                    "text" -> TextPage(
                        row = row,
                        app = app,
                        onToggleChrome = { chrome = !chrome },
                    )
                    else -> ZoomImage(
                        file = File(row.localPath),
                        imageId = row.id,
                        heroPrefix = heroPrefix,
                        shared = row.id == originId && pager.currentPage == page,
                        active = page == pager.currentPage,
                        onToggleChrome = { chrome = !chrome },
                        onZoomed = { if (page == pager.currentPage) zoomed = it },
                        onPull = { dy, released ->
                            if (page != pager.currentPage) return@ZoomImage
                            if (!released) {
                                pull = dy
                            } else if (dy > dismissAt) {
                                back()
                            } else {
                                scope.launch {
                                    Animatable(dy).animateTo(0f, spring()) { pull = value }
                                }
                            }
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(effects) + slideInVertically(spatial) { -it },
                exit = fadeOut(effects) + slideOutVertically(spatial) { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glass(Color.Black.copy(alpha = 0.45f), tint = Color.Black.copy(alpha = 0.35f))
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(R.string.action_back), tint = PhotoOnBackdrop) }
                    Box(Modifier.weight(1f))
                    IconButton(onClick = { shareRow(context, current) }) {
                        Icon(Lucide.Share2, stringResource(R.string.action_share), tint = PhotoOnBackdrop)
                    }
                    IconButton(onClick = { info = true }) { Icon(Lucide.Info, stringResource(R.string.cd_info), tint = PhotoOnBackdrop) }
                }
            }
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(effects) + slideInVertically(spatial) { it },
                exit = fadeOut(effects) + slideOutVertically(spatial) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glass(Color.Black.copy(alpha = 0.45f), tint = Color.Black.copy(alpha = 0.35f))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current.kind != "text") {
                        IconButton(onClick = {
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) { saveImageIfNeeded(context, current) }
                                when (outcome) {
                                    SaveOutcome.Already -> notify(toaster, context, context.str(R.string.already_saved))
                                    is SaveOutcome.Saved -> {
                                        withContext(Dispatchers.IO) { app.container.db.markSaved(current.id, outcome.uri) }
                                        rows = rows.map {
                                            if (it.id == current.id) it.copy(savedToAlbum = true, albumUri = outcome.uri) else it
                                        }
                                        notify(toaster, context, context.str(R.string.saved_to_gallery))
                                    }
                                    is SaveOutcome.Failed -> notify(toaster, context, outcome.message)
                                }
                            }
                        }) {
                            Icon(
                                if (saved) Lucide.CircleCheck else Lucide.Download,
                                if (saved) stringResource(R.string.saved_check) else stringResource(R.string.action_save_album),
                                tint = PhotoOnBackdrop,
                            )
                        }
                        if (canI2i && current.kind == "image") {
                            IconButton(onClick = { onUseForI2i(current.localPath) }) {
                                Icon(Lucide.Palette, stringResource(R.string.viewer_to_i2i), tint = PhotoOnBackdrop)
                            }
                        }
                    }
                    IconButton(onClick = { confirm = true }) { Icon(Lucide.Trash2, stringResource(R.string.action_delete), tint = PhotoOnBackdrop) }
                }
            }
        }
    }
    if (info) InfoSheet(current) { info = false }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = {
                Text(
                    when (current.kind) {
                        "video" -> stringResource(R.string.viewer_delete_video)
                        "text" -> stringResource(R.string.viewer_delete_text)
                        else -> stringResource(R.string.viewer_delete_image)
                    },
                )
            },
            text = {
                Column {
                    Text(stringResource(R.string.viewer_delete_body))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoServer, onCheckedChange = { alsoServer = it })
                        Text(stringResource(R.string.gallery_delete_server))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val row = current
                    val deleteServer = alsoServer
                    alsoServer = false
                    scope.launch {
                        val serverErr = persistDelete(app, row, deleteServer)
                        val next = rows.filter { it.id != row.id }
                        rows = next
                        if (serverErr != null) notify(toaster, context, context.str(R.string.gallery_deleted_server, serverErr))
                        if (next.isEmpty()) onBack()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun HideSystemBars(hide: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    DisposableEffect(hide) {
        val c = WindowCompat.getInsetsController(window, view)
        val types = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        if (hide) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(types)
        } else {
            c.show(types)
        }
        onDispose { c.show(types) }
    }
}

/** 按类型分享：图片/视频走 FileProvider，文本走纯文本。 */
private fun shareRow(context: Context, row: ImageRow) {
    when (row.kind) {
        "video" -> shareVideo(context, File(row.localPath))
        "text" -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, row.text)
            }
            context.startActivity(Intent.createChooser(intent, context.str(R.string.share_text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        else -> shareImage(context, File(row.localPath))
    }
}

/** 文本结果页（round6）：可滚动可选中，带复制/存库（不删数据，文本卡由换绑工作流时统一清空）。点空白切换工具条。 */
@Composable
private fun TextPage(
    row: ImageRow,
    app: KreaApp,
    onToggleChrome: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(PhotoBackdrop)
            .clickable(interactionSource = press, indication = null, onClick = onToggleChrome),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 72.dp),
        ) {
            Text(
                stringResource(R.string.text_result),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PhotoOnBackdrop,
            )
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(row.text, color = PhotoOnBackdrop, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            TextResultActions(text = row.text, app = app)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(row: ImageRow, onClose: () -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val maxPrompt = LocalConfiguration.current.screenHeightDp.dp * 0.45f
    val ink = MaterialTheme.colorScheme.onSurface
    val emptyPrompt = stringResource(R.string.prompt_empty)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            if (row.kind == "text") {
                Text(stringResource(R.string.text_result), style = MaterialTheme.typography.titleSmall, color = ink)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Column(Modifier.heightIn(max = maxPrompt).verticalScroll(rememberScrollState())) {
                        Text(row.text, color = ink)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_prompt), style = MaterialTheme.typography.titleSmall, color = ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("prompt", row.prompt))
                        notify(toaster, context, context.str(R.string.copied))
                    }) {
                        Icon(Lucide.Copy, null)
                        Text(stringResource(R.string.action_copy))
                    }
                }
                SelectionContainer {
                    Column(Modifier.heightIn(max = maxPrompt).verticalScroll(rememberScrollState())) {
                        Text(row.prompt.ifBlank { emptyPrompt }, color = ink)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.seed_value, row.seed), color = ink)
                Text("${row.width} × ${row.height}", color = ink)
            }
            // 工作流名：新任务 paramsJson 带 workflow_name；老记录按 workflow_id 去工作流库查当前名字；
            // 都没有（已删除/非导入工作流）再退回原来的类别标签
            val workflowName by produceState<String?>(null, row.id) {
                value = workflowNameOf(row, context)
            }
            Text(
                workflowName ?: when {
                    row.kind == "video" -> stringResource(R.string.kind_video)
                    row.kind == "text" -> stringResource(R.string.label_text)
                    row.mode == "i2i" -> stringResource(R.string.mode_i2i)
                    row.mode == "custom" -> stringResource(R.string.mode_import)
                    else -> stringResource(R.string.mode_t2i)
                },
                color = ink,
            )
            val fileInfo by produceState<Pair<Long, Long>?>(null, row.id) {
                value = withContext(Dispatchers.IO) {
                    val f = row.localPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
                    val size = if (row.kind == "text") 0L else row.sizeBytes.takeIf { it > 0 } ?: f?.length() ?: 0L
                    val time = row.createdAt.takeIf { it > 0 } ?: f?.lastModified() ?: 0L
                    size to time
                }
            }
            fileInfo?.let { (size, time) ->
                if (size > 0) Text(stringResource(R.string.info_file_size, formatInfoSize(size)), color = ink)
                if (time > 0) {
                    val formatted = remember(time) { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time)) }
                    Text(stringResource(R.string.info_created_at, formatted), color = ink)
                }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        }
    }
}

private suspend fun workflowNameOf(row: ImageRow, context: Context): String? {
    val params = try {
        JSONObject(row.paramsJson)
    } catch (_: Exception) {
        return null
    }
    params.optString("workflow_name").takeIf { it.isNotBlank() }?.let { return it }
    val id = params.optString("workflow_id").takeIf { it.isNotBlank() } ?: return null
    return try {
        (context.applicationContext as KreaApp).container.workflowStore.labelOf(id)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

private fun formatInfoSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
