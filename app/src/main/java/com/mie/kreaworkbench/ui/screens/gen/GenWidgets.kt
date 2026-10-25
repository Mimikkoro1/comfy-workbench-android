@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.mie.kreaworkbench.ui.screens.gen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.db.ImageRow
import com.mie.kreaworkbench.service.LiveJob
import com.mie.kreaworkbench.service.downloadCapsule
import com.mie.kreaworkbench.service.jobProgress
import com.mie.kreaworkbench.service.line
import com.mie.kreaworkbench.ui.components.ImmersiveHairline
import com.mie.kreaworkbench.ui.components.ImmersiveIcon
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveInkMuted
import com.mie.kreaworkbench.ui.components.ImmersiveShadow
import com.mie.kreaworkbench.ui.components.KreaCard
import com.mie.kreaworkbench.ui.components.LocalGlassChrome
import com.mie.kreaworkbench.ui.components.glass
import com.mie.kreaworkbench.ui.components.glassToggleButtonColors
import com.mie.kreaworkbench.ui.components.hazeSourceIfEnabled
import com.mie.kreaworkbench.ui.components.immersiveGlass
import dev.chrisbanes.haze.HazeProgressive
import com.mie.kreaworkbench.ui.locale.qty
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.ui.motion.buzz
import com.mie.kreaworkbench.ui.motion.pressScale
import com.mie.kreaworkbench.ui.motion.sharedImage
import com.mie.kreaworkbench.ui.motion.thumbRequest
import com.mie.kreaworkbench.ui.theme.DarkBg
import com.mie.kreaworkbench.ui.theme.LocalIsDark
import com.mie.kreaworkbench.ui.theme.cardBorder
import com.mie.kreaworkbench.ui.theme.isClassic
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
        val progress = job.jobProgress()
        val indeterminate = progress.indeterminate
        val bar = progress.bar
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

/**
 * r21 底栏瘦进度条（替换主列顶部大块 JobStatusCard 当默认主进度）：
 * 细进度条（3dp）+ 一行状态（单行省略）+ 取消/继续/重试小按钮 +「另有 N 个排队」提示。
 * 数据来自「当前应展示的进行中任务」，由调用方（CustomScreen）按不含被盖的规则选出；
 * queuedExtra 为该任务之外还排队的数量（0 不显示）。
 */
