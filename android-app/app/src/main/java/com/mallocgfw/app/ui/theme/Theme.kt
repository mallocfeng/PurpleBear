package com.mallocgfw.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimaryColor,
    onPrimary = Color(0xFF24113F),
    secondary = DarkSecondaryColor,
    onSecondary = Color(0xFF2A1845),
    background = DarkBackgroundColor,
    onBackground = DarkTextPrimaryColor,
    surface = DarkSurfaceColor,
    onSurface = DarkTextPrimaryColor,
    surfaceVariant = DarkSurfaceHighColor,
    onSurfaceVariant = DarkTextSecondaryColor,
    outline = DarkOutlineColor,
    error = DarkErrorColor,
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimaryColor,
    onPrimary = Color(0xFFFFFFFF),
    secondary = LightSecondaryColor,
    onSecondary = Color(0xFF24113F),
    background = LightBackgroundColor,
    onBackground = LightTextPrimaryColor,
    surface = LightSurfaceColor,
    onSurface = LightTextPrimaryColor,
    surfaceVariant = LightSurfaceHighColor,
    onSurfaceVariant = LightTextSecondaryColor,
    outline = LightOutlineColor,
    error = LightErrorColor,
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

@Composable
fun MallocGfwTheme(
    lightTheme: Boolean = !isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    SideEffect {
        applyThemePreference(lightTheme)
    }
    MaterialTheme(
        colorScheme = if (lightTheme) LightColorScheme else DarkColorScheme,
        shapes = AppShapes,
        content = content,
    )
}
