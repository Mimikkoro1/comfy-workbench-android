@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.mie.kreaworkbench.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mie.kreaworkbench.ui.theme.cardBorder
import com.mie.kreaworkbench.ui.theme.isClassic

/** 玻璃弹层里为 true。模型和采样页不包这层，控件保持原来的配色。 */
val LocalGlassChrome = staticCompositionLocalOf { false }

/**
 * 玻璃底上的「实心胶囊类」控件（下拉、勾选）是否也换成玻璃。
 *
 * 故意和 [LocalGlassChrome] 分开：生成页参数抽屉本身就是玻璃卡（`LocalGlassChrome = true`），
 * 它的外观 r26.1 已验收，不能再跟着公共件一起变。所以那边保持默认 false，
 * 只有模型采样页、工作流导入配置页在卡内显式置 true。
 */
val LocalGlassFields = staticCompositionLocalOf { false }

private val GlassFieldFill = Color.White.copy(alpha = 0.08f)
private val GlassFieldFillDisabled = Color.White.copy(alpha = 0.04f)
private val GlassFieldStroke = Color.White.copy(alpha = 0.70f)

@Composable
fun glassColorScheme(): ColorScheme {
    if (isClassic()) return MaterialTheme.colorScheme
    val scheme = MaterialTheme.colorScheme
    return scheme.copy(
        primary = Color.White,
        onPrimary = Color.Black,
        onSurface = ImmersiveInk,
        onSurfaceVariant = ImmersiveInkMuted,
        surface = Color.White.copy(alpha = 0.08f),
        surfaceBright = Color.White.copy(alpha = 0.12f),
        surfaceContainerLowest = Color.White.copy(alpha = 0.04f),
        surfaceContainerLow = Color.White.copy(alpha = 0.06f),
        surfaceContainer = Color.White.copy(alpha = 0.10f),
        surfaceContainerHigh = Color.White.copy(alpha = 0.14f),
        surfaceContainerHighest = Color.White.copy(alpha = 0.18f),
        surfaceVariant = Color.White.copy(alpha = 0.10f),
        outline = ImmersiveHairline,
        outlineVariant = ImmersiveHairline,
        error = GlassDanger,
        primaryContainer = Color.White.copy(alpha = 0.22f),
        onPrimaryContainer = Color.White,
        secondaryContainer = Color.White.copy(alpha = 0.20f),
        onSecondaryContainer = Color.White,
    )
}

@Composable
fun glassTextFieldColors() =
    if (isClassic()) {
        OutlinedTextFieldDefaults.colors()
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = ImmersiveInk,
            unfocusedTextColor = ImmersiveInk,
            disabledTextColor = ImmersiveInkMuted,
            focusedContainerColor = GlassFieldFill,
            unfocusedContainerColor = GlassFieldFill,
            disabledContainerColor = GlassFieldFillDisabled,
            cursorColor = Color.White,
            errorCursorColor = GlassDanger,
            focusedBorderColor = GlassFieldStroke,
            unfocusedBorderColor = ImmersiveHairline,
            disabledBorderColor = Color.White.copy(alpha = 0.12f),
            errorBorderColor = GlassDanger,
            focusedPlaceholderColor = ImmersiveInkMuted,
            unfocusedPlaceholderColor = ImmersiveInkMuted,
            disabledPlaceholderColor = ImmersiveInkMuted,
            focusedLabelColor = ImmersiveInk,
            unfocusedLabelColor = ImmersiveInkMuted,
            disabledLabelColor = ImmersiveInkMuted,
            focusedLeadingIconColor = ImmersiveInk,
            unfocusedLeadingIconColor = ImmersiveInkMuted,
            focusedTrailingIconColor = ImmersiveInk,
            unfocusedTrailingIconColor = ImmersiveInkMuted,
        )
    }

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = if (minLines > 1) minLines else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = if (keyboardType == KeyboardType.Text) keyboardOptions else KeyboardOptions(keyboardType = keyboardType),
        colors = glassTextFieldColors(),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    glassFields: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    // 传 true 才点亮；不传时**继承外层**，别把页面级的 true 又打回 false
    // （模型采样页就是整页 provider(true)，卡里再 provide false 会让下拉变回白胶囊）。
    val fields = glassFields || LocalGlassFields.current
    val classic = isClassic()
    CompositionLocalProvider(
        LocalContentColor provides if (classic) MaterialTheme.colorScheme.onSurface else ImmersiveInk,
        LocalGlassChrome provides !classic,
        // 只影响下拉/勾选这类实心控件。生成页参数抽屉的卡片不传，外观与 r26.1 一致。
        LocalGlassFields provides (fields && !classic),
        LocalOverPhoto provides false,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>())
                .clip(shape)
                .then(
                    if (classic) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceBright)
                    } else {
                        Modifier.immersiveGlass(20.dp)
                    },
                )
                .border(1.dp, if (classic) cardBorder() else ImmersiveHairline, shape)
                .padding(padding),
            content = content,
        )
    }
}

@Composable
fun glassSwitchColors() =
    if (isClassic()) {
        SwitchDefaults.colors()
    } else {
        SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color.White.copy(alpha = 0.45f),
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
            uncheckedBorderColor = ImmersiveHairline,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.40f),
            disabledCheckedTrackColor = Color.White.copy(alpha = 0.16f),
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.35f),
            disabledUncheckedTrackColor = Color.White.copy(alpha = 0.08f),
            disabledUncheckedBorderColor = Color.White.copy(alpha = 0.12f),
        )
    }

@Composable
fun glassSliderColors() =
    if (isClassic()) {
        SliderDefaults.colors()
    } else {
        SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White.copy(alpha = 0.85f),
            inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            disabledThumbColor = Color.White.copy(alpha = 0.40f),
            disabledActiveTrackColor = Color.White.copy(alpha = 0.30f),
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
        )
    }

/** 玻璃底上的勾选框：白勾黑底、未选是半透明白描边，不再是一块实心底。 */
@Composable
fun glassCheckboxColors() =
    if (isClassic()) {
        CheckboxDefaults.colors()
    } else {
        CheckboxDefaults.colors(
            checkedColor = Color.White,
            checkmarkColor = Color.Black,
            uncheckedColor = ImmersiveInkMuted,
            disabledCheckedColor = Color.White.copy(alpha = 0.40f),
            disabledUncheckedColor = Color.White.copy(alpha = 0.28f),
        )
    }

@Composable
fun glassToggleButtonColors() =
    if (isClassic()) {
        ToggleButtonDefaults.toggleButtonColors()
    } else {
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = ImmersiveInk,
            checkedContainerColor = Color.White,
            checkedContentColor = Color(0xFF1A1D23),
        )
    }

@Composable
fun glassChipColors() =
    if (isClassic()) {
        FilterChipDefaults.filterChipColors()
    } else {
        FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.08f),
            labelColor = ImmersiveInk,
            iconColor = ImmersiveInk,
            selectedContainerColor = Color.White.copy(alpha = 0.28f),
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
            disabledContainerColor = GlassFieldFillDisabled,
            disabledLabelColor = ImmersiveInkMuted,
        )
    }