@Composable
fun JobProgressSlim(
    job: LiveJob,
    queuedExtra: Int = 0,
    onCancel: () -> Unit,
    onResume: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val progress = job.jobProgress()
    // 有确定/不定进度才画条；排队/提交中等无进度阶段不画（状态文字已说明）
    if (progress.indeterminate) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
    } else if (progress.bar >= 0f) {
        LinearProgressIndicator(progress = { progress.bar.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            job.line(ctx),
            style = MaterialTheme.typography.labelSmall,
            color = if (job.phase == "failed") scheme.error else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            job.phase == "paused" -> {
                TextButton(onClick = onResume, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.action_continue), fontSize = 12.sp)
                }
            }
            job.phase == "downloading" && job.downloadFailed -> {
                TextButton(onClick = onResume, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.action_retry_download), fontSize = 12.sp)
                }
            }
            job.phase !in setOf("success", "partial", "failed", "cancelled") -> {
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.action_cancel), fontSize = 12.sp, color = scheme.error)
                }
            }
        }
    }
    if (queuedExtra > 0) {
        Text(
            ctx.qty(R.plurals.more_jobs, queuedExtra, queuedExtra),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * 按钮主行。正在跑且算得出进度时是「生成中 42%」，另外还有排队就再接「排队 N」。
 * 暂停、纯排队、下载失败仍用各自的短句。算不出百分比时不写 0%。
 */
fun capsuleStatus(job: LiveJob, queuedExtra: Int, context: Context): String {
    val special = when {
        job.phase == "paused" -> job.line(context)
        job.phase == "queued" -> job.line(context)
        job.phase == "downloading" && job.downloadFailed -> context.str(R.string.phase_download_retry)
        job.phase == "upload_ask" || job.phase == "upload_fail_ask" -> job.line(context)
        else -> null
    }
    if (special != null) {
        if (queuedExtra <= 0 || job.phase == "paused") return special
        return special + " · " + context.str(R.string.queue_badge, queuedExtra)
    }
    job.downloadCapsule(context, queuedExtra)?.let { return it }
    val bar = job.jobProgress().bar
    val pct = if (bar >= 0f) (bar * 100).toInt().coerceIn(0, 100) else null
    return when {
        pct != null && queuedExtra > 0 -> context.str(R.string.gen_capsule_running, pct, queuedExtra)
        pct != null -> context.str(R.string.gen_capsule_pct, pct)
        queuedExtra > 0 -> context.str(R.string.phase_running_plain) + " · " + context.str(R.string.queue_badge, queuedExtra)
        else -> context.str(R.string.phase_running_plain)
    }
}

/** 胶囊副行：告诉这颗按钮现在轻点和长按分别干什么。 */
fun capsuleHint(job: LiveJob, context: Context): String = when {
    job.phase == "paused" -> context.str(R.string.gen_hint_resume)
    job.phase == "downloading" && job.downloadFailed -> context.str(R.string.gen_hint_retry)
    else -> context.str(R.string.gen_hint_queue)
}

/**
 * 生成按钮。空闲、生成、下载都是同一颗 56dp 圆角矩形，只换里面的字。
 * 进度铺在文字后面，绝对定位，不把按钮撑高。按下会缩小并轻震。
 * 生成中单击继续排队，长按交给调用方（一般是弹出取消确认）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GenerateCapsule(
    generating: Boolean,
    label: String,
    status: String,
    hint: String = "",
    fraction: Float,
    indeterminate: Boolean,
    enabled: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    frosted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) haptic.buzz(HapticFeedbackType.ContextClick)
    }
    val shape = RoundedCornerShape(16.dp)
    // r48：经典主题下生成按钮恢复主色实底（同设置页「恢复导入时默认值」的主按钮），空闲与生成中同色。
    val classic = frosted && isClassic()
    val plate = when {
        classic -> Modifier.background(scheme.primary)
        frosted -> Modifier.immersiveGlass(20.dp)
        generating -> Modifier.background(scheme.primary.copy(alpha = 0.22f))
        else -> Modifier.background(scheme.primary)
    }
    Box(
        modifier
            .height(56.dp)
            .alpha(if (dimmed) 0.45f else 1f)
            .pressScale(interaction, pressedScale = 0.92f)
            .clip(shape)
            .then(plate)
            .then(if (frosted && !classic) Modifier.border(1.dp, ImmersiveHairline, shape) else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = if (classic) scheme.onPrimary else if (frosted) Color.White else if (generating) scheme.primary else scheme.onPrimary),
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (generating && fraction > 0f) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    // r48：经典主题下底板是主色实底，进度改 onPrimary 才看得见；毛玻璃仍是白进度。
                    .background(
                        if (classic) scheme.onPrimary.copy(alpha = 0.28f)
                        else if (frosted) Color.White.copy(alpha = 0.28f)
                        else scheme.primary.copy(alpha = 0.55f),
                    ),
            )
        } else if (generating && indeterminate) {
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = if (classic) scheme.onPrimary else if (frosted) Color.White else scheme.primary,
                trackColor = if (classic) scheme.onPrimary.copy(alpha = 0.15f)
                else if (frosted) Color.White.copy(alpha = 0.15f)
                else scheme.primary.copy(alpha = 0.15f),
            )
        }
        val ink = if (classic) scheme.onPrimary else if (frosted) ImmersiveInk else if (generating) scheme.onSurface else scheme.onPrimary
        val inkStyle = if (frosted) ImmersiveShadow else null
        if (generating) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    status,
                    color = ink,
                    style = MaterialTheme.typography.titleSmall.copy(shadow = inkStyle),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hint.isNotBlank()) {
                    Text(
                        hint,
                        color = if (frosted && !classic) ImmersiveInkMuted else ink.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall.copy(shadow = inkStyle),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Text(
                label,
                color = ink,
                style = MaterialTheme.typography.titleMedium.copy(shadow = inkStyle),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
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

/**
 * 状态栏那一条。有大图时只盖住状态栏再多一点点：模糊从顶上最实，往下很快淡没。
 * 点这一条不会误开全屏。没有大图时只留出状态栏高度。
 */
@Composable
fun StatusScrim(active: Boolean, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (!active) {
        Spacer(modifier.fillMaxWidth().height(top))
        return
    }
    val sink = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .height(top + 8.dp)
            .clickable(interactionSource = sink, indication = null, onClick = {}),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .glass(
                    fallback = Color.Transparent,
                    radius = 6.dp,
                    fade = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.32f),
                        1f to Color.Transparent,
                    ),
                ),
        )
    }
}

