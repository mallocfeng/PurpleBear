package com.mallocgfw.app.model

import android.content.Context

/**
 * Per-app nodes: in per-app mode an app can use its own node instead of the
 * selected one. Xray matches each connection's owner (an Android UID, looked up
 * through [com.mallocgfw.app.xray.AppProcessFinder]) against `process` rules.
 */
object PerAppRouting {
    /**
     * Above media routing (1000) and user rule sources (2000): the app's
     * choice wins. Kept strictly higher so it doesn't hang on list order.
     */
    const val PRIORITY = 3_000

    fun buildSetup(
        context: Context,
        state: PersistedAppState,
        selectedServerId: String,
    ): StreamingRoutingSetup {
        if (state.proxyMode != ProxyMode.PerApp) return StreamingRoutingSetup()
        val serverById = state.servers.associateBy { it.id }
        val uidsByNode = linkedMapOf<String, MutableList<String>>()
        // An app pinned to the node that is also the main one still gets its
        // own rule: otherwise media or direct rules could take its traffic
        // elsewhere. It reuses the main "proxy" outbound.
        state.protectedApps
            .filter { it.enabled && it.serverId.isNotBlank() }
            .forEach { app ->
                val node = serverById[app.serverId] ?: return@forEach
                if (node.hiddenUnsupported || !ManualNodeFactory.isRunnable(node)) return@forEach
                val uid = uidOf(context, app.id) ?: return@forEach
                uidsByNode.getOrPut(node.id) { mutableListOf() } += uid.toString()
            }
        val outbounds = mutableListOf<XrayNamedOutbound>()
        val rules = mutableListOf<XrayRoutingRule>()
        uidsByNode.entries.forEachIndexed { index, (nodeId, uids) ->
            val tag = if (nodeId == selectedServerId) "proxy" else "app_$index"
            if (tag != "proxy") outbounds += XrayNamedOutbound(tag = tag, node = serverById.getValue(nodeId))
            rules += XrayRoutingRule(
                target = RuleTargetPolicy.Proxy,
                domainSuffixes = emptyList(),
                fullDomains = emptyList(),
                domainKeywords = emptyList(),
                ipCidrs = emptyList(),
                ipCidrs6 = emptyList(),
                outboundTag = tag,
                priority = PRIORITY,
                processes = uids.distinct(),
            )
        }
        if (uidsByNode.isNotEmpty()) {
            val summary = uidsByNode.entries.joinToString("; ") { (nodeId, uids) ->
                "${serverById.getValue(nodeId).name} ← UID ${uids.distinct().joinToString(",")}"
            }
            com.mallocgfw.app.xray.AppLogManager.append(context, "PerAppRouting", "应用独立节点：$summary")
        }
        return StreamingRoutingSetup(outbounds = outbounds, routingRules = rules)
    }

    fun uidOf(context: Context, packageName: String): Int? =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
}
