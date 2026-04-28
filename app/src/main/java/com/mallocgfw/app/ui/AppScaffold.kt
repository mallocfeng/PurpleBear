package com.mallocgfw.app.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mallocgfw.app.R
import com.mallocgfw.app.model.AppScreen
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.MainTab
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.ui.theme.Background
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Outline
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.PrimaryStrong
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.Surface as SurfaceTone
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScaffold(
    screen: AppScreen,
    currentTab: MainTab,
    lightThemeEnabled: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScan: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(
                modifier = Modifier.navigationBarsPadding(),
                color = BottomBarSurfaceColor,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        Triple(MainTab.Home, "首页", Icons.Rounded.Home),
                        Triple(MainTab.Servers, "线路", Icons.Rounded.Dns),
                        Triple(MainTab.Rules, "规则", Icons.Rounded.Description),
                        Triple(MainTab.Import, "导入", Icons.Rounded.AddCircle),
                        Triple(MainTab.Me, "我的", Icons.Rounded.Person),
                    ).forEach { (tab, label, icon) ->
                        BottomNavItem(
                            modifier = Modifier.weight(1f),
                            selected = currentTab == tab && screenMatchesTab(screen, tab),
                            label = label,
                            icon = icon,
                            onClick = { onSelectTab(tab) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(padding)
            if (screenHasFixedMainTopBar(screen)) {
                FixedMainTopBar(
                    isLightTheme = lightThemeEnabled,
                    onToggleTheme = onToggleTheme,
                    onOpenSettings = onOpenSettings,
                    onOpenScan = onOpenScan,
                )
            }
        }
    }
}

@Composable
internal fun BottomNavItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) SelectedTabBackgroundColor else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) SelectedTabForegroundColor else TextSecondary,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = label,
            color = if (selected) SelectedTabForegroundColor else TextSecondary,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
        )
    }
}

internal fun screenMatchesTab(screen: AppScreen, tab: MainTab): Boolean {
    return when (tab) {
        MainTab.Home -> screen == AppScreen.Home || screen == AppScreen.QrScanner
        MainTab.Servers -> screen == AppScreen.Servers || screen == AppScreen.NodeDetail ||
            screen == AppScreen.LocalNodeBuilder || screen == AppScreen.PreProxyNodePicker
        MainTab.Rules -> screen == AppScreen.Rules || screen == AppScreen.RuleSourceDetail || screen == AppScreen.AddRuleSource
        MainTab.Import -> screen == AppScreen.Import || screen == AppScreen.ConfirmImport || screen == AppScreen.Subscriptions
        MainTab.Me -> screen == AppScreen.Me || screen == AppScreen.PerApp || screen == AppScreen.Diagnostics ||
            screen == AppScreen.Settings || screen == AppScreen.Permission || screen == AppScreen.MediaRouting ||
            screen == AppScreen.MediaRoutingNodePicker || screen == AppScreen.LogViewer
    }
}

internal fun screenHasFixedMainTopBar(screen: AppScreen): Boolean {
    return screen in setOf(
        AppScreen.Home,
        AppScreen.Servers,
        AppScreen.Rules,
        AppScreen.Import,
        AppScreen.Me,
    )
}

@Composable
internal fun FixedMainTopBar(
    isLightTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScan: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        FixedTopBarSurfaceColor,
                        FixedTopBarSurfaceColor.copy(alpha = 0.86f),
                    ),
                ),
            ),
    ) {
        MainTopBar(
            isLightTheme = isLightTheme,
            onToggleTheme = onToggleTheme,
            onOpenSettings = onOpenSettings,
            onOpenScan = onOpenScan,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(CardOutlineColor),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainTopBar(
    isLightTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScan: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val brandLogo = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)?.asImageBitmap()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 5.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (brandLogo != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    Image(
                        bitmap = brandLogo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1.9f),
                    )
                }
            } else {
                Icon(
                    Icons.Rounded.Security,
                    contentDescription = null,
                    tint = BrandIconTint,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                APP_BRAND_NAME,
                color = BrandTitleColor,
                fontWeight = FontWeight.Bold,
                fontSize = TypeScale.SectionTitle,
                lineHeight = TypeScale.SectionTitleLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = APP_VERSION_BADGE,
                color = BrandTitleColor,
                fontWeight = FontWeight.Bold,
                fontSize = TypeScale.SectionTitle,
                lineHeight = TypeScale.SectionTitleLine,
                maxLines = 1,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonCard(
                icon = Icons.Rounded.LightMode,
                onClick = onToggleTheme,
                tint = if (isLightTheme) PrimaryStrong else TextSecondary,
                background = if (isLightTheme) Primary.copy(alpha = 0.22f) else ControlSurfaceColor,
                borderColor = if (isLightTheme) Primary.copy(alpha = 0.35f) else Color.Transparent,
            )
            if (onOpenScan != null) {
                IconButtonCard(icon = Icons.Rounded.QrCodeScanner, onClick = onOpenScan)
            }
            IconButtonCard(icon = Icons.Rounded.Settings, onClick = onOpenSettings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)?,
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector = Icons.Rounded.Settings,
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = TypeScale.Tiny,
                        lineHeight = TypeScale.TinyLine,
                    )
                }
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = TypeScale.ListTitle,
                    lineHeight = TypeScale.ListTitleLine,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButtonCard(icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
            }
        },
        actions = {
            if (onAction != null) {
                IconButtonCard(icon = actionIcon, onClick = onAction)
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = TextPrimary,
        ),
        modifier = Modifier.statusBarsPadding(),
    )
}

