package com.austream.client.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5E35B1),
    onPrimaryContainer = Color.White,
    secondary = AccentTeal,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF00897B),
    onSecondaryContainer = Color.White,
    background = BackgroundDark,
    onBackground = Color(0xFFE1E1E1),
    surface = SurfaceDark,
    onSurface = Color(0xFFE1E1E1),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFCACACA),
    error = ErrorRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = AccentPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF1F0057),
    secondary = AccentTeal,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFC2FFF5),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE8DEF8),
    onSurfaceVariant = Color(0xFF4A4458),
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun AuStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val isLight = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLight
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = isLight
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
