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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.Backdrop
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
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
import com.mallocgfw.app.ui.theme.Warning
import com.mallocgfw.app.xray.NodeLatencyTester
import java.util.Locale

@Composable
internal fun AppScaffold(
    screen: AppScreen,
    currentTab: MainTab,
    lightThemeEnabled: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenImport: () -> Unit = { onSelectTab(MainTab.Import) },
    onBack: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // Everything under the tab bar is recorded so the glass can refract it.
    val backdrop = rememberLayerBackdrop()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = navigationBottom + GlassTabBarHeight + TabBarBottomMargin + 8.dp
    CompositionLocalProvider(LocalBottomBarInset provides bottomInset) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(Background),
            ) {
                content(PaddingValues(0.dp))
                if (screenHasFixedMainTopBar(screen)) {
                    FixedMainTopBar(
                        screen = screen,
                        onOpenSettings = onOpenSettings,
                        onOpenScan = onOpenScan,
                        onOpenImport = onOpenImport,
                        onBack = onBack,
                    )
                }
            }
            FloatingTabBar(
                screen = screen,
                currentTab = currentTab,
                onSelectTab = onSelectTab,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private val TabBarBottomMargin = 8.dp

/** Import lives behind the "+" on the Nodes tab, so it highlights Nodes. */
private val VisibleTabs = listOf(
    MainTab.Home to GlassTabItem("首页", Icons.Rounded.Home),
    MainTab.Servers to GlassTabItem("节点", Icons.Rounded.Dns),
    MainTab.Rules to GlassTabItem("规则", Icons.Rounded.Description),
    MainTab.Me to GlassTabItem("我的", Icons.Rounded.Person),
)

@Composable
private fun FloatingTabBar(
    screen: AppScreen,
    currentTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val tabs = VisibleTabs.map { it.first }
    val screenTab = tabs.firstOrNull { tab ->
        screenMatchesTab(screen, tab) || (tab == MainTab.Servers && screenMatchesTab(screen, MainTab.Import))
    }
    val activeTab = screenTab ?: if (currentTab == MainTab.Import) MainTab.Servers else currentTab
    val selectedIndex = tabs.indexOf(activeTab).coerceAtLeast(0)
    LiquidGlassTabBar(
        items = VisibleTabs.map { it.second },
        selectedIndex = selectedIndex,
        onSelect = { index -> onSelectTab(tabs[index]) },
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = TabBarBottomMargin),
    )
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
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) SelectedTabBackgroundColor else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(
            icon,
            contentDescription = uiText(label),
            tint = if (selected) SelectedTabForegroundColor else TextSecondary,
            modifier = Modifier.size(23.dp),
        )
        Text(
            text = uiText(label),
            color = if (selected) SelectedTabForegroundColor else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 14.sp,
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
            screen == AppScreen.MediaRoutingNodePicker || screen == AppScreen.LogViewer || screen == AppScreen.Update ||
            screen == AppScreen.OpenSourceLicenses || screen == AppScreen.Tailscale || screen == AppScreen.Mcp
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
    screen: AppScreen,
    onOpenSettings: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenImport: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FixedTopBarSurfaceColor),
    ) {
        MainTopBar(
            screen = screen,
            onOpenSettings = onOpenSettings,
            onOpenScan = onOpenScan,
            onOpenImport = onOpenImport,
            onBack = onBack,
        )
    }
}

