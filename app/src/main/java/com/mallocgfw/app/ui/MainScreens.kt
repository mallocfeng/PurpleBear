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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import com.mallocgfw.app.ui.theme.PrimaryStrong
import androidx.compose.ui.draw.scale
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.Surface as SurfaceTone
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.Warning
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
import com.mallocgfw.app.model.NodeFlags
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.ssrCompatibilityLabel
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
) {
    val currentRouteHint = currentServer?.ssrCompatibilityLabel()
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
    val speedTestTimeoutSubtitle = if (currentRouteHint != null) {
        uiText("连接可用可忽略", "Usable connection", "Соединение работает")
    } else {
        uiText("请稍后重试")
    }
    val downloadRate = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> "测速中"
            vpnSnapshot.speedTestTimedOut -> "测速超时"
            else -> formatRate(vpnSnapshot.rxRateBytesPerSec)
        }
        ConnectionStatus.Connecting,
        ConnectionStatus.Disconnected -> "0 B/s"
    }
    val uploadRate = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> "测速中"
            vpnSnapshot.speedTestTimedOut -> "测速超时"
            else -> formatRate(vpnSnapshot.txRateBytesPerSec)
        }
        ConnectionStatus.Connecting,
        ConnectionStatus.Disconnected -> "0 B/s"
    }
    val downloadSubtitle = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> uiText("正在刷新")
            vpnSnapshot.speedTestTimedOut -> speedTestTimeoutSubtitle
            else -> uiText("下行样本 ${formatByteCount(vpnSnapshot.rxBytes)}", "Down ${formatByteCount(vpnSnapshot.rxBytes)}", "Приём ${formatByteCount(vpnSnapshot.rxBytes)}")
        }
        ConnectionStatus.Connecting,
        ConnectionStatus.Disconnected -> uiText("等待连接")
    }
    val uploadSubtitle = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Disconnecting -> when {
            vpnSnapshot.speedTestInFlight -> uiText("正在刷新")
            vpnSnapshot.speedTestTimedOut -> speedTestTimeoutSubtitle
            else -> uiText("上行样本 ${formatByteCount(vpnSnapshot.txBytes)}", "Up ${formatByteCount(vpnSnapshot.txBytes)}", "Отправка ${formatByteCount(vpnSnapshot.txBytes)}")
        }
        ConnectionStatus.Connecting -> uiText("等待连接")
        ConnectionStatus.Disconnected -> uiText("等待连接")
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

    val connected = connectionStatus == ConnectionStatus.Connected ||
        connectionStatus == ConnectionStatus.Disconnecting

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = listState,
        contentPadding = mainScreenPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "home-connect") {
            ConnectHero(
                connectionStatus = connectionStatus,
                proxyMode = proxyMode,
                durationValue = durationValue,
                onToggleConnection = onToggleConnection,
            )
        }
        if (connected) {
            item(key = "home-stats") {
                // Eases in on connect: fades in while settling down from just
                // above and growing to full size; the node card below slides
                // down to make room (animateItem), and it fades out on disconnect.
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    // Let the node card start moving first, so the two never overlap.
                    delay(100)
                    appear.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow))
                }
                Box(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            fadeOutSpec = tween(durationMillis = 220),
                        )
                        .graphicsLayer {
                            val p = appear.value
                            alpha = p.coerceIn(0f, 1f)
                            translationY = (1f - p) * -18.dp.toPx()
                            val scale = 0.94f + 0.06f * p
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        },
                ) {
                    HomeStatsCard(
                        downloadRate = downloadRate,
                        downloadSubtitle = downloadSubtitle,
                        uploadRate = uploadRate,
                        uploadSubtitle = uploadSubtitle,
                        latencyTesting = latencyTesting,
                        onRefresh = onRetestLatency,
                    )
                }
            }
        }
        item(key = "home-current-node-card") {
            Column(
                modifier = Modifier.animateItem(placementSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel("当前节点")
                pendingServer?.let { server ->
                    PendingSwitchBanner(serverName = server.name, onClick = onOpenServers)
                }
                CurrentNodeCard(
                    server = currentServer,
                    hint = currentRouteHint,
                    latencyTesting = latencyTesting,
                    onOpenServers = onOpenServers,
                    onOpenImport = onOpenImport,
                    onOpenDetail = onOpenNodeDetail,
                    onRetestLatency = onRetestLatency,
                )
            }
        }
    }
}