/** 底部一条毛玻璃。收起时一行字，点一下向上打开编辑或选择。 */
@Composable
fun GlassPeekBar(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .immersiveGlass(18.dp)
            .border(1.dp, ImmersiveHairline, shape)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(shadow = ImmersiveShadow),
            color = if (placeholder) ImmersiveInkMuted else ImmersiveInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
        ImmersiveIcon(Lucide.ChevronUp, contentDescription, size = 18.dp)
    }
}

/**
 * 整页背景：最新一张铺满。r27.2 起全 App 只在 AppNav 根挂这一份（AnimatedContent 外面），
 * 进出图库 / 看图页 / 切 tab 都不再拆它，所以背景图不会闪。
 *
 * 换图时先把新图解码完，再让新图从左滑入、旧图向右退出，中间不留黑底。
 * 还没有图时用渐变色块铺底，毛玻璃才有东西可模糊。
 * 上下加一层暗色，好让顶栏和底栏上的字还能看清。毛玻璃控件要放在这一层外面。
 *
 * [slideInKey]：调用方明确标记「这张是刚生成完成的新结果」时才传它的行 id。
 * 只有当前正显示着另一张图、且新图已经解码完成时，才播滑入；其余情况（冷启动、重新挂载、
 * 进出图库/看图页、切 tab）一律直接替换。
 *
 * [cacheKey]：跨重组保留的最后一帧。根背景固定用 "root"，
 * 冷启动 / 场景切换时先画它，避免图还没解码完先闪一下空底。
 *
 * [awaitingMedia]：媒体列表还没完成第一次读库（冷启动）。此时不画 GlassFallback，
 * 用深色底顶着，等数据和解码结果，避免「兜底渐变 → 实图」的跳变。
 */
