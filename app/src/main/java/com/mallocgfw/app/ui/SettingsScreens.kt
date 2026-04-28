package com.mallocgfw.app.ui

import android.graphics.drawable.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.AppDnsMode
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
import com.mallocgfw.app.model.StreamingMediaService
import com.mallocgfw.app.model.StreamingRouteSelection
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.XrayCoreStatus

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
    onApplyChanges: () -> Unit,
) {
    val normalizedSearch = search.trim().lowercase()
    val filteredApps = remember(apps, normalizedSearch) {
        if (normalizedSearch.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.name.lowercase().contains(normalizedSearch) ||
                    app.category.lowercase().contains(normalizedSearch)
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
                            text = if (proxyMode == ProxyMode.PerApp) "仅代理选中应用" else "智能分流",
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = if (proxyMode == ProxyMode.PerApp) {
                                "仅勾选应用进入 VPN。"
                            } else {
                                "切换到分应用后生效。"
                            },
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
            SearchField(
                search = search,
                placeholder = "搜索应用名称或分类…",
                onValueChange = onSearchChange,
            )
        }
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "应用设置后会重连 VPN。",
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
        items(filteredApps, key = { it.id }) { app ->
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        GlassBadge(
                            icon = Icons.Rounded.Apps,
                            modifier = Modifier.size(44.dp),
                            innerPadding = 10.dp,
                        )
                        Column {
                            Text(
                                text = app.name,
                                fontSize = TypeScale.ListTitle,
                                lineHeight = TypeScale.ListTitleLine,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(app.category, color = TextSecondary)
                        }
                    }
                    TogglePill(checked = app.enabled, onClick = { onToggleApp(app.id) })
                }
            }
        }
        item {
            NoteBox(
                text = if (filteredApps.isEmpty()) {
                    "没有匹配到应用。"
                } else {
                    "搜索会实时过滤应用。"
                },
            )
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
        item {
            ScreenHeader(
                title = "系统诊断",
                subtitle = "检查配置、内核、VPN、DNS 和握手。",
            )
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
    onHeartbeatIntervalChange: (Int) -> Unit,
    onDailyAutoUpdateChange: (Boolean) -> Unit,
    onUpdateNotificationsChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onDnsChange: (AppDnsMode, String) -> Unit,
    onLogLevelChange: (AppLogLevel) -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onViewLogs: () -> Unit,
) {
    var showDnsDialog by remember { mutableStateOf(false) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showHeartbeatDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "设置", subtitle = "偏好与高级", onBack = onBack)
        }
        item {
            ScreenHeader(
                title = "设置",
                subtitle = "连接、更新、DNS 与日志。",
            )
        }
        if (!message.isNullOrBlank()) {
            item {
                NoteBox(text = message)
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
                    title = "快捷开关",
                    subtitle = "添加到系统下拉菜单。",
                    actionText = "添加",
                    onAction = onAddQuickSettingsTile,
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
                    title = "显示更新通知",
                    subtitle = "同步完成后通知。",
                    checked = settings.showUpdateNotifications,
                    onToggle = { onUpdateNotificationsChange(!settings.showUpdateNotifications) },
                )
            }
        }
        item {
            SettingsGroup(title = "高级设置") {
                SettingActionRow(
                    title = "自定义 DNS",
                    subtitle = "当前 ${settings.dnsSummary()}。",
                    actionText = "编辑",
                    onAction = { showDnsDialog = true },
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingDualActionRow(
                    title = "日志级别",
                    subtitle = "当前 ${settings.logLevel.displayName}。",
                    primaryActionText = "调整",
                    onPrimaryAction = { showLogLevelDialog = true },
                    secondaryActionText = "查看",
                    onSecondaryAction = onViewLogs,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingInfoRow(
                    title = "核心资产",
                    subtitle = buildString {
                        append("官方编译产物 · ")
                        append((coreVersion ?: "Xray").trim())
                        append(" · ")
                        append(coreAbi ?: "未初始化")
                        append(" · ")
                        append(coreStatus.label())
                        append('\n')
                        append(systemVersion)
                        append(" · ")
                        append(APP_BRAND_NAME)
                        append(' ')
                        append(appVersion)
                    },
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
}

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
        item {
            ScreenHeader(
                title = "流媒体分流",
                subtitle = "按服务指定出口节点。",
            )
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
                        text = "应用设置后会重连 VPN。",
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
                services.forEachIndexed { index, service ->
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
                    if (index != services.lastIndex) {
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
                title = "选择 ${service?.name ?: "流媒体"} 出口",
                subtitle = service?.let {
                    "${it.suggestedRegion} · 未指定则走默认线路。"
                } ?: "选择一个出口节点。",
            )
        }
        item {
            SettingsGroup(title = "默认线路") {
                MediaRoutingNodeRow(
                    title = currentServer?.name ?: "当前还没有选中线路",
                    subtitle = "默认出口",
                    selected = selectedServerId.isBlank(),
                    onClick = { onSelect("") },
                )
            }
        }
        item {
            SettingsGroup(title = "可选节点") {
                if (sections.isEmpty()) {
                    Text("当前还没有 Local 或订阅节点可选。", color = TextSecondary)
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
                        indication = rememberRipple(bounded = true),
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
                        text = "${section.servers.size} 个节点",
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
                        subtitle = "${node.subscription} · ${node.region}",
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

