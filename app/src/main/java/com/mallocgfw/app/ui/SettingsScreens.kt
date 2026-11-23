package com.mallocgfw.app.ui

import android.graphics.drawable.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.mallocgfw.app.model.FakeRepository
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.AppLanguage
import com.mallocgfw.app.model.AppLogLevel
import com.mallocgfw.app.model.AppSettings
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.DiagnosticStep
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.ProtectedApp
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.StreamingMediaService
import com.mallocgfw.app.model.StreamingRegion
import com.mallocgfw.app.model.StreamingRouteSelection
import com.mallocgfw.app.update.AppUpdateInfo
import com.mallocgfw.app.update.AppUpdateStatus
import com.mallocgfw.app.update.AppUpdateUiState
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.XrayCoreStatus

private const val PER_APP_TAB_INSTALLED = "__installed__"
private const val PER_APP_TAB_ALL = "__all__"

private fun installedPackageIds(context: android.content.Context, apps: List<ProtectedApp>): Set<String> =
    apps.mapNotNull { app ->
        app.id.takeIf {
            runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess
        }
    }.toSet()

@Composable
internal fun PerAppScreen(
    padding: PaddingValues,
    proxyMode: ProxyMode,
    search: String,
    apps: List<ProtectedApp>,
    hasPendingChanges: Boolean,
    applyEnabled: Boolean,
    onBack: () -> Unit,
    onModeChange: (ProxyMode) -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onSetApps: (List<String>, Boolean) -> Unit,
    onApplyChanges: () -> Unit,
    serverNames: Map<String, String> = emptyMap(),
    onOpenApp: (String) -> Unit = {},
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    // Which presets are on this phone (visible through <queries> in the manifest).
    var installedIds by remember { mutableStateOf(installedPackageIds(context, apps)) }
    LifecycleResumeEffect(apps.size) {
        installedIds = installedPackageIds(context, apps)
        onPauseOrDispose { }
    }
    // Tab: INSTALLED, ALL or a category; opens on the installed apps when there are any.
    var tab by rememberSaveable { mutableStateOf(if (installedIds.isNotEmpty()) PER_APP_TAB_INSTALLED else PER_APP_TAB_ALL) }
    val normalizedSearch = search.trim().lowercase()
    val filteredApps = remember(apps, normalizedSearch, language, tab, installedIds) {
        val scoped = when {
            normalizedSearch.isNotBlank() -> apps
            tab == PER_APP_TAB_INSTALLED -> apps.filter { it.id in installedIds }
            tab == PER_APP_TAB_ALL -> apps
            else -> apps.filter { it.category == tab }
        }
        val matched = if (normalizedSearch.isBlank()) {
            scoped
        } else {
            scoped.filter { app ->
                app.name.lowercase().contains(normalizedSearch) ||
                    app.id.lowercase().contains(normalizedSearch) ||
                    app.category.lowercase().contains(normalizedSearch) ||
                    appText(app.category, language).lowercase().contains(normalizedSearch)
            }
        }
        // Installed apps first within each category; categories in preset order.
        matched.sortedWith(
            compareBy<ProtectedApp> { FakeRepository.protectedAppCategories.indexOf(it.category).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { if (it.id in installedIds) 0 else 1 },
        )
    }
    val grouped = tab == PER_APP_TAB_ALL || tab == PER_APP_TAB_INSTALLED || normalizedSearch.isNotBlank()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "分应用代理", subtitle = "应用选择", onBack = onBack)
        }
        item {
            SurfaceCard {
                ScreenHeader(
                    title = "只代理需要保护的应用",
                    subtitle = "选择需要代理的应用。",
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Eyebrow("当前模式")
                        Text(
                            text = uiText(if (proxyMode == ProxyMode.PerApp) "仅代理选中应用" else "智能分流"),
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = uiText(
                                if (proxyMode == ProxyMode.PerApp) {
                                    "仅勾选应用进入 VPN。"
                                } else {
                                    "切换到分应用后生效。"
                                },
                            ),
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    StatusPill(
                        text = if (proxyMode == ProxyMode.PerApp) "已启用" else "未启用",
                        color = if (proxyMode == ProxyMode.PerApp) Success else TextSecondary,
                        background = if (proxyMode == ProxyMode.PerApp) Success.copy(alpha = 0.14f) else ControlSurfaceStrongColor,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedActionButton(
                    text = if (proxyMode == ProxyMode.PerApp) "切回智能分流" else "启用分应用代理",
                    onClick = {
                        onModeChange(
                            if (proxyMode == ProxyMode.PerApp) ProxyMode.Smart else ProxyMode.PerApp,
                        )
                    },
                )
            }
        }
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = uiText("应用设置后会重连 VPN。"),
                        color = TextSecondary,
                    )
                    OutlinedActionButton(
                        text = when {
                            proxyMode != ProxyMode.PerApp -> "先启用分应用代理"
                            hasPendingChanges && applyEnabled -> "应用设置"
                            hasPendingChanges -> "等待连接后应用"
                            else -> "当前名单已生效"
                        },
                        enabled = applyEnabled,
                        onClick = onApplyChanges,
                    )
                }
            }
        }
        item {
            SearchField(
                search = search,
                placeholder = "搜索应用名称或分类…",
                onValueChange = onSearchChange,
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tabs = buildList {
                    if (installedIds.isNotEmpty()) add(PER_APP_TAB_INSTALLED to installedIds.size)
                    add(PER_APP_TAB_ALL to apps.size)
                    FakeRepository.protectedAppCategories.forEach { category ->
                        val count = apps.count { it.category == category }
                        if (count > 0) add(category to count)
                    }
                }
                tabs.forEach { (id, count) ->
                    val label = when (id) {
                        PER_APP_TAB_INSTALLED -> uiText("已安装")
                        PER_APP_TAB_ALL -> uiText("全部")
                        else -> uiText(id)
                    }
                    ChoiceChip(text = "$label $count", selected = tab == id && normalizedSearch.isBlank(), onClick = { tab = id })
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Primary.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                Text(
                    text = uiText(
                        "点应用可单独选择节点；右侧开关决定是否代理。",
                        "Tap an app to pick its own node; the switch turns its proxy on or off.",
                        "Нажмите на приложение, чтобы выбрать узел; переключатель включает прокси.",
                    ),
                    color = TextPrimary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                )
            }
        }
        val sections = if (grouped) {
            filteredApps.groupBy { it.category }.toList()
        } else {
            listOf((filteredApps.firstOrNull()?.category ?: tab) to filteredApps)
        }
        sections.forEach { (category, sectionApps) ->
            item(key = "header_$category") {
                val allOn = sectionApps.all { it.enabled }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${uiText(category)} · ${sectionApps.count { it.enabled }}/${sectionApps.size}",
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextAction(
                        text = if (allOn) "全部关闭" else "全部开启",
                        onClick = { onSetApps(sectionApps.map { it.id }, !allOn) },
                    )
                }
            }
            items(sectionApps, key = { it.id }) { app ->
                val installed = app.id in installedIds
                val route = when {
                    !app.enabled -> uiText("不代理", "Not proxied", "Без прокси")
                    app.serverId.isBlank() -> uiText("默认节点", "Default node", "Узел по умолчанию")
                    else -> serverNames[app.serverId]?.let { uiText("节点：$it", "Node: $it", "Узел: $it") }
                        ?: uiText("默认节点（原节点已删除）", "Default node (its node was deleted)", "Узел по умолчанию (свой удалён)")
                }
                SurfaceCard(modifier = Modifier.clickable { onOpenApp(app.id) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            GlassBadge(
                                icon = Icons.Rounded.Apps,
                                modifier = Modifier.size(44.dp),
                                innerPadding = 10.dp,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = app.name,
                                    fontSize = TypeScale.ListTitle,
                                    lineHeight = TypeScale.ListTitleLine,
                                    fontWeight = FontWeight.Bold,
                                    color = if (installed) TextPrimary else TextSecondary,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = if (installed) uiText("已安装") else uiText("未安装"),
                                        color = if (installed) Success else TextSecondary,
                                        fontSize = TypeScale.Meta,
                                        lineHeight = TypeScale.MetaLine,
                                        maxLines = 1,
                                    )
                                    // Looks like a button so people know the row opens the node choice.
                                    Row(
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .clip(CircleShape)
                                            .background(if (app.enabled) Primary.copy(alpha = 0.14f) else ControlSurfaceStrongColor)
                                            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = route,
                                            color = if (app.enabled) Primary else TextSecondary,
                                            fontSize = TypeScale.Meta,
                                            lineHeight = TypeScale.MetaLine,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Icon(
                                            Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = if (app.enabled) Primary else TextSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                        TogglePill(checked = app.enabled, onClick = { onToggleApp(app.id) })
                    }
                }
            }
        }
        if (filteredApps.isEmpty()) {
            item {
                NoteBox(text = "没有匹配到应用。")
            }
        }
    }
}

