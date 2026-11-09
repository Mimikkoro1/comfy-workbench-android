package com.mie.kreaworkbench.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mie.kreaworkbench.ui.theme.isClassic
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }
val LocalBlurEnabled = staticCompositionLocalOf { true }

@Composable
fun Modifier.hazeSourceIfEnabled(): Modifier {
    if (isClassic()) return this
    val state = LocalHazeState.current
    val enabled = LocalBlurEnabled.current && Build.VERSION.SDK_INT >= 31
    if (state == null || !enabled) return this
    return hazeSource(state = state)
}

@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glass(
    fallback: Color,
    radius: androidx.compose.ui.unit.Dp = 16.dp,
    tint: Color? = null,
    fade: HazeProgressive? = null,
    shape: RoundedCornerShape = RoundedCornerShape(0.dp),
): Modifier {
    if (isClassic()) return background(fallback)
    val state = LocalHazeState.current
    val enabled = LocalBlurEnabled.current && Build.VERSION.SDK_INT >= 31
    if (state == null || !enabled) return background(fallback)
    // r53 液态玻璃：GlassStyle.clear 保住透底，轻黑 tint 压对比（起点 0.22，调厚勿超 0.28），
    // specular/ambient 出边光与菲涅尔感。shape 必须与外层 clip 一致，否则折射裁角错位/方角漏光。
    val style = GlassStyle.clear.then {
        if (tint != null) this.tint(tint)
        shape(shape)
        specularIntensity(0.5f)
        ambientResponse(0.2f)
    }
    // r53：开启路径换 hazeGlass 液态玻璃管线（折射/边光），采景走 Sources——与 hazeSourceIfEnabled
    // 挂的源配套，不用 Backdrop（不默认开 isPlatformBackdropEnabled 实验旗）。
    // 禁止玻璃路径垫 .background(fallback) 实底：实底会盖死折射和边光，退回 r52 的灰黑扁块。
    // radius 是历史 blur 半径，液态玻璃不消费；fade 玻璃路径暂不支持（optics 通道未接，见 FEEDBACK_r53）。
    return hazeGlass(input = HazeInput.Sources(state), style = style)
}