@Composable
internal fun HeroOverviewCard(
    connectionStatus: ConnectionStatus,
    proxyMode: ProxyMode,
    currentRouteLabel: String,
    coreVersion: String?,
    onToggleConnection: () -> Unit,
) {
    SurfaceCard(
        background = Brush.verticalGradient(
            colors = listOf(SurfaceLow, SurfaceTone),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow("状态总览")
                Text(
                    text = connectionStatus.statusHeadline(),
                    fontSize = TypeScale.SectionTitle,
                    lineHeight = TypeScale.SectionTitleLine,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = connectionStatus.statusDescription(),
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            TogglePill(
                checked = connectionStatus == ConnectionStatus.Connected ||
                    connectionStatus == ConnectionStatus.Connecting,
                onClick = if (
                    connectionStatus == ConnectionStatus.Connecting ||
                    connectionStatus == ConnectionStatus.Disconnecting
                ) {
                    null
                } else {
                    onToggleConnection
                },
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            InfoMini(
                label = "当前节点",
                value = currentRouteLabel,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                InfoMini(
                    label = "模式",
                    value = proxyModeText(proxyMode),
                    modifier = Modifier.weight(1f),
                )
                InfoMini(
                    label = "核心版本",
                    value = coreVersion ?: "初始化中",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun ServerCard(
    server: ServerNode,
    selected: Boolean,
    latencyTesting: Boolean,
    onClick: () -> Unit,
    onOpenDetail: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTestLatency: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val highlightedPreProxy = server.hasPreProxyConfigured()
    val highlightedFallback = server.hasFallbackConfigured()
    val subtitle = buildString {
        val primaryMeta = server.region.ifBlank { server.description }.ifBlank { server.subscription }
        append(primaryMeta)
        if (highlightedPreProxy) {
            append(" · 前置")
        }
        if (highlightedFallback) {
            append(" · 备用")
        }
    }
    val rowBackground = when {
        selected && IsLightTheme -> Secondary.copy(alpha = 0.12f)
        selected -> Color.White.copy(alpha = 0.06f)
        IsLightTheme -> Color.White.copy(alpha = 0.66f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> Secondary
                        highlightedPreProxy || highlightedFallback -> Secondary.copy(alpha = 0.70f)
                        else -> Outline.copy(alpha = if (IsLightTheme) 0.26f else 0.22f)
                    },
                ),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = server.name,
                color = TextPrimary,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = if (server.latencyMs > 0) "${server.latencyMs} ms" else "--",
                color = if (server.latencyMs > 0) Secondary else TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = server.protocol.uppercase(Locale.ROOT),
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactIconAction(
                icon = Icons.Rounded.Sync,
                tint = when {
                    latencyTesting -> Primary
                    server.stable -> Success
                    else -> TextSecondary
                },
                onClick = onTestLatency,
            )
            CompactIconAction(
                icon = Icons.Rounded.MoreHoriz,
                tint = Primary,
                onClick = onOpenDetail,
            )
            CompactIconAction(
                icon = if (server.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                tint = if (server.favorite) Secondary else TextSecondary,
                onClick = onToggleFavorite,
            )
            if (onDelete != null) {
                CompactIconAction(
                    icon = Icons.Rounded.Delete,
                    tint = Error,
                    onClick = onDelete,
                )
            }
        }
    }
}

internal fun ServerNode.hasPreProxyConfigured(): Boolean = preProxyNodeId.isNotBlank()

internal fun ServerNode.hasFallbackConfigured(): Boolean = fallbackNodeId.isNotBlank()
