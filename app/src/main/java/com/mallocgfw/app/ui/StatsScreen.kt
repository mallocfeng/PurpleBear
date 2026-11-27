package com.mallocgfw.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mallocgfw.app.panel.PanelConfig
import com.mallocgfw.app.panel.PanelRuntime
import com.mallocgfw.app.panel.PanelScope
import com.mallocgfw.app.panel.PanelSettings
import com.mallocgfw.app.mcp.McpSettings
import com.mallocgfw.app.stats.ConnectionMonitor
import com.mallocgfw.app.stats.LabeledConnection
import com.mallocgfw.app.stats.TrafficCount
import com.mallocgfw.app.stats.TrafficLabels
import com.mallocgfw.app.stats.TrafficStatsStore
import com.mallocgfw.app.stats.TrafficSummary
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class StatsTab { Connections, Traffic, Panel }

private enum class TrafficGroup { Apps, Nodes }

/** One-line state for the Me tab row. */
@Composable
internal fun statsMenuValue(): String? {
    val view by ConnectionMonitor.view.collectAsState()
    return if (view.running) uiText("${view.active.size} 个连接", if (view.active.size == 1) "1 connection" else "${view.active.size} connections", "Соединений: ${view.active.size}") else null
}

@Composable
internal fun StatsScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(StatsTab.Connections) }
    var search by rememberSaveable { mutableStateOf("") }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    var rangeDays by rememberSaveable { mutableStateOf(1) }
    var group by rememberSaveable { mutableStateOf(TrafficGroup.Apps) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view by ConnectionMonitor.view.collectAsState()
    val version by TrafficStatsStore.version.collectAsState()
    val summary by produceState<TrafficSummary?>(initialValue = null, version, rangeDays) {
        value = withContext(Dispatchers.IO) {
            TrafficStatsStore.history(context).summarize(TrafficStatsStore.today(), rangeDays)
        }
    }

    // Live updates while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            ConnectionMonitor.touch()
            delay(3_000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { AppTopBar(title = uiText("统计看板", "Statistics", "Статистика"), onBack = onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(text = uiText("连接", "Connections", "Соединения"), selected = tab == StatsTab.Connections) { tab = StatsTab.Connections }
                ModeChip(text = uiText("流量", "Traffic", "Трафик"), selected = tab == StatsTab.Traffic) { tab = StatsTab.Traffic }
                ModeChip(text = uiText("远程面板", "Remote panel", "Удалённая панель"), selected = tab == StatsTab.Panel) { tab = StatsTab.Panel }
            }
        }
        when (tab) {
            StatsTab.Connections -> connectionItems(
                running = view.running,
                active = view.active,
                closed = view.closed,
                upSpeed = view.upSpeed,
                downSpeed = view.downSpeed,
                nowMs = view.updatedMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                search = search,
                onSearch = { search = it },
                showClosed = showClosed,
                onShowClosed = { showClosed = it },
            )
            StatsTab.Traffic -> trafficItems(
                summary = summary,
                rangeDays = rangeDays,
                onRange = { rangeDays = it },
                group = group,
                onGroup = { group = it },
                onClear = { confirmClear = true },
            )
            StatsTab.Panel -> item { RemotePanelSection() }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(uiText("清空流量统计？", "Clear traffic statistics?", "Очистить статистику трафика?")) },
            text = { Text(uiText("所有日期的应用和节点流量记录都会删除，无法恢复。", "Every day's per-app and per-node records are deleted. This can't be undone.", "Записи по приложениям и узлам за все дни будут удалены без возможности восстановления.")) },
            confirmButton = {
                TextButton(onClick = {
                    TrafficStatsStore.clear(context)
                    confirmClear = false
                }) { Text(uiText("清空", "Clear", "Очистить")) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(uiText("取消", "Cancel", "Отмена")) } },
            containerColor = SurfaceHigh,
        )
    }
}

// ---- connections ----------------------------------------------------------------