@Composable
fun ImmersiveBackdrop(
    image: ImageRow?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    slideInKey: Long? = null,
    cacheKey: String? = null,
    awaitingMedia: Boolean = false,
) {
    val ctx = LocalContext.current
    val dark = LocalIsDark.current
    val tap = remember { MutableInteractionSource() }
    var front by remember { mutableStateOf(cacheKey?.let { HeroBackdropCache.get(it) }) }
    var back by remember { mutableStateOf<HeroShot?>(null) }
    // 本次挂载后是否已经真正显示过一张图。缓存恢复出来的那一帧不算——
    // 否则「进图库期间生成完成，再返回生成页」会被误判成换图，又播一次滑入。
    var shownThisMount by remember { mutableStateOf(false) }
    // 解码失败（文件被删/损坏）：别一直停在深色底，退回兜底。
    var decodeFailed by remember { mutableStateOf(false) }
    val slide = remember { Animatable(1f) }
    LaunchedEffect(image?.id, image?.localPath, image?.kind, slideInKey) {
        decodeFailed = false
        if (image == null) {
            // 真的一张图都没有：清掉当前帧，交给 GlassFallback。
            // 注意这里不置 shownThisMount——冷启动第一次拿到图不该算「已经显示过旧图」。
            if (front?.image != null) {
                back = null
                front = HeroShot(null, null)
                slide.snapTo(1f)
                if (cacheKey != null) HeroBackdropCache.put(cacheKey, HeroShot(null, null))
            }
            return@LaunchedEffect
        }
        val bmp = decodeHero(ctx, image)
        if (bmp == null) {
            // 文件没了 / 解不出来：退回兜底，别停在纯黑上
            decodeFailed = true
            back = null
            front = HeroShot(null, null)
            slide.snapTo(1f)
            if (cacheKey != null) HeroBackdropCache.put(cacheKey, HeroShot(null, null))
            return@LaunchedEffect
        }
        val next = HeroShot(image, bmp)
        val current = front
        val same = current?.image?.id == next.image?.id &&
            current?.image?.localPath == next.image?.localPath &&
            current?.bitmap != null && next.bitmap != null
        if (same) {
            shownThisMount = true
            return@LaunchedEffect
        }
        // r27.1：只有「这次生成完成的新结果」才滑入。首次拿到图（current 为空）、
        // 缓存恢复的旧帧、或不是引擎事件指定的那张，都直接替换——不先清空、也不播动画。
        val isNewResult = slideInKey != null && image.id == slideInKey
        val canSlide = isNewResult && shownThisMount && current?.image != null &&
            current.bitmap != null && current.image.id != image.id
        if (!canSlide) {
            back = null
            front = next
            slide.snapTo(1f)
            shownThisMount = true
            if (cacheKey != null) HeroBackdropCache.put(cacheKey, next)
            return@LaunchedEffect
        }
        back = current
        front = next
        slide.snapTo(0f)
        slide.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        back = null
        shownThisMount = true
        if (cacheKey != null) HeroBackdropCache.put(cacheKey, next)
    }
    val photoOn = front?.bitmap != null || back?.bitmap != null
    // 有图/正在加载但还没解出位图：画深色底，不要先闪 GlassFallback。
    // 解码失败时交给下面的 GlassFallback，避免永远停在黑底。
    val pendingCover = front?.bitmap == null && !decodeFailed && (image != null || awaitingMedia)
    val openable = image != null
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSourceIfEnabled()
                .then(
                    if (openable) {
                        Modifier.clickable(interactionSource = tap, indication = null, onClick = onOpen)
                    } else {
                        Modifier
                    },
                ),
        ) {
            val leaving = back
            if (leaving != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = slide.value * size.width },
                ) {
                    HeroStill(leaving, dark, false, Modifier)
                }
            }
            val shown = front
            val share = if (
                leaving == null && image != null && image.kind != "video" && shown?.image?.id == image.id
            ) {
                Modifier.sharedImage("home_img_${image.id}")
            } else {
                Modifier
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = if (leaving != null) (slide.value - 1f) * size.width else 0f
                    }
                    .then(share),
            ) {
                HeroStill(shown, dark, pendingCover, Modifier)
            }
        }
        if (photoOn) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.20f),
                            0.08f to Color.Transparent,
                            0.72f to Color.Transparent,
                            1.00f to Color.Black.copy(alpha = 0.45f),
                        ),
                    ),
            )
        }
    }
}

private data class HeroShot(val image: ImageRow?, val bitmap: ImageBitmap?)

/**
 * 最近画出来的一帧背景，按场景 key 隔离。只留一张（最后显示的那张），
 * 用来扛过 HomeLayer 的重新挂载：先立刻画出旧帧，等解码确认后再直接换成当前图。
 */
private object HeroBackdropCache {
    private val shots = HashMap<String, HeroShot>()
    fun get(key: String): HeroShot? = shots[key]
    fun put(key: String, shot: HeroShot) {
        shots[key] = shot
    }
}

