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
            Text(title, fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Text(message, color = TextSecondary)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = Error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
            Text("自定义 DNS", fontWeight = FontWeight.ExtraBold)
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
                Text("保存", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
            Text("日志级别", fontWeight = FontWeight.ExtraBold)
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
                Text("保存", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
            Text("备用节点心跳", fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "连接后按间隔检测当前节点；连续 3 次失败时自动切换到备用节点。",
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                )
                HeartbeatIntervalOptionsMinutes.forEach { minutes ->
                    ModeChip(
                        text = "$minutes 分钟",
                        selected = pendingMinutes == minutes,
                    ) {
                        pendingMinutes = minutes
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingMinutes) }) {
                Text("保存", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

