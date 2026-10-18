package com.mie.kreaworkbench.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 压在大图上的控件：固定暗底、白字白图标。不跟主题色，也不跟图片取色。 */
val ImmersiveGlassFallback = Color.Black.copy(alpha = 0.58f)
val ImmersiveGlassTint = Color.Black.copy(alpha = 0.50f)
val ImmersiveInk = Color.White
val ImmersiveInkMuted = Color.White.copy(alpha = 0.72f)
val ImmersiveHairline = Color.White.copy(alpha = 0.22f)
val ImmersiveShadow = Shadow(
    color = Color.Black.copy(alpha = 0.65f),
    offset = Offset(0f, 1.2f),
    blurRadius = 4f,
)

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
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
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
