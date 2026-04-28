package com.mallocgfw.app.ui

import androidx.compose.ui.graphics.Color
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.DiagnosticStep
import com.mallocgfw.app.model.RuleSourceStatus
import com.mallocgfw.app.model.RuleSourceType
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.DiagnosticsManager

internal fun groupedServers(
    groups: List<ServerGroup>,
    servers: List<ServerNode>,
    filter: String,
    search: String,
): List<ServerSection> {
    val normalizedSearch = search.trim()
    val searchEnabled = normalizedSearch.isNotBlank()
    val groupedNodes = servers.groupBy { it.groupId }
    val orderedGroups = groups.sortedWith(
        compareBy<ServerGroup> { if (it.type == ServerGroupType.Local) 0 else 1 }
            .thenBy { it.name.lowercase() },
    )

    return orderedGroups.mapNotNull { group ->
        val allGroupServers = groupedNodes[group.id].orEmpty()
        val hiddenUnsupportedNodeCount = allGroupServers.count { it.hiddenUnsupported }
        val groupServers = allGroupServers.filterNot { it.hiddenUnsupported }.filter { server ->
            val matchesFilter = when (filter) {
                "asia" -> listOf("亚洲", "日本", "香港", "新加坡").contains(server.region)
                "latency" -> server.latencyMs in 1..100
                "favorites" -> server.favorite
                else -> true
            }
            val matchesSearch = if (!searchEnabled) {
                true
            } else {
                buildString {
                    append(server.name)
                    append(' ')
                    append(server.region)
                    append(' ')
                    append(server.protocol)
                    append(' ')
                    append(server.code)
                    append(' ')
                    append(group.name)
                }.contains(normalizedSearch, ignoreCase = true)
            }
            matchesFilter && matchesSearch
        }.sortedWith(
            compareByDescending<ServerNode> { it.hasPreProxyConfigured() }
                .thenByDescending { it.favorite }
                .thenBy { it.name.lowercase() },
        )
        if (groupServers.isEmpty() && searchEnabled && hiddenUnsupportedNodeCount == 0) {
            null
        } else {
            ServerSection(
                group = group,
                servers = groupServers,
                hiddenUnsupportedNodeCount = hiddenUnsupportedNodeCount,
            )
        }
    }
}

internal data class ImportApplyResult(
    val selectedServerChanged: Boolean = false,
    val message: String? = null,
    val reconnectServer: ServerNode? = null,
)

internal fun buildDiagnostics(): List<DiagnosticStep> {
    return DiagnosticsManager.pendingSteps()
}

internal fun ConnectionStatus.pillText(): String = when (this) {
    ConnectionStatus.Connected -> "已连接"
    ConnectionStatus.Connecting -> "连接中"
    ConnectionStatus.Disconnecting -> "断开中"
    ConnectionStatus.Disconnected -> "未连接"
}

internal fun ConnectionStatus.pillColor(): Color = when (this) {
    ConnectionStatus.Connected -> Success
    ConnectionStatus.Connecting,
    ConnectionStatus.Disconnecting -> Primary
    ConnectionStatus.Disconnected -> Error
}

internal fun ConnectionStatus.pillBackground(): Color = when (this) {
    ConnectionStatus.Connected -> Success.copy(alpha = 0.14f)
    ConnectionStatus.Connecting,
    ConnectionStatus.Disconnecting -> Primary.copy(alpha = 0.14f)
    ConnectionStatus.Disconnected -> Error.copy(alpha = 0.14f)
}

internal fun ConnectionStatus.statusHeadline(): String = when (this) {
    ConnectionStatus.Connected -> "系统受保护"
    ConnectionStatus.Connecting -> "正在建立安全隧道…"
    ConnectionStatus.Disconnecting -> "正在关闭安全隧道…"
    ConnectionStatus.Disconnected -> "尚未连接"
}

internal fun ConnectionStatus.statusDescription(): String = when (this) {
    ConnectionStatus.Connected -> "DNS 与分流规则已生效。"
    ConnectionStatus.Connecting -> "正在启动内核和 VPN。"
    ConnectionStatus.Disconnecting -> "正在释放 VPN 会话。"
    ConnectionStatus.Disconnected -> "导入线路后即可连接。"
}

internal fun ConnectionStatus.diagnosticsSummary(): String = when (this) {
    ConnectionStatus.Connected -> "当前线路工作正常。"
    ConnectionStatus.Connecting -> "正在检查连接。"
    ConnectionStatus.Disconnecting -> "正在关闭 VPN。"
    ConnectionStatus.Disconnected -> "建议刷新订阅或切换节点。"
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

internal fun formatByteCount(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val decimals = if (value >= 100 || unitIndex == 0) 0 else 1
    return "%,.${decimals}f %s".format(value, units[unitIndex])
}

internal fun formatRate(bytesPerSecond: Long): String = "${formatByteCount(bytesPerSecond)}/s"

internal fun RuleSourceType.displayName(): String = when (this) {
    RuleSourceType.Auto -> "自动识别"
    RuleSourceType.Shadowrocket -> "Shadowrocket"
    RuleSourceType.Surge -> "Surge"
}

internal fun RuleSourceStatus.displayName(): String = when (this) {
    RuleSourceStatus.Idle -> "未更新"
    RuleSourceStatus.Updating -> "更新中"
    RuleSourceStatus.Ready -> "正常"
    RuleSourceStatus.FetchFailed -> "拉取失败"
    RuleSourceStatus.ParseFailed -> "解析失败"
    RuleSourceStatus.Disabled -> "未启用"
}

internal fun RuleSourceStatus.accent(): Color = when (this) {
    RuleSourceStatus.Ready -> Success
    RuleSourceStatus.Updating -> Primary
    RuleSourceStatus.Idle, RuleSourceStatus.Disabled -> TextSecondary
    RuleSourceStatus.FetchFailed, RuleSourceStatus.ParseFailed -> Error
}