@Composable
private fun ConnectHero(
    connectionStatus: ConnectionStatus,
    proxyMode: ProxyMode,
    durationValue: String,
    onToggleConnection: () -> Unit,
) {
    val busy = connectionStatus == ConnectionStatus.Connecting ||
        connectionStatus == ConnectionStatus.Disconnecting
    val connected = connectionStatus == ConnectionStatus.Connected
    val haloScale by animateFloatAsState(
        targetValue = if (connected) 1f else 0.82f,
        animationSpec = tween(durationMillis = 420),
        label = "connect-halo",
    )
    // Backdrop: a faint glow and two hairline rings, a touch stronger when
    // connected, plus one slow ripple then. Kept quiet so the button leads.
    val glowAlpha by animateFloatAsState(
        targetValue = when {
            connected -> if (IsLightTheme) 0.16f else 0.20f
            else -> if (IsLightTheme) 0.09f else 0.10f
        },
        animationSpec = tween(durationMillis = 420),
        label = "connect-glow",
    )
    val ripple = if (connected) {
        rememberInfiniteTransition(label = "connect-ripple").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2800, easing = LinearEasing)),
            label = "connect-ripple-progress",
        ).value
    } else {
        null
    }
    val accent = Primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 30.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(212.dp)
                .drawBehind {
                    val glowRadius = 150.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                            center = center,
                            radius = glowRadius,
                        ),
                        radius = glowRadius,
                    )
                    val hairline = 1.dp.toPx()
                    drawCircle(accent.copy(alpha = glowAlpha * 0.9f), radius = 116.dp.toPx(), style = Stroke(hairline))
                    drawCircle(accent.copy(alpha = glowAlpha * 0.5f), radius = 130.dp.toPx(), style = Stroke(hairline))
                    if (ripple != null) {
                        val radius = 84.dp.toPx() + (130.dp.toPx() - 84.dp.toPx()) * ripple
                        drawCircle(
                            accent.copy(alpha = 0.35f * (1f - ripple)),
                            radius = radius,
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(212.dp)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = if (connected) 0.12f else 0.06f)),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(176.dp),
                    color = Primary,
                    trackColor = Primary.copy(alpha = 0.12f),
                    strokeWidth = 3.dp,
                )
            }
            // A soft top-to-bottom gradient instead of a flat face.
            val face = when {
                connected -> Brush.linearGradient(listOf(Secondary, PrimaryStrong))
                IsLightTheme -> Brush.verticalGradient(listOf(Color.White, Color(0xFFEFEBFF)))
                else -> Brush.verticalGradient(listOf(SurfaceHigh, SurfaceTone))
            }
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                shadowElevation = if (connected || !IsLightTheme) 0.dp else 8.dp,
                border = if (connected) null else BorderStroke(1.dp, Primary.copy(alpha = if (IsLightTheme) 0.14f else 0.22f)),
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !busy, onClick = onToggleConnection),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(face), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = uiText(if (connected) "断开连接" else "连接"),
                        tint = if (connected) AccentContentColor else Primary,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = uiText(
                when (connectionStatus) {
                    ConnectionStatus.Connected -> "已连接"
                    ConnectionStatus.Connecting -> "正在连接…"
                    ConnectionStatus.Disconnecting -> "正在断开…"
                    ConnectionStatus.Disconnected -> "未连接"
                },
            ),
            color = TextPrimary,
            fontSize = TypeScale.Hero,
            lineHeight = TypeScale.HeroLine,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (connected) {
                "$durationValue · ${uiText(proxyModeText(proxyMode))}"
            } else {
                uiText("轻触按钮开始连接", "Tap to connect")
            },
            color = TextSecondary,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
        )
    }
}

