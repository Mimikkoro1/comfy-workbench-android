package com.mie.kreaworkbench.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.dokar.sonner.Toaster
import com.dokar.sonner.ToasterState
import com.dokar.sonner.rememberToasterState
import com.mie.kreaworkbench.ui.theme.LocalIsDark

val LocalToaster = staticCompositionLocalOf<ToasterState?> { null }

@Composable
fun AppToaster(content: @Composable () -> Unit) {
    val toaster = rememberToasterState()
    CompositionLocalProvider(LocalToaster provides toaster) {
        content()
        // r27：换成深色玻璃观感——深底、1dp 细白边、白字、圆角。
        // sonner 是独立 Popup，拿不到共享背景的实时模糊，这里用着色兜底；
        // 进出场动画不传参数，保留 sonner 默认的滑入/淡出，别改。
        Toaster(
            state = toaster,
            darkTheme = LocalIsDark.current,
            richColors = true,
            alignment = Alignment.TopCenter,
            contentColor = { ImmersiveInk },
            background = { SolidColor(GlassPanelFallback) },
            border = { BorderStroke(1.dp, ImmersiveHairline) },
            shape = { RoundedCornerShape(16.dp) },
        )
    }
}

fun notify(toaster: ToasterState?, context: Context, message: String) {
    if (message.isBlank()) return
    if (toaster != null) {
        toaster.show(message)
    } else {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
