package app.feddy.sdk.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import app.feddy.sdk.core.FeddyConfig
import app.feddy.sdk.core.FeddyCore
import app.feddy.sdk.core.Rgb

/**
 * The colours the screens use, by role. General UI is the system's own
 * (Material 3, with the wallpaper colours on Android 12+); the brand colour
 * appears only where the iOS SDK puts it — the new-message card, See all,
 * unread dots, the logo-less mark, send, the teammate avatar ground.
 * Bubbles are never tinted: the panel sits inside someone else's app.
 */
@Immutable
internal data class FeddyColors(
    /** The plain page. */
    val page: Color,
    /** Raised surfaces: inputs, chips, the teammate's bubbles. */
    val surface: Color,
    /** The contact's own bubble: one step heavier than [surface]. */
    val ownBubble: Color,
    /** Rules between regions, never an outline around a control. */
    val hairline: Color,
    val text: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val accent: Color,
    /** Readable text on a solid accent fill. */
    val onAccent: Color,
    val error: Color,
)

internal val LocalFeddyColors = staticCompositionLocalOf<FeddyColors> { error("FeddyTheme missing") }

internal object FeddyTheme {
    val colors: FeddyColors
        @Composable get() = LocalFeddyColors.current
}

@Composable
internal fun FeddyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val config by FeddyCore.config.collectAsState()
    val colors = feddyColors(scheme, config)
    MaterialTheme(colorScheme = scheme.copy(primary = colors.accent, onPrimary = colors.onAccent)) {
        CompositionLocalProvider(LocalFeddyColors provides colors, content = content)
    }
}

internal fun feddyColors(scheme: ColorScheme, config: FeddyConfig?): FeddyColors {
    // With no brand colour configured the panel borrows the system accent
    // rather than imposing one of its own.
    val brand = Rgb.parse(config?.brand?.color)
    val accent = brand?.let { Color(it.argb) } ?: scheme.primary
    val accentRgb = brand ?: Rgb.fromArgb(accent.toArgb())
    return FeddyColors(
        page = scheme.surface,
        surface = scheme.surfaceContainerHigh,
        ownBubble = scheme.surfaceContainerHighest,
        hairline = scheme.outlineVariant,
        text = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        tertiaryText = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        accent = accent,
        onAccent = if (accentRgb.needsDarkText) Color.Black else Color.White,
        error = scheme.error,
    )
}
