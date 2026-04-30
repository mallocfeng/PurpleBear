package com.mallocgfw.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.NodeLatencyTester
import com.mallocgfw.app.xray.VpnRuntimeSnapshot
import kotlinx.coroutines.delay

@Composable
internal fun HomeScreen(
    padding: PaddingValues,
    listState: LazyListState,
    connectionStatus: ConnectionStatus,
    vpnSnapshot: VpnRuntimeSnapshot,
    proxyMode: ProxyMode,
    currentServer: ServerNode?,
    pendingServer: ServerNode?,
    focusPendingSwitchNonce: Int,
    coreVersion: String?,
    latencyTesting: Boolean,
    onToggleConnection: () -> Unit,
    onRetestLatency: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenNodeDetail: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenPerApp: () -> Unit,
    onOpenMediaRouting: () -> Unit,
) {
    val currentRouteLabel = currentServer?.name ?: "未选择"
    val liveDurationMs by produceState(
        initialValue = 0L,
        key1 = connectionStatus,
        key2 = vpnSnapshot.connectedAtMs,
    ) {
        val connectedAt = vpnSnapshot.connectedAtMs
        if (connectedAt == null || (connectionStatus != ConnectionStatus.Connected &&
                connectionStatus != ConnectionStatus.Disconnecting)
        ) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = (System.currentTimeMillis() - connectedAt).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val durationValue = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> formatDuration(liveDurationMs)
        ConnectionStatus.Connecting -> "00:00:00"
        ConnectionStatus.Disconnected -> "--:--:--"
    }
    val downloadRate = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> "测速中"
            vpnSnapshot.speedTestTimedOut -> "节点超时"
            else -> formatRate(vpnSnapshot.rxRateBytesPerSec)
        }
        ConnectionStatus.Connecting,
        ConnectionStatus.Disconnected -> "0 B/s"
    }
    val uploadRate = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> "测速中"
            vpnSnapshot.speedTestTimedOut -> "节点超时"
            else -> formatRate(vpnSnapshot.txRateBytesPerSec)
        }
        ConnectionStatus.Connecting,
        ConnectionStatus.Disconnected -> "0 B/s"
    }
    val downloadSubtitle = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> "正在刷新"
            vpnSnapshot.speedTestTimedOut -> "请稍后重试"
            else -> "下行样本 ${formatByteCount(vpnSnapshot.rxBytes)}"
        }
        ConnectionStatus.Connecting,
        ConnectionStatus.Disconnected -> "等待连接"
    }
    val uploadSubtitle = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> "正在刷新"
            vpnSnapshot.speedTestTimedOut -> "请稍后重试"
            else -> "上行样本 ${formatByteCount(vpnSnapshot.txBytes)}"
        }
        ConnectionStatus.Connecting -> "等待连接"
        ConnectionStatus.Disconnected -> "等待连接"
    }

    LaunchedEffect(focusPendingSwitchNonce, pendingServer?.id) {
        if (focusPendingSwitchNonce == 0 || pendingServer == null) return@LaunchedEffect
        val cardIndex = 3
        listState.animateScrollToItem(cardIndex)
        val cardInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == cardIndex } ?: return@LaunchedEffect
        val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        val desiredTop = (viewportHeight * 0.18f).toInt()
        val delta = cardInfo.offset - desiredTop
        if (delta != 0) {
            listState.animateScrollBy(delta.toFloat())
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = listState,
        contentPadding = mainScreenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroOverviewCard(
                connectionStatus = connectionStatus,
                proxyMode = proxyMode,
                currentRouteLabel = currentRouteLabel,
                coreVersion = coreVersion,
                onToggleConnection = onToggleConnection,
            )
        }
        item {
            HomeMetricsCard(
                durationValue = durationValue,
                downloadRate = downloadRate,
                downloadSubtitle = downloadSubtitle,
                uploadRate = uploadRate,
                uploadSubtitle = uploadSubtitle,
                latencyTesting = latencyTesting,
                onRetestLatency = onRetestLatency,
            )
        }
        item(key = "home-current-node-card") {
            SurfaceCard(
                shape = RoundedCornerShape(28.dp),
                background = Brush.verticalGradient(listOf(SurfaceLow, SurfaceLow)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Eyebrow("当前节点")
                        Text(
                            currentServer?.name ?: "暂无节点",
                            fontSize = TypeScale.ListTitle,
                            lineHeight = TypeScale.ListTitleLine,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (currentServer == null) {
                                "请先导入线路。"
                            } else {
                                "${currentServer.subscription} · ${currentServer.description}"
                            },
                            color = TextSecondary,
                            fontSize = TypeScale.Meta,
                            lineHeight = TypeScale.MetaLine,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = currentServer?.latencyMs?.takeIf { it > 0 }?.let { "$it ms" } ?: "--",
                        color = Secondary,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                pendingServer?.let { server ->
                    Spacer(modifier = Modifier.height(8.dp))
                    NoteBox(
                        text = "待切换线路：${server.name}",
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (pendingServer != null) {
                    CompactButtonRow(
                        primaryText = "切换线路",
                        onPrimary = onOpenServers,
                        secondaryText = "查看详情",
                        onSecondary = onOpenNodeDetail,
                    )
                } else {
                    CompactButtonRow(
                        primaryText = when {
                            currentServer == null -> "导入线路"
                            else -> "选择线路"
                        },
                        onPrimary = if (currentServer == null) onOpenImport else onOpenServers,
                        secondaryText = if (currentServer == null) "订阅管理" else "查看详情",
                        onSecondary = if (currentServer == null) onOpenSubscriptions else onOpenNodeDetail,
                    )
                }
            }
        }
        item {
            FeatureList(
                items = listOf(
                    FeatureAction("系统诊断", "内核 / VPN / DNS", Icons.Rounded.GppGood, onOpenDiagnostics),
                    FeatureAction("订阅管理", "节点与资源", Icons.Rounded.Inventory2, onOpenSubscriptions),
                    FeatureAction("分应用代理", "应用名单", Icons.Rounded.Apps, onOpenPerApp),
                    FeatureAction("流媒体分流", "单独出口", Icons.Rounded.LiveTv, onOpenMediaRouting),
                ),
            )
        }
    }
}

@Composable
internal fun ServersScreen(
    padding: PaddingValues,
    listState: LazyListState,
    sections: List<ServerSection>,
    expandedGroups: Map<String, Boolean>,
    selectedServerId: String,
    filter: String,
    search: String,
    onFilterChange: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenServerDetail: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    latencyTestingIds: Set<String>,
    onTestLatency: (String) -> Unit,
    onDeleteServer: (String) -> Unit,
    onDeleteSubscriptionGroup: (String) -> Unit,
    onCreateLocalNode: () -> Unit,
) {
    var pendingDeleteServer by remember { mutableStateOf<ServerNode?>(null) }
    var pendingDeleteGroup by remember { mutableStateOf<ServerGroup?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = listState,
        contentPadding = mainScreenPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "切换线路",
                subtitle = "按分组管理线路。",
            )
        }
        item {
            SearchField(search = search, onValueChange = onSearchChange)
        }
        item {
            FilterRow(
                filter = filter,
                onFilterChange = onFilterChange,
            )
        }
        items(sections, key = { it.group.id }) { section ->
            GroupSectionCard(
                section = section,
                expanded = expandedGroups[section.group.id] ?: true,
                selectedServerId = selectedServerId,
                onToggleGroup = { onToggleGroup(section.group.id) },
                onSelectServer = onSelectServer,
                onOpenServerDetail = onOpenServerDetail,
                onToggleFavorite = onToggleFavorite,
                latencyTestingIds = latencyTestingIds,
                onTestLatency = onTestLatency,
                onDeleteServer = { pendingDeleteServer = it },
                onDeleteGroup = { pendingDeleteGroup = it },
                onCreateLocalNode = onCreateLocalNode,
            )
        }
    }

    pendingDeleteServer?.let { server ->
        ConfirmDeleteDialog(
            title = "删除节点",
            message = "确认删除本地节点“${server.name}”？删除后需要重新导入才能恢复。",
            confirmText = "删除",
            onConfirm = {
                onDeleteServer(server.id)
                pendingDeleteServer = null
            },
            onDismiss = { pendingDeleteServer = null },
        )
    }

    pendingDeleteGroup?.let { group ->
        ConfirmDeleteDialog(
            title = "删除订阅组",
            message = "确认删除订阅组“${group.name}”？组内所有节点都会一起删除。",
            confirmText = "删除",
            onConfirm = {
                onDeleteSubscriptionGroup(group.id)
                pendingDeleteGroup = null
            },
            onDismiss = { pendingDeleteGroup = null },
        )
    }
}

