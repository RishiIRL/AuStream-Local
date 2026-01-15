package com.austream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    // Catppuccin Latte (https://catppuccin.com/palette)
    primary = Color(0xFF8839EF), // Mauve
    onPrimary = Color(0xFFEFF1F5), // Base
    primaryContainer = Color(0xFFDCE0E8), // Crust
    onPrimaryContainer = Color(0xFF4C4F69), // Text
    secondary = Color(0xFF1E66F5), // Blue
    onSecondary = Color(0xFFEFF1F5),
    secondaryContainer = Color(0xFFCCD0DA), // Surface0
    onSecondaryContainer = Color(0xFF4C4F69),
    tertiary = Color(0xFF179299), // Teal
    onTertiary = Color(0xFFEFF1F5),
    tertiaryContainer = Color(0xFFE6E9EF), // Mantle
    onTertiaryContainer = Color(0xFF4C4F69),
    background = Color(0xFFEFF1F5), // Base
    onBackground = Color(0xFF4C4F69), // Text
    surface = Color(0xFFEFF1F5), // Base
    onSurface = Color(0xFF4C4F69), // Text
    surfaceVariant = Color(0xFFE6E9EF), // Mantle
    onSurfaceVariant = Color(0xFF5C5F77), // Subtext1
    outline = Color(0xFF9CA0B0), // Overlay0
    outlineVariant = Color(0xFFBCC0CC), // Surface1
    error = Color(0xFFD20F39), // Red
    onError = Color(0xFFEFF1F5),
    errorContainer = Color(0xFFFFDCC1), // (Peach-ish container)
    onErrorContainer = Color(0xFF4C4F69)
)

private val DarkColorScheme = darkColorScheme(
    // Catppuccin Mocha (https://catppuccin.com/palette)
    primary = Color(0xFFCBA6F7), // Mauve
    onPrimary = Color(0xFF1E1E2E), // Base
    primaryContainer = Color(0xFF313244), // Surface0
    onPrimaryContainer = Color(0xFFCDD6F4), // Text
    secondary = Color(0xFF89B4FA), // Blue
    onSecondary = Color(0xFF1E1E2E),
    secondaryContainer = Color(0xFF45475A), // Surface1
    onSecondaryContainer = Color(0xFFCDD6F4),
    tertiary = Color(0xFF94E2D5), // Teal
    onTertiary = Color(0xFF1E1E2E),
    tertiaryContainer = Color(0xFF585B70), // Surface2
    onTertiaryContainer = Color(0xFFCDD6F4),
    background = Color(0xFF1E1E2E), // Base
    onBackground = Color(0xFFCDD6F4), // Text
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF181825), // Mantle
    onSurfaceVariant = Color(0xFFBAC2DE), // Subtext1
    outline = Color(0xFF6C7086), // Overlay0
    outlineVariant = Color(0xFF45475A), // Surface1
    error = Color(0xFFF38BA8), // Red
    onError = Color(0xFF1E1E2E),
    errorContainer = Color(0xFF313244),
    onErrorContainer = Color(0xFFCDD6F4)
)

@Composable
fun AuStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography(),
        content = content
    )
}
