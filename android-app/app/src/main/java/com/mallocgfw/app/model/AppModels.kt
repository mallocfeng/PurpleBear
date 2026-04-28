package com.mallocgfw.app.model

enum class AppScreen {
    Launch,
    Onboarding,
    Permission,
    QrScanner,
    Home,
    Servers,
    Rules,
    Import,
    Me,
    NodeDetail,
    ConfirmImport,
    Subscriptions,
    RuleSourceDetail,
    AddRuleSource,
    PerApp,
    Diagnostics,
    Settings,
    MediaRouting,
    MediaRoutingNodePicker,
    PreProxyNodePicker,
    LogViewer,
    LocalNodeBuilder,
}

enum class MainTab {
    Home,
    Servers,
    Rules,
    Import,
    Me,
}

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Disconnecting,
    Connected,
}

enum class ProxyMode {
    Smart,
    Global,
    PerApp,
}

enum class AppDnsMode {
    System,
    Remote,
    Custom,
}

enum class AppLogLevel(
    val wireValue: String,
    val displayName: String,
) {
    Error("error", "error"),
    Warning("warning", "warning"),
    Info("info", "info"),
    Debug("debug", "debug"),
}

enum class ServerGroupType {
    Subscription,
    Local,
}

enum class RuleSourceType {
    Auto,
    Shadowrocket,
    Surge,
}

enum class RuleSourceStatus {
    Idle,
    Updating,
    Ready,
    FetchFailed,
    ParseFailed,
    Disabled,
}

enum class RuleTargetPolicy {
    Direct,
    Proxy,
}

data class ServerGroup(
    val id: String,
    val name: String,
    val type: ServerGroupType,
    val sourceUrl: String? = null,
    val updatedAt: String,
)

data class ServerNode(
    val id: String,
    val groupId: String,
    val name: String,
    val code: String,
    val subscription: String,
    val region: String,
    val latencyMs: Int,
    val protocol: String,
    val security: String,
    val transport: String,
    val description: String,
    val address: String,
    val port: String,
    val flow: String,
    val stable: Boolean,
    val favorite: Boolean,
    val rawUri: String,
    val outboundJson: String = "",
    val hiddenUnsupported: Boolean = false,
    val preProxyNodeId: String = "",
    val fallbackNodeId: String = "",
)

private val usageAmountSuffixRegex = Regex(
    pattern = """[-\s]?\d+(?:\.\d+)?\s?(?:[KMGT](?:B)?)(?:[\p{So}\p{Sk}\uFE0F\u200D]*)$""",
    option = RegexOption.IGNORE_CASE,
)

fun ServerNode.subscriptionMergeKey(): String {
    return listOf(
        groupId,
        protocol.uppercase(),
        address.lowercase(),
        port,
        normalizedSubscriptionNodeName(name),
    ).joinToString("|")
}

fun ServerNode.subscriptionNameKey(): String = normalizedSubscriptionNodeName(name)

private fun normalizedSubscriptionNodeName(name: String): String {
    val trimmed = name.trim()
    return usageAmountSuffixRegex.replace(trimmed, "").trim().ifBlank { trimmed }.lowercase()
}

