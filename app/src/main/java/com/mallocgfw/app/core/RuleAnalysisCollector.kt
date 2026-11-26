package com.mallocgfw.app.core

import android.content.Context
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.InactiveReason
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.PerAppRouting
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.RouteTarget
import com.mallocgfw.app.model.RuleAnalysisInput
import com.mallocgfw.app.model.RuleAnalyzer
import com.mallocgfw.app.model.RuleEntryResult
import com.mallocgfw.app.model.RuleLayer
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.RuleTargetPolicy
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.XrayRoutingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the rules screen's analysis shows: the routing chain as the VPN builds it. */
data class RuleAnalysisReport(
    val globalMode: Boolean,
    val perAppMode: Boolean,
    /** Per-app mode: apps that go through the VPN at all (the rest bypass it). */
    val vpnAppNames: List<String>,
    val region: AppGeoRoutingRegion,
    val mainNodeName: String?,
    /** Active entries in matching order, then the inactive ones. */
    val entries: List<RuleEntryResult>,
    val quicBlocked: Boolean,
    val tailscaleEnabled: Boolean,
    val tailscaleSubnetCount: Int,
    /** The region's site list (geosite) goes direct: the home-sites switch. */
    val homeSitesDirect: Boolean,
    val fallback: RouteTarget,
    val generatedAtMs: Long,
) {
    val active: List<RuleEntryResult> get() = entries.filter { it.order > 0 }
    val inactive: List<RuleEntryResult> get() = entries.filter { it.order == 0 }
}

/**
 * Builds a [RuleAnalysisReport] from the saved settings and the rule lists on
 * disk, as [com.mallocgfw.app.xray.XrayVpnService] would for the next
 * connection. Nothing is downloaded: a list that isn't on the phone yet shows
 * as not downloaded.
 */
object RuleAnalysisCollector {
    suspend fun collect(context: Context): RuleAnalysisReport = withContext(Dispatchers.IO) {
        build(context, AppController.load(context))
    }

