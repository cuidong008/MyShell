package com.dxkj.myshell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

private val LightColors = lightColorScheme(
    // ShellBean 风格参考：更中性的灰底 + 青绿/绿色强调，避免紫/蓝的“塑料感”
    primary = Color(0xFF19C37D),
    onPrimary = Color(0xFF062016),
    primaryContainer = Color(0xFFCFFAE6),
    onPrimaryContainer = Color(0xFF052014),

    secondary = Color(0xFF21B6C7),
    onSecondary = Color(0xFF001F23),
    secondaryContainer = Color(0xFFB7F2FA),
    onSecondaryContainer = Color(0xFF002022),

    tertiary = Color(0xFF94A3B8),
    onTertiary = Color(0xFF0B1220),
    tertiaryContainer = Color(0xFFE2E8F0),
    onTertiaryContainer = Color(0xFF0B1220),

    background = Color(0xFFF5F6F7),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF0F2F4),
    onSurfaceVariant = Color(0xFF4B5563),

    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),

    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A),
)

private val DarkColors = darkColorScheme(
    // ShellBean 风格参考：深灰底、低饱和，强调用青绿
    primary = Color(0xFF19C37D),
    onPrimary = Color(0xFF062016),
    primaryContainer = Color(0xFF0C3B29),
    onPrimaryContainer = Color(0xFFCFFAE6),

    secondary = Color(0xFF21B6C7),
    onSecondary = Color(0xFF001F23),
    secondaryContainer = Color(0xFF0D3A40),
    onSecondaryContainer = Color(0xFFB7F2FA),

    tertiary = Color(0xFF94A3B8),
    onTertiary = Color(0xFF0B1220),
    tertiaryContainer = Color(0xFF1F2937),
    onTertiaryContainer = Color(0xFFE5E7EB),

    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF0F141B),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF151B23),
    onSurfaceVariant = Color(0xFFA3AAB6),

    outline = Color(0xFF2A3340),
    outlineVariant = Color(0xFF1B2430),

    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

@Composable
fun MyShellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}

