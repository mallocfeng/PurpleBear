package com.mallocgfw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.AppLogLevel
import com.mallocgfw.app.model.AppVpnMtuOptions
import com.mallocgfw.app.model.normalizedAppVpnMtu
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary

@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(uiText(title), fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Text(uiText(message), color = TextSecondary)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(uiText(confirmText), color = Error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("取消"))
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
internal fun DnsSettingsDialog(
    initialMode: AppDnsMode,
    initialCustomDns: String,
    onDismiss: () -> Unit,
    onConfirm: (AppDnsMode, String) -> Unit,
) {
    var dnsMode by remember(initialMode) { mutableStateOf(initialMode) }
    var customDns by remember(initialCustomDns) { mutableStateOf(initialCustomDns) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(uiText("自定义 DNS"), fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    AppDnsMode.System to "系统 DNS",
                    AppDnsMode.Remote to "远端 1.1.1.1",
                    AppDnsMode.Custom to "手动输入",
                ).forEach { (mode, label) ->
                    ModeChip(
                        text = label,
                        selected = dnsMode == mode,
                    ) {
                        dnsMode = mode
                    }
                }
                if (dnsMode == AppDnsMode.Custom) {
                    OutlinedTextField(
                        value = customDns,
                        onValueChange = { customDns = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        textStyle = compactInputTextStyle(),
                        label = { CompactInputLabel("DNS 地址") },
                        placeholder = { CompactInputPlaceholder("例如 8.8.8.8 或 8.8.8.8:53") },
                        shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceLow,
                            unfocusedContainerColor = SurfaceLow,
                            focusedBorderColor = Primary.copy(alpha = 0.3f),
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = Primary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dnsMode, customDns) }) {
                Text(uiText("保存"), color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("取消"))
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
internal fun LogLevelDialog(
    selected: AppLogLevel,
    onDismiss: () -> Unit,
    onConfirm: (AppLogLevel) -> Unit,
) {
    var pendingLevel by remember(selected) { mutableStateOf(selected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(uiText("日志级别"), fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppLogLevel.entries.forEach { level ->
                    ModeChip(
                        text = level.displayName,
                        selected = pendingLevel == level,
                    ) {
                        pendingLevel = level
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingLevel) }) {
                Text(uiText("保存"), color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("取消"))
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
internal fun HeartbeatIntervalDialog(
    selectedMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var pendingMinutes by remember(selectedMinutes) {
        mutableIntStateOf(
            selectedMinutes.takeIf { it in HeartbeatIntervalOptionsMinutes } ?: 5,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(uiText("备用节点心跳"), fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = uiText(
                        "连接后按间隔检测当前节点；连续 3 次失败时自动切换到备用节点。",
                        "Checks the active node after connection. Switches to the fallback after 3 failures.",
                    ),
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                )
                HeartbeatIntervalOptionsMinutes.forEach { minutes ->
                    ModeChip(
                        text = uiText("$minutes 分钟", "$minutes min"),
                        selected = pendingMinutes == minutes,
                    ) {
                        pendingMinutes = minutes
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingMinutes) }) {
                Text(uiText("保存"), color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("取消"))
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
internal fun VpnMtuDialog(
    selectedMtu: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var pendingMtu by remember(selectedMtu) {
        mutableIntStateOf(normalizedAppVpnMtu(selectedMtu))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("VPN MTU", fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = uiText(
                        "不清楚就保留 1400。较低 MTU 可减少移动网、PPPoE 和 QUIC 节点出现加载到一半卡住的问题；修改后需要重连 VPN 生效。",
                        "Keep 1400 if unsure. Lower MTU can reduce half-loaded pages on mobile, PPPoE, and QUIC links. Reconnect VPN to apply.",
                    ),
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                )
                AppVpnMtuOptions.forEach { mtu ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModeChip(
                            text = if (mtu == 1400) uiText("$mtu 推荐", "$mtu Recommended") else mtu.toString(),
                            selected = pendingMtu == mtu,
                        ) {
                            pendingMtu = mtu
                        }
                        Text(
                            text = uiText(vpnMtuOptionHint(mtu)),
                            color = TextSecondary,
                            fontSize = TypeScale.Meta,
                            lineHeight = TypeScale.MetaLine,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingMtu) }) {
                Text(uiText("保存"), color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("取消"))
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

private fun vpnMtuOptionHint(mtu: Int): String {
    return when (mtu) {
        1400 -> "默认推荐：移动网络、PPPoE 宽带、QUIC / Hysteria2 节点，或不确定当前网络时使用。"
        1420 -> "偏稳妥：普通宽带但偶尔出现网页半加载、下载停住时使用。"
        1460 -> "偏性能：确认网络路径较稳定，且没有明显卡顿或下载 stall 时使用。"
        1500 -> "最大值：仅建议在直连宽带、局域网或确认链路支持 1500 MTU 时使用。"
        else -> "不确定时使用 1400。"
    }
}