@Composable
internal fun DiagnosticsScreen(
    padding: PaddingValues,
    steps: List<DiagnosticStep>,
    connectionStatus: ConnectionStatus,
    onBack: () -> Unit,
    onRerun: () -> Unit,
    onChangeServer: () -> Unit,
    onCopySummary: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "系统诊断", subtitle = "故障分析", onBack = onBack)
        }
        items(steps, key = { it.key }) { step ->
            DiagnosticCard(step = step)
        }
        item {
            ButtonRow(
                primaryText = "重新测试",
                onPrimary = onRerun,
                secondaryText = "切换线路",
                onSecondary = onChangeServer,
                tertiaryText = "复制诊断摘要",
                onTertiary = onCopySummary,
            )
        }
        item {
            NoteBox(
                text = connectionStatus.diagnosticsSummary(),
            )
        }
    }
}

@Composable
internal fun SettingsScreen(
    padding: PaddingValues,
    settings: AppSettings,
    coreVersion: String?,
    coreAbi: String?,
    coreStatus: XrayCoreStatus,
    systemVersion: String,
    appVersion: String,
    syncInFlight: Boolean,
    message: String?,
    onBack: () -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onHeartbeatIntervalChange: (Int) -> Unit,
    onVpnMtuChange: (Int) -> Unit,
    onProxyQuicChange: (Boolean) -> Unit,
    onDailyAutoUpdateChange: (Boolean) -> Unit,
    onUpdateNotificationsChange: (Boolean) -> Unit,
    onOpenUpdates: () -> Unit,
    updateBadge: Boolean = false,
    onSyncNow: () -> Unit,
    onDnsChange: (AppDnsMode, String) -> Unit,
    onLogLevelChange: (AppLogLevel) -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onViewLogs: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenMcp: () -> Unit,
) {
    var showDnsDialog by remember { mutableStateOf(false) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showHeartbeatDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showQuickTileDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = uiText("设置"), subtitle = uiText("偏好与高级"), onBack = onBack)
        }
        if (!message.isNullOrBlank()) {
            item {
                NoteBox(text = message)
            }
        }
        item {
            SettingsGroup(title = uiText("语言")) {
                SettingActionRow(
                    title = uiText("界面语言"),
                    subtitle = if (settings.language == AppLanguage.System) {
                        uiText("使用 Android 系统语言。")
                    } else {
                        languageNativeName(settings.language)
                    },
                    actionText = uiText("更改", "Change", "Изменить"),
                    onAction = { showLanguageDialog = true },
                )
            }
        }
        item {
            SettingsGroup(title = "连接设置") {
                SettingToggleRow(
                    title = "启动后自动连接",
                    subtitle = "打开应用时恢复上次线路。",
                    checked = settings.autoConnectOnLaunch,
                    onToggle = { onAutoConnectChange(!settings.autoConnectOnLaunch) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingToggleRow(
                    title = "断线自动重连",
                    subtitle = "断开后重连上次节点。",
                    checked = settings.autoReconnect,
                    onToggle = { onAutoReconnectChange(!settings.autoReconnect) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingActionRow(
                    title = "备用节点心跳",
                    subtitle = settings.heartbeatSummary(),
                    actionText = "设置",
                    onAction = { showHeartbeatDialog = true },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingActionRow(
                    title = "VPN MTU",
                    subtitle = settings.vpnMtuSummary(),
                    actionText = "设置",
                    onAction = { showMtuDialog = true },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingToggleRow(
                    title = "允许 QUIC 走代理",
                    subtitle = "适合 Hysteria2、TUIC 节点。关闭时国外 QUIC 改用 TCP，更稳定。",
                    checked = settings.proxyQuicEnabled,
                    onToggle = { onProxyQuicChange(!settings.proxyQuicEnabled) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingActionRow(
                    title = "快捷开关",
                    subtitle = "添加到系统下拉菜单。",
                    actionText = "添加",
                    onAction = { showQuickTileDialog = true },
                )
            }
        }
        item {
            SettingsGroup(title = "订阅与更新") {
                SettingToggleRow(
                    title = "每日自动更新",
                    subtitle = "非计费网络下自动刷新。",
                    checked = settings.dailyAutoUpdate,
                    onToggle = { onDailyAutoUpdateChange(!settings.dailyAutoUpdate) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingActionRow(
                    title = "检查更新",
                    subtitle = "查看 GitHub Release 最新版本。",
                    actionText = "进入",
                    onAction = onOpenUpdates,
                    badge = updateBadge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingActionRow(
                    title = "规则资源同步",
                    subtitle = if (syncInFlight) {
                        "正在后台同步。"
                    } else {
                        "同步订阅、规则和 Geo。"
                    },
                    actionText = if (syncInFlight) "同步中" else "同步",
                    onAction = onSyncNow,
                    actionEnabled = !syncInFlight,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingToggleRow(
                    title = "同步完成通知",
                    subtitle = "订阅和规则同步完成后，在通知栏提示结果。",
                    checked = settings.showUpdateNotifications,
                    onToggle = { onUpdateNotificationsChange(!settings.showUpdateNotifications) },
                )
            }
        }
        item {
            SettingsGroup(title = uiText("AI 远程控制", "AI remote control", "Удалённое управление ИИ")) {
                SettingActionRow(
                    title = uiText("AI 远程控制（MCP）", "AI remote control (MCP)", "Удалённое управление ИИ (MCP)"),
                    subtitle = mcpSettingsSubtitle(),
                    actionText = uiText("进入", "Open", "Открыть"),
                    onAction = onOpenMcp,
                )
            }
        }
        item {
            SettingsGroup(title = "高级设置") {
                SettingActionRow(
                    title = "自定义 DNS",
                    subtitle = uiText("当前 ${settings.dnsSummary()}。", "Current ${uiText(settings.dnsSummary())}.", "Сейчас ${uiText(settings.dnsSummary())}."),
                    actionText = "编辑",
                    onAction = { showDnsDialog = true },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingDualActionRow(
                    title = "日志级别",
                    subtitle = uiText("当前 ${settings.logLevel.displayName}。", "Current ${settings.logLevel.displayName}.", "Сейчас ${settings.logLevel.displayName}."),
                    primaryActionText = "调整",
                    onPrimaryAction = { showLogLevelDialog = true },
                    secondaryActionText = "查看",
                    onSecondaryAction = onViewLogs,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingInfoRow(
                    title = "核心资产",
                    subtitle = buildString {
                        append(uiText("官方编译产物 · "))
                        append((coreVersion ?: "Xray").trim())
                        append(" · ")
                        append(coreAbi ?: uiText("未初始化"))
                        append(" · ")
                        append(uiText(coreStatus.label()))
                        append('\n')
                        append(systemVersion)
                        append(" · ")
                        append(APP_BRAND_NAME)
                        append(' ')
                        append(appVersion)
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingActionRow(
                    title = "开源许可",
                    subtitle = "查看 Xray、mihomo 与 GPL-3.0 说明。",
                    actionText = "查看",
                    onAction = onOpenLicenses,
                )
            }
        }
    }

    if (showDnsDialog) {
        DnsSettingsDialog(
            initialMode = settings.dnsMode,
            initialCustomDns = settings.customDnsValue,
            onDismiss = { showDnsDialog = false },
            onConfirm = { dnsMode, customDns ->
                onDnsChange(dnsMode, customDns)
                showDnsDialog = false
            },
        )
    }

    if (showQuickTileDialog) {
        QuickTileDialog(
            // Android 13+ lets apps ask to add a tile; older versions need the manual steps.
            canRequest = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU,
            onDismiss = { showQuickTileDialog = false },
            onContinue = {
                showQuickTileDialog = false
                onAddQuickSettingsTile()
            },
        )
    }

    if (showLanguageDialog) {
        LanguageDialog(
            selected = settings.language,
            onDismiss = { showLanguageDialog = false },
            onConfirm = { language ->
                onLanguageChange(language)
                showLanguageDialog = false
            },
        )
    }

    if (showLogLevelDialog) {
        LogLevelDialog(
            selected = settings.logLevel,
            onDismiss = { showLogLevelDialog = false },
            onConfirm = { level ->
                onLogLevelChange(level)
                showLogLevelDialog = false
            },
        )
    }

    if (showHeartbeatDialog) {
        HeartbeatIntervalDialog(
            selectedMinutes = settings.heartbeatIntervalMinutes,
            onDismiss = { showHeartbeatDialog = false },
            onConfirm = { minutes ->
                onHeartbeatIntervalChange(minutes)
                showHeartbeatDialog = false
            },
        )
    }

    if (showMtuDialog) {
        VpnMtuDialog(
            selectedMtu = settings.vpnMtu,
            onDismiss = { showMtuDialog = false },
            onConfirm = { mtu ->
                onVpnMtuChange(mtu)
                showMtuDialog = false
            },
        )
    }
}

@Composable
internal fun OpenSourceLicensesScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "开源许可", subtitle = "Third-party notices", onBack = onBack)
        }
        item {
            ScreenHeader(
                title = "第三方组件",
                subtitle = "PurpleBear 是开源软件；第三方组件保留各自许可证。",
            )
        }
        item {
            SettingsGroup(title = "mihomo") {
                SelectionContainer {
                    Text(
                        text = """
                            Project: MetaCubeX/mihomo
                            Version: v1.19.34
                            License: GPL-3.0
                            Source: https://github.com/MetaCubeX/mihomo/tree/v1.19.34
                            Release asset: mihomo-android-arm64-v8-v1.19.34.gz
                            Release asset SHA-256:
                            e45a0b18ea3554cf7f322b59cf2dd21f3e4879dd2db657cbba6a557235e33115
                            Packaged binary:
                            app/src/main/jniLibs/arm64-v8a/libmihomo.so
                            Packaged binary SHA-256:
                            5915d69c8440267158d7b9fbcb4089d522a3d66e86482dd121200e614c1af68f
                        """.trimIndent(),
                        color = TextSecondary,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "GPL-3.0") {
                Text(
                    text = "GPL-3.0 全文已随 APK 打包在 assets/licenses/GPL-3.0.txt，并在源码仓库的 LICENSES/GPL-3.0.txt 提供。",
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                )
            }
        }
        item {
            SettingsGroup(title = "Xray") {
                SelectionContainer {
                    Text(
                        text = """
                            Project: XTLS/Xray-core
                            Source: https://github.com/XTLS/Xray-core
                            PurpleBear 使用 Xray 作为主要代理核心。请查看上游项目获取源码和许可证详情。
                        """.trimIndent(),
                        color = TextSecondary,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "Tailscale") {
                SelectionContainer {
                    Text(
                        text = """
                            Project: Tailscale
                            Version: v1.104.7
                            License: BSD 3-Clause
                            Source: https://github.com/tailscale/tailscale/tree/v1.104.7
                            PurpleBear 在进程内运行 Tailscale userspace 节点，
                            并将其本地 SOCKS5 接口交给 Xray 做 Tailnet 分流。
                            BSD 3-Clause 全文已随 APK 打包在
                            assets/licenses/Tailscale-BSD-3-Clause.txt。
                        """.trimIndent(),
                        color = TextSecondary,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "Backdrop") {
                SelectionContainer {
                    Text(
                        text = """
                            Project: Kyant0/AndroidLiquidGlass (backdrop)
                            Version: 2.0.1
                            License: Apache License 2.0
                            Source: https://github.com/Kyant0/AndroidLiquidGlass
                            底部导航栏的液态玻璃效果基于该库及其示例代码改写。
                        """.trimIndent(),
                        color = TextSecondary,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                    )
                }
            }
        }
    }
}

@Composable
internal fun UpdateScreen(
    padding: PaddingValues,
    state: AppUpdateUiState,
    currentVersionName: String,
    currentVersionCode: Int,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "检查更新", subtitle = "GitHub Release", onBack = onBack)
        }
        state.message?.takeIf { it.isNotBlank() }?.let { message ->
            item {
                NoteBox(text = message)
            }
        }
        item {
            SettingsGroup(title = "版本状态") {
                UpdateStatusContent(
                    state = state,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                )
            }
        }
        val info = state.info
        if (info != null) {
            item {
                ReleaseNotes(
                    info = info,
                    isCurrentVersion = state.status == AppUpdateStatus.Latest,
                )
            }
        }
        item {
            UpdateAction(
                state = state,
                onCheck = onCheck,
                onDownload = onDownload,
                onInstall = onInstall,
            )
        }
    }
}

@Composable
private fun UpdateStatusContent(
    state: AppUpdateUiState,
    currentVersionName: String,
    currentVersionCode: Int,
) {
    val info = state.info
    val title = when (state.status) {
        AppUpdateStatus.Idle -> "尚未检查更新"
        AppUpdateStatus.Checking -> "正在检查更新"
        AppUpdateStatus.Latest -> "当前版本"
        AppUpdateStatus.Available -> "可更新版本"
        AppUpdateStatus.Downloading -> "正在下载更新"
        AppUpdateStatus.Downloaded -> "安装包已下载"
        AppUpdateStatus.Failed -> "检查更新失败"
    }
    SettingInfoRow(
        title = uiText(title),
        subtitle = if (info == null) {
            uiText(
                "当前 $currentVersionName · versionCode $currentVersionCode",
                "Current $currentVersionName · versionCode $currentVersionCode", "Текущая версия $currentVersionName · versionCode $currentVersionCode",
            )
        } else {
            "${info.releaseName} · ${info.tagName}" +
                (info.versionCode?.let { " · versionCode $it" } ?: "")
        },
    )
    if (state.status == AppUpdateStatus.Downloading) {
        Spacer(modifier = Modifier.height(14.dp))
        val total = state.totalBytes.takeIf { it > 0L } ?: state.info?.apkSizeBytes ?: 0L
        LinearProgressIndicator(
            progress = {
                if (total > 0L) {
                    (state.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier.fillMaxWidth(),
            color = Primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${formatByteCount(state.downloadedBytes)} / ${formatByteCount(total)}",
            color = TextSecondary,
        )
    }
}

@Composable
private fun ReleaseNotes(
    info: AppUpdateInfo,
    isCurrentVersion: Boolean,
) {
    val publishedPrefix = uiText("发布于 ", "Published ")
    val emptyReleaseNotes = uiText("此版本没有填写更新说明。")
    SettingsGroup(title = uiText(if (isCurrentVersion) "当前版本描述" else "更新版本描述")) {
        SettingInfoRow(
            title = if (isCurrentVersion) {
                uiText("当前已安装：${info.releaseName}", "Installed: ${info.releaseName}", "Установлено: ${info.releaseName}")
            } else {
                uiText("可升级到：${info.releaseName}", "Available: ${info.releaseName}", "Доступно: ${info.releaseName}")
            },
            subtitle = buildString {
                append(publishedPrefix)
                append(info.publishedAt.ifBlank { "GitHub Release" })
                if (info.apkSizeBytes > 0L) {
                    append(" · ")
                    append(formatByteCount(info.apkSizeBytes))
                }
            },
        )
        Spacer(modifier = Modifier.height(14.dp))
        SelectionContainer {
            Text(
                text = info.body.ifBlank { emptyReleaseNotes },
                color = TextPrimary,
                fontFamily = FontFamily.Default,
            )
        }
    }
}

@Composable
private fun UpdateAction(
    state: AppUpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    when (state.status) {
        AppUpdateStatus.Checking -> OutlinedActionButton(
            text = "检查中",
            enabled = false,
            onClick = {},
        )
        AppUpdateStatus.Downloading -> OutlinedActionButton(
            text = "下载中",
            enabled = false,
            onClick = {},
        )
        AppUpdateStatus.Available -> PrimaryActionButton(
            text = "下载更新",
            onClick = onDownload,
        )
        AppUpdateStatus.Downloaded -> PrimaryActionButton(
            text = "安装",
            onClick = onInstall,
        )
        AppUpdateStatus.Latest,
        AppUpdateStatus.Failed,
        AppUpdateStatus.Idle -> PrimaryActionButton(
            text = "重新检查",
            onClick = onCheck,
        )
    }
}

/** Country tab of the media routing list; kept while the node picker is open. null: all. */
private val mediaRoutingRegion = mutableStateOf<StreamingRegion?>(null)

@Composable
internal fun MediaRoutingScreen(
    padding: PaddingValues,
    enabled: Boolean,
    connectionStatus: ConnectionStatus,
    hasPendingChanges: Boolean,
    services: List<StreamingMediaService>,
    selections: List<StreamingRouteSelection>,
    currentServer: ServerNode?,
    servers: List<ServerNode>,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onApplyChanges: () -> Unit,
    onOpenService: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "流媒体分流", subtitle = "按服务指定出口", onBack = onBack)
        }
        if (connectionStatus == ConnectionStatus.Connected) {
            item {
                NoteBox(
                    text = "应用设置后会重连 VPN。",
                )
            }
        }
        item {
            SettingsGroup(title = "总开关") {
                SettingToggleRow(
                    title = "启用流媒体分流",
                    subtitle = if (connectionStatus == ConnectionStatus.Connected) {
                        "应用设置后生效。"
                    } else {
                        "未指定时走默认线路。"
                    },
                    checked = enabled,
                    onToggle = { onEnabledChange(!enabled) },
                )
            }
        }
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = uiText("应用设置后会重连 VPN。"),
                        color = TextSecondary,
                    )
                    OutlinedActionButton(
                        text = when {
                            hasPendingChanges && connectionStatus == ConnectionStatus.Connected -> "应用设置"
                            hasPendingChanges -> "等待连接后应用"
                            else -> "当前设置已生效"
                        },
                        enabled = hasPendingChanges && connectionStatus == ConnectionStatus.Connected,
                        onClick = onApplyChanges,
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "流媒体列表") {
                var region by mediaRoutingRegion
                // In a country tab, that country's own services come before global ones.
                val shown = region?.let { tab ->
                    services.filter { tab in it.regions }.sortedBy { if (it.regions.first() == tab) 0 else 1 }
                } ?: services
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChoiceChip(
                        text = "${uiText("全部", "All", "Все")} ${services.size}",
                        selected = region == null,
                        onClick = { region = null },
                    )
                    StreamingRegion.entries.forEach { option ->
                        val count = services.count { option in it.regions }
                        if (count > 0) {
                            ChoiceChip(
                                text = "${option.flag} ${uiText(option.zh, option.en, option.ru)} $count",
                                selected = region == option,
                                onClick = { region = option },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                shown.forEachIndexed { index, service ->
                    MediaRoutingServiceRow(
                        service = service,
                        enabled = enabled,
                        selectedNodeLabel = mediaRoutingSelectionLabel(
                            serviceId = service.id,
                            selections = selections,
                            currentServer = currentServer,
                            servers = servers,
                        ),
                        onClick = { onOpenService(service.id) },
                    )
                    if (index != shown.lastIndex) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MediaRoutingNodePickerScreen(
    padding: PaddingValues,
    service: StreamingMediaService?,
    currentServer: ServerNode?,
    groups: List<ServerGroup>,
    servers: List<ServerNode>,
    selectedServerId: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sections = remember(groups, servers) {
        groupedServers(groups, servers, filter = "all", search = "")
            .filter { it.servers.isNotEmpty() }
    }
    val selectedGroupId = remember(sections, selectedServerId) {
        sections.firstOrNull { section -> section.servers.any { it.id == selectedServerId } }?.group?.id
    }
    val expandedGroups = remember(service?.id) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(service?.id, sections, selectedGroupId) {
        val validIds = sections.map { it.group.id }.toSet()
        expandedGroups.keys.toList()
            .filterNot { it in validIds }
            .forEach(expandedGroups::remove)
        sections.forEach { section ->
            if (expandedGroups[section.group.id] == null) {
                expandedGroups[section.group.id] =
                    section.group.id == ImportParser.LOCAL_GROUP_ID || section.group.id == selectedGroupId
            }
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(
                title = service?.name ?: "选择节点",
                subtitle = "单独出口",
                onBack = onBack,
            )
        }
        item {
            ScreenHeader(
                title = uiText(
                    "选择 ${service?.name ?: "流媒体"} 出口",
                    "Select ${service?.name ?: "media"} exit", "Выход для ${service?.name ?: "медиа"}",
                ),
                subtitle = service?.let {
                    uiText(
                        "${uiText(it.suggestedRegion)} · 默认走当前节点；也可以单独指定节点，或者直连。",
                        "${uiText(it.suggestedRegion)} · Uses the selected node by default; you can pick its own node or go direct.",
                        "${uiText(it.suggestedRegion)} · По умолчанию — выбранный узел; можно задать свой узел или напрямую.",
                    )
                } ?: uiText("选择一个出口节点。"),
            )
        }
        item {
            SettingsGroup(title = uiText("基本", "Basics", "Основное")) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaRoutingNodeRow(
                        title = uiText("默认节点", "Default node", "Узел по умолчанию"),
                        subtitle = currentServer?.name?.let { uiText("当前：$it", "Now: $it", "Сейчас: $it") } ?: uiText("当前还没有选中线路"),
                        selected = selectedServerId.isBlank(),
                        onClick = { onSelect("") },
                    )
                    MediaRoutingNodeRow(
                        title = uiText("直连", "Direct", "Напрямую"),
                        subtitle = uiText(
                            "不走代理，直接用手机网络访问。",
                            "Skips the proxy and uses the phone's own network.",
                            "Без прокси, через сеть телефона.",
                        ),
                        selected = selectedServerId == StreamingMediaManager.DIRECT,
                        onClick = { onSelect(StreamingMediaManager.DIRECT) },
                    )
                }
            }
        }
        item {
            SettingsGroup(title = uiText("指定节点", "Its own node", "Свой узел")) {
                if (sections.isEmpty()) {
                    Text(uiText("当前还没有 Local 或订阅节点可选。"), color = TextSecondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        sections.forEach { section ->
                            MediaRoutingGroupCard(
                                section = section,
                                expanded = expandedGroups[section.group.id] ?: false,
                                selectedServerId = selectedServerId,
                                onToggleGroup = {
                                    expandedGroups[section.group.id] = !(expandedGroups[section.group.id] ?: false)
                                },
                                onSelectServer = onSelect,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Per-app route: not proxied, the selected node, or a node of its own. */
@Composable
internal fun PerAppNodePickerScreen(
    padding: PaddingValues,
    app: ProtectedApp?,
    currentServer: ServerNode?,
    groups: List<ServerGroup>,
    servers: List<ServerNode>,
    onBack: () -> Unit,
    onSelect: (enabled: Boolean, serverId: String) -> Unit,
) {
    val selectedServerId = app?.serverId.orEmpty().takeIf { id -> servers.any { it.id == id } }.orEmpty()
    val sections = remember(groups, servers) {
        groupedServers(groups, servers, filter = "all", search = "")
            .filter { it.servers.isNotEmpty() }
    }
    val selectedGroupId = remember(sections, selectedServerId) {
        sections.firstOrNull { section -> section.servers.any { it.id == selectedServerId } }?.group?.id
    }
    val expandedGroups = remember(app?.id) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(app?.id, sections, selectedGroupId) {
        sections.forEach { section ->
            if (expandedGroups[section.group.id] == null) {
                expandedGroups[section.group.id] =
                    section.group.id == ImportParser.LOCAL_GROUP_ID || section.group.id == selectedGroupId
            }
        }
    }
    val proxied = app?.enabled == true
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = app?.name ?: uiText("选择节点"), subtitle = uiText("分应用代理", "Per-app proxy", "Прокси по приложениям"), onBack = onBack)
        }
        item {
            ScreenHeader(
                title = uiText("${app?.name.orEmpty()} 走哪条线路", "Route for ${app?.name.orEmpty()}", "Маршрут для ${app?.name.orEmpty()}"),
                subtitle = uiText(
                    "默认跟随当前选中的节点；也可以单独指定，或者不代理。",
                    "Follows the selected node by default; you can pick its own node or not proxy it.",
                    "По умолчанию — выбранный узел; можно задать свой узел или не проксировать.",
                ),
            )
        }
        item {
            SettingsGroup(title = uiText("基本", "Basics", "Основное")) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaRoutingNodeRow(
                        title = uiText("默认节点", "Default node", "Узел по умолчанию"),
                        subtitle = currentServer?.name?.let { uiText("当前：$it", "Now: $it", "Сейчас: $it") } ?: uiText("当前还没有选中线路"),
                        selected = proxied && selectedServerId.isBlank(),
                        onClick = { onSelect(true, "") },
                    )
                    MediaRoutingNodeRow(
                        title = uiText("不代理", "Don't proxy", "Не проксировать"),
                        subtitle = uiText("这个应用不进 VPN，直接用手机网络。", "This app stays outside the VPN and uses the phone's network.", "Приложение не идёт через VPN и использует сеть телефона."),
                        selected = !proxied,
                        onClick = { onSelect(false, "") },
                    )
                }
            }
        }
        item {
            SettingsGroup(title = uiText("指定节点", "Its own node", "Свой узел")) {
                if (sections.isEmpty()) {
                    Text(uiText("当前还没有 Local 或订阅节点可选。"), color = TextSecondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        sections.forEach { section ->
                            MediaRoutingGroupCard(
                                section = section,
                                expanded = expandedGroups[section.group.id] ?: false,
                                selectedServerId = if (proxied) selectedServerId else "",
                                onToggleGroup = {
                                    expandedGroups[section.group.id] = !(expandedGroups[section.group.id] ?: false)
                                },
                                onSelectServer = { id -> onSelect(true, id) },
                            )
                        }
                    }
                }
            }
        }
        item {
            NoteBox(
                text = uiText(
                    "单独指定的节点只在“仅代理选中应用”模式下生效，该应用的流量（局域网除外）都会走这个节点。",
                    "An app's own node applies in per-app mode; all its traffic (except the local network) uses that node.",
                    "Свой узел действует в режиме по приложениям; весь трафик приложения (кроме локальной сети) идёт через него.",
                ),
            )
        }
    }
}

@Composable
internal fun MediaRoutingGroupCard(
    section: ServerSection,
    expanded: Boolean,
    selectedServerId: String,
    onToggleGroup: () -> Unit,
    onSelectServer: (String) -> Unit,
) {
    val headerShape = RoundedCornerShape(24.dp)
    val interactionSource = remember { MutableInteractionSource() }
    SurfaceCard {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(headerShape)
                    .indication(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true),
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onToggleGroup,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusPill(
                            text = if (section.group.type == ServerGroupType.Subscription) "订阅" else "Local",
                            color = if (section.group.type == ServerGroupType.Subscription) Primary else Secondary,
                            background = if (section.group.type == ServerGroupType.Subscription) {
                                Primary.copy(alpha = 0.14f)
                            } else {
                                Secondary.copy(alpha = 0.14f)
                            },
                        )
                        Text(
                            text = section.group.name,
                            fontSize = TypeScale.ListTitle,
                            lineHeight = TypeScale.ListTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Text(
                        text = uiText("${section.servers.size} 个节点"),
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary,
                )
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                section.servers.forEach { node ->
                    MediaRoutingNodeRow(
                        title = node.name,
                        subtitle = "${uiText(node.subscription)} · ${uiText(node.region)}",
                        selected = selectedServerId == node.id,
                        onClick = { onSelectServer(node.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LogViewerScreen(
    padding: PaddingValues,
    logs: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(
                title = "日志查看",
                subtitle = "最新日志",
                onBack = onBack,
                onAction = onRefresh,
                actionIcon = Icons.Rounded.Sync,
            )
        }
        item {
            SurfaceCard {
                SelectionContainer {
                    Text(
                        text = logs.ifBlank { "暂无日志。" },
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TypeScale.Code,
                        lineHeight = TypeScale.CodeLine,
                    )
                }
            }
        }
    }
}