@Composable
private fun HeroStill(shot: HeroShot?, dark: Boolean, pending: Boolean, modifier: Modifier) {
    val bmp = shot?.bitmap
    when {
        bmp != null -> Image(
            bitmap = bmp,
            contentDescription = stringResource(R.string.gacha_hero_open),
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
        // r27.2：有图/正在加载但位图还没解出来 → 纯黑顶住，不再先画兜底渐变。
        // 冷启动有历史图时，用户全程看不到那张灰紫渐变。
        pending -> Box(modifier.fillMaxSize().background(Color.Black))
        // 真的一张图都没有（或解码失败）→ 兜底渐变，毛玻璃才有东西可模糊。
        // r29.1：无图一律走深色配方，不再看 LocalIsDark。沉浸式 UI 是白字体系，
        // 浅色主题的亮底叠上 ImmersiveInk 会把字洗白（反推/文本类工作流一片看不清）。
        // dark 参数仍保留给有图分支的调用语义，此处固定 true。
        // r46：经典主题不画三层径向渐变，用深色纯色顶住（压图白字仍要深底）。
        else -> if (isClassic()) {
            Box(modifier.fillMaxSize().background(DarkBg))
        } else {
            GlassFallback(dark = true, modifier)
        }
    }
}

/** 无图时的底。Compose 直接画渐变，不用 painterResource 去读 XML shape（那种资源会在启动时崩）。 */
@Composable
private fun GlassFallback(dark: Boolean, modifier: Modifier) {
    val top = if (dark) Color(0xFF1A1F29) else Color(0xFFF7F9FC)
    val bottom = if (dark) Color(0xFF0E1116) else Color(0xFFE4E9F2)
    val blue = Color(0xFF5C8CFF).copy(alpha = if (dark) 0.53f else 0.40f)
    val violet = Color(0xFF7B6CFF).copy(alpha = if (dark) 0.47f else 0.33f)
    val teal = Color(0xFF48C7C0).copy(alpha = if (dark) 0.40f else 0.25f)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = (-70).dp, y = (-50).dp)
                .size(300.dp)
                .background(Brush.radialGradient(listOf(blue, Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = (-20).dp)
                .size(340.dp)
                .background(Brush.radialGradient(listOf(violet, Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(220.dp)
                .background(Brush.radialGradient(listOf(teal, Color.Transparent))),
        )
    }
}

/** 背景换图用。解码完才返回，调用方在这之前继续画旧图，避免黑一下。 */
private suspend fun decodeHero(context: Context, row: ImageRow): ImageBitmap? {
    val file = if (row.kind == "video") {
        withContext(Dispatchers.IO) { VideoThumb.thumbFile(row.localPath) }
    } else {
        File(row.localPath)
    } ?: return null
    if (!file.isFile) return null
    val dm = context.resources.displayMetrics
    val edge = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(720, 1600)
    val result = context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(file)
            .size(edge)
            .allowHardware(false)
            .memoryCacheKey(coil.memory.MemoryCache.Key("hero_${row.kind}_${row.id}"))
            .build(),
    )
    val drawable = (result as? SuccessResult)?.drawable ?: return null
    val bitmap = when (drawable) {
        is BitmapDrawable -> drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else -> {
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(created)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            created
        }
    } ?: return null
    return bitmap.asImageBitmap()
}

/**
 * 右下角只露最近一张。多张时后面错开两张，角上标总数。点一下进图库。
 */
@Composable
fun AssetStack(
    images: List<ImageRow>,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val total = images.size
    val desc = stringResource(R.string.gacha_stack_desc, total)
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(118.dp, 108.dp)
            .semantics { contentDescription = desc }
            .pressScale(press)
            .clickable(interactionSource = press, indication = null, onClick = onGallery),
    ) {
        if (total >= 3) {
            StackThumb(
                images[2],
                Modifier.align(Alignment.TopStart).size(62.dp).graphicsLayer { rotationZ = -9f },
            )
        }
        if (total >= 2) {
            StackThumb(
                images[1],
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 4.dp)
                    .size(66.dp)
                    .graphicsLayer { rotationZ = 8f },
            )
        }
        Box(Modifier.align(Alignment.BottomEnd)) {
            StackThumb(images[0], Modifier.size(78.dp))
            if (total > 1) {
                Text(
                    if (total > 99) "99+" else total.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-6).dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun StackThumb(img: ImageRow, modifier: Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            .glass(scheme.surface.copy(alpha = 0.72f), 14.dp, scheme.surface.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.75f), shape)
            .padding(2.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            if (img.kind == "video") {
                VideoThumbCell(img)
            } else {
                AsyncImage(
                    model = thumbRequest(ctx, File(img.localPath), img.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BatchPicker(
    value: Int,
    modifier: Modifier = Modifier,
    showTen: Boolean = false,
    onChange: (Int) -> Unit,
) {
    // E2 fix: material3 1.5.0-alpha09 ButtonGroup(overflowIndicator) crashed in measure
    // (Constraints.copy IllegalArgumentException). Use a plain connected ToggleButton row instead.
    // 高级抽卡多一个「10」，当十连。普通生成仍是 1–8。
    val glass = LocalGlassChrome.current
    val nums = if (showTen) (1..8).toList() + 10 else (1..8).toList()
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        nums.forEachIndexed { index, n ->
            androidx.compose.material3.ToggleButton(
                checked = value == n,
                onCheckedChange = { on -> if (on) onChange(n) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    nums.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = if (glass) glassToggleButtonColors() else androidx.compose.material3.ToggleButtonDefaults.toggleButtonColors(),
                modifier = Modifier.weight(1f),
            ) {
                Text("$n", maxLines = 1, softWrap = false, fontSize = if (showTen) 12.sp else 14.sp)
            }
        }
    }
}
