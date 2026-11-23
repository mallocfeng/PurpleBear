package com.mallocgfw.app.model

import android.content.Context

/**
 * Per-app nodes: in per-app mode an app can use its own node instead of the
 * selected one. Xray matches each connection's owner (an Android UID, looked up
 * through [com.mallocgfw.app.xray.AppProcessFinder]) against `process` rules.
 */
object PerAppRouting {
    /** Above media routing (1000) and rule sources: the app's choice wins. */
    const val PRIORITY = 2_000

    fun buildSetup(
        context: Context,
        state: PersistedAppState,
        selectedServerId: String,
    ): StreamingRoutingSetup {
        if (state.proxyMode != ProxyMode.PerApp) return StreamingRoutingSetup()
        val serverById = state.servers.associateBy { it.id }
        val uidsByNode = linkedMapOf<String, MutableList<String>>()
        state.protectedApps
            .filter { it.enabled && it.serverId.isNotBlank() && it.serverId != selectedServerId }
            .forEach { app ->
                val node = serverById[app.serverId] ?: return@forEach
                if (node.hiddenUnsupported || !ManualNodeFactory.isRunnable(node)) return@forEach
                val uid = uidOf(context, app.id) ?: return@forEach
                uidsByNode.getOrPut(node.id) { mutableListOf() } += uid.toString()
            }
        val outbounds = mutableListOf<XrayNamedOutbound>()
        val rules = mutableListOf<XrayRoutingRule>()
        uidsByNode.entries.forEachIndexed { index, (nodeId, uids) ->
            val tag = "app_$index"
            outbounds += XrayNamedOutbound(tag = tag, node = serverById.getValue(nodeId))
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
