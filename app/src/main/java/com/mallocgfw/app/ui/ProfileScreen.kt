package com.mallocgfw.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.ui.theme.TextSecondary

@Composable
internal fun MeScreen(
    padding: PaddingValues,
    connectionStatus: ConnectionStatus,
    proxyMode: ProxyMode,
    server: ServerNode?,
    streamingRoutingEnabled: Boolean,
    onOpenSubscriptions: () -> Unit,
    onOpenPerApp: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMediaRouting: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val previous = hostView.isVerticalScrollBarEnabled
        hostView.isVerticalScrollBarEnabled = false
        onDispose {
            hostView.isVerticalScrollBarEnabled = previous
        }
    }

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
                title = "个人环境",
                subtitle = "状态与工具。",
            )
        }
        item {
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Eyebrow("当前环境")
                        Text(
                            uiText(when (connectionStatus) {
                                ConnectionStatus.Connected -> "已受保护"
                                ConnectionStatus.Connecting -> "正在建立连接"
                                ConnectionStatus.Disconnecting -> "正在断开连接"
                                ConnectionStatus.Disconnected -> "尚未建立连接"
                            }),
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${server?.name ?: uiText("暂无节点")} · ${uiText(proxyModeText(proxyMode))}",
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusPill(
                        text = connectionStatus.pillText(),
                        color = connectionStatus.pillColor(),
                        background = connectionStatus.pillBackground(),
                    )
                }
            }
        }
        item {
            FeatureList(
                items = listOf(
                    FeatureAction("订阅管理", "节点与资源", Icons.Rounded.Inventory2, onOpenSubscriptions),
                    FeatureAction("分应用代理", "应用名单", Icons.Rounded.Apps, onOpenPerApp),
                    FeatureAction(
                        title = "流媒体分流",
                        subtitle = if (streamingRoutingEnabled) {
                            "已启用"
                        } else {
                            "单独出口"
                        },
                        icon = Icons.Rounded.LiveTv,
                        onClick = onOpenMediaRouting,
                    ),
                    FeatureAction("系统诊断", "VPN / DNS / 握手", Icons.Rounded.GppGood, onOpenDiagnostics),
                    FeatureAction("设置", "连接与更新", Icons.Rounded.Settings, onOpenSettings),
                ),
            )
        }
        item {
            SurfaceCard {
                Text(
                    text = "授权与设备",
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(14.dp))
                DetailRow(label = "VPN 授权状态", value = uiText("已授权"), trailing = uiText("查看"), trailingClickable = onOpenPermission)
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(label = "兼容平台", value = "Android · arm64-v8a / amd64", trailing = uiText("已匹配"))
            }
        }
    }
}
