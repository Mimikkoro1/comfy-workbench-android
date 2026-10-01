@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.mie.kreaworkbench.ui.screens.gen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.service.LiveJob
import com.mie.kreaworkbench.service.line
import com.mie.kreaworkbench.ui.components.KreaCard
import com.mie.kreaworkbench.ui.motion.pressScale
import com.mie.kreaworkbench.ui.motion.sharedImage
import com.mie.kreaworkbench.ui.motion.thumbRequest
import com.mie.kreaworkbench.ui.motion.ShimmerBox
import com.mie.kreaworkbench.util.VideoThumb
import java.io.File

@Composable
fun JobStatusCard(job: LiveJob, onCancel: () -> Unit, onResume: () -> Unit = {}) {
    var now by remember(job.clientJobId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(job.clientJobId, job.phase) {
        while (job.phase !in setOf("success", "partial", "failed", "cancelled")) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val sec = ((now - job.createdAt) / 1000).coerceAtLeast(0)
    val scheme = MaterialTheme.colorScheme
    val busy = job.phase !in setOf("success", "partial", "failed", "cancelled", "paused")
    KreaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    job.line(LocalContext.current),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (job.phase == "failed") scheme.error else scheme.onSurface,
                )
                Text(stringResource(R.string.elapsed, (sec / 60).toInt(), (sec % 60).toInt()), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (busy) {
                ContainedLoadingIndicator(Modifier.padding(start = 8.dp).size(48.dp))
            }
        }
        val indeterminate = job.phase == "downloading" && job.bytesTotal <= 0L && job.bytesDone > 0L
        val bar = when (job.phase) {
            "running" -> if (job.stepMax > 0) job.step.toFloat() / job.stepMax else -1f
            "downloading" -> if (job.bytesTotal > 0) (job.bytesDone.toFloat() / job.bytesTotal).coerceIn(0f, 1f) else -1f
            "uploading", "upload_ask" -> if (job.download >= 0f) job.download else -1f
            else -> -1f
        }
        if (indeterminate) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (bar >= 0f) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { bar.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (job.phase == "paused") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onResume) { Text(stringResource(R.string.action_continue)) }
        } else if (job.phase == "downloading" && job.downloadFailed) {
            // 视频下载失败：任务本身没失败，点重试重启轮询（不重新提交）
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onResume) { Text(stringResource(R.string.action_retry_download)) }
        } else if (job.phase !in setOf("success", "partial", "failed", "cancelled")) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
fun ThumbRow(
    images: List<ImageRow>,
    onOpen: (Int) -> Unit,
    onGallery: () -> Unit,
) {
    val ctx = LocalContext.current
    val shown = images.take(20)
    KreaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.title_assets),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onGallery) {
                Text(
                    stringResource(R.string.more),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.gen_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (shown.isNotEmpty()) {
            // 有新图插到最前时自动滚回最左，让新图露出；首次进入页面不做动画。
            val listState = rememberLazyListState()
            val firstId = shown.firstOrNull()?.id
            var lastFirstId by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(firstId) {
                if (firstId == null) return@LaunchedEffect
                if (lastFirstId != null && firstId != lastFirstId) {
                    listState.animateScrollToItem(0)
                }
                lastFirstId = firstId
            }
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(shown, key = { _, img -> img.id }) { i, img ->
                    // 高度固定 120dp，宽度按原始宽高比还原，不裁剪；比例缺失按 1:1。
                    val ratio = when {
                        img.width > 0 && img.height > 0 -> img.width.toFloat() / img.height
                        img.kind == "video" -> 16f / 9f
                        else -> 1f
                    }
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressScale(press)
                            .height(120.dp)
                            .width((120f * ratio).dp)
                            .clip(RoundedCornerShape(16.dp))
                            .sharedImage("home_img_${img.id}")
                            .clickable(interactionSource = press, indication = null) { onOpen(i) },
                    ) {
                        if (img.kind == "video") {
                            VideoThumbCell(img)
                        } else {
                            AsyncImage(
                                model = thumbRequest(ctx, File(img.localPath), img.id),
                                contentDescription = stringResource(R.string.asset_index, img.idx),
                                contentScale = ContentScale.Fit,
                                // 图片自身也裁圆角：历史 0 宽高/异常比例下 Fit 居中，可见四角不能露直角
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                            )
                        }
                    }
                }
                item(key = "more") {
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressScale(press)
                            .height(120.dp)
                            .width(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(interactionSource = press, indication = null) { onGallery() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.more),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoThumbCell(img: ImageRow) {
    val ctx = LocalContext.current
    var thumb by remember(img.id) { mutableStateOf<File?>(null) }
    var duration by remember(img.id) { mutableStateOf(0L) }
    LaunchedEffect(img.id, img.localPath) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            thumb = VideoThumb.thumbFile(img.localPath)
            duration = VideoThumb.durationMs(img.localPath)
        }
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
        val t = thumb
        if (t != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(t).size(480).build(),
                contentDescription = stringResource(R.string.video_index, img.idx),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 首帧取不到（reti 失败/文件还没写完）不给 Coil 喂 mp4（无 VideoFrameDecoder 只会空白），
            // 用底色 + 播放图标占位
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Icon(
            Lucide.Play,
            stringResource(R.string.action_play),
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .size(36.dp)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
        )
        val label = VideoThumb.durationLabel(duration)
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
@Composable
fun ShimmerStrip(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count.coerceIn(1, 4)) {
            ShimmerBox(Modifier.size(96.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BatchPicker(value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    // E2 fix: material3 1.5.0-alpha09 ButtonGroup(overflowIndicator) crashed in measure
    // (Constraints.copy IllegalArgumentException). Use a plain connected ToggleButton row instead.
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        for (n in 1..8) {
            androidx.compose.material3.ToggleButton(
                checked = value == n,
                onCheckedChange = { on -> if (on) onChange(n) },
                shapes = when (n) {
                    1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    8 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("$n", maxLines = 1, softWrap = false)
            }
        }
    }
}