private fun LazyListScope.connectionItems(
    running: Boolean,
    active: List<LabeledConnection>,
    closed: List<LabeledConnection>,
    upSpeed: Long,
    downSpeed: Long,
    nowMs: Long,
    search: String,
    onSearch: (String) -> Unit,
    showClosed: Boolean,
    onShowClosed: (Boolean) -> Unit,
) {
    item {
        SurfaceCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(label = uiText("活动连接", "Active", "Активные"), value = active.size.toString())
                Metric(label = uiText("上传", "Upload", "Отдача"), value = "${formatDataSize(upSpeed)}/s")
                Metric(label = uiText("下载", "Download", "Загрузка"), value = "${formatDataSize(downSpeed)}/s")
            }
        }
    }
    if (!running) {
        item {
            NoteBox(
                text = uiText(
                    "VPN 未连接。连接后这里会实时列出每条连接：访问的地址、所属应用、命中的规则和走的节点。",
                    "VPN is off. Once connected, every connection shows here live: where it goes, which app opened it, the rule it hit and the node it uses.",
                    "VPN выключен. После подключения здесь в реальном времени появятся соединения: адрес, приложение, сработавшее правило и узел.",
                ),
            )
        }
    }
    item {
        SearchField(
            search = search,
            placeholder = uiText("搜索域名、应用、规则或节点…", "Search host, app, rule or node…", "Поиск: адрес, приложение, правило, узел…"),
            onValueChange = onSearch,
        )
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(text = uiText("活动中 ${active.size}", "Active ${active.size}", "Активные ${active.size}"), selected = !showClosed) { onShowClosed(false) }
            ModeChip(text = uiText("已关闭 ${closed.size}", "Closed ${closed.size}", "Закрытые ${closed.size}"), selected = showClosed) { onShowClosed(true) }
        }
    }
    val query = search.trim().lowercase()
    val shown = (if (showClosed) closed else active).filter { item ->
        query.isEmpty() || listOf(item.connection.host, item.connection.ip.orEmpty(), item.appLabel, item.ruleLabel, item.nodeLabel)
            .any { it.lowercase().contains(query) }
    }
    if (shown.isEmpty()) {
        item {
            Text(
                text = when {
                    query.isNotEmpty() -> uiText("没有匹配的连接。", "No matching connections.", "Ничего не найдено.")
                    showClosed -> uiText("还没有已关闭的连接。", "No closed connections yet.", "Закрытых соединений пока нет.")
                    else -> uiText("没有活动连接。", "No active connections.", "Нет активных соединений.")
                },
                color = TextSecondary,
                fontSize = TypeScale.Body,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
    items(shown, key = { "conn_${it.connection.id}" }) { item -> ConnectionCard(item, nowMs) }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, color = TextPrimary, fontSize = TypeScale.CardTitle, lineHeight = TypeScale.CardTitleLine, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
    }
}

@Composable
private fun ConnectionCard(item: LabeledConnection, nowMs: Long) {
    val c = item.connection
    SurfaceCard(compact = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PackageIcon(item.appKey.takeIf(TrafficLabels::isPackage), size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${c.host}:${c.port}",
                    color = TextPrimary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.appLabel} · ${c.network.uppercase()}",
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.ruleLabel} → ${item.nodeLabel}",
                    color = Primary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↑ ${formatDataSize(c.up)}", color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
                Text("↓ ${formatDataSize(c.down)}", color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
                Text(formatConnectionAge((c.endMs ?: nowMs) - c.startMs), color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            }
        }
    }
}

