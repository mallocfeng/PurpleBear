package com.mallocgfw.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Secondary

internal const val APP_BRAND_NAME = "PurpleBear"
internal const val APP_DISPLAY_VERSION = "0.6.6"
internal const val APP_VERSION_BADGE = "0.6.6"
internal val HeartbeatIntervalOptionsMinutes = listOf(2, 5, 10)
internal val BrandWordmark = Primary
internal val BrandTitleColor: Color get() = if (IsLightTheme) Color(0xFF6849CB) else BrandWordmark
internal val BrandIconTint = Secondary
internal val ModalScrimColor: Color get() = if (IsLightTheme) Color(0x99E6D9FB) else Color(0xCC05080F)
internal val ModalSurfaceColor: Color get() = if (IsLightTheme) Color(0xFFFFFFFF) else Color(0xFF111723)
internal val LaunchGradientStart: Color get() = if (IsLightTheme) Color(0xFFEFE4FF) else Color(0xFF0C1220)
internal val ControlSurfaceColor: Color get() = if (IsLightTheme) Color(0x142A1845) else Color.White.copy(alpha = 0.05f)
internal val ControlSurfaceStrongColor: Color get() = if (IsLightTheme) Color(0x1C2A1845) else Color.White.copy(alpha = 0.06f)
internal val ControlSurfaceTrackColor: Color get() = if (IsLightTheme) Color(0x1F2A1845) else Color.White.copy(alpha = 0.12f)
internal val AccentContentColor: Color get() = if (IsLightTheme) Color(0xFFF5EDFF) else Color(0xFF05121B)
internal val PendingBannerColor: Color get() = if (IsLightTheme) Color(0xFFF1E9FF) else Color(0xFF111723)
internal val SelectedTabBackgroundColor: Color get() = if (IsLightTheme) Primary.copy(alpha = 0.18f) else Color(0xFF5B526B)
internal val SelectedTabForegroundColor: Color get() = if (IsLightTheme) Color(0xFF2A1845) else Color(0xFFF5EDFF)
internal val BottomBarSurfaceColor: Color get() = if (IsLightTheme) Color(0xFFFDF9FF) else Color(0xE50A1020)
internal val FixedTopBarSurfaceColor: Color get() = if (IsLightTheme) Color(0xEAFDF9FF) else Color(0xD90A1020)
internal val CardOutlineColor: Color get() = if (IsLightTheme) Color(0x162A1845) else Color.Transparent
internal val PromoGradientStartColor: Color get() = if (IsLightTheme) Color(0x33BDA9FF) else Color(0x66005580)
internal val PromoGradientEndColor: Color get() = if (IsLightTheme) Color(0xFFF5EEFF) else Color(0xFF11151A)
internal val PreProxyCardStartColor: Color get() = if (IsLightTheme) Color(0xFFFFF6D8) else Color(0xFF172128)
internal val PreProxyCardEndColor: Color get() = if (IsLightTheme) Color(0xFFF6E8B4) else Color(0xFF243631)
internal val PreProxyCardBorderColor: Color get() = if (IsLightTheme) Color(0x33B89A38) else Color(0x4DA9A05A)

internal object TypeScale {
    val Hero = 27.sp
    val HeroLine = 33.sp
    val PageTitle = 23.sp
    val PageTitleLine = 28.sp
    val SectionTitle = 19.sp
    val SectionTitleLine = 24.sp
    val CardTitle = 17.sp
    val CardTitleLine = 21.sp
    val ListTitle = 16.sp
    val ListTitleLine = 20.sp
    val Body = 15.sp
    val BodyLine = 19.sp
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
