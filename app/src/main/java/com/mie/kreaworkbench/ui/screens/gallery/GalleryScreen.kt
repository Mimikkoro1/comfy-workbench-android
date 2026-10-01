package com.mie.kreaworkbench.ui.screens.gallery

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.ui.components.CacheManagementSheet
import com.mie.kreaworkbench.ui.components.LargeBarScaffold
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.hazeSourceIfEnabled
import com.mie.kreaworkbench.ui.components.notify
import com.mie.kreaworkbench.ui.motion.pressScale
import com.mie.kreaworkbench.ui.motion.sharedImage
import com.mie.kreaworkbench.ui.motion.thumbRequest
import com.mie.kreaworkbench.ui.screens.gen.VideoThumbCell
import com.mie.kreaworkbench.util.SaveOutcome
import com.mie.kreaworkbench.util.persistDelete
import com.mie.kreaworkbench.util.saveImageIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GalleryModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KreaApp).container
    var images by mutableStateOf<List<ImageRow>>(emptyList())
    var message by mutableStateOf("")

    init {
        viewModelScope.launch {
            reload()
            c.engine.revision.collect { reload() }
        }
    }

    suspend fun reload() {
        images = withContext(Dispatchers.IO) {
            // 文本结果不进画廊（在生成页/查看页展示）；视频与图片都要求本地文件在
            c.db.images().filter { it.kind != "text" && File(it.localPath).exists() }
        }
    }

    fun save(ids: List<Long>, toast: (String) -> Unit, done: () -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            var already = 0
            var saved = 0
            var failed = ""
            withContext(Dispatchers.IO) {
                ids.mapNotNull { c.db.image(it) }.forEach { row ->
                    when (val outcome = saveImageIfNeeded(app, row)) {
                        SaveOutcome.Already -> already++
                        is SaveOutcome.Saved -> {
                            c.db.markSaved(row.id, outcome.uri)
                            saved++
                        }
                        is SaveOutcome.Failed -> if (failed.isBlank()) failed = outcome.message
                    }
                }
            }
            reload()
            toast(
                when {
                    failed.isNotBlank() && saved == 0 && already == 0 -> failed
                    already > 0 && saved == 0 -> app.str(R.string.already_saved)
                    else -> app.str(R.string.saved_to_gallery)
                },
            )
            done()
        }
    }

    fun delete(ids: List<Long>, alsoServer: Boolean, toast: (String) -> Unit, done: () -> Unit) {
        val app = getApplication<KreaApp>()
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { ids.mapNotNull { c.db.image(it) } }
            var serverErr: String? = null
            rows.forEach { row ->
                val err = persistDelete(app, row, alsoServer)
                if (err != null && serverErr == null) serverErr = err
            }
            reload()
            val serverMsg = serverErr
            if (serverMsg != null) toast(app.str(R.string.gallery_deleted_server, serverMsg))
            done()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onBack: () -> Unit, onOpen: (List<Long>, Int) -> Unit, vm: GalleryModel = viewModel()) {
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    var alsoServer by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    // 三档分类（direct12）：全部 / 图片 / 视频；文本行本来就不进画廊
    var filter by rememberSaveable { mutableStateOf("all") }
    val filtered = remember(vm.images, filter) {
        when (filter) {
            "image" -> vm.images.filter { it.kind == "image" }
            "video" -> vm.images.filter { it.kind == "video" }
            else -> vm.images
        }
    }
    val ctx = LocalContext.current
    val groups = remember(filtered, ctx) {
        filtered.groupBy { dayLabel(it.createdAt, ctx) }.toList()
    }
    val toaster = LocalToaster.current
    val layoutDirection = LocalLayoutDirection.current
    BackHandler(enabled = selecting) { selected = emptySet() }
    Box(Modifier.fillMaxSize()) {
        LargeBarScaffold(
            title = if (selecting) stringResource(R.string.gallery_selected, selected.size) else stringResource(R.string.title_gallery),
            onBack = { if (selecting) selected = emptySet() else onBack() },
            blur = true,
            actions = {
                if (!selecting) {
                    TextButton(onClick = { cache = true }) { Text(stringResource(R.string.cache_title)) }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize()) {
                // 分类条（direct12）：全部 / 图片 / 视频，rememberSaveable 记住本次会话的选择
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = padding.calculateStartPadding(layoutDirection) + 12.dp,
                        end = padding.calculateEndPadding(layoutDirection) + 12.dp,
                        top = padding.calculateTopPadding() + 4.dp,
                        bottom = 4.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text(stringResource(R.string.label_all)) })
                    FilterChip(selected = filter == "image", onClick = { filter = "image" }, label = { Text(stringResource(R.string.label_images)) })
                    FilterChip(selected = filter == "video", onClick = { filter = "video" }, label = { Text(stringResource(R.string.label_videos)) })
                }
                AnimatedContent(targetState = filtered.isEmpty(), label = "gallery-body") { empty ->
                    if (empty) {
                        Text(
                            stringResource(R.string.gallery_empty),
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().hazeSourceIfEnabled(),
                            contentPadding = PaddingValues(
                                start = padding.calculateStartPadding(layoutDirection) + 12.dp,
                                end = padding.calculateEndPadding(layoutDirection) + 12.dp,
                                top = 4.dp,
                                bottom = padding.calculateBottomPadding() + if (selecting) 96.dp else 24.dp,
                            ),
                        ) {
                            groups.forEach { (label, rows) ->
                                item(key = "h-$label") {
                                    Text(
                                        label,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.animateItem().padding(vertical = 8.dp),
                                    )
                                }
                                rows.chunked(3).forEach { chunk ->
                                    item(key = "r-${chunk.first().id}") {
                                        Row(Modifier.fillMaxWidth().animateItem()) {
                                            chunk.forEach { img ->
                                                val flat = filtered
                                                val index = flat.indexOfFirst { it.id == img.id }
                                                Cell(
                                                    img,
                                                    selecting,
                                                    img.id in selected,
                                                    Modifier.weight(1f),
                                                    onClick = {
                                                        if (selecting) {
                                                            selected = if (img.id in selected) selected - img.id else selected + img.id
                                                        } else {
                                                            onOpen(flat.map { it.id }, index)
                                                        }
                                                    },
                                                    onLong = { selected = selected + img.id },
                                                )
                                            }
                                            repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
        // 只在有选中项时才组合工具栏：HorizontalFloatingToolbar 的折叠态仍会留一个空胶囊。
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = FloatingToolbarDefaults.ScreenOffset),
        ) {
            HorizontalFloatingToolbar(expanded = true) {
                TextButton(onClick = {
                    vm.save(selected.toList(), { notify(toaster, ctx, it) }) { selected = emptySet() }
                }) {
                    Icon(Lucide.Download, null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.action_save_album))
                }
                TextButton(onClick = { confirm = true }) {
                    Icon(Lucide.Trash2, null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
    if (cache) CacheManagementSheet(onDismiss = { cache = false })
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.gallery_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.gallery_delete_body))
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
                    val server = alsoServer
                    alsoServer = false
                    vm.delete(selected.toList(), server, { notify(toaster, ctx, it) }) {
                        selected = emptySet()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(
    img: ImageRow,
    selecting: Boolean,
    checked: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLong: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(press)
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .sharedImage("gal_img_${img.id}")
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onClick = onClick,
                onLongClick = onLong,
            ),
    ) {
        if (img.kind == "video") {
            // 视频格：首帧缩略图 + 播放图标 + 时长角标
            VideoThumbCell(img)
        } else {
            AsyncImage(
                model = thumbRequest(LocalContext.current, File(img.localPath), img.id),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

private fun dayLabel(ms: Long, context: Context): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> context.str(R.string.gallery_today)
        today.minusDays(1) -> context.str(R.string.gallery_yesterday)
        else -> date.toString()
    }
}
