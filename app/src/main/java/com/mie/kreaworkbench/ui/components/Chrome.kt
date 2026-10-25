@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.mie.kreaworkbench.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sparkles
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.ui.motion.buzz
import com.mie.kreaworkbench.ui.motion.pressScale
import com.mie.kreaworkbench.ui.theme.cardBorder
import com.mie.kreaworkbench.ui.theme.isClassic

enum class AppTab(@StringRes val labelRes: Int) {
    T2I(R.string.tab_generate),
    WORKFLOWS(R.string.tab_library),
    MODELS(R.string.tab_models),
    SETTINGS(R.string.title_settings),
}

@Composable
fun KreaCard(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Column(
            modifier
                .fillMaxWidth()
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>())
                .clip(shape)
                .background(scheme.surfaceBright)
                .border(1.dp, cardBorder(), shape)
                .padding(padding),
            content = content,
        )
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScreenHeader(title: String, actions: @Composable () -> Unit = {}, ink: Color? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = ink ?: MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun HeaderIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun BottomDock(tab: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraLarge
    val haptic = LocalHapticFeedback.current
    val classic = isClassic()
    val scheme = MaterialTheme.colorScheme
    // 固定暗底白字，不跟主题色，浅色图和深色图都看得清。
    val itemShape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(shape)
                .immersiveGlass(28.dp)
                .border(1.dp, ImmersiveHairline, shape),
        )
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { item ->
                val selected = tab == item
                // 经典：选中态用主色（素皮的样子）；毛玻璃：白字体系不变。
                val ink = when {
                    selected && classic -> scheme.primary
                    selected -> ImmersiveInk
                    classic -> scheme.onSurfaceVariant
                    else -> ImmersiveInkMuted
                }
                val press = remember { MutableInteractionSource() }
                val pill by animateColorAsState(
                    targetValue = when {
                        selected && classic -> scheme.primary.copy(alpha = 0.14f)
                        selected -> Color.White.copy(alpha = 0.16f)
                        else -> Color.Transparent
                    },
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Color>(),
                    label = "tab-pill",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(press)
                        .clip(itemShape)
                        .background(pill, itemShape)
                        .clickable(
                            interactionSource = press,
                            indication = ripple(color = if (classic) scheme.primary else Color.White),
                        ) {
                            haptic.buzz(HapticFeedbackType.SegmentTick)
                            onSelect(item)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val label = stringResource(item.labelRes)
                        ImmersiveIcon(
                            tabIcon(item),
                            label,
                            size = 22.dp,
                            alpha = if (selected) 1f else 0.72f,
                            tint = when {
                                selected && classic -> scheme.primary
                                classic -> scheme.onSurfaceVariant
                                else -> null
                            },
                        )
                        Text(
                            label,
                            color = ink,
                            style = MaterialTheme.typography.labelSmall.copy(shadow = ImmersiveShadow),
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.T2I -> Lucide.Sparkles
    AppTab.WORKFLOWS -> Lucide.FolderOpen
    AppTab.MODELS -> Lucide.Cpu
    AppTab.SETTINGS -> Lucide.Settings
}
