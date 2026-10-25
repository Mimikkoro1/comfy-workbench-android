package com.mie.kreaworkbench.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mie.kreaworkbench.ui.theme.isClassic

/** 压在大图上的控件：固定暗底、白字白图标。不跟主题色，也不跟图片取色。 */
val ImmersiveGlassFallback: Color
    @Composable @ReadOnlyComposable
    get() = if (isClassic()) MaterialTheme.colorScheme.surfaceBright else Color.Black.copy(alpha = 0.58f)
val ImmersiveGlassTint: Color
    @Composable @ReadOnlyComposable
    get() = if (isClassic()) MaterialTheme.colorScheme.surfaceBright else Color.Black.copy(alpha = 0.50f)
val ImmersiveInk: Color
    @Composable @ReadOnlyComposable
    get() = if (themedInk()) MaterialTheme.colorScheme.onSurface else Color.White
val ImmersiveInkMuted: Color
    @Composable @ReadOnlyComposable
    get() = if (themedInk()) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.72f)
val ImmersiveHairline: Color
    @Composable @ReadOnlyComposable
    get() = if (themedInk()) MaterialTheme.colorScheme.outlineVariant else Color.White.copy(alpha = 0.22f)
val ImmersiveShadow: Shadow
    @Composable @ReadOnlyComposable
    get() = if (themedInk()) {
        Shadow(color = Color.Transparent, offset = Offset.Zero, blurRadius = 0f)
    } else {
        Shadow(
            color = Color.Black.copy(alpha = 0.65f),
            offset = Offset(0f, 1.2f),
            blurRadius = 4f,
        )
    }

/** 经典主题下压在实心面上时字色跟主题；直接压在大图/黑底上时仍是白字。毛玻璃下永远 false。 */
@Composable
@ReadOnlyComposable
internal fun themedInk(): Boolean = isClassic() && !LocalOverPhoto.current

/** 经典主题里「直接压在大图上」的子树（生成页沉浸态、看图页）由根部 provide true；面内部 provide 回 false。 */
val LocalOverPhoto = staticCompositionLocalOf { false }

@Composable
fun Modifier.immersiveGlass(radius: Dp = 18.dp): Modifier =
    glass(ImmersiveGlassFallback, radius, ImmersiveGlassTint)

/** 白图标，底下垫一层很轻的暗影，浅色图和深色图上都站得住。 */
@Composable
fun ImmersiveIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    alpha: Float = 1f,
    tint: Color? = null,
) {
    val themed = themedInk()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (themed) {
            // tint 仅经典跟主题分支可用（经典里给选中态换主色之类）；毛玻璃分支不读它。
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = (tint ?: ImmersiveInk).copy(alpha = alpha),
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.55f * alpha),
                modifier = Modifier
                    .size(size)
                    .offset(y = 1.dp)
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(2.dp) else Modifier),
            )
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(size),
            )
        }
    }
}
