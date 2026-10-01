package com.mie.kreaworkbench.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val Blue = Color(0xFF2F6BFF)
val BlueDark = Color(0xFF4F7CFF)
val LightBg = Color(0xFFEEF1F6)
val DarkBg = Color(0xFF0E1116)
val DarkCard = Color(0xFF1A1D23)
val Ink = Color(0xFF152033)

/** Fullscreen photo canvas stays black so the image is not tinted by the app theme. */
val PhotoBackdrop = Color(0xFF000000)
val PhotoOnBackdrop = Color(0xFFFFFFFF)
val PhotoScrim = Color(0xCC000000)

val LocalIsDark = staticCompositionLocalOf { false }

val KreaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val KreaTypography = Typography()

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B44),
    secondary = Color(0xFF4B5F86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF061A35),
    tertiary = Color(0xFF6B4FCF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF23005C),
    background = LightBg,
    onBackground = Ink,
    surface = Color(0xFFF7F9FC),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE1E6EF),
    onSurfaceVariant = Color(0xFF545E6E),
    surfaceDim = Color(0xFFD9DDE5),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F6FA),
    surfaceContainer = Color(0xFFEEF1F6),
    surfaceContainerHigh = Color(0xFFE6EAF1),
    surfaceContainerHighest = Color(0xFFDEE3EB),
    outline = Color(0xFF747C8A),
    outlineVariant = Color(0xFFC4CBD6),
    inverseSurface = Color(0xFF2C313A),
    inverseOnSurface = Color(0xFFF2F4F8),
    inversePrimary = BlueDark,
    scrim = Color(0x99000000),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Blue,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF001B44),
    primaryContainer = Color(0xFF1E3F8A),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB4C5E4),
    onSecondary = Color(0xFF1C3048),
    secondaryContainer = Color(0xFF334666),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFFCDB4FF),
    onTertiary = Color(0xFF3B1D73),
    tertiaryContainer = Color(0xFF533A9A),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = DarkBg,
    onBackground = Color(0xFFE7ECF4),
    surface = DarkCard,
    onSurface = Color(0xFFE7ECF4),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFFB7C0CC),
    surfaceDim = Color(0xFF0B0D11),
    surfaceBright = Color(0xFF2A3038),
    surfaceContainerLowest = Color(0xFF090B0E),
    surfaceContainerLow = Color(0xFF12151A),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF242830),
    surfaceContainerHighest = Color(0xFF2E333C),
    outline = Color(0xFF8B94A3),
    outlineVariant = Color(0xFF3A4150),
    inverseSurface = Color(0xFFE7ECF2),
    inverseOnSurface = Color(0xFF1A1D23),
    inversePrimary = Blue,
    scrim = Color(0xCC000000),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = BlueDark,
)

@Composable
fun pageBackground(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalIsDark.current) scheme.background else scheme.surfaceContainer
}

@Composable
fun cardBorder(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KreaTheme(
    mode: String,
    dynamicColor: Boolean = false,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = themeIsDark(mode, isSystemInDarkTheme())
    val ctx = LocalContext.current
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    val scheme = if (dark && amoled) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceDim = Color.Black,
        )
    } else {
        base
    }
    SystemBarAppearance(dark)
    CompositionLocalProvider(
        LocalIsDark provides dark,
        LocalOverscrollFactory provides null,
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            typography = KreaTypography,
            shapes = KreaShapes,
            motionScheme = MotionScheme.expressive(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (dark) scheme.background else scheme.surfaceContainer,
                contentColor = scheme.onBackground,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                content()
            }
        }
    }
}

@Composable
fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    DisposableEffect(dark) {
        val c = WindowCompat.getInsetsController(window, view)
        val oldS = c.isAppearanceLightStatusBars
        val oldN = c.isAppearanceLightNavigationBars
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
        onDispose {
            c.isAppearanceLightStatusBars = oldS
            c.isAppearanceLightNavigationBars = oldN
        }
    }
}

fun themeIsDark(mode: String, systemDark: Boolean): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

@Composable
fun warningContainer(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF3A2A10) else Color(0xFFFFF4E0)

@Composable
fun onWarningContainer(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFE0A3) else Color(0xFF6D4A00)