fun List<ServerNode>.mergedSubscriptionGroupNodes(targetGroupId: String): List<ServerNode> {
    val merged = mutableListOf<ServerNode>()
    val rawUriIndex = mutableMapOf<String, Int>()
    val mergeKeyIndex = mutableMapOf<String, Int>()

    for (node in this) {
        if (node.groupId != targetGroupId) {
            merged += node
            continue
        }
        val duplicateIndex = rawUriIndex[node.rawUri] ?: mergeKeyIndex[node.subscriptionMergeKey()]
        if (duplicateIndex == null) {
            rawUriIndex[node.rawUri] = merged.size
            mergeKeyIndex[node.subscriptionMergeKey()] = merged.size
            merged += node
            continue
        }
        val existing = merged[duplicateIndex]
        val normalized = node.copy(
            id = existing.id,
            favorite = existing.favorite || node.favorite,
            latencyMs = if (node.latencyMs > 0) node.latencyMs else existing.latencyMs,
            stable = existing.stable || node.stable,
            hiddenUnsupported = existing.hiddenUnsupported || node.hiddenUnsupported,
            preProxyNodeId = existing.preProxyNodeId,
            fallbackNodeId = existing.fallbackNodeId,
        )
        merged[duplicateIndex] = normalized
        rawUriIndex.remove(existing.rawUri)
        mergeKeyIndex[existing.subscriptionMergeKey()] = duplicateIndex
        rawUriIndex[normalized.rawUri] = duplicateIndex
        mergeKeyIndex[normalized.subscriptionMergeKey()] = duplicateIndex
    }

    return merged
}

fun ServerNode.isUnsupportedGrpcRealityNode(): Boolean {
    return security.equals("REALITY", ignoreCase = true) &&
        transport.equals("GRPC", ignoreCase = true)
}

data class SubscriptionItem(
    val id: String,
    val name: String,
    val type: String,
    val nodes: Int,
    val updatedAt: String,
    val updatedAtMs: Long? = null,
    val nextSync: String,
    val status: String,
    val autoUpdate: Boolean,
    val sourceUrl: String? = null,
)

data class RuleSourceItem(
    val id: String,
    val name: String,
    val url: String,
    val type: RuleSourceType,
    val policy: RuleTargetPolicy,
    val enabled: Boolean,
    val systemDefault: Boolean,
    val updatedAt: String,
    val status: RuleSourceStatus,
    val totalRules: Int,
    val convertedRules: Int,
    val skippedRules: Int,
    val lastError: String? = null,
)

data class RuleSourceDetailSummary(
    val metadata: RuleSourceItem,
    val fullUrl: String,
    val domainRuleCount: Int,
    val ipRuleCount: Int,
    val processRuleCount: Int,
)

data class ProtectedApp(
    val id: String,
    val name: String,
    val category: String,
    val enabled: Boolean,
)

enum class DiagnosticStatus {
    Success,
    Pending,
    Failed,
}

data class DiagnosticStep(
    val key: String,
    val title: String,
    val detail: String,
    val status: DiagnosticStatus,
)

data class ImportPreview(
    val input: String,
    val group: ServerGroup,
    val nodes: List<ServerNode>,
    val subscription: SubscriptionItem? = null,
    val summary: String,
    val hiddenUnsupportedNodeCount: Int = 0,
)

data class PersistedAppState(
    val serverGroups: List<ServerGroup>,
    val servers: List<ServerNode>,
    val subscriptions: List<SubscriptionItem>,
    val ruleSources: List<RuleSourceItem>,
    val recentImports: List<String>,
    val protectedApps: List<ProtectedApp>,
    val selectedServerId: String,
    val proxyMode: ProxyMode,
    val settings: AppSettings,
)

data class LoadedAppState(
    val state: PersistedAppState,
    val newlyHiddenUnsupportedNodeCount: Int = 0,
)

data class StreamingRouteSelection(
    val serviceId: String,
    val serverId: String,
)

data class AppSettings(
    val autoConnectOnLaunch: Boolean,
    val autoReconnect: Boolean,
    val dailyAutoUpdate: Boolean,
    val showUpdateNotifications: Boolean,
    val lightThemeEnabled: Boolean,
    val onboardingCompleted: Boolean,
    val dnsMode: AppDnsMode,
    val customDnsValue: String,
    val logLevel: AppLogLevel,
    val lastConnectedServerId: String,
    val resumeConnectionOnLaunch: Boolean,
    val streamingRoutingEnabled: Boolean,
    val streamingSelections: List<StreamingRouteSelection>,
    val heartbeatIntervalMinutes: Int,
)
