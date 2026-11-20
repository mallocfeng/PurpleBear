package com.mallocgfw.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

private object ThemePaletteState {
    var lightThemeEnabled by mutableStateOf(false)
}

internal fun applyThemePreference(lightThemeEnabled: Boolean) {
    ThemePaletteState.lightThemeEnabled = lightThemeEnabled
}

val IsLightTheme: Boolean
    get() = ThemePaletteState.lightThemeEnabled

// Neutral canvas + white cards + one vivid accent (2025-style grouped UI).
internal val DarkBackgroundColor = Color(0xFF0C0C10)
internal val DarkSurfaceColor = Color(0xFF18181D)
internal val DarkSurfaceLowColor = Color(0xFF212128)
internal val DarkSurfaceHighColor = Color(0xFF2A2A33)
internal val DarkSurfaceBrightColor = Color(0xFF34343E)
internal val DarkPrimaryColor = Color(0xFFA794FF)
internal val DarkPrimaryStrongColor = Color(0xFF8F78FF)
internal val DarkSecondaryColor = Color(0xFFBBACFF)
internal val DarkTextPrimaryColor = Color(0xFFF3F2F7)
internal val DarkTextSecondaryColor = Color(0xFF9C9AA8)
internal val DarkOutlineColor = Color(0x24FFFFFF)

internal val LightBackgroundColor = Color(0xFFF2F2F7)
internal val LightSurfaceColor = Color(0xFFFFFFFF)
internal val LightSurfaceLowColor = Color(0xFFF2F1F7)
internal val LightSurfaceHighColor = Color(0xFFE9E7F2)
internal val LightSurfaceBrightColor = Color(0xFFDDDAEA)
internal val LightPrimaryColor = Color(0xFF6B4CF6)
internal val LightPrimaryStrongColor = Color(0xFF5936EC)
internal val LightSecondaryColor = Color(0xFF8A73F8)
internal val LightTextPrimaryColor = Color(0xFF16151B)
internal val LightTextSecondaryColor = Color(0xFF75737F)
internal val LightOutlineColor = Color(0x1F16151B)

internal val DarkSuccessColor = Color(0xFF4ADE9B)
internal val DarkWarningColor = Color(0xFFF5B94C)
internal val DarkErrorColor = Color(0xFFFF6B70)
internal val LightSuccessColor = Color(0xFF16A06A)
internal val LightWarningColor = Color(0xFFC27C0E)
internal val LightErrorColor = Color(0xFFE5484D)

val Background: Color
    get() = if (IsLightTheme) LightBackgroundColor else DarkBackgroundColor
val Surface: Color
    get() = if (IsLightTheme) LightSurfaceColor else DarkSurfaceColor
val SurfaceLow: Color
    get() = if (IsLightTheme) LightSurfaceLowColor else DarkSurfaceLowColor
val SurfaceHigh: Color
    get() = if (IsLightTheme) LightSurfaceHighColor else DarkSurfaceHighColor
val SurfaceBright: Color
    get() = if (IsLightTheme) LightSurfaceBrightColor else DarkSurfaceBrightColor
val Primary: Color
    get() = if (IsLightTheme) LightPrimaryColor else DarkPrimaryColor
val PrimaryStrong: Color
    get() = if (IsLightTheme) LightPrimaryStrongColor else DarkPrimaryStrongColor
val Secondary: Color
    get() = if (IsLightTheme) LightSecondaryColor else DarkSecondaryColor
val TextPrimary: Color
    get() = if (IsLightTheme) LightTextPrimaryColor else DarkTextPrimaryColor
val TextSecondary: Color
    get() = if (IsLightTheme) LightTextSecondaryColor else DarkTextSecondaryColor
val Success: Color
    get() = if (IsLightTheme) LightSuccessColor else DarkSuccessColor
val Warning: Color
    get() = if (IsLightTheme) LightWarningColor else DarkWarningColor
val Error: Color
    get() = if (IsLightTheme) LightErrorColor else DarkErrorColor
val Outline: Color
    get() = if (IsLightTheme) LightOutlineColor else DarkOutlineColor