/** Large page title on the left, contextual round actions on the right. */
@Composable
internal fun MainTopBar(
    screen: AppScreen,
    onOpenSettings: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenImport: () -> Unit,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val brandLogo = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)?.asImageBitmap()
    }
    val title = when (screen) {
        AppScreen.Home -> APP_BRAND_NAME
        AppScreen.Servers -> uiText("节点", "Nodes")
        AppScreen.Rules -> uiText("规则", "Rules")
        AppScreen.Import -> uiText("添加节点", "Add nodes")
        AppScreen.Me -> uiText("我的", "Me")
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Add nodes" used to be a tab; it is now opened from the Nodes page's
        // "+", so it needs a way back like any other sub-page.
        if (screen == AppScreen.Import) {
            IconButtonCard(icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
            Spacer(modifier = Modifier.width(12.dp))
        }
        if (screen == AppScreen.Home && brandLogo != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp)),
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
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            title,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = if (screen == AppScreen.Home) 22.sp else TypeScale.PageTitle,
            lineHeight = TypeScale.PageTitleLine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (screen) {
                AppScreen.Home, AppScreen.Import -> IconButtonCard(icon = Icons.Rounded.QrCodeScanner, onClick = onOpenScan)
                AppScreen.Servers -> IconButtonCard(
                    icon = Icons.Rounded.Add,
                    onClick = onOpenImport,
                    tint = AccentContentColor,
                    background = Primary,
                )
                AppScreen.Me -> IconButtonCard(icon = Icons.Rounded.Settings, onClick = onOpenSettings)
                else -> Unit
            }
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
    // The subtitle is intentionally not shown: one short title reads cleaner.
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = uiText(title),
                color = TextPrimary,
                fontSize = TypeScale.CardTitle,
                lineHeight = TypeScale.CardTitleLine,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val fallbackConfigured = server.hasFallbackConfigured()
    val fallbackUnsupported = fallbackConfigured && !NodeLatencyTester.supportsHeartbeatFallback(server)
    val flag = leadingFlagEmoji(server.name)
    // The avatar already shows a leading flag; don't repeat it in the name.
    val displayName = flag?.let { server.name.removePrefix(it).trimStart() }?.ifBlank { null } ?: server.name
    val subtitle = buildList {
        server.protocol.takeIf { it.isNotBlank() }?.let { add(protocolDisplayName(it)) }
        // Region only when known and not already obvious from a flag in the name.
        server.region.takeIf { it.isNotBlank() && it != "未标注" && flag == null }?.let { add(uiText(it)) }
        if (highlightedPreProxy) add(uiText("前置"))
        if (fallbackConfigured) add(uiText(if (fallbackUnsupported) "备用不可用" else "备用"))
    }.joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Primary.copy(alpha = if (IsLightTheme) 0.06f else 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = AccentContentColor, modifier = Modifier.size(18.dp))
            }
        } else {
            NodeAvatar(server = server, size = 32.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = if (selected) Primary else TextPrimary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = TypeScale.Tiny,
                    lineHeight = TypeScale.TinyLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTestLatency)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            LatencyText(latencyMs = server.latencyMs, testing = latencyTesting)
        }
        CompactIconAction(
            icon = if (server.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            tint = if (server.favorite) Warning else TextSecondary.copy(alpha = 0.7f),
            onClick = onToggleFavorite,
        )
        CompactIconAction(
            icon = Icons.Rounded.MoreVert,
            tint = TextSecondary,
            onClick = onOpenDetail,
        )
        if (onDelete != null) {
            CompactIconAction(icon = Icons.Rounded.Delete, tint = Error, onClick = onDelete)
        }
    }
}

internal fun ServerNode.hasPreProxyConfigured(): Boolean = preProxyNodeId.isNotBlank()

/** A regional-indicator flag emoji at the start of [name], e.g. "🇯🇵 Tokyo" → "🇯🇵". */
internal fun leadingFlagEmoji(name: String): String? {
    val trimmed = name.trimStart()
    if (trimmed.length < 4) return null
    val first = trimmed.codePointAt(0)
    val second = trimmed.codePointAt(Character.charCount(first))
    val isIndicator = { cp: Int -> cp in 0x1F1E6..0x1F1FF }
    return if (isIndicator(first) && isIndicator(second)) trimmed.substring(0, 4) else null
}

/** Protocol labels as people write them, instead of all caps. */
internal fun protocolDisplayName(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "VLESS" -> "VLESS"
    "VMESS" -> "VMess"
    "TROJAN" -> "Trojan"
    "SHADOWSOCKS", "SS" -> "Shadowsocks"
    "SHADOWSOCKSR", "SSR" -> "SSR"
    "HYSTERIA2", "HY2" -> "Hysteria2"
    "HYSTERIA" -> "Hysteria"
    "TUIC" -> "TUIC"
    "ANYTLS" -> "AnyTLS"
    "WIREGUARD" -> "WireGuard"
    "SOCKS", "SOCKS5" -> "SOCKS5"
    "HTTP", "HTTP PROXY" -> "HTTP"
    "SNELL" -> "Snell"
    "MIERU" -> "Mieru"
    "SSH" -> "SSH"
    else -> raw.trim()
}

internal fun ServerNode.hasFallbackConfigured(): Boolean = fallbackNodeId.isNotBlank()
