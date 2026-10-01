package com.mie.kreaworkbench.ui.screens.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mie.kreaworkbench.ui.motion.fullRequest
import com.mie.kreaworkbench.ui.motion.sharedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun ZoomImage(
    file: File,
    imageId: Long,
    heroPrefix: String = "home",
    shared: Boolean,
    active: Boolean,
    onToggleChrome: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    onPull: (dy: Float, released: Boolean) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    // E3: read image bounds synchronously (header only, cheap) so the very first frame of the hero
    // transition already knows the aspect ratio.
    val dims = remember(file) {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath, o) }
        o.outWidth.coerceAtLeast(1) to o.outHeight.coerceAtLeast(1)
    }
    val iw = dims.first
    val ih = dims.second
    val heroScope = com.mie.kreaworkbench.ui.motion.LocalAnimScope.current
    // 1 = viewer fully shown (Fit), 0 = collapsed into the thumbnail (Crop). Same timing as the bounds.
    val heroFit: Float = if (shared && heroScope != null) {
        val v by heroScope.transition.animateFloat(
            transitionSpec = { tween(com.mie.kreaworkbench.ui.motion.HERO_MS, easing = com.mie.kreaworkbench.ui.motion.HeroEasing) },
            label = "hero-fit",
        ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
        v
    } else {
        1f
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val boxW = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        val boxH = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
        val fit = min(boxW / iw, boxH / ih).coerceAtLeast(0.01f)
        val maxScale = max(3f, fit * 8f)
        var scale by remember(file) { mutableFloatStateOf(-1f) }
        var offset by remember(file) { mutableStateOf(Offset.Zero) }
        if (scale < 0f) scale = fit
        val pullCb by rememberUpdatedState(onPull)
        LaunchedEffect(active, fit) {
            if (active) {
                scale = fit
                offset = Offset.Zero
                onZoomed(false)
            }
        }
        fun clamp(o: Offset, s: Float): Offset {
            val mx = max(0f, (iw * s - boxW) / 2f)
            val my = max(0f, (ih * s - boxH) / 2f)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        Box(
            Modifier.fillMaxSize().sharedImage(if (shared) "${heroPrefix}_img_$imageId" else null, viewer = true).pointerInput(fit, maxScale) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val atFit = abs(scale - fit) < fit * 0.12f
                        val next = if (atFit) 1f.coerceAtMost(maxScale) else fit
                        val center = Offset(boxW / 2f, boxH / 2f)
                        val local = (tap - center - offset) / scale
                        offset = clamp(tap - center - local * next, next)
                        scale = next
                        onZoomed(abs(next - fit) > fit * 0.08f)
                    },
                    onTap = { onToggleChrome() },
                )
            }.pointerInput(fit, maxScale) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pullY = 0f
                    var pulling = false
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val zooming = abs(zoom - 1f) > 0.01f
                        val atFit = abs(scale - fit) <= fit * 0.12f
                        if (!zooming && atFit && event.changes.count { it.pressed } <= 1) {
                            if (!pulling && pan.y > 6f && abs(pan.y) > abs(pan.x) * 1.2f) pulling = true
                            if (pulling) {
                                pullY = (pullY + pan.y).coerceAtLeast(0f)
                                pullCb(pullY, false)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }
                        }
                        val panning = abs(scale - fit) > fit * 0.08f && (pan.x != 0f || pan.y != 0f)
                        if (zooming || panning) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            val next = (scale * zoom).coerceIn(min(fit, 1f), maxScale)
                            offset = clamp(offset + pan, next)
                            scale = next
                            onZoomed(abs(next - fit) > fit * 0.08f)
                        }
                    } while (event.changes.any { it.pressed })
                    if (pulling) pullCb(pullY, true)
                }
            },
        ) {
            // E3: during the hero transition this box is remeasured to the animating bounds; scale the
            // image relative to the *current* bounds, interpolating Crop (thumbnail) -> Fit (viewer).
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val ch = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val fitNow = min(cw / iw, ch / ih)
                val cropNow = max(cw / iw, ch / ih)
                val rel = fitNow / fit
                val drawScale = androidx.compose.ui.util.lerp(cropNow, scale * rel, heroFit)
                val drawOffset = offset * (rel * heroFit)
                Box(Modifier.fillMaxSize().wrapContentSize(unbounded = true, align = Alignment.Center)) {
                    AsyncImage(
                        model = fullRequest(ctx, file, imageId),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.graphicsLayer {
                            scaleX = drawScale
                            scaleY = drawScale
                            translationX = drawOffset.x
                            translationY = drawOffset.y
                        }.size(with(LocalDensity.current) { iw.toDp() }, with(LocalDensity.current) { ih.toDp() }),
                    )
                }
            }
        }
    }
}
