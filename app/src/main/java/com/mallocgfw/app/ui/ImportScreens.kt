package com.mallocgfw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
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
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.SurfaceHigh
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
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = mainScreenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = "添加新连接",
                subtitle = "支持订阅和单节点链接。",
            )
        }
        item {
                SurfaceCard {
                    Eyebrow("导入源")
                Text(
                    text = uiText("粘贴订阅或单节点链接"),
                    fontSize = TypeScale.CardTitle,
                    lineHeight = TypeScale.CardTitleLine,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(uiText("订阅进入 group，单节点进入 Local。"), color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = compactInputTextStyle(),
                    minLines = 4,
                    maxLines = 8,
                    label = { CompactInputLabel("订阅链接或节点 URL") },
                    placeholder = { CompactInputPlaceholder("https://... 或 ss://... / vless://...") },
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceHigh,
                        unfocusedContainerColor = SurfaceHigh,
                        focusedBorderColor = Primary.copy(alpha = 0.3f),
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
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                CompactButtonRow(
                    primaryText = if (importInFlight) "正在解析…" else "解析并确认",
                    onPrimary = onOpenConfirm,
                    primaryEnabled = !importInFlight,
                    secondaryText = "导入附件",
                    onSecondary = onOpenFile,
                )
            }
        }
        item {
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Eyebrow("最近导入")
                        Text(
                            text = uiText("继续上次操作"),
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    OutlinedActionChip(text = "最多 30 天")
                }
                Spacer(modifier = Modifier.height(14.dp))
                recentImports.forEach { item ->
                    ActionListRow(text = uiText(item), icon = Icons.Rounded.Inventory2)
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
        item {
            PromoCard(
                title = "订阅与规则统一更新",
                subtitle = "一键同步节点与规则资源。",
                buttonText = null,
                onClick = {},
            )
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
                title = if (preview?.group?.type == ServerGroupType.Local) "准备导入本地节点" else "准备导入订阅",
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
                        Eyebrow(if (preview?.group?.type == ServerGroupType.Local) "目标分组" else "订阅名称")
                        Text(
                            text = preview?.group?.name?.let { uiText(it) } ?: uiText("暂无可导入数据"),
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
                        add("节点数量" to uiText("${preview?.nodes?.size ?: 0} 个节点", "${preview?.nodes?.size ?: 0} nodes"))
                        if ((preview?.hiddenUnsupportedNodeCount ?: 0) > 0) {
                            add("已隐藏节点" to uiText("已隐藏 ${preview?.hiddenUnsupportedNodeCount ?: 0} 个", "${preview?.hiddenUnsupportedNodeCount ?: 0} hidden"))
                        }
                        add("更新时间" to (preview?.group?.updatedAt?.let { uiText(it) } ?: "--"))
                        add("导入类型" to if (preview?.group?.type == ServerGroupType.Local) uiText("单节点 / Local") else uiText("订阅"))
                        add("自动更新" to if (preview?.subscription != null) uiText("可开启") else uiText("不适用"))
                    },
                )
            }
        }
        item {
            SurfaceCard {
                SettingRow(title = "覆盖同来源节点", subtitle = "替换同 group 旧节点。", checked = true)
                Spacer(modifier = Modifier.height(12.dp))
                SettingRow(title = "启用自动更新", subtitle = "订阅链接可用。", checked = preview?.subscription != null)
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

@Composable
internal fun SubscriptionsScreen(
    padding: PaddingValues,
    subscriptions: List<SubscriptionItem>,
    message: String?,
    refreshingIds: Set<String>,
    onRefresh: (String) -> Unit,
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
        item {
            ScreenHeader(
                title = "订阅与资源",
                subtitle = "节点订阅与资源状态。",
            )
        }
        if (!message.isNullOrBlank()) {
            item { NoteBox(text = message) }
        }
        items(subscriptions, key = { it.id }) { subscription ->
            val refreshable = subscription.autoUpdate && !subscription.sourceUrl.isNullOrBlank()
            val refreshing = refreshingIds.contains(subscription.id)
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Eyebrow(subscription.type)
                        Text(
                            text = uiText(subscription.name),
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            uiText("${subscription.nodes} 个节点", "${subscription.nodes} nodes"),
                            color = TextSecondary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        StatusPill(
                            text = if (refreshing) "刷新中" else subscription.status,
                            color = when {
                                refreshing -> Primary
                                subscription.status.contains("失败") -> Error
                                else -> Success
                            },
                            background = when {
                                refreshing -> Primary.copy(alpha = 0.14f)
                                subscription.status.contains("失败") -> Error.copy(alpha = 0.14f)
                                else -> Success.copy(alpha = 0.14f)
                            },
                        )
                        Text(
                            text = uiText("删除"),
                            color = Error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .clickable { onDelete(subscription.id) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = uiText("最近同步：${formatRelativeSyncTime(subscription.updatedAtMs, subscription.updatedAt, nowMs)}"),
                            color = TextSecondary,
                        )
                        Text(
                            text = uiText(when {
                                subscription.sourceUrl == null -> "来源：文件导入"
                                subscription.autoUpdate -> "来源：订阅链接"
                                else -> "来源：订阅快照（不刷新）"
                            }),
                            color = TextSecondary,
                            fontSize = TypeScale.Meta,
                            lineHeight = TypeScale.MetaLine,
                        )
                    }
                    if (refreshable) {
                        Text(
                            text = uiText(if (refreshing) "刷新中…" else "手动刷新"),
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(enabled = !refreshing) { onRefresh(subscription.id) },
                        )
                    }
                }
            }
        }
        if (subscriptions.isEmpty()) {
            item {
                NoteBox(text = "还没有订阅组。")
            }
        }
        item {
            CompactButtonRow(
                primaryText = "立即刷新",
                onPrimary = { subscriptions.firstOrNull()?.id?.let(onRefresh) },
                primaryEnabled = subscriptions.isNotEmpty(),
                secondaryText = "打开设置",
                onSecondary = onOpenSettings,
            )
        }
    }
}