    private fun build(context: Context, state: PersistedAppState): RuleAnalysisReport {
        val settings = state.settings
        val global = settings.globalProxyEnabled
        val perAppMode = state.proxyMode == ProxyMode.PerApp
        val servers = state.servers
        val serverById = servers.associateBy { it.id }
        val mainNode = serverById[state.selectedServerId]
        val mainTarget = RouteTarget(RouteTarget.Kind.Proxy, mainNode?.name)
        fun nodeTarget(node: ServerNode) =
            if (node.id == state.selectedServerId) mainTarget else RouteTarget(RouteTarget.Kind.Node, node.name)

        val inputs = mutableListOf<RuleAnalysisInput>()

        // 1. Per-app nodes, grouped by node like PerAppRouting does.
        state.protectedApps
            .filter { it.enabled && it.serverId.isNotBlank() }
            .groupBy { it.serverId }
            .forEach { (serverId, apps) ->
                val node = serverById[serverId]
                val runnable = node != null && !node.hiddenUnsupported && ManualNodeFactory.isRunnable(node)
                val target = node?.let(::nodeTarget) ?: RouteTarget(RouteTarget.Kind.Node, null)
                inputs += RuleAnalysisInput(
                    id = "app_$serverId",
                    layer = RuleLayer.PerApp,
                    name = node?.name.orEmpty(),
                    priority = PerAppRouting.PRIORITY,
                    segments = listOf(RuleAnalysisInput.Segment(target, emptyRule(RuleTargetPolicy.Proxy))),
                    apps = apps.map { it.name.ifBlank { it.id } },
                    inactiveReason = when {
                        !perAppMode -> InactiveReason.NotPerAppMode
                        !runnable -> InactiveReason.NodeMissing
                        else -> null
                    },
                )
            }

        // 2. Media routing, for the services with a route chosen.
        val selections = settings.streamingSelections.associateBy { it.serviceId }
        StreamingMediaManager.routedServices(settings).forEach { service ->
            val choice = selections[service.id]?.serverId.orEmpty()
            val node = if (choice == StreamingMediaManager.DIRECT) null else serverById[choice]
            val target = when {
                choice == StreamingMediaManager.DIRECT -> RouteTarget.Direct
                node != null -> nodeTarget(node)
                else -> RouteTarget(RouteTarget.Kind.Node, null)
            }
            val compiled = StreamingMediaManager.cachedRulesForAnalysis(context, service.id)
            val rule = compiled?.let {
                XrayRoutingRule(
                    target = if (node == null) RuleTargetPolicy.Direct else RuleTargetPolicy.Proxy,
                    domainSuffixes = it.domainSuffixes,
                    fullDomains = it.fullDomains,
                    domainKeywords = it.domainKeywords,
                    ipCidrs = it.ipCidrs,
                    ipCidrs6 = it.ipCidrs6,
                )
            }
            inputs += RuleAnalysisInput(
                id = "stream_${service.id}",
                layer = RuleLayer.Streaming,
                name = service.name,
                priority = StreamingMediaManager.STREAMING_RULE_PRIORITY,
                segments = rule?.let { listOf(RuleAnalysisInput.Segment(target, it)) }.orEmpty(),
                inactiveReason = when {
                    !settings.streamingRoutingEnabled -> InactiveReason.StreamingOff
                    global -> InactiveReason.GlobalMode
                    choice != StreamingMediaManager.DIRECT && node == null -> InactiveReason.NodeMissing
                    rule == null || !rule.hasEntries() -> InactiveReason.NotDownloaded
                    else -> null
                },
            )
        }

        // 3. Rule sources, in list order (user ones outrank the built-in ones).
        state.ruleSources.forEach { source ->
            val top = RuleSourceManager.isTopRule(source)
            val layer = when {
                top -> RuleLayer.Top
                source.systemDefault -> RuleLayer.Default
                else -> RuleLayer.Custom
            }
            val priority = RuleSourceManager.priorityOf(source)
            val earlyReason = when {
                !source.enabled -> InactiveReason.Disabled
                RuleSourceManager.usesGeoData(source, settings.geoRoutingRegion) -> InactiveReason.ReplacedByGeoData
                global && !top -> InactiveReason.GlobalMode
                else -> null
            }
            if (earlyReason != null) {
                inputs += RuleAnalysisInput(source.id, layer, source.name, priority, inactiveReason = earlyReason)
                return@forEach
            }
            val compiled = RuleSourceManager.currentRulesForAnalysis(context, source)
            if (compiled == null) {
                inputs += RuleAnalysisInput(source.id, layer, source.name, priority, inactiveReason = InactiveReason.NotDownloaded)
                return@forEach
            }
            val nodesByTag = mutableMapOf<String, ServerNode>()
            val missing = linkedSetOf<String>()
            val rules = compiled.toRoutingRules(
                fallbackPolicy = source.policy,
                priority = priority,
                servers = servers,
                onResolvedNode = { tag, node -> nodesByTag[tag] = node },
                onMissingNodePolicy = { missing += RuleSourceManager.nodePolicyDisplayName(it) },
            ).filter { it.hasEntries() }
            inputs += RuleAnalysisInput(
                id = source.id,
                layer = layer,
                name = source.name,
                priority = priority,
                segments = rules.map { rule ->
                    val target = rule.outboundTag?.let(nodesByTag::get)?.let(::nodeTarget)
                        ?: when (rule.target) {
                            RuleTargetPolicy.Direct -> RouteTarget.Direct
                            RuleTargetPolicy.Proxy -> mainTarget
                            RuleTargetPolicy.Block -> RouteTarget.Block
                        }
                    RuleAnalysisInput.Segment(target, rule)
                },
                missingNodes = missing.toList(),
                inactiveReason = if (rules.isEmpty()) InactiveReason.NotDownloaded else null,
            )
        }

        val tailscaleExit = settings.tailscaleEnabled && settings.tailscaleExitNodeId.isNotBlank()
        return RuleAnalysisReport(
            globalMode = global,
            perAppMode = perAppMode && !global,
            vpnAppNames = if (perAppMode && !global) {
                state.protectedApps.filter { it.enabled }.map { it.name.ifBlank { it.id } }
            } else {
                emptyList()
            },
            region = settings.geoRoutingRegion,
            mainNodeName = mainNode?.name,
            entries = RuleAnalyzer.analyze(inputs),
            quicBlocked = !settings.proxyQuicEnabled,
            tailscaleEnabled = settings.tailscaleEnabled,
            tailscaleSubnetCount = settings.tailscaleSubnetRoutes.size,
            homeSitesDirect = settings.geoRoutingRegion.geositeCode != null && RuleSourceManager.homeSitesDirect(state.ruleSources),
            fallback = when {
                tailscaleExit -> RouteTarget(RouteTarget.Kind.Tailscale)
                settings.fallbackDirect && !global -> RouteTarget.Direct
                else -> mainTarget
            },
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun emptyRule(policy: RuleTargetPolicy) = XrayRoutingRule(
        target = policy,
        domainSuffixes = emptyList(),
        fullDomains = emptyList(),
        domainKeywords = emptyList(),
        ipCidrs = emptyList(),
        ipCidrs6 = emptyList(),
    )
}
