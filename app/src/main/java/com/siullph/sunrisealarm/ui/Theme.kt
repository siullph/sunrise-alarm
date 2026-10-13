package com.siullph.sunrisealarm.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Fallback palette for phones before Android 12, which have no wallpaper colors to borrow.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8F4C2B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF351000),
    secondary = Color(0xFF765749),
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF2C160B),
    tertiary = Color(0xFF665F31),
    tertiaryContainer = Color(0xFFEEE4A9),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
    surfaceContainerLow = Color(0xFFFFF1EC),
    surfaceContainer = Color(0xFFFCEAE4),
    surfaceContainerHigh = Color(0xFFF6E5DE),
    surfaceContainerHighest = Color(0xFFF0DFD8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB693),
    onPrimary = Color(0xFF552003),
    primaryContainer = Color(0xFF723516),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFE6BEAD),
    secondaryContainer = Color(0xFF5C4033),
    onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFD1C88F),
    tertiaryContainer = Color(0xFF4E471C),
    background = Color(0xFF1A110E),
    surface = Color(0xFF1A110E),
    surfaceContainerLow = Color(0xFF231915),
    surfaceContainer = Color(0xFF271D19),
    surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D322D),
)

private val SunriseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Material You: colors come from the wallpaper on Android 12+, a warm dawn palette before. */
@Composable
fun SunriseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, shapes = SunriseShapes, content = content)
}
