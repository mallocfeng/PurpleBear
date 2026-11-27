package com.mallocgfw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.ImportPreview
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.SubscriptionItem
import com.mallocgfw.app.model.SubscriptionUsage
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
internal fun ImportScreen(
    padding: PaddingValues,
    input: String,
    recentImports: List<String>,
    importInFlight: Boolean,
    errorMessage: String?,
    onInputChange: (String) -> Unit,
    onOpenFile: () -> Unit,
    onOpenConfirm: () -> Unit,
    onCreateManual: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = mainScreenPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SurfaceCard {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = compactInputTextStyle(),
                    minLines = 4,
                    maxLines = 8,
                    placeholder = { CompactInputPlaceholder("粘贴订阅链接或节点链接（vless:// ss:// …）") },
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceLow,
                        unfocusedContainerColor = SurfaceLow,
                        focusedBorderColor = Primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = Error,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                CompactButtonRow(
                    primaryText = if (importInFlight) "正在解析…" else "解析",
                    onPrimary = onOpenConfirm,
                    primaryEnabled = !importInFlight && input.isNotBlank(),
                    secondaryText = "从文件导入",
                    onSecondary = onOpenFile,
                )
            }
        }
        item {
            // The manual builder is easy to miss otherwise: it used to sit on the
            // Local group header only.
            GroupedCard {
                ListRow(
                    title = "手动添加节点",
                    subtitle = "逐项填写协议、地址和密钥",
                    icon = Icons.Rounded.EditNote,
                    onClick = onCreateManual,
                )
            }
        }
        if (recentImports.isNotEmpty()) {
            item { SectionLabel("最近导入") }
            item {
                GroupedCard {
                    recentImports.forEachIndexed { index, item ->
                        if (index > 0) GroupDivider(startIndent = 58.dp)
                        ListRow(title = item, icon = Icons.Rounded.Inventory2)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConfirmImportScreen(
    padding: PaddingValues,
    preview: ImportPreview?,
    onConfirm: () -> Unit,
    onImportOnly: () -> Unit,
) {
    val isLocalImport = preview?.group?.type == ServerGroupType.Local
    val localNode = preview?.nodes?.firstOrNull()
    val isBatchLocalImport = isLocalImport && (preview?.nodes?.size ?: 0) > 1
    val localNodeCount = preview?.nodes?.count { !it.hiddenUnsupported } ?: 0
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "导入确认", subtitle = "配置导入", onBack = onImportOnly)
        }
        item {
            ScreenHeader(
                title = if (isLocalImport) "准备导入本地节点" else "准备导入订阅",
                subtitle = preview?.summary ?: "请返回导入页先输入有效的订阅链接或单节点 URL。",
            )
        }
        item {
            SurfaceCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow(if (isLocalImport) "当前导入节点" else "订阅名称")
                        Text(
                            text = if (isBatchLocalImport) {
                                uiText("$localNodeCount 个节点", "$localNodeCount nodes", "Узлов: $localNodeCount")
                            } else if (isLocalImport) {
                                localNode?.name?.let { uiText(it) } ?: uiText("暂无可导入数据")
                            } else {
                                preview?.group?.name?.let { uiText(it) } ?: uiText("暂无可导入数据")
                            },
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusPill(
                        text = if (preview == null) "待输入" else "检测完成",
                        color = Primary,
                        background = Primary.copy(alpha = 0.14f),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                TwoColumnInfoGrid(
                    items = buildList {
                        if (isBatchLocalImport) {
                            add("节点数量" to uiText("$localNodeCount 个节点", "$localNodeCount nodes", "Узлов: $localNodeCount"))
                            if ((preview?.hiddenUnsupportedNodeCount ?: 0) > 0) {
                                add("已隐藏节点" to uiText("已隐藏 ${preview?.hiddenUnsupportedNodeCount ?: 0} 个", "${preview?.hiddenUnsupportedNodeCount ?: 0} hidden", "Скрыто: ${preview?.hiddenUnsupportedNodeCount ?: 0}"))
                            }
                            add("目标分组" to (preview?.group?.name?.let { uiText(it) } ?: "Local"))
                        } else if (isLocalImport) {
                            add("协议" to (localNode?.protocol ?: "--"))
                            add("地址" to (localNode?.address ?: "--"))
                            add("端口" to (localNode?.port ?: "--"))
                            add("传输" to (localNode?.transport?.let { uiText(it) } ?: "--"))
                            add("安全" to (localNode?.security?.let { uiText(it) } ?: "--"))
                            add("目标分组" to (preview?.group?.name?.let { uiText(it) } ?: "Local"))
                        } else {
                            add("节点数量" to uiText("${preview?.nodes?.size ?: 0} 个节点", "${preview?.nodes?.size ?: 0} nodes", "Узлов: ${preview?.nodes?.size ?: 0}"))
                            if ((preview?.hiddenUnsupportedNodeCount ?: 0) > 0) {
                                add("已隐藏节点" to uiText("已隐藏 ${preview?.hiddenUnsupportedNodeCount ?: 0} 个", "${preview?.hiddenUnsupportedNodeCount ?: 0} hidden", "Скрыто: ${preview?.hiddenUnsupportedNodeCount ?: 0}"))
                            }
                            add("更新时间" to (preview?.group?.updatedAt?.let { uiText(it) } ?: "--"))
                            add("导入类型" to uiText("订阅"))
                            add("自动更新" to if (preview?.subscription != null) uiText("可开启") else uiText("不适用"))
                        }
                    },
                    compact = isLocalImport,
                )
            }
        }
        if (!isLocalImport) {
            item {
                SurfaceCard {
                    SettingRow(title = "覆盖同来源节点", subtitle = "替换同 group 旧节点。", checked = true)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingRow(title = "启用自动更新", subtitle = "订阅链接可用。", checked = preview?.subscription != null)
                }
            }
        }
        item {
            ButtonRow(
                primaryText = if (preview == null) "返回导入页" else "导入",
                onPrimary = if (preview == null) onImportOnly else onConfirm,
                secondaryText = "取消",
                onSecondary = onImportOnly,
            )
        }
    }
}

/**
 * Traffic and expiry the provider reported with the subscription
 * (`subscription-userinfo`), as of the last refresh.
 */
@Composable
private fun SubscriptionUsageBlock(usage: SubscriptionUsage, nowMs: Long) {
    val used = usage.usedBytes
    val limit = usage.limitBytes
    val expired = usage.isExpired(nowMs)
    val usedUp = usage.isUsedUp()
    val traffic = when {
        used != null && limit != null -> uiText(
            "已用 ${formatDataSize(used)} / ${formatDataSize(limit)}",
            "${formatDataSize(used)} of ${formatDataSize(limit)} used",
            "Использовано ${formatDataSize(used)} из ${formatDataSize(limit)}",
        )
        used != null -> uiText("已用 ${formatDataSize(used)}", "${formatDataSize(used)} used", "Использовано ${formatDataSize(used)}")
        limit != null -> uiText("总流量 ${formatDataSize(limit)}", "${formatDataSize(limit)} total", "Всего ${formatDataSize(limit)}")
        else -> null
    }
    val expiry = usage.expiresAtSec?.let { expireSec ->
        val date = java.time.Instant.ofEpochSecond(expireSec).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val daysLeft = ((expireSec * 1000L - nowMs) / 86_400_000L).toInt()
        when {
            expired -> uiText("已于 $date 到期", "Expired on $date", "Истекла $date")
            daysLeft < 1 -> uiText("今天到期", "Expires today", "Истекает сегодня")
            else -> uiText("$date 到期（剩 $daysLeft 天）", "Expires $date ($daysLeft days left)", "До $date (осталось дней: $daysLeft)")
        }
    }
    val line = listOfNotNull(traffic, expiry).joinToString(" · ")
    if (line.isEmpty()) return
    val warn = expired || usedUp
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (used != null && limit != null) {
            val fraction = (used.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0).toFloat()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(TextSecondary.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (fraction >= 0.9f) Error else Primary),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        Text(
            text = line,
            color = if (warn) Error else TextSecondary,
            fontSize = TypeScale.Tiny,
            lineHeight = TypeScale.TinyLine,
        )
    }
}

@Composable
internal fun SubscriptionsScreen(
    padding: PaddingValues,
    subscriptions: List<SubscriptionItem>,
    message: String?,
    refreshingIds: Set<String>,
    onRefresh: (String) -> Unit,
    onSetAutoUpdate: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
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
            AppTopBar(title = "订阅管理", subtitle = "资源中心", onBack = onBack)
        }
        if (!message.isNullOrBlank()) {
            item { NoteBox(text = message) }
        }
        items(subscriptions, key = { it.id }) { subscription ->
            val refreshable = subscription.autoUpdate && !subscription.sourceUrl.isNullOrBlank()
            val refreshing = refreshingIds.contains(subscription.id)
            val failed = subscription.status.contains("失败")
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowIcon(icon = Icons.Rounded.Inventory2, tint = if (failed) Error else Primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiText(subscription.name),
                            color = TextPrimary,
                            fontSize = TypeScale.ListTitle,
                            lineHeight = TypeScale.ListTitleLine,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOf(
                                uiText("${subscription.nodes} 个节点", "${subscription.nodes} nodes", "Узлов: ${subscription.nodes}"),
                                uiText(formatRelativeSyncTime(subscription.updatedAtMs, subscription.updatedAt, nowMs)),
                                uiText(
                                    when {
                                        subscription.sourceUrl == null -> "文件导入"
                                        subscription.autoUpdate -> "订阅链接"
                                        else -> "快照"
                                    },
                                ),
                            ).joinToString(" · "),
                            color = TextSecondary,
                            fontSize = TypeScale.Tiny,
                            lineHeight = TypeScale.TinyLine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    if (refreshing || failed) {
                        StatusPill(
                            text = if (refreshing) "刷新中" else subscription.status,
                            color = if (refreshing) Primary else Error,
                            background = (if (refreshing) Primary else Error).copy(alpha = 0.12f),
                            compact = true,
                        )
                    }
                }
                subscription.usage?.let { usage -> SubscriptionUsageBlock(usage, nowMs) }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    // Links that work only once: stop fetching them, keep the nodes.
                    if (!subscription.sourceUrl.isNullOrBlank()) {
                        TextAction(
                            text = if (subscription.autoUpdate) "不再刷新" else "恢复刷新",
                            enabled = !refreshing,
                            onClick = { onSetAutoUpdate(subscription.id, !subscription.autoUpdate) },
                        )
                    }
                    if (refreshable) {
                        TextAction(
                            text = if (refreshing) "刷新中…" else "刷新",
                            enabled = !refreshing,
                            onClick = { onRefresh(subscription.id) },
                        )
                    }
                    Text(
                        text = uiText("删除"),
                        color = Error,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Error.copy(alpha = 0.10f))
                            .clickable { onDelete(subscription.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (subscriptions.isEmpty()) {
            item {
                SurfaceCard {
                    Text(
                        text = uiText("还没有订阅，可在“节点”页点 + 导入"),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                    )
                }
            }
        }
    }
}
