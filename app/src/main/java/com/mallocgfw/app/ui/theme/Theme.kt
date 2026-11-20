package com.mallocgfw.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color.TRANSPARENT
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimaryColor,
    onPrimary = Color(0xFF130B30),
    secondary = DarkSecondaryColor,
    onSecondary = Color(0xFF130B30),
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
    onSecondary = Color(0xFFFFFFFF),
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
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun MallocGfwTheme(
    lightTheme: Boolean = !isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    SideEffect {
        applyThemePreference(lightTheme)
        val window = activity?.window ?: return@SideEffect
        window.statusBarColor = TRANSPARENT
        window.navigationBarColor = TRANSPARENT
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
    }
    MaterialTheme(
        colorScheme = if (lightTheme) LightColorScheme else DarkColorScheme,
        shapes = AppShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
