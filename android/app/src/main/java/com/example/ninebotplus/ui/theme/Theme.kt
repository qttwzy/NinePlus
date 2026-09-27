package com.example.ninebotplus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Automotive-inspired palette: near-black surfaces, one accent green, warm warning.
val TeslaGreen = Color(0xFF21D147)
val TeslaGreenDark = Color(0xFF1FB83F)
val TeslaRed = Color(0xFFFF453A)
val TeslaOrange = Color(0xFFFF9F0A)
val TeslaBlue = Color(0xFF0A84FF)

val LightBackground = Color(0xFFF1F3F5)
val LightSurface = Color(0xFFFFFFFF)
val LightControl = Color(0xFFE8EAED)
val LightOnSurface = Color(0xFF0B0C0E)
val LightOnSurfaceVariant = Color(0xFF6B7280)

val DarkBackground = Color(0xFF060708)
val DarkSurface = Color(0xFF131418)
val DarkControl = Color(0xFF1F2126)
val DarkOnSurface = Color(0xFFF5F6F7)
val DarkOnSurfaceVariant = Color(0xFF9AA0A6)

private val LightColors = lightColorScheme(
    primary = TeslaGreen,
    onPrimary = Color.White,
    secondary = TeslaBlue,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightControl,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = TeslaRed,
)

private val DarkColors = darkColorScheme(
    primary = TeslaGreen,
    onPrimary = Color.Black,
    secondary = TeslaBlue,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkControl,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = TeslaRed,
)

@Composable
fun NinePlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NinePlusTypography,
        content = content,
    )
}

val NinePlusTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
    ),
)
