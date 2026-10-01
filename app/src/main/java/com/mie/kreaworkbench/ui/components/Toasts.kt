package com.mie.kreaworkbench.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
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
        Toaster(
            state = toaster,
            darkTheme = LocalIsDark.current,
            richColors = true,
            alignment = Alignment.TopCenter,
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
