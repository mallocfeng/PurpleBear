package com.mallocgfw.app.model

import java.util.Locale

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
    CustomRules,
    DefaultRules,
    PerApp,
    Diagnostics,
    Settings,
    Tailscale,
    OpenSourceLicenses,
    Mcp,
    Update,
    MediaRouting,
    MediaRoutingNodePicker,
    PerAppNodePicker,
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

enum class AppLanguage {
    System,
    Chinese,
    English,
    Russian,
}

enum class AppGeoRoutingRegion(
    val geoipCode: String,
    val geositeCode: String?,
) {
    China("cn", "cn"),
    English("us", null),
    Russia("ru", "category-ru");

    companion object {
        /**
         * Russian users get Russian sites direct; everyone else China, since an
         * English interface doesn't mean the phone is outside China. The US
         * region stays available as a manual choice.
         */
        fun defaultForLanguage(language: AppLanguage): AppGeoRoutingRegion {
            return when (language) {
                AppLanguage.Russian -> Russia
                AppLanguage.System -> if (Locale.getDefault().language.lowercase(Locale.ROOT) == "ru") Russia else China
                AppLanguage.Chinese, AppLanguage.English -> China
            }
        }
    }
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

enum class RuleSourceKind {
    RemoteUrl,
    LocalText,
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
    Block,
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
    /** Clash/mihomo proxy map (JSON) for nodes that run in the mihomo sidecar. */
    val mihomoProxyJson: String = "",
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

/**
 * Whether [other] is the same imported node as this one. Nodes parsed from
 * Clash / sing-box / Xray configs have no share link (blank rawUri), so a blank
 * rawUri must never count as a match: it made every such node of a group
 * "merge" into one, and a Clash subscription imported as a single node.
 */
fun ServerNode.isSameImportedNode(other: ServerNode): Boolean {
    if (groupId != other.groupId) return false
    if (rawUri.isNotBlank() && rawUri == other.rawUri) return true
    return subscriptionMergeKey() == other.subscriptionMergeKey()
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
        // A blank rawUri (config-file nodes) identifies nothing; see isSameImportedNode.
        val duplicateIndex = node.rawUri.takeIf { it.isNotBlank() }?.let(rawUriIndex::get)
            ?: mergeKeyIndex[node.subscriptionMergeKey()]
        if (duplicateIndex == null) {
            if (node.rawUri.isNotBlank()) rawUriIndex[node.rawUri] = merged.size
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
        if (normalized.rawUri.isNotBlank()) rawUriIndex[normalized.rawUri] = duplicateIndex
        mergeKeyIndex[normalized.subscriptionMergeKey()] = duplicateIndex
    }

    return merged
}

fun ServerNode.ssrCompatibilityLabel(): String? {
    if (!rawUri.trim().startsWith("ssr://", ignoreCase = true) &&
        !protocol.equals("SSR", ignoreCase = true) &&
        !protocol.equals("SHADOWSOCKSR", ignoreCase = true)
    ) {
        return null
    }
    return if (protocol.equals("SHADOWSOCKS", ignoreCase = true) &&
        flow.startsWith("2022-blake3-", ignoreCase = true)
    ) {
        tr("SSR 链接 · Shadowsocks 2022", "SSR link · Shadowsocks 2022", "Ссылка SSR · Shadowsocks 2022")
    } else {
        tr("SSR 兼容模式", "SSR compatibility mode", "Режим совместимости SSR")
    }
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
    /** Traffic and expiry as the provider last reported them; null when it reports none. */
    val usage: SubscriptionUsage? = null,
)

/**
 * A provider's `subscription-userinfo` response header:
 * `upload=…; download=…; total=…; expire=…` (bytes; expire in Unix seconds).
 * A field the provider left out is null; total 0 and expire 0 mean "no limit".
 */
data class SubscriptionUsage(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireAtSec: Long? = null,
) {
    /** Upload plus download, or null when neither was reported. */
    val usedBytes: Long?
        get() = if (uploadBytes == null && downloadBytes == null) null else (uploadBytes ?: 0L) + (downloadBytes ?: 0L)

    /** The traffic allowance, or null when unlimited or not reported. */
    val limitBytes: Long? get() = totalBytes?.takeIf { it > 0L }

    /** When it expires (Unix seconds), or null when it doesn't or wasn't reported. */
    val expiresAtSec: Long? get() = expireAtSec?.takeIf { it > 0L }

    fun isExpired(nowMs: Long): Boolean = expiresAtSec?.let { it * 1000L < nowMs } == true

    fun isUsedUp(): Boolean {
        val limit = limitBytes ?: return false
        return (usedBytes ?: 0L) >= limit
    }

    companion object {
        /** Null when [header] has none of the known fields. */
        fun parse(header: String?): SubscriptionUsage? {
            if (header.isNullOrBlank()) return null
            val fields = header.split(';').mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().lowercase()
                // Some panels send floats ("1.2E10") or quote the value.
                val value = part.substringAfter('=', "").trim().trim('"').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }?.toLong()
                if (key.isEmpty() || value == null) null else key to value
            }.toMap()
            val usage = SubscriptionUsage(
                uploadBytes = fields["upload"],
                downloadBytes = fields["download"],
                totalBytes = fields["total"],
                expireAtSec = fields["expire"],
            )
            return usage.takeIf { it != SubscriptionUsage() }
        }
    }
}

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
    val sourceKind: RuleSourceKind = RuleSourceKind.RemoteUrl,
    val content: String = "",
    /**
     * When the download / compile of the list now in use started. Orders
     * refreshes: one that started earlier can't replace it (StateOps.applyRuleSourceRefresh).
     */
    val updatedAtMs: Long? = null,
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
    /** Node this app uses in per-app mode; blank means the currently selected node. */
    val serverId: String = "",
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

const val DEFAULT_APP_VPN_MTU = 1400
const val DEFAULT_TAILSCALE_CONTROL_URL = "https://controlplane.tailscale.com"
val AppVpnMtuOptions = listOf(1400, 1420, 1460, 1500)

fun normalizedAppVpnMtu(value: Int): Int {
    return value.takeIf { it in AppVpnMtuOptions } ?: DEFAULT_APP_VPN_MTU
}

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
    val language: AppLanguage,
    val geoRoutingRegion: AppGeoRoutingRegion,
    val globalProxyEnabled: Boolean,
    val streamingRoutingEnabled: Boolean,
    val streamingSelections: List<StreamingRouteSelection>,
    val heartbeatIntervalMinutes: Int,
    val vpnMtu: Int,
    val tailscaleEnabled: Boolean,
    val tailscaleAuthKey: String,
    val tailscaleControlUrl: String,
    val tailscaleSubnetRoutes: List<String>,
    val tailscaleExitNodeId: String,
    val tailscaleAlwaysUseDerp: Boolean,
    /** Send foreign QUIC (UDP 443) through the proxy instead of making apps fall back to TCP. */
    val proxyQuicEnabled: Boolean = false,
)
