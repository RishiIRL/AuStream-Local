package com.austream.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Responsive sizing utilities for adapting UI to different screen sizes.
 * 
 * Uses screen width as the reference:
 * - Compact: < 360dp (small phones)
 * - Medium: 360-400dp (typical phones)
 * - Expanded: > 400dp (large phones, tablets)
 */
data class Dimensions(
    val screenWidth: Dp,
    val screenHeight: Dp,
    
    // Icon sizes
    val iconLarge: Dp,
    val iconMedium: Dp,
    val iconSmall: Dp,
    val iconTiny: Dp,
    
    // Container sizes
    val heroContainer: Dp,
    val avatarSize: Dp,
    val waveformHeight: Dp,
    
    // Button sizes
    val buttonHeight: Dp,
    val buttonHeightSmall: Dp,
    
    // Paddings
    val paddingLarge: Dp,
    val paddingMedium: Dp,
    val paddingSmall: Dp,
    val paddingTiny: Dp,
    
    // Corner radius
    val radiusLarge: Dp,
    val radiusMedium: Dp,
    val radiusSmall: Dp,
    
    // Text sizes
    val titleLarge: TextUnit,
    val titleMedium: TextUnit,
    val bodyLarge: TextUnit,
    val bodyMedium: TextUnit,
    val labelMedium: TextUnit
)

/**
 * Get responsive dimensions based on screen size.
 */
@Composable
fun rememberDimensions(): Dimensions {
    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp.dp
    val screenHeight = config.screenHeightDp.dp
    
    return remember(screenWidth, screenHeight) {
        when {
            // Compact (small phones)
            screenWidth < 360.dp -> Dimensions(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                iconLarge = 44.dp,
                iconMedium = 28.dp,
                iconSmall = 20.dp,
                iconTiny = 16.dp,
                heroContainer = 80.dp,
                avatarSize = 56.dp,
                waveformHeight = 160.dp,
                buttonHeight = 44.dp,
                buttonHeightSmall = 36.dp,
                paddingLarge = 16.dp,
                paddingMedium = 12.dp,
                paddingSmall = 8.dp,
                paddingTiny = 4.dp,
                radiusLarge = 20.dp,
                radiusMedium = 12.dp,
                radiusSmall = 8.dp,
                titleLarge = 22.sp,
                titleMedium = 18.sp,
                bodyLarge = 14.sp,
                bodyMedium = 13.sp,
                labelMedium = 11.sp
            )
            // Medium (typical phones)
            screenWidth < 400.dp -> Dimensions(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                iconLarge = 52.dp,
                iconMedium = 32.dp,
                iconSmall = 24.dp,
                iconTiny = 18.dp,
                heroContainer = 96.dp,
                avatarSize = 64.dp,
                waveformHeight = 180.dp,
                buttonHeight = 48.dp,
                buttonHeightSmall = 40.dp,
                paddingLarge = 20.dp,
                paddingMedium = 14.dp,
                paddingSmall = 10.dp,
                paddingTiny = 6.dp,
                radiusLarge = 24.dp,
                radiusMedium = 14.dp,
                radiusSmall = 10.dp,
                titleLarge = 24.sp,
                titleMedium = 20.sp,
                bodyLarge = 15.sp,
                bodyMedium = 14.sp,
                labelMedium = 12.sp
            )
            // Expanded (large phones, tablets)
            else -> Dimensions(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                iconLarge = 56.dp,
                iconMedium = 36.dp,
                iconSmall = 28.dp,
                iconTiny = 20.dp,
                heroContainer = 108.dp,
                avatarSize = 72.dp,
                waveformHeight = 200.dp,
                buttonHeight = 52.dp,
                buttonHeightSmall = 44.dp,
                paddingLarge = 24.dp,
                paddingMedium = 16.dp,
                paddingSmall = 12.dp,
                paddingTiny = 8.dp,
                radiusLarge = 28.dp,
                radiusMedium = 16.dp,
                radiusSmall = 12.dp,
                titleLarge = 26.sp,
                titleMedium = 22.sp,
                bodyLarge = 16.sp,
                bodyMedium = 15.sp,
                labelMedium = 13.sp
            )
        }
    }
}

/**
 * Scale a dp value based on screen width.
 * Uses 392dp as the reference (typical phone width).
 */
@Composable
fun Dp.scaled(): Dp {
    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp
    val scaleFactor = (screenWidth / 392f).coerceIn(0.75f, 1.25f)
    return (this.value * scaleFactor).dp
}

/**
 * Scale a text size based on screen width.
 */
@Composable
fun TextUnit.scaled(): TextUnit {
    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp
    val scaleFactor = (screenWidth / 392f).coerceIn(0.85f, 1.15f)
    return (this.value * scaleFactor).sp
}
