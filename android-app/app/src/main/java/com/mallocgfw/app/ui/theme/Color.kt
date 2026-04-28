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

internal val DarkBackgroundColor = Color(0xFF121416)
internal val DarkSurfaceColor = Color(0xFF171B1F)
internal val DarkSurfaceLowColor = Color(0xFF1C2125)
internal val DarkSurfaceHighColor = Color(0xFF242A30)
internal val DarkSurfaceBrightColor = Color(0xFF2C333A)
internal val DarkPrimaryColor = Color(0xFFBDA9FF)
internal val DarkPrimaryStrongColor = Color(0xFF9E84F7)
internal val DarkSecondaryColor = Color(0xFFC9B8FF)
internal val DarkTextPrimaryColor = Color(0xFFE2E2E5)
internal val DarkTextSecondaryColor = Color(0xFF9EA5AF)
internal val DarkOutlineColor = Color(0x338D9198)

internal val LightBackgroundColor = Color(0xFFF7F3FF)
internal val LightSurfaceColor = Color(0xFFFFFFFF)
internal val LightSurfaceLowColor = Color(0xFFF1EAFE)
internal val LightSurfaceHighColor = Color(0xFFE7DDFB)
internal val LightSurfaceBrightColor = Color(0xFFDDD1F6)
internal val LightPrimaryColor = Color(0xFF7C62D8)
internal val LightPrimaryStrongColor = Color(0xFF6849CB)
internal val LightSecondaryColor = Color(0xFF8E74DE)
internal val LightTextPrimaryColor = Color(0xFF24113F)
internal val LightTextSecondaryColor = Color(0xFF54496D)
internal val LightOutlineColor = Color(0x332A1845)

internal val DarkSuccessColor = Color(0xFF7EF0CC)
internal val DarkWarningColor = Color(0xFFF5C87B)
internal val DarkErrorColor = Color(0xFFFFB4AB)
internal val LightSuccessColor = Color(0xFF166A52)
internal val LightWarningColor = Color(0xFF8A5A00)
internal val LightErrorColor = Color(0xFFB14C57)

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
