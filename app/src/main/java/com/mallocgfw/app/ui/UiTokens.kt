package com.mallocgfw.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.mallocgfw.app.BuildConfig
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Secondary

internal const val APP_BRAND_NAME = "PurpleBear"
internal val APP_DISPLAY_VERSION: String get() = BuildConfig.VERSION_NAME
internal val APP_VERSION_BADGE: String get() = BuildConfig.VERSION_NAME
internal val HeartbeatIntervalOptionsMinutes = listOf(2, 5, 10)
internal val BrandWordmark = Primary
internal val BrandTitleColor: Color get() = Primary
internal val BrandIconTint = Secondary
internal val ModalScrimColor: Color get() = if (IsLightTheme) Color(0x66000000) else Color(0xB3000000)
internal val ModalSurfaceColor: Color get() = if (IsLightTheme) Color(0xFFFFFFFF) else Color(0xFF1C1C22)
internal val LaunchGradientStart: Color get() = if (IsLightTheme) Color(0xFFEDE8FF) else Color(0xFF14121F)
internal val ControlSurfaceColor: Color get() = if (IsLightTheme) Color(0xFFF2F1F7) else Color(0xFF26262E)
internal val ControlSurfaceStrongColor: Color get() = if (IsLightTheme) Color(0xFFEAE8F1) else Color(0xFF2E2E37)
internal val ControlSurfaceTrackColor: Color get() = if (IsLightTheme) Color(0xFFE2E1E8) else Color(0xFF3A3A44)
internal val AccentContentColor: Color get() = if (IsLightTheme) Color(0xFFFFFFFF) else Color(0xFF130B30)
internal val PendingBannerColor: Color get() = if (IsLightTheme) Color(0xFFF0ECFF) else Color(0xFF221D38)
internal val SelectedTabBackgroundColor: Color get() = Primary.copy(alpha = if (IsLightTheme) 0.12f else 0.20f)
internal val SelectedTabForegroundColor: Color get() = Primary
internal val BottomBarSurfaceColor: Color get() = if (IsLightTheme) Color(0xF7FFFFFF) else Color(0xF21E1E25)
internal val FixedTopBarSurfaceColor: Color get() = if (IsLightTheme) Color(0xF2F2F2F7) else Color(0xF20C0C10)
internal val CardOutlineColor: Color get() = if (IsLightTheme) Color(0x0F16151B) else Color(0x14FFFFFF)
/** Shade under a group's node rows, so they read as its contents. */
// Nodes tab: each group is a lavender "folder" card with a solid purple icon
// tile; its nodes sit in an inset panel of a different shade, so groups and
// the nodes in them read apart without a big colour block.
internal val GroupCardColor: Color get() = if (IsLightTheme) Color(0xFFE1DAF8) else Color(0xFF2D2843)
internal val GroupCardPressedColor: Color get() = if (IsLightTheme) Color(0xFFD2C8F4) else Color(0xFF3A3354)
internal val GroupPanelColor: Color get() = if (IsLightTheme) Color(0xFFFFFFFF) else Color(0xFF15141B)
internal val DividerColor: Color get() = if (IsLightTheme) Color(0x1416151B) else Color(0x1AFFFFFF)
internal val PromoGradientStartColor: Color get() = if (IsLightTheme) Color(0xFFEFEAFF) else Color(0xFF221D38)
internal val PromoGradientEndColor: Color get() = if (IsLightTheme) Color(0xFFFFFFFF) else Color(0xFF18181D)
internal val PreProxyCardStartColor: Color get() = if (IsLightTheme) Color(0xFFFFF7E0) else Color(0xFF2A2516)
internal val PreProxyCardEndColor: Color get() = if (IsLightTheme) Color(0xFFFFF7E0) else Color(0xFF2A2516)
internal val PreProxyCardBorderColor: Color get() = if (IsLightTheme) Color(0x33C27C0E) else Color(0x4DF5B94C)

internal object TypeScale {
    val Hero = 30.sp
    val HeroLine = 36.sp
    val PageTitle = 28.sp
    val PageTitleLine = 34.sp
    val SectionTitle = 20.sp
    val SectionTitleLine = 25.sp
    val CardTitle = 17.sp
    val CardTitleLine = 22.sp
    val ListTitle = 16.sp
    val ListTitleLine = 21.sp
    val Body = 15.sp
    val BodyLine = 20.sp
    val Meta = 13.sp
    val MetaLine = 17.sp
    val Tiny = 12.sp
    val TinyLine = 15.sp
    val Metric = 23.sp
    val MetricLine = 27.sp
    val LargeMetric = 29.sp
    val LargeMetricLine = 33.sp
    val Code = 14.sp
    val CodeLine = 19.sp
}

internal enum class NodeLinkPickerMode {
    PreProxy,
    Fallback,
}
