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
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
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
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary

@Composable
internal fun MeScreen(
    padding: PaddingValues,
    connectionStatus: ConnectionStatus,
    proxyMode: ProxyMode,
    server: ServerNode?,
    lightThemeEnabled: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTailscale: () -> Unit,
    onOpenPermission: () -> Unit,
    vpnPermissionGranted: Boolean = true,
    updateBadge: Boolean = false,
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowIcon(
                        icon = Icons.Rounded.Shield,
                        tint = if (connectionStatus == ConnectionStatus.Connected) Success else TextSecondary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            uiText(
                                when (connectionStatus) {
                                    ConnectionStatus.Connected -> "已受保护"
                                    ConnectionStatus.Connecting -> "正在连接…"
                                    ConnectionStatus.Disconnecting -> "正在断开…"
                                    ConnectionStatus.Disconnected -> "未连接"
                                },
                            ),
                            color = TextPrimary,
                            fontSize = TypeScale.ListTitle,
                            lineHeight = TypeScale.ListTitleLine,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${server?.name ?: uiText("暂无节点")} · ${uiText(proxyModeText(proxyMode))}",
                            color = TextSecondary,
                            fontSize = TypeScale.Meta,
                            lineHeight = TypeScale.MetaLine,
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
            GroupedCard {
                ListRow(title = "订阅管理", icon = Icons.Rounded.Inventory2, onClick = onOpenSubscriptions)
                GroupDivider(startIndent = 58.dp)
                ListRow(title = "Tailscale", icon = Icons.Rounded.DeviceHub, onClick = onOpenTailscale)
            }
        }
        item {
            GroupedCard {
                ListRow(
                    title = "深色模式",
                    icon = Icons.Rounded.DarkMode,
                    onClick = onToggleTheme,
                    trailing = { TogglePill(checked = !lightThemeEnabled, onClick = onToggleTheme) },
                )
                GroupDivider(startIndent = 58.dp)
                ListRow(title = "系统诊断", icon = Icons.Rounded.GppGood, onClick = onOpenDiagnostics)
                GroupDivider(startIndent = 58.dp)
                ListRow(
                    title = "VPN 权限",
                    value = if (vpnPermissionGranted) "已授权" else "未授权",
                    icon = Icons.Rounded.VpnKey,
                    onClick = onOpenPermission,
                )
                GroupDivider(startIndent = 58.dp)
                ListRow(title = "设置", icon = Icons.Rounded.Settings, onClick = onOpenSettings, badge = updateBadge)
            }
        }
    }
}