private fun formatConnectionAge(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

// ---- traffic --------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.trafficItems(
    summary: TrafficSummary?,
    rangeDays: Int,
    onRange: (Int) -> Unit,
    group: TrafficGroup,
    onGroup: (TrafficGroup) -> Unit,
    onClear: () -> Unit,
) {
    item {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(text = uiText("今天", "Today", "Сегодня"), selected = rangeDays == 1) { onRange(1) }
            ModeChip(text = uiText("近 7 天", "7 days", "7 дней"), selected = rangeDays == 7) { onRange(7) }
            ModeChip(text = uiText("近 30 天", "30 days", "30 дней"), selected = rangeDays == 30) { onRange(30) }
        }
    }
    if (summary == null) return
    item {
        SurfaceCard {
            Text(uiText("总流量", "Total", "Всего"), color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            Text(
                formatDataSize(summary.total.total),
                color = TextPrimary,
                fontSize = TypeScale.LargeMetric,
                lineHeight = TypeScale.LargeMetricLine,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "↑ ${formatDataSize(summary.total.up)}   ↓ ${formatDataSize(summary.total.down)}",
                color = TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
            )
            // Proxied vs direct: what the bars below are colored by.
            val routes = routeSplit(summary.nodes)
            if (routes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RouteOrder.filter { (routes[it] ?: 0) > 0 }.forEach { route ->
                        RouteLegendItem(route, formatDataSize(routes.getValue(route)))
                    }
                }
            }
            if (summary.daily.size > 1) {
                Spacer(Modifier.height(14.dp))
                DailyBars(summary.daily)
            }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(text = uiText("按应用", "By app", "По приложениям"), selected = group == TrafficGroup.Apps) { onGroup(TrafficGroup.Apps) }
            ModeChip(text = uiText("按节点", "By node", "По узлам"), selected = group == TrafficGroup.Nodes) { onGroup(TrafficGroup.Nodes) }
        }
    }
    val rows = if (group == TrafficGroup.Apps) summary.apps else summary.nodes
    if (rows.isEmpty()) {
        item { NoteBox(text = uiText("这段时间没有记录到流量。只统计经过 VPN 的流量。", "No traffic recorded in this period. Only traffic through the VPN is counted.", "За этот период трафика нет. Учитывается только трафик через VPN.")) }
    } else {
        val max = rows.maxOf { it.second.total }.coerceAtLeast(1)
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    rows.take(50).forEach { (key, count) ->
                        val parts = if (group == TrafficGroup.Apps) {
                            val split = routeSplit(summary.appNodes[key].orEmpty())
                            // Days counted before the split was kept.
                            val rest = count.total - split.values.sum()
                            if (rest > 0) split + (NOT_SPLIT to rest) else split
                        } else {
                            mapOf(TrafficLabels.routeKind(key) to count.total)
                        }
                        TrafficRow(key, count, max, group, parts)
                    }
                }
            }
        }
    }
    item {
        Text(
            text = uiText(
                "只统计经过 VPN 的流量，数据只保存在本机，保留 90 天。",
                "Only traffic through the VPN is counted. The data stays on this phone for 90 days.",
                "Учитывается только трафик через VPN. Данные хранятся на телефоне 90 дней.",
            ),
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    item {
        TextButton(onClick = onClear) { Text(uiText("清空流量统计", "Clear traffic statistics", "Очистить статистику трафика")) }
    }
}

@Composable
private fun TrafficRow(key: String, count: TrafficCount, max: Long, group: TrafficGroup, parts: Map<String, Long>) {
    val context = LocalContext.current
    val label = remember(key, group) {
        if (group == TrafficGroup.Apps) TrafficLabels.appLabel(context, key) else TrafficLabels.nodeLabel(key)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (group == TrafficGroup.Apps) PackageIcon(key.takeIf(TrafficLabels::isPackage), size = 32.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    color = TextPrimary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatDataSize(count.total), color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            }
            if (group == TrafficGroup.Apps) {
                val shown = (RouteOrder + NOT_SPLIT).filter { (parts[it] ?: 0) > 0 }
                if (shown.isNotEmpty()) {
                    val names = shown.associateWith { routeName(it) }
                    Text(
                        shown.joinToString("  ·  ") { "${names.getValue(it)} ${formatDataSize(parts.getValue(it))}" },
                        color = TextSecondary,
                        fontSize = TypeScale.Tiny,
                        lineHeight = TypeScale.TinyLine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ControlSurfaceColor),
            ) {
                // One colored piece per route: proxied, direct, blocked ….
                Row(
                    modifier = Modifier
                        .fillMaxWidth((count.total.toFloat() / max).coerceIn(0.01f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    (RouteOrder + NOT_SPLIT).forEach { route ->
                        val bytes = parts[route] ?: 0
                        if (bytes > 0) {
                            Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(routeColor(route)))
                        }
                    }
                }
            }
        }
    }
}

/** Route kinds ([TrafficLabels.routeKind]) in the order bars and legends show them. */
private val RouteOrder = listOf("proxy", "direct", "block", "quic", "dns", "tailscale")

/** An app's bytes from days counted before the per-app split was kept. */
private const val NOT_SPLIT = "none"

/** Bytes per route kind for a node list. */
private fun routeSplit(nodes: List<Pair<String, TrafficCount>>): Map<String, Long> =
    nodes.groupBy({ TrafficLabels.routeKind(it.first) }, { it.second.total }).mapValues { it.value.sum() }.filterValues { it > 0 }

@Composable
private fun routeColor(route: String): Color = when (route) {
    "proxy" -> Primary
    "direct" -> Success
    "block" -> Error
    "quic" -> Color(0xFFF59E0B)
    "dns" -> Color(0xFF60A5FA)
    "tailscale" -> Color(0xFF2DD4BF)
    else -> TextSecondary.copy(alpha = 0.35f)
}

@Composable
private fun routeName(route: String): String = when (route) {
    "proxy" -> uiText("代理", "Proxy", "Прокси")
    "direct" -> uiText("直连", "Direct", "Напрямую")
    "block" -> uiText("拦截", "Blocked", "Заблокировано")
    "quic" -> uiText("QUIC 转 TCP", "QUIC → TCP", "QUIC → TCP")
    "dns" -> "DNS"
    "tailscale" -> "Tailscale"
    else -> uiText("未区分", "Not split", "Без разбивки")
}

@Composable
private fun RouteLegendItem(route: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(routeColor(route)))
        Text("${routeName(route)} $value", color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
    }
}

@Composable
private fun DailyBars(daily: List<Pair<String, TrafficCount>>) {
    val max = daily.maxOf { it.second.total }.coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(if (daily.size > 10) 2.dp else 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        daily.forEach { (_, count) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight((count.total.toFloat() / max).coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (count.total > 0) Primary else ControlSurfaceColor),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(daily.first().first.drop(5), color = TextSecondary, fontSize = TypeScale.Tiny, modifier = Modifier.weight(1f))
        Text(daily.last().first.drop(5), color = TextSecondary, fontSize = TypeScale.Tiny)
    }
}

// ---- remote panel ---------------------------------------------------------------

@Composable
private fun RemotePanelSection() {
    val context = LocalContext.current
    val status by PanelRuntime.status.collectAsState()
    var config by remember { mutableStateOf(PanelSettings.load(context)) }
    var tokenVisible by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun change(update: (PanelConfig) -> PanelConfig) {
        config = PanelSettings.update(context, update)
        PanelRuntime.restartIfRunning(context)
    }

    // The panel keeps running in the background: its notification is the way to see and stop it.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PanelRuntime.start(context)
    }

    fun turnOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PanelRuntime.start(context)
        }
    }

    val link = status.url?.let { "$it#token=${config.token}" }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            uiText(
                "在电脑浏览器里查看这台手机的实时连接和流量统计。只读：面板不能修改任何设置。",
                "View this phone's live connections and traffic statistics in a browser on your computer. Read-only: the panel can't change any setting.",
                "Смотрите соединения и статистику трафика телефона в браузере на компьютере. Только чтение: панель ничего не меняет.",
            ),
            color = TextSecondary,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SettingsGroup(title = uiText("服务", "Server", "Сервер")) {
            SettingToggleRow(
                title = uiText("开启远程面板", "Turn on the remote panel", "Включить удалённую панель"),
                subtitle = when {
                    !status.running -> uiText("未开启", "Off", "Выключено")
                    status.url != null -> uiText("运行中 · ${status.url}", "Running · ${status.url}", "Работает · ${status.url}")
                    else -> uiText("运行中 · ${status.message.orEmpty()}", "Running · ${status.message.orEmpty()}", "Работает · ${status.message.orEmpty()}")
                },
                checked = status.running,
                onToggle = { if (status.running) PanelRuntime.stop(context) else turnOn() },
            )
            if (link != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    uiText("在电脑浏览器打开这个地址（已带令牌）：", "Open this address in a browser on your computer (token included):", "Откройте этот адрес в браузере компьютера (токен включён):"),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                )
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        if (tokenVisible) link else "${status.url}#token=" + "•".repeat(8),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TypeScale.Code,
                        lineHeight = TypeScale.CodeLine,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(text = uiText("复制地址", "Copy link", "Копировать ссылку"), selected = false) { copyToClipboard(context, link, sensitive = true) }
                    ModeChip(text = if (tokenVisible) uiText("隐藏令牌", "Hide token", "Скрыть токен") else uiText("显示令牌", "Show token", "Показать токен"), selected = false) {
                        tokenVisible = !tokenVisible
                    }
                }
            }
        }
        SettingsGroup(title = uiText("监听范围", "Where it listens", "Где слушает")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(text = uiText("局域网（Wi-Fi）", "LAN (Wi-Fi)", "Локальная сеть (Wi-Fi)"), selected = config.scope == PanelScope.Lan) {
                    change { it.copy(scope = PanelScope.Lan) }
                }
                ModeChip(text = "Tailnet", selected = config.scope == PanelScope.Tailnet) {
                    change { it.copy(scope = PanelScope.Tailnet) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (config.scope == PanelScope.Lan) {
                    uiText(
                        "只在当前 Wi-Fi 地址上监听，移动数据和 VPN 隧道上不开放。局域网是明文 HTTP，只在家里等可信网络使用；在外面请用 Tailnet。",
                        "Listens only on the current Wi-Fi address, never on mobile data or the VPN tunnel. The LAN mode is plain HTTP: use it only on trusted networks such as home; use Tailnet elsewhere.",
                        "Слушает только адрес текущей сети Wi-Fi, не мобильные данные и не туннель VPN. В локальной сети используется HTTP без шифрования: только в доверенной сети, например дома; в остальных случаях — Tailnet.",
                    )
                } else {
                    uiText(
                        "通过 Tailscale 在本机的 Tailnet 地址上监听，流量经 WireGuard 加密，电脑在任何地方都能查看。需要先开启 Tailscale。",
                        "Listens on this phone's tailnet address through Tailscale: traffic is WireGuard-encrypted and your computer can look from anywhere. Tailscale must be on.",
                        "Слушает адрес телефона в tailnet через Tailscale: трафик шифруется WireGuard, компьютер может подключиться откуда угодно. Tailscale должен быть включён.",
                    )
                },
                color = TextSecondary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
            )
            Spacer(Modifier.height(12.dp))
            SettingInfoRow(title = uiText("端口", "Port", "Порт"), subtitle = config.port.toString())
        }
        SettingsGroup(title = uiText("令牌", "Token", "Токен")) {
            Text(
                uiText(
                    "打开面板需要令牌。地址里已经带上；也可以在面板里手动输入。重置后旧地址立即失效。",
                    "The panel needs the token. The link above includes it; you can also type it in the panel. Resetting it makes old links stop working at once.",
                    "Для панели нужен токен. Он уже есть в ссылке; его можно ввести и вручную. После сброса старые ссылки сразу перестают работать.",
                ),
                color = TextSecondary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(text = uiText("复制令牌", "Copy token", "Копировать токен"), selected = false) { copyToClipboard(context, config.token, sensitive = true) }
                ModeChip(text = uiText("重置令牌", "Reset token", "Сбросить токен"), selected = false) { confirmReset = true }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(uiText("重置令牌？", "Reset the token?", "Сбросить токен?")) },
            text = { Text(uiText("已打开的面板和旧地址都会失效，需要用新地址重新打开。", "Open panels and old links stop working; open the panel again with the new link.", "Открытые панели и старые ссылки перестанут работать; откройте панель по новой ссылке.")) },
            confirmButton = {
                TextButton(onClick = {
                    change { it.copy(token = McpSettings.newToken()) }
                    confirmReset = false
                }) { Text(uiText("重置", "Reset", "Сбросить")) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(uiText("取消", "Cancel", "Отмена")) } },
            containerColor = SurfaceHigh,
        )
    }
}
