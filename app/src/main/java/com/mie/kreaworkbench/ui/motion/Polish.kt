package com.mie.kreaworkbench.ui.motion

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Size
import com.mie.kreaworkbench.service.LiveJob
import java.io.File
import kotlin.math.tan

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
val LocalExtraBottom = compositionLocalOf { 0.dp }

fun HapticFeedback.buzz(type: HapticFeedbackType) {
    try {
        performHapticFeedback(type)
    } catch (_: Exception) {
    }
}

fun thumbRequest(context: Context, file: File, id: Long): ImageRequest =
    ImageRequest.Builder(context)
        .data(file)
        .size(360)
        .memoryCacheKey(MemoryCache.Key("img_$id"))
        .crossfade(true)
        .build()

fun fullRequest(context: Context, file: File, id: Long): ImageRequest =
    ImageRequest.Builder(context)
        .data(file)
        .size(Size.ORIGINAL)
        .memoryCacheKey(MemoryCache.Key("img_full_$id"))
        .placeholderMemoryCacheKey(MemoryCache.Key("img_$id"))
        .crossfade(true)
        .build()

/** Duration/easing shared by the thumbnail<->viewer hero transition and the viewer scene fade. */
const val HERO_MS = 380
val HeroEasing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * E3: hero transition between a thumbnail and the fullscreen viewer.
 * The viewer side ([viewer] = true) stays fully opaque for the whole transition and renders the image
 * itself (interpolating Crop -> Fit, see ZoomImage), so there is no semi-transparent cross-fade block.
 * The thumbnail side is hidden during the transition and only appears (snap) at the very end, exactly
 * when the bounds have landed on it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedImage(key: Any?, viewer: Boolean = false): Modifier {
    if (key == null) return this
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalAnimScope.current ?: return this
    return with(shared) {
        this@sharedImage.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = anim,
            enter = if (viewer) androidx.compose.animation.EnterTransition.None
            else androidx.compose.animation.fadeIn(androidx.compose.animation.core.snap(delayMillis = HERO_MS)),
            exit = if (viewer) androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap(delayMillis = HERO_MS))
            else androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap()),
            boundsTransform = { _, _ -> tween(HERO_MS, easing = HeroEasing) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp)),
            zIndexInOverlay = if (viewer) 1f else 0f,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
        label = "shimmer-shift",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceBright
    val angle = tan(Math.toRadians(20.0)).toFloat()
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(shift * 800f - 400f, 0f),
        end = Offset(shift * 800f, 800f * angle),
    )
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(brush))
}

@Composable
fun JobHaptics(jobs: List<LiveJob>) {
    val haptic = LocalHapticFeedback.current
    val seen = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(jobs) {
        jobs.forEach { job ->
            val prev = seen[job.clientJobId]
            if (prev != null && prev !in TERMINAL_PHASES && job.phase in setOf("success", "partial")) {
                haptic.buzz(HapticFeedbackType.Confirm)
            }
            if (prev != null && prev !in TERMINAL_PHASES && job.phase == "failed") {
                haptic.buzz(HapticFeedbackType.Reject)
            }
            seen[job.clientJobId] = job.phase
        }
    }
}

private val TERMINAL_PHASES = setOf("success", "partial", "failed", "cancelled")

@Composable
fun ErrorHaptic(message: String) {
    val haptic = LocalHapticFeedback.current
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(message) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (message.isNotBlank()) haptic.buzz(HapticFeedbackType.Reject)
    }
}
