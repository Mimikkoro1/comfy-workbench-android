package com.mie.kreaworkbench.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mie.kreaworkbench.ui.motion.pressScale

@Composable
fun CardGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlassChrome.current
    Column(modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            GroupTitle(title)
        }
        // 玻璃语境里一组列表共用一张玻璃卡，行间只留细分割线；
        // 不再给每一行单独套一张厚卡（r29 真机反馈：五张独立卡太碎）。
        if (glass) {
            GlassCard(padding = 0.dp) { content() }
        } else {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupTitle(text: String) {
    Text(
        text,
        color = if (LocalGlassChrome.current) ImmersiveInk else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmallEmphasized,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp),
    )
}

/**
 * 分组列表里的一行。设置/关于页在用。
 *
 * [LocalGlassChrome] 为 true 时（r29 设置页）：行本身透明，整组由 [CardGroup] 包成一张
 * `GlassCard`，行间只画一条细白分割线；标题白字、副文浅白。
 * 否则保持原来的 `surfaceBright` 实心条 + 首尾圆角 + 2dp 缝（给以后经典主题留路，别删素色分支）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupedItem(
    index: Int,
    count: Int,
    onClick: (() -> Unit)? = null,
    headline: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val glass = LocalGlassChrome.current
    val outer = 20.dp
    val inner = 4.dp
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    val top by animateDpAsState(if (pressed || index == 0) outer else inner, spec, label = "grouped-top")
    val bottom by animateDpAsState(if (pressed || index == count - 1) outer else inner, spec, label = "grouped-bottom")
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    if (glass) {
        if (index > 0) {
            HorizontalDivider(
                thickness = 1.dp,
                color = ImmersiveHairline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    } else if (index > 0) {
        Spacer(Modifier.height(2.dp))
    }
    ListItem(
        headlineContent = headline,
        supportingContent = supporting,
        leadingContent = leading,
        trailingContent = trailing,
        colors = if (glass) {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = ImmersiveInk,
                supportingColor = ImmersiveInkMuted,
                leadingIconColor = ImmersiveInk,
                trailingIconColor = ImmersiveInkMuted,
            )
        } else {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright)
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressScale(src) else Modifier)
            .then(if (glass) Modifier else Modifier.clip(shape))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = src,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    )
}
