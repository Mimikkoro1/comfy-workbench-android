package com.mie.kreaworkbench.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.theme.pageBackground

/**
 * [immersive] = 压在大图/深色底上的页面（如 r27 图库）：白字、深色玻璃、白图标。
 * 默认关闭，其他页面保持原来的主题色。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LargeBarScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    blur: Boolean = false,
    immersive: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scheme = MaterialTheme.colorScheme
    val barColor = if (blur) Color.Transparent else pageBackground()
    val fallback = if (immersive) ImmersiveGlassFallback else scheme.surfaceContainerLow.copy(alpha = 0.92f)
    val tint = if (immersive) ImmersiveGlassTint else null
    val ink = if (immersive) ImmersiveInk else scheme.primary
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = ink,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Lucide.ArrowLeft,
                                contentDescription = stringResource(R.string.action_back),
                                tint = if (immersive) ImmersiveInk else scheme.onSurface,
                            )
                        }
                    }
                },
                actions = actions,
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor,
                    scrolledContainerColor = barColor,
                    titleContentColor = ink,
                    navigationIconContentColor = if (immersive) ImmersiveInk else scheme.onSurface,
                    actionIconContentColor = ink,
                ),
                modifier = if (blur) Modifier.glass(fallback, tint = tint) else Modifier,
            )
        },
        content = content,
    )
}