@Composable
internal fun NodeDetailScreen(
    padding: PaddingValues,
    server: ServerNode?,
    group: ServerGroup?,
    preProxyNode: ServerNode?,
    fallbackNode: ServerNode?,
    canSelectPreProxy: Boolean,
    canSelectFallback: Boolean,
    connectionStatus: ConnectionStatus,
    activeServerId: String?,
    runtimeMessage: String?,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onTestLatency: () -> Unit,
    latencyTesting: Boolean,
    onOpenPreProxyPicker: () -> Unit,
    onOpenFallbackPicker: () -> Unit,
    onEditLocalNode: () -> Unit,
    onDeleteNode: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
) {
    var showDeleteNodeDialog by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    val isActiveServer = server?.id != null && server.id == activeServerId
    val parameterRows = remember(server?.id, server?.outboundJson, server?.rawUri) {
        server?.let(::buildNodeParameterRows).orEmpty()
    }
    val fallbackUnsupportedMessage = server
        ?.takeIf { canSelectFallback && !NodeLatencyTester.supportsHeartbeatFallback(it) }
        ?.let(NodeLatencyTester::heartbeatFallbackUnsupportedMessage)
    val fallbackSelectionEnabled = canSelectFallback && fallbackUnsupportedMessage == null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "节点详情", subtitle = "线路信息", onBack = onBack)
        }
        item {
            ScreenHeader(
                title = server?.name ?: "暂无节点",
                subtitle = server?.description ?: "当前没有可查看的节点信息。",
            )
        }
        if (server != null && (canSelectPreProxy || canSelectFallback)) {
            item {
                SurfaceCard(
                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.18f)),
                    background = Brush.verticalGradient(listOf(SurfaceHigh, SurfaceLow)),
                ) {
                    if (canSelectPreProxy) {
                        DetailMenuRow(
                            label = "前置代理节点",
                            value = "选择可用节点作为跳板",
                            selection = preProxyNode?.name ?: "未设置",
                            onClick = onOpenPreProxyPicker,
                        )
                    }
                    if (canSelectPreProxy && canSelectFallback) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (canSelectFallback) {
                        DetailMenuRow(
                            label = "备用节点",
                            value = if (fallbackUnsupportedMessage == null) {
                                "当前线路检测失败时自动切换"
                            } else {
                                "此协议不能可靠触发自动切换"
                            },
                            selection = if (fallbackUnsupportedMessage == null) {
                                fallbackNode?.name ?: "未设置"
                            } else {
                                "不支持"
                            },
                            onClick = onOpenFallbackPicker,
                            enabled = fallbackSelectionEnabled,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    NoteBox(
                        text = fallbackUnsupportedMessage
                            ?: "前置代理重新连接后生效；备用节点会在当前连接节点检测失败后自动阻断操作并切换。",
                    )
                }
            }
        }
        item {
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Eyebrow("当前状态")
                        Text(
                            when {
                                server == null -> "等待导入"
                                connectionStatus == ConnectionStatus.Connected && isActiveServer -> "当前已连接线路"
                                connectionStatus == ConnectionStatus.Connected -> "可热切换到这条线路"
                                else -> "可用节点"
                            },
                            fontSize = TypeScale.ListTitle,
                            lineHeight = TypeScale.ListTitleLine,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    StatusPill(
                        text = server?.let { "${it.latencyMs} ms" } ?: "--",
                        color = Secondary,
                        background = Secondary.copy(alpha = 0.14f),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                TwoColumnInfoGrid(
                    items = listOf(
                        "协议" to (server?.protocol ?: "--"),
                        "安全层" to (server?.security ?: "--"),
                        "传输层" to (server?.transport ?: "--"),
                        "Flow" to (server?.flow ?: "--"),
                    ),
                    compact = true,
                )
            }
        }
        item {
            SurfaceCard {
                DetailRow(
                    label = "接入地址",
                    value = server?.address ?: "暂无",
                    trailing = "端口 ${server?.port ?: "--"}",
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(
                    label = "订阅来源",
                    value = server?.subscription ?: "未导入",
                    trailing = group?.let { if (it.type == ServerGroupType.Local) "Local" else "订阅组" } ?: "官方核心兼容",
                )
                NoteBox(text = "完整节点参数在这里查看。")
            }
        }
        if (parameterRows.isNotEmpty()) {
            item {
                SurfaceCard {
                    Eyebrow("全部参数")
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        parameterRows.forEach { entry ->
                            ParameterReadRow(label = entry.first, value = entry.second)
                        }
                    }
                }
            }
        }
        item {
            ButtonRow(
                primaryText = when {
                    server == null -> "返回线路页"
                    connectionStatus == ConnectionStatus.Connected && isActiveServer -> "当前线路已连接"
                    connectionStatus == ConnectionStatus.Connected -> "切换为当前线路"
                    else -> "连接此线路"
                },
                onPrimary = if (server == null) onBack else onConnect,
                secondaryText = if (latencyTesting) "检测中…" else "测试延迟",
                onSecondary = onTestLatency,
                tertiaryText = when {
                    server == null -> null
                    group?.type == ServerGroupType.Local -> "编辑节点"
                    group?.type == ServerGroupType.Subscription -> "删除整个订阅"
                    else -> null
                },
                onTertiary = when {
                    server == null -> null
                    group?.type == ServerGroupType.Local -> onEditLocalNode
                    group?.type == ServerGroupType.Subscription -> ({ showDeleteGroupDialog = true })
                    else -> null
                },
            )
        }
        if (server != null && group?.type == ServerGroupType.Local) {
            item {
                CompactOutlinedActionButton(
                    text = "删除节点",
                    onClick = { showDeleteNodeDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!runtimeMessage.isNullOrBlank() && runtimeMessage != "官方 Xray 内核已就绪") {
            item {
                NoteBox(text = runtimeMessage)
            }
        }
    }

    if (showDeleteNodeDialog && server != null) {
        ConfirmDeleteDialog(
            title = "删除节点",
            message = "确认删除本地节点“${server.name}”？删除后需要重新导入才能恢复。",
            confirmText = "删除",
            onConfirm = {
                showDeleteNodeDialog = false
                onDeleteNode(server.id)
            },
            onDismiss = { showDeleteNodeDialog = false },
        )
    }

    if (showDeleteGroupDialog && group != null) {
        ConfirmDeleteDialog(
            title = "删除订阅组",
            message = "确认删除订阅组“${group.name}”？组内所有节点都会一起删除。",
            confirmText = "删除",
            onConfirm = {
                showDeleteGroupDialog = false
                onDeleteGroup(group.id)
            },
            onDismiss = { showDeleteGroupDialog = false },
        )
    }
}

@Composable
internal fun PreProxyNodePickerScreen(
    padding: PaddingValues,
    mode: NodeLinkPickerMode,
    server: ServerNode?,
    group: ServerGroup?,
    candidates: List<ServerNode>,
    selectedServerId: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val title = when (mode) {
        NodeLinkPickerMode.PreProxy -> "前置代理"
        NodeLinkPickerMode.Fallback -> "备用节点"
    }
    val headerTitle = when (mode) {
        NodeLinkPickerMode.PreProxy -> "为 ${server?.name ?: "当前节点"} 选择前置代理"
        NodeLinkPickerMode.Fallback -> "为 ${server?.name ?: "当前节点"} 选择备用节点"
    }
    val headerSubtitle = when (mode) {
        NodeLinkPickerMode.PreProxy -> "选择任意可用节点作为跳板。"
        NodeLinkPickerMode.Fallback -> "当前节点检测失败时，会自动断开并切换到备用节点。"
    }
    val emptyTitle = when (mode) {
        NodeLinkPickerMode.PreProxy -> "不使用前置代理"
        NodeLinkPickerMode.Fallback -> "不使用备用节点"
    }
    val emptySubtitle = when (mode) {
        NodeLinkPickerMode.PreProxy -> "直接连接目标节点"
        NodeLinkPickerMode.Fallback -> "检测失败时只提示，不自动切换"
    }
    val emptyListText = when (mode) {
        NodeLinkPickerMode.PreProxy -> "当前没有其它可用节点可做前置代理。"
        NodeLinkPickerMode.Fallback -> "当前没有其它可用节点可做备用节点。"
    }
    val fallbackUnsupportedMessage = server
        ?.takeIf { mode == NodeLinkPickerMode.Fallback && !NodeLatencyTester.supportsHeartbeatFallback(it) }
        ?.let(NodeLatencyTester::heartbeatFallbackUnsupportedMessage)

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
                title = title,
                subtitle = group?.name ?: "当前节点",
                onBack = onBack,
            )
        }
        item {
            ScreenHeader(
                title = headerTitle,
                subtitle = fallbackUnsupportedMessage ?: headerSubtitle,
            )
        }
        item {
            SettingsGroup(title = "当前设置") {
                MediaRoutingNodeRow(
                    title = emptyTitle,
                    subtitle = emptySubtitle,
                    selected = selectedServerId.isBlank(),
                    onClick = { onSelect("") },
                    enabled = fallbackUnsupportedMessage == null,
                )
            }
        }
        item {
            SettingsGroup(title = "可用节点") {
                if (candidates.isEmpty()) {
                    Text(emptyListText, color = TextSecondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        candidates.forEach { node ->
                            MediaRoutingNodeRow(
                                title = node.name,
                                subtitle = "${node.subscription} · ${node.protocol} · ${node.transport}",
                                selected = selectedServerId == node.id,
                                onClick = { onSelect(node.id) },
                                enabled = fallbackUnsupportedMessage == null,
                            )
                        }
                    }
                }
            }
        }
    }
}
