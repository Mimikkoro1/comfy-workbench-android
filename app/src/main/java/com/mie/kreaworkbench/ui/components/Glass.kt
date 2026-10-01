package com.mie.kreaworkbench.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3

val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }
val LocalBlurEnabled = staticCompositionLocalOf { true }

@Composable
fun Modifier.hazeSourceIfEnabled(): Modifier {
    val state = LocalHazeState.current
    val enabled = LocalBlurEnabled.current && Build.VERSION.SDK_INT >= 31
    if (state == null || !enabled) return this
    return hazeSource(state = state)
}

@Composable
fun Modifier.glass(fallback: Color, radius: androidx.compose.ui.unit.Dp = 16.dp, tint: Color? = null): Modifier {
    val state = LocalHazeState.current
    val enabled = LocalBlurEnabled.current && Build.VERSION.SDK_INT >= 31
    if (state == null || !enabled) return background(fallback)
    val style = HazeBlurStyle.Material3 {
        blurRadius(radius)
        if (tint != null) backgroundColor(tint)
    }
    return hazeBlur(input = HazeInput.Backdrop(state), style = style)
}