@Composable
private fun HomeStatsCard(
    downloadRate: String,
    downloadSubtitle: String,
    uploadRate: String,
    uploadSubtitle: String,
    latencyTesting: Boolean,
    onRefresh: () -> Unit,
) {
    SurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                icon = Icons.Rounded.ArrowDownward,
                label = "下载",
                value = uiText(downloadRate),
                subtitle = downloadSubtitle,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .width(0.6.dp)
                    .background(DividerColor),
            )
            StatCell(
                icon = Icons.Rounded.ArrowUpward,
                label = "上传",
                value = uiText(uploadRate),
                subtitle = uploadSubtitle,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            )
            CompactIconAction(
                icon = Icons.Rounded.Sync,
                tint = if (latencyTesting) Primary else TextSecondary,
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun StatCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
            Eyebrow(label)
        }
        Text(
            text = value,
            color = TextPrimary,
            fontSize = TypeScale.CardTitle,
            lineHeight = TypeScale.CardTitleLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = uiText(subtitle),
            color = TextSecondary,
            fontSize = TypeScale.Tiny,
            lineHeight = TypeScale.TinyLine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CurrentNodeCard(
    server: ServerNode?,
    hint: String?,
    latencyTesting: Boolean,
    onOpenServers: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenDetail: () -> Unit,
    onRetestLatency: () -> Unit,
) {
    GroupedCard {
        if (server == null) {
            ListRow(
                title = "导入节点",
                subtitle = "粘贴订阅链接、扫码或选择文件",
                icon = Icons.Rounded.Add,
                onClick = onOpenImport,
            )
            return@GroupedCard
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenServers)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NodeAvatar(server = server)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Same as the node list: a leading flag is shown by the avatar only.
                    text = leadingFlagEmoji(server.name)
                        ?.let { server.name.removePrefix(it).trimStart().ifBlank { null } }
                        ?: server.name,
                    color = TextPrimary,
                    fontSize = TypeScale.ListTitle,
                    lineHeight = TypeScale.ListTitleLine,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        server.protocol.takeIf { it.isNotBlank() }?.let(::protocolDisplayName),
                        hint?.let { uiText(it) } ?: uiText(server.subscription).takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            LatencyText(latencyMs = server.latencyMs, testing = latencyTesting)
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp),
            )
        }
        GroupDivider(startIndent = 16.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            NodeQuickAction(
                text = if (latencyTesting) "测速中…" else "测速",
                icon = Icons.Rounded.Speed,
                onClick = onRetestLatency,
                modifier = Modifier.weight(1f),
            )
            NodeQuickAction(
                text = "详情",
                icon = Icons.Rounded.Info,
                onClick = onOpenDetail,
                modifier = Modifier.weight(1f),
            )
            NodeQuickAction(
                text = "切换",
                icon = Icons.Rounded.SwapHoriz,
                onClick = onOpenServers,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NodeQuickAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = uiText(text),
            color = TextPrimary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Round badge for a node: the country flag when one can be told from the name
 * (see NodeFlags), otherwise the first letter / emoji of the name.
 */
@Composable
internal fun NodeAvatar(server: ServerNode, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val label = remember(server.name) {
        NodeFlags.flagFor(server.name) ?: server.name.trim().let { name ->
            val codePoint = name.codePointAt(0).takeIf { name.isNotEmpty() }
            when {
                codePoint == null -> "?"
                Character.isLetterOrDigit(codePoint) -> String(Character.toChars(codePoint)).uppercase()
                else -> String(Character.toChars(codePoint))
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Primary.copy(alpha = if (IsLightTheme) 0.10f else 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Primary,
            fontSize = TypeScale.ListTitle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Latency coloured by quality: green < 200ms, amber < 500ms, red beyond. */
@Composable
internal fun LatencyText(latencyMs: Int, testing: Boolean = false) {
    val color = when {
        testing || latencyMs <= 0 -> TextSecondary
        // A real request through the node (handshake included), not a bare
        // TCP connect: a good node is a few hundred milliseconds.
        latencyMs < 400 -> Success
        latencyMs < 1000 -> Warning
        else -> Error
    }
    Text(
        text = when {
            testing -> "…"
            latencyMs > 0 -> "$latencyMs ms"
            else -> "--"
        },
        color = color,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
internal fun ServersScreen(
    padding: PaddingValues,
    listState: LazyListState,
    sections: List<ServerSection>,
    isGroupExpanded: (String) -> Boolean,
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SearchField(search = search, placeholder = "搜索节点", onValueChange = onSearchChange)
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
                expanded = isGroupExpanded(section.group.id),
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
            message = uiText(
                "确认删除本地节点“${server.name}”？删除后需要重新导入才能恢复。",
                "Delete local node \"${server.name}\"? You will need to import it again to restore it.", "Удалить локальный узел «${server.name}»? Чтобы восстановить его, понадобится импортировать заново.",
            ),
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
            message = uiText(
                "确认删除订阅组“${group.name}”？组内所有节点都会一起删除。",
                "Delete subscription group \"${group.name}\" and all nodes in it?", "Удалить группу подписки «${group.name}» и все её узлы?",
            ),
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
    preProxyUnsupported: Boolean = false,
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
    var showAllParameters by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    val isActiveServer = server?.id != null && server.id == activeServerId
    val parameterRows = remember(server?.id, server?.outboundJson, server?.rawUri) {
        server?.let(::buildNodeParameterRows).orEmpty()
    }
    val fallbackUnsupportedMessage = server
        ?.takeIf { canSelectFallback && !NodeLatencyTester.supportsHeartbeatFallback(it) }
        ?.let(NodeLatencyTester::heartbeatFallbackUnsupportedMessage)
    val fallbackUnsupportedUiMessage = server
        ?.takeIf { canSelectFallback && !NodeLatencyTester.supportsHeartbeatFallback(it) }
        ?.let {
            uiText(
                NodeLatencyTester.heartbeatFallbackUnsupportedMessage(it),
                "${it.protocol}/${it.transport} cannot be checked reliably while connected, so automatic fallback is unavailable.",
            )
        }
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
        val showPreProxyRow = canSelectPreProxy || preProxyUnsupported
        if (server != null && (showPreProxyRow || canSelectFallback)) {
            item {
                GroupedCard {
                    if (canSelectPreProxy) {
                        ListRow(
                            title = "前置代理",
                            subtitle = "重新连接后生效",
                            value = preProxyNode?.name ?: "未设置",
                            icon = Icons.Rounded.Link,
                            onClick = onOpenPreProxyPicker,
                        )
                    } else if (preProxyUnsupported) {
                        // Shown greyed rather than hidden, so the limit is visible. These
                        // nodes can still be chosen as another node's pre-proxy.
                        ListRow(
                            title = "前置代理",
                            subtitle = "兼容内核运行的协议暂不支持",
                            value = "不支持",
                            icon = Icons.Rounded.Link,
                            enabled = false,
                            onClick = {},
                        )
                    }
                    if (showPreProxyRow && canSelectFallback) GroupDivider(startIndent = 58.dp)
                    if (canSelectFallback) {
                        ListRow(
                            title = "备用节点",
                            subtitle = fallbackUnsupportedUiMessage ?: "检测失败时自动切换",
                            value = if (fallbackUnsupportedMessage == null) fallbackNode?.name ?: "未设置" else "不支持",
                            icon = Icons.Rounded.SwapHoriz,
                            enabled = fallbackSelectionEnabled,
                            onClick = onOpenFallbackPicker,
                        )
                    }
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
                                server == null -> uiText("等待导入")
                                connectionStatus == ConnectionStatus.Connected && isActiveServer -> uiText("当前已连接线路")
                                connectionStatus == ConnectionStatus.Connected -> uiText("可热切换到这条线路")
                                else -> uiText("可用节点")
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
                        "安全层" to (server?.security?.let { uiText(it) } ?: "--"),
                        "传输层" to (server?.transport ?: "--"),
                        "Flow" to (server?.flow?.let { uiText(it) } ?: "--"),
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
                    trailing = uiText("端口 ${server?.port ?: "--"}", "Port ${server?.port ?: "--"}", "Порт ${server?.port ?: "--"}"),
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(
                    label = "订阅来源",
                    value = server?.subscription?.let { uiText(it) } ?: uiText("未导入"),
                    trailing = group?.let { if (it.type == ServerGroupType.Local) "Local" else "订阅组" } ?: "官方核心兼容",
                )
            }
        }
        if (parameterRows.isNotEmpty()) {
            item {
                // Raw parameters are long; keep them folded until asked for.
                GroupedCard {
                    ListRow(
                        title = "全部参数",
                        value = uiText("${parameterRows.size} 项", "${parameterRows.size} items", "Параметров: ${parameterRows.size}"),
                        icon = Icons.Rounded.Code,
                        onClick = { showAllParameters = !showAllParameters },
                        trailing = {
                            Icon(
                                imageVector = if (showAllParameters) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary,
                            )
                        },
                    )
                    if (showAllParameters) {
                        GroupDivider()
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            parameterRows.forEach { entry ->
                                // Values are raw config data; only the security label is a display word.
                                ParameterReadRow(
                                    label = entry.first,
                                    value = if (entry.first == "安全层") uiText(entry.second) else entry.second,
                                )
                            }
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
        if (server != null) {
            item {
                CompactOutlinedActionButton(
                    text = uiText("分享节点", "Share node", "Поделиться узлом"),
                    onClick = { showShareDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

    if (showShareDialog && server != null) {
        NodeShareDialog(server = server, onDismiss = { showShareDialog = false })
    }

    if (showDeleteNodeDialog && server != null) {
                ConfirmDeleteDialog(
            title = "删除节点",
            message = uiText(
                "确认删除本地节点“${server.name}”？删除后需要重新导入才能恢复。",
                "Delete local node \"${server.name}\"? You will need to import it again to restore it.", "Удалить локальный узел «${server.name}»? Чтобы восстановить его, понадобится импортировать заново.",
            ),
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
            message = uiText(
                "确认删除订阅组“${group.name}”？组内所有节点都会一起删除。",
                "Delete subscription group \"${group.name}\" and all nodes in it?", "Удалить группу подписки «${group.name}» и все её узлы?",
            ),
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
        NodeLinkPickerMode.PreProxy -> uiText(
            "为 ${server?.name ?: "当前节点"} 选择前置代理",
            "Choose pre-proxy for ${server?.name ?: uiText("当前节点")}", "Предварительный прокси для ${server?.name ?: uiText("当前节点")}",
        )
        NodeLinkPickerMode.Fallback -> uiText(
            "为 ${server?.name ?: "当前节点"} 选择备用节点",
            "Choose fallback for ${server?.name ?: uiText("当前节点")}", "Резервный узел для ${server?.name ?: uiText("当前节点")}",
        )
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
    val fallbackUnsupportedUiMessage = server
        ?.takeIf { mode == NodeLinkPickerMode.Fallback && !NodeLatencyTester.supportsHeartbeatFallback(it) }
        ?.let {
            uiText(
                NodeLatencyTester.heartbeatFallbackUnsupportedMessage(it),
                "${it.protocol}/${it.transport} cannot be checked reliably while connected, so automatic fallback is unavailable.",
            )
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
                title = title,
                subtitle = group?.name ?: "当前节点",
                onBack = onBack,
            )
        }
        item {
            ScreenHeader(
                title = headerTitle,
                subtitle = fallbackUnsupportedUiMessage ?: headerSubtitle,
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
                    Text(uiText(emptyListText), color = TextSecondary)
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
