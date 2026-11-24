package com.mallocgfw.app.core

import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.AppLanguage
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.subscriptionMergeKey
import com.mallocgfw.app.model.ImportPreview
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProtectedApp
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceStatus
import com.mallocgfw.app.model.normalizedAppVpnMtu
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.StreamingRouteSelection
import com.mallocgfw.app.model.isSameImportedNode
import com.mallocgfw.app.model.mergedSubscriptionGroupNodes
import com.mallocgfw.app.model.subscriptionNameKey
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.xray.NodeLatencyTester
import com.mallocgfw.app.xray.normalizePrivateTailscaleSubnet

/**
 * Business operations on the saved app state, shared by the UI and the MCP
 * server. Pure functions: no Android, no I/O, so they run in JVM tests.
 * Side effects (reconnecting the VPN …) are reported, not performed.
 */
object StateOps {

    data class ImportOutcome(
        val state: PersistedAppState,
        val selectedServerChanged: Boolean = false,
        val message: String? = null,
        /**
         * The node to reconnect to: the connected node's group was refreshed, or
         * the import dropped a node the tunnel uses (see [removalEffect]).
         */
        val reconnectServer: ServerNode? = null,
        /** Set when the import dropped the connected node and left no usable one: disconnect. */
        val disconnectMessage: String? = null,
    )

    data class LinkOutcome(
        val state: PersistedAppState,
        val changed: Boolean,
        val message: String?,
        val error: String? = null,
    )

    /**
     * Merges [preview] into [state]. [connectedServerId] is the node the VPN is
     * running on (null when disconnected), used to decide whether to reconnect.
     */
    /**
     * Replaces a subscription group's nodes with a refreshed list while keeping
     * each surviving node's id and the user's settings for it (favorite,
     * latency, pre-proxy, fallback). Parsed ids carry the line number, so
     * without this a reordered subscription would drop every app route, media
     * route and chain pointing at its nodes. A node matches by share link, else
     * by protocol, address, port and name. Ids of removed nodes are not reused:
     * a new node gets a freshly generated id, since its parsed id (group, line,
     * link prefix) can equal that of a node removed earlier, which rule
     * sources (NODE: targets) or other settings may still name.
     */
    fun refreshSubscriptionNodes(
        servers: List<ServerNode>,
        groupId: String,
        refreshedNodes: List<ServerNode>,
    ): List<ServerNode> {
        val others = servers.filterNot { it.groupId == groupId }
        val unmatched = servers.filter { it.groupId == groupId }.toMutableList()
        val takenIds = servers.map { it.id }.toMutableSet()
        val matched = mutableMapOf<String, ServerNode>()
        val finalIds = mutableMapOf<String, String>()
        refreshedNodes.forEach { node ->
            val old = unmatched.firstOrNull { it.rawUri.isNotBlank() && it.rawUri == node.rawUri }
                ?: unmatched.firstOrNull { it.subscriptionMergeKey() == node.subscriptionMergeKey() }
                ?: return@forEach
            unmatched.remove(old)
            matched[node.id] = old
            finalIds[node.id] = old.id
        }
        refreshedNodes.forEach { node ->
            if (node.id in finalIds) return@forEach
            val id = ImportParser.freshNodeId(node.id, takenIds)
            takenIds += id
            finalIds[node.id] = id
        }
        val updated = refreshedNodes.map { node ->
            val old = matched[node.id]
            val preProxy = finalIds[node.preProxyNodeId] ?: node.preProxyNodeId
            val fallback = finalIds[node.fallbackNodeId] ?: node.fallbackNodeId
            if (old == null) {
                node.copy(id = finalIds.getValue(node.id), preProxyNodeId = preProxy, fallbackNodeId = fallback)
            } else {
                node.copy(
                    id = old.id,
                    favorite = old.favorite,
                    latencyMs = old.latencyMs,
                    stable = old.stable,
                    preProxyNodeId = old.preProxyNodeId.ifBlank { preProxy },
                    fallbackNodeId = old.fallbackNodeId.ifBlank { fallback },
                )
            }
        }
        return others + updated
    }

    fun applyImport(
        state: PersistedAppState,
        preview: ImportPreview,
        connectedServerId: String?,
        /** Node ids the running tunnel was built with (null: inferred from settings). */
        runningNodeIds: Set<String>? = null,
    ): ImportOutcome {
        val servers = state.servers.toMutableList()
        val serverGroups = state.serverGroups.toMutableList()
        val subscriptions = state.subscriptions.toMutableList()
        val recentImports = state.recentImports.toMutableList()
        var selectedServerId = state.selectedServerId

        val selectedBefore = servers.firstOrNull { it.id == selectedServerId }
        val targetGroupId = preview.group.id
        val existingGroupIndex = serverGroups.indexOfFirst { it.id == targetGroupId }
        val selectedWasInTargetGroup = selectedBefore?.groupId == targetGroupId
        val selectedWasConnected = selectedWasInTargetGroup &&
            selectedBefore != null &&
            connectedServerId == selectedBefore.id
        val targetGroup = preview.group.copy(id = targetGroupId)
        if (existingGroupIndex >= 0) {
            serverGroups[existingGroupIndex] = targetGroup
        } else {
            serverGroups.add(0, targetGroup)
        }

        val normalizedPreviewNodes = preview.nodes.map { node ->
            if (node.groupId == targetGroupId) node else node.copy(groupId = targetGroupId)
        }
        if (preview.group.type == ServerGroupType.Subscription) {
            val refreshed = refreshSubscriptionNodes(servers, targetGroupId, normalizedPreviewNodes)
            servers.clear()
            servers.addAll(refreshed)
        } else {
            // Local ids restart at 1 on every import, so a new node can carry the
            // id of an unrelated existing or deleted one. Settle each node's final
            // id first (existing match, or a never-used id), then remap links
            // inside the batch.
            val takenIds = servers.map { it.id }.toMutableSet()
            val existingIndexes = mutableMapOf<String, Int>()
            val finalIds = mutableMapOf<String, String>()
            normalizedPreviewNodes.forEach { normalizedNode ->
                val existingNodeIndex = servers.indexOfFirst { it.isSameImportedNode(normalizedNode) }
                if (existingNodeIndex >= 0) {
                    existingIndexes[normalizedNode.id] = existingNodeIndex
                    finalIds[normalizedNode.id] = servers[existingNodeIndex].id
                } else {
                    val id = ImportParser.freshNodeId(normalizedNode.id, takenIds)
                    takenIds += id
                    finalIds[normalizedNode.id] = id
                }
            }
            normalizedPreviewNodes.forEach { normalizedNode ->
                val existingNodeIndex = existingIndexes[normalizedNode.id]
                if (existingNodeIndex != null) {
                    val existingNode = servers[existingNodeIndex]
                    servers[existingNodeIndex] = normalizedNode.copy(
                        id = existingNode.id,
                        favorite = existingNode.favorite,
                        latencyMs = existingNode.latencyMs,
                        stable = existingNode.stable,
                        preProxyNodeId = existingNode.preProxyNodeId,
                        fallbackNodeId = existingNode.fallbackNodeId,
                    )
                } else {
                    servers.add(
                        normalizedNode.copy(
                            id = finalIds.getValue(normalizedNode.id),
                            preProxyNodeId = finalIds[normalizedNode.preProxyNodeId] ?: normalizedNode.preProxyNodeId,
                            fallbackNodeId = finalIds[normalizedNode.fallbackNodeId] ?: normalizedNode.fallbackNodeId,
                        ),
                    )
                }
            }
        }
        val mergedServers = normalizeNodeLinks(servers.mergedSubscriptionGroupNodes(targetGroupId))
        val mergedNodeCount = mergedServers.count { it.groupId == targetGroupId && !it.hiddenUnsupported }
        // Never a hidden or unrunnable node: when the refreshed group has none
        // left, any usable node elsewhere (the VPN moves there, see below).
        fun usable(node: ServerNode) = !node.hiddenUnsupported && ManualNodeFactory.isRunnable(node)
        val selectedAfter = if (selectedWasInTargetGroup && selectedBefore != null) {
            mergedServers.firstOrNull {
                it.groupId == targetGroupId && usable(it) && it.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
            } ?: mergedServers.firstOrNull { it.groupId == targetGroupId && usable(it) }
                ?: fallbackSelection(mergedServers).let { id -> mergedServers.firstOrNull { it.id == id } }
        } else {
            null
        }
        selectedAfter?.let { selectedServerId = it.id }

        preview.subscription?.let { subscription ->
            val normalizedSubscription = subscription.copy(
                id = targetGroupId,
                name = targetGroup.name,
                nodes = mergedNodeCount,
                autoUpdate = targetGroup.sourceUrl != null && subscription.autoUpdate,
                sourceUrl = targetGroup.sourceUrl,
                nextSync = if (targetGroup.sourceUrl != null && subscription.autoUpdate) "手动" else "快照",
                status = subscription.status,
            )
            val subscriptionIndex = subscriptions.indexOfFirst { it.id == targetGroupId }
            if (subscriptionIndex >= 0) {
                subscriptions[subscriptionIndex] = normalizedSubscription
            } else {
                subscriptions.add(0, normalizedSubscription)
            }
        }

        // Stored, so kept in Chinese and translated when shown.
        val recentTitle = when (preview.group.type) {
            ServerGroupType.Subscription -> "${preview.group.name} · 订阅 · 刚刚"
            ServerGroupType.Local -> "${preview.nodes.firstOrNull()?.name ?: "Local"} · 单节点 · 刚刚"
        }
        recentImports.remove(recentTitle)
        recentImports.add(0, recentTitle)
        while (recentImports.size > 5) recentImports.removeAt(recentImports.lastIndex)

        val nextState = normalizeNodeReferences(
            state.copy(
                serverGroups = serverGroups,
                servers = mergedServers,
                subscriptions = subscriptions,
                recentImports = recentImports,
                selectedServerId = selectedServerId,
            ),
        )
        val selectedNameStillExists = selectedAfter != null &&
            selectedBefore != null &&
            selectedAfter.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
        // One decision for the running VPN, the same as for a delete: the
        // connected node gone (reconnect to a usable node, or disconnect when
        // none is left), or a node the tunnel uses gone (reconnect on the same
        // node). Otherwise a refresh of the connected node's group reconnects
        // to pick up its new settings.
        var reconnectServer: ServerNode? = null
        var disconnectMessage: String? = null
        when (val effect = removalEffect(state, nextState, connectedServerId, runningNodeIds)) {
            is RemovalEffect.Reconnect -> reconnectServer = effect.server
            is RemovalEffect.Disconnect -> disconnectMessage = effect.message
            RemovalEffect.None -> if (selectedWasConnected) {
                reconnectServer = nextState.servers.firstOrNull { it.id == connectedServerId }
            }
        }
        val selectionMessage = if (selectedAfter != null && selectedBefore != null && !selectedNameStillExists) {
            tr(
                "原选中节点 ${selectedBefore.name} 已不存在，已切换到 ${selectedAfter.name}。",
                "The selected node ${selectedBefore.name} no longer exists; switched to ${selectedAfter.name}.",
                "Выбранного узла ${selectedBefore.name} больше нет, выбран ${selectedAfter.name}.",
            )
        } else {
            null
        }
        return ImportOutcome(
            state = nextState,
            selectedServerChanged = selectionMessage != null,
            message = disconnectMessage ?: selectionMessage,
            reconnectServer = reconnectServer,
            disconnectMessage = disconnectMessage,
        )
    }

    /**
     * Commits a subscription refresh whose download started at [fetchStartedAtMs].
     * Every refresh (the subscription screen, MCP, sync) goes through here.
     * Null, and nothing changes, when the result no longer applies:
     * - the subscription was deleted, or its link changed, meanwhile;
     * - a refresh that started later already committed (its updatedAtMs is
     *   newer), so this older list would roll it back.
     * The subscription's updatedAtMs becomes [fetchStartedAtMs]: the time the
     * list was asked for, which orders refreshes.
     */
    fun applySubscriptionRefresh(
        state: PersistedAppState,
        subscriptionId: String,
        sourceUrl: String?,
        preview: ImportPreview,
        fetchStartedAtMs: Long,
        connectedServerId: String?,
        runningNodeIds: Set<String>? = null,
    ): ImportOutcome? {
        if (!refreshStillApplies(state, subscriptionId, sourceUrl, fetchStartedAtMs)) return null
        val outcome = applyImport(state, preview, connectedServerId, runningNodeIds)
        // A refresh isn't an import the user made: keep their recent-imports list.
        val refreshed = setSubscriptionStatus(
            outcome.state.copy(recentImports = state.recentImports),
            subscriptionId,
            "已同步",
            fetchStartedAtMs,
        )
        return outcome.copy(state = refreshed)
    }

    /** Marks a failed refresh, unless the subscription is gone or a later refresh already committed. */
    fun markSubscriptionRefreshFailed(
        state: PersistedAppState,
        subscriptionId: String,
        sourceUrl: String?,
        fetchStartedAtMs: Long,
    ): PersistedAppState? {
        if (!refreshStillApplies(state, subscriptionId, sourceUrl, fetchStartedAtMs)) return null
        return setSubscriptionStatus(state, subscriptionId, "刷新失败")
    }

    private fun refreshStillApplies(state: PersistedAppState, subscriptionId: String, sourceUrl: String?, fetchStartedAtMs: Long): Boolean {
        val current = state.subscriptions.firstOrNull { it.id == subscriptionId } ?: return false
        if (current.sourceUrl != sourceUrl) return false
        return (current.updatedAtMs ?: 0L) <= fetchStartedAtMs
    }

    /** Drops pre-proxy / fallback links that point at missing, hidden or unrunnable nodes. */
    fun normalizeNodeLinks(servers: List<ServerNode>): List<ServerNode> {
        val visibleServerMap = servers.filterNot { it.hiddenUnsupported }.associateBy { it.id }
        return servers.map { server ->
            val preProxyId = server.preProxyNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    ManualNodeFactory.supportsPreProxy(server) &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::isRunnable) == true
            }.orEmpty()
            val fallbackId = server.fallbackNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    ManualNodeFactory.isRunnable(server) &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::isRunnable) == true
            }.orEmpty()
            if (preProxyId != server.preProxyNodeId || fallbackId != server.fallbackNodeId) {
                server.copy(preProxyNodeId = preProxyId, fallbackNodeId = fallbackId)
            } else {
                server
            }
        }
    }

    /**
     * Drops media routes, per-app nodes and the last-connected node when they
     * point at nodes that no longer exist, and moves a dangling selection to a
     * usable node.
     */
    fun normalizeNodeReferences(state: PersistedAppState): PersistedAppState {
        val next = normalizeProtectedAppRoutes(normalizeStreamingSelections(state))
        val ids = next.servers.map { it.id }.toSet()
        val selected = next.selectedServerId.takeIf { it.isBlank() || it in ids } ?: fallbackSelection(next.servers)
        val lastConnected = next.settings.lastConnectedServerId.takeIf { it.isBlank() || it in ids }.orEmpty()
        if (selected == next.selectedServerId && lastConnected == next.settings.lastConnectedServerId) return next
        return next.copy(
            selectedServerId = selected,
            settings = next.settings.copy(lastConnectedServerId = lastConnected),
        )
    }

    /** The node to select when the selected one is gone: a visible, runnable one when there is one. */
    fun fallbackSelection(servers: List<ServerNode>): String =
        (
            servers.firstOrNull { !it.hiddenUnsupported && ManualNodeFactory.isRunnable(it) }
                ?: servers.firstOrNull { !it.hiddenUnsupported }
                ?: servers.firstOrNull()
            )?.id.orEmpty()

    /** What the running VPN must do after nodes were removed from the state. */
    sealed interface RemovalEffect {
        data object None : RemovalEffect
        data class Reconnect(val server: ServerNode, val message: String) : RemovalEffect
        data class Disconnect(val message: String) : RemovalEffect
    }

    /**
     * [before] → [after] removed nodes while the VPN ran on [connectedServerId]
     * (null: not connected). The tunnel keeps using the nodes it was built
     * with, so a removed node it uses would keep carrying traffic:
     * - the connected node itself: reconnect to the new selection, or
     *   disconnect when no usable node is left;
     * - a node in its pre-proxy chain, a per-app node, a media route node or a
     *   rule target (NODE:): reconnect on the same node, which drops it.
     * Removing a node the tunnel doesn't use changes nothing.
     */
    fun removalEffect(
        before: PersistedAppState,
        after: PersistedAppState,
        connectedServerId: String?,
        /**
         * Node ids the running tunnel was built with (the core's node, extra
         * routed outbounds and their pre-proxy chains), recorded when it started.
         * Null: inferred from [before]'s settings, which can differ from the
         * tunnel when a setting was changed but not applied yet.
         */
        runningNodeIds: Set<String>? = null,
    ): RemovalEffect {
        val activeId = connectedServerId?.takeIf { it.isNotBlank() } ?: return RemovalEffect.None
        val remaining = after.servers.map { it.id }.toSet()
        val removed = before.servers.map { it.id }.filterNot { it in remaining }.toSet()
        if (removed.isEmpty()) return RemovalEffect.None
        val activeNode = before.servers.firstOrNull { it.id == activeId }
        if (activeId in removed) return moveOffRemovedNode(after, activeNode?.name ?: activeId)
        val active = after.servers.firstOrNull { it.id == activeId } ?: return RemovalEffect.None
        val used = runningNodeIds ?: nodesInRunningConfig(before, activeId)
        if (used.none { it in removed }) return RemovalEffect.None
        return reconnectToDropRemoved(active)
    }

    /**
     * A tunnel that just came up, checked against [state]: a node it was built
     * with ([installedNodeIds]) may have been removed while it was connecting
     * (the service read the settings before the removal).
     */
    fun staleTunnelEffect(state: PersistedAppState, activeId: String, installedNodeIds: Set<String>): RemovalEffect {
        val ids = state.servers.map { it.id }.toSet()
        if (installedNodeIds.all { it in ids }) return RemovalEffect.None
        if (activeId !in ids) return moveOffRemovedNode(state, activeId)
        return reconnectToDropRemoved(state.servers.first { it.id == activeId })
    }

    private fun reconnectToDropRemoved(active: ServerNode) = RemovalEffect.Reconnect(
        active,
        tr(
            "已删除的节点仍在当前连接的分流中使用，正在重连以移除…",
            "A deleted node was still used by the running VPN's routes; reconnecting to drop it…",
            "Удалённый узел ещё использовался в маршрутах VPN, переподключение…",
        ),
    )

    /** The connected node is gone: the selection if usable, else any usable node, else disconnect. */
    private fun moveOffRemovedNode(after: PersistedAppState, name: String): RemovalEffect {
        fun usable(node: ServerNode) = !node.hiddenUnsupported && ManualNodeFactory.isRunnable(node)
        val target = after.servers.firstOrNull { it.id == after.selectedServerId && usable(it) }
            ?: after.servers.firstOrNull(::usable)
        return if (target != null) {
            RemovalEffect.Reconnect(
                target,
                tr(
                    "当前连接的节点 $name 已删除，正在切换到 ${target.name}…",
                    "The connected node $name was deleted; switching to ${target.name}…",
                    "Подключённый узел $name удалён, переключение на ${target.name}…",
                ),
            )
        } else {
            RemovalEffect.Disconnect(
                tr(
                    "当前连接的节点 $name 已删除，没有可用节点，VPN 已断开。",
                    "The connected node $name was deleted and no usable node is left, so the VPN was disconnected.",
                    "Подключённый узел $name удалён, других узлов нет, VPN отключён.",
                ),
            )
        }
    }

    /**
     * What a removal cleared in the settings of what is left, for the user
     * (null: nothing). Pre-proxies matter most: the node then dials its server
     * directly, which may not work where the pre-proxy was needed.
     */
    fun removalNotice(before: PersistedAppState, after: PersistedAppState): String? {
        val remaining = after.servers.associateBy { it.id }
        val removedIds = before.servers.map { it.id }.filterNot { it in remaining }.toSet()
        if (removedIds.isEmpty()) return null
        val nameOf = before.servers.associate { it.id to it.name }
        fun list(names: List<String>): String {
            val shown = names.distinct()
            return if (shown.size <= 3) {
                shown.joinToString(tr("、", ", ", ", "))
            } else {
                shown.take(3).joinToString(tr("、", ", ", ", ")) + tr(" 等 ${shown.size} 个", " and ${shown.size - 3} more", " и ещё ${shown.size - 3}")
            }
        }
        val lines = mutableListOf<String>()
        // Only nodes still there; a removed node's own links went with it.
        val lostPreProxy = before.servers.filter { it.id in remaining && it.preProxyNodeId in removedIds }
        if (lostPreProxy.isNotEmpty()) {
            val names = list(lostPreProxy.map { it.name })
            lines += tr(
                "$names 的前置代理已删除，已清除这项设置；重新连接后将直接连接自己的服务器，如果原来必须经前置代理才能连通，请重新设置。",
                "The pre-proxy of $names was deleted and the setting was cleared. After reconnecting it dials its own server directly; if it needed the pre-proxy to get through, set a new one.",
                "Предварительный прокси для $names удалён, настройка сброшена. После переподключения узел будет подключаться к своему серверу напрямую; если без прокси он недоступен, задайте новый.",
            )
        }
        val lostFallback = before.servers.filter { it.id in remaining && it.fallbackNodeId in removedIds }
        if (lostFallback.isNotEmpty()) {
            val names = list(lostFallback.map { it.name })
            lines += tr(
                "$names 的备用节点已删除，检测失败时不会再自动切换。",
                "The fallback node of $names was deleted; it won't switch automatically when checks fail.",
                "Резервный узел для $names удалён; автоматического переключения при сбое не будет.",
            )
        }
        val lostApps = before.protectedApps.filter { it.serverId in removedIds }
        if (lostApps.isNotEmpty()) {
            val names = list(lostApps.map { it.name })
            lines += tr(
                "分应用代理：$names 指定的节点已删除，改用当前节点。",
                "Per-app proxy: the node chosen for $names was deleted; they now use the current node.",
                "Прокси по приложениям: узел для $names удалён, теперь используется текущий узел.",
            )
        }
        val lostMedia = before.settings.streamingSelections.filter { it.serverId in removedIds }
        if (lostMedia.isNotEmpty()) {
            val names = list(lostMedia.map { StreamingMediaManager.serviceById(it.serviceId)?.name ?: it.serviceId })
            lines += tr(
                "流媒体分流：$names 指定的节点已删除，改走默认线路。",
                "Media routing: the node chosen for $names was deleted; they now use the default route.",
                "Медиа: узел для $names удалён, теперь используется маршрут по умолчанию.",
            )
        }
        // A NODE: target whose node is gone and that no other node matches by
        // server: those rules now go through the main proxy (RuleSourceManager).
        val lostRules = RuleSourceManager.nodeRuleTargets(before.ruleSources, before.servers)
            .filter { it.node.id in removedIds && !RuleSourceManager.resolvesNodePolicy(it.token, after.servers) }
        if (lostRules.isNotEmpty()) {
            val sources = list(lostRules.map { it.source.name })
            val nodes = list(lostRules.map { it.node.name })
            lines += tr(
                "规则：$sources 中指定的节点 $nodes 已删除，这些规则改走当前节点。规则文本未修改，可在规则页重新选择节点。",
                "Rules: the node $nodes chosen in $sources was deleted; those rules now use the current node. The rule text is unchanged; pick a new node on the Rules page.",
                "Правила: узел $nodes, указанный в $sources, удалён; эти правила теперь идут через текущий узел. Текст правил не изменён, выберите новый узел на странице правил.",
            )
        }
        if (lines.isEmpty()) return null
        val removedNames = list(removedIds.mapNotNull { nameOf[it] })
        return tr("已删除 $removedNames。", "Deleted $removedNames.", "Удалено: $removedNames.") + "\n\n" + lines.joinToString("\n\n")
    }

    /** Nodes the tunnel running on [activeId] was built with (see XrayVpnService.connect). */
    private fun nodesInRunningConfig(state: PersistedAppState, activeId: String): Set<String> {
        val roots = mutableListOf(activeId)
        if (state.proxyMode == ProxyMode.PerApp) {
            state.protectedApps.filter { it.enabled && it.serverId.isNotBlank() }.mapTo(roots) { it.serverId }
        }
        if (!state.settings.globalProxyEnabled) {
            // Rule sources are only built outside global mode.
            RuleSourceManager.nodeRuleTargets(state.ruleSources, state.servers).mapTo(roots) { it.node.id }
        }
        if (!state.settings.globalProxyEnabled && state.settings.streamingRoutingEnabled) {
            state.settings.streamingSelections
                .map { it.serverId }
                .filter { it.isNotBlank() && it != StreamingMediaManager.DIRECT }
                .let(roots::addAll)
        }
        // Each of them brings its pre-proxy chain.
        val byId = state.servers.associateBy { it.id }
        val result = mutableSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!result.add(id)) continue
            byId[id]?.preProxyNodeId?.takeIf { it.isNotBlank() }?.let(queue::addLast)
        }
        return result
    }

    /**
     * Clears per-app nodes that no longer exist (the app then uses the selected
     * node, as it already did at run time). Left in place, a later node that
     * happens to get the same id would silently take over the app's traffic.
     */
    fun normalizeProtectedAppRoutes(state: PersistedAppState): PersistedAppState {
        val validServerIds = state.servers.map { it.id }.toSet()
        if (state.protectedApps.none { it.serverId.isNotBlank() && it.serverId !in validServerIds }) return state
        return state.copy(
            protectedApps = state.protectedApps.map { app ->
                if (app.serverId.isNotBlank() && app.serverId !in validServerIds) app.copy(serverId = "") else app
            },
        )
    }

    /** Drops streaming routes that point at nodes that no longer exist. */
    fun normalizeStreamingSelections(state: PersistedAppState): PersistedAppState {
        val validServerIds = state.servers.map { it.id }.toSet()
        val selections = state.settings.streamingSelections
            .filter { it.serverId == StreamingMediaManager.DIRECT || (it.serverId.isNotBlank() && it.serverId in validServerIds) }
            .distinctBy { it.serviceId }
        return if (selections == state.settings.streamingSelections) {
            state
        } else {
            state.copy(settings = state.settings.copy(streamingSelections = selections))
        }
    }

    private fun isRunnableOtherNode(servers: List<ServerNode>, owner: ServerNode, candidateId: String): Boolean {
        val id = candidateId.trim()
        return id.isNotBlank() && id != owner.id &&
            servers.any { it.id == id && ManualNodeFactory.isRunnable(it) }
    }

    fun isValidPreProxy(servers: List<ServerNode>, owner: ServerNode, candidateId: String): Boolean =
        ManualNodeFactory.supportsPreProxy(owner) && isRunnableOtherNode(servers, owner, candidateId)

    fun isValidFallback(servers: List<ServerNode>, owner: ServerNode, candidateId: String): Boolean =
        ManualNodeFactory.isRunnable(owner) &&
            NodeLatencyTester.supportsHeartbeatFallback(owner) &&
            isRunnableOtherNode(servers, owner, candidateId)

    /** Sets or clears ([targetId] blank) the pre-proxy of [nodeId]. */
    fun setPreProxy(state: PersistedAppState, nodeId: String, targetId: String): LinkOutcome {
        val index = state.servers.indexOfFirst { it.id == nodeId }
        if (index < 0) return LinkOutcome(state, false, null, error = nodeNotFound(nodeId))
        val node = state.servers[index]
        val wanted = targetId.trim()
        if (wanted.isNotBlank() && !isValidPreProxy(state.servers, node, wanted)) {
            return LinkOutcome(
                state, false, null,
                error = if (!ManualNodeFactory.supportsPreProxy(node)) {
                    tr("${node.name} 不支持前置代理。", "${node.name} doesn't support a pre-proxy.", "${node.name} не поддерживает предварительный прокси.")
                } else {
                    tr("$wanted 不是可用的前置代理节点。", "$wanted isn't a usable pre-proxy node.", "$wanted нельзя использовать как предварительный прокси.")
                },
            )
        }
        if (wanted == node.preProxyNodeId) return LinkOutcome(state, false, null)
        val servers = state.servers.toMutableList().also { it[index] = node.copy(preProxyNodeId = wanted) }
        val message = if (wanted.isBlank()) {
            tr("已清除 ${node.name} 的前置代理。重新连接后生效。", "Cleared the pre-proxy of ${node.name}. Takes effect after reconnecting.", "Предварительный прокси для ${node.name} удалён. Вступит в силу после переподключения.")
        } else {
            val targetName = state.servers.firstOrNull { it.id == wanted }?.name ?: wanted
            tr("已将 ${targetName} 设为 ${node.name} 的前置代理。重新连接后生效。", "Set ${targetName} as the pre-proxy for ${node.name}. Takes effect after reconnecting.", "${targetName} назначен предварительным прокси для ${node.name}. Вступит в силу после переподключения.")
        }
        return LinkOutcome(state.copy(servers = servers), true, message)
    }

    /** Sets or clears ([targetId] blank) the fallback node of [nodeId]. */
    fun setFallback(state: PersistedAppState, nodeId: String, targetId: String): LinkOutcome {
        val index = state.servers.indexOfFirst { it.id == nodeId }
        if (index < 0) return LinkOutcome(state, false, null, error = nodeNotFound(nodeId))
        val node = state.servers[index]
        if (!NodeLatencyTester.supportsHeartbeatFallback(node)) {
            val cleared = if (node.fallbackNodeId.isNotBlank()) {
                state.copy(servers = state.servers.toMutableList().also { it[index] = node.copy(fallbackNodeId = "") })
            } else {
                state
            }
            return LinkOutcome(cleared, cleared != state, null, error = NodeLatencyTester.heartbeatFallbackUnsupportedMessage(node))
        }
        val wanted = targetId.trim()
        if (wanted.isNotBlank() && !isValidFallback(state.servers, node, wanted)) {
            return LinkOutcome(
                state, false, null,
                error = tr("$wanted 不是可用的备用节点。", "$wanted isn't a usable fallback node.", "$wanted нельзя использовать как резервный узел."),
            )
        }
        if (wanted == node.fallbackNodeId) return LinkOutcome(state, false, null)
        val servers = state.servers.toMutableList().also { it[index] = node.copy(fallbackNodeId = wanted) }
        val message = if (wanted.isBlank()) {
            tr("已清除 ${node.name} 的备用节点。", "Cleared the fallback node of ${node.name}.", "Резервный узел для ${node.name} удалён.")
        } else {
            val targetName = state.servers.firstOrNull { it.id == wanted }?.name ?: wanted
            tr("已将 ${targetName} 设为 ${node.name} 的备用节点。当前节点检测失败时会自动切换。", "Set ${targetName} as the fallback for ${node.name}. The app switches to it automatically if the current node fails checks.", "${targetName} назначен резервным узлом для ${node.name}. Если текущий узел не пройдёт проверку, переключение произойдёт автоматически.")
        }
        return LinkOutcome(state.copy(servers = servers), true, message)
    }

    fun removeNode(state: PersistedAppState, nodeId: String): PersistedAppState? {
        val node = state.servers.firstOrNull { it.id == nodeId } ?: return null
        val servers = normalizeNodeLinks(state.servers.filterNot { it.id == nodeId })
        return normalizeNodeReferences(
            state.copy(
                servers = servers,
                recentImports = state.recentImports.filterNot { it.contains(node.name) },
                selectedServerId = if (state.selectedServerId == nodeId) fallbackSelection(servers) else state.selectedServerId,
            ),
        )
    }

    /** Removes a subscription group and its nodes. Local groups can't be removed. */
    fun removeSubscriptionGroup(state: PersistedAppState, groupId: String): PersistedAppState? {
        val group = state.serverGroups.firstOrNull { it.id == groupId } ?: return null
        if (group.type != ServerGroupType.Subscription) return null
        val removedNodeIds = state.servers.filter { it.groupId == groupId }.map { it.id }.toSet()
        val servers = normalizeNodeLinks(state.servers.filterNot { it.groupId == groupId })
        return normalizeNodeReferences(
            state.copy(
                serverGroups = state.serverGroups.filterNot { it.id == groupId },
                subscriptions = state.subscriptions.filterNot { it.id == groupId },
                servers = servers,
                recentImports = state.recentImports.filterNot { it.contains(group.name) },
                selectedServerId = if (state.selectedServerId in removedNodeIds) fallbackSelection(servers) else state.selectedServerId,
            ),
        )
    }

    /**
     * Routes [serviceId] through [serverId]; a blank [serverId] uses the default
     * node and [StreamingMediaManager.DIRECT] sends it direct.
     */
    fun setStreamingRoute(state: PersistedAppState, serviceId: String, serverId: String): PersistedAppState {
        val selections = state.settings.streamingSelections.filterNot { it.serviceId == serviceId }.toMutableList()
        if (serverId.isNotBlank()) selections += StreamingRouteSelection(serviceId = serviceId, serverId = serverId)
        return state.copy(settings = state.settings.copy(streamingSelections = selections))
    }

    // ---- nodes -------------------------------------------------------------------

    /** Saves a node from the editor: replaces [editingId] (keeping its links and stats) or adds it first. */
    fun saveLocalNode(state: PersistedAppState, node: ServerNode, editingId: String?, editedDescription: String): PersistedAppState {
        val existing = editingId?.let { id -> state.servers.firstOrNull { it.id == id } }
        val servers = if (existing != null) {
            state.servers.map {
                if (it.id != existing.id) it else node.copy(
                    id = existing.id,
                    groupId = existing.groupId,
                    subscription = existing.subscription,
                    latencyMs = existing.latencyMs,
                    stable = existing.stable,
                    favorite = existing.favorite,
                    rawUri = existing.rawUri,
                    hiddenUnsupported = existing.hiddenUnsupported,
                    preProxyNodeId = existing.preProxyNodeId,
                    fallbackNodeId = existing.fallbackNodeId,
                    description = editedDescription,
                )
            }
        } else {
            listOf(node) + state.servers
        }
        return state.copy(servers = servers, selectedServerId = existing?.id ?: node.id)
    }

    /** Records a latency result; null means the test failed. */
    fun setNodeLatency(state: PersistedAppState, nodeId: String, latencyMs: Int?): PersistedAppState =
        state.copy(servers = state.servers.map { if (it.id == nodeId) it.copy(latencyMs = latencyMs ?: 0, stable = latencyMs != null) else it })

    fun toggleFavorite(state: PersistedAppState, nodeId: String): PersistedAppState =
        state.copy(servers = state.servers.map { if (it.id == nodeId) it.copy(favorite = !it.favorite) else it })

    /** Status words are stored in Chinese and translated when shown ("刷新中", "已同步", "刷新失败"). */
    fun setSubscriptionStatus(state: PersistedAppState, id: String, status: String, syncedAtMs: Long? = null): PersistedAppState =
        state.copy(
            subscriptions = state.subscriptions.map {
                when {
                    it.id != id -> it
                    syncedAtMs != null -> it.copy(status = status, updatedAt = "刚刚", updatedAtMs = syncedAtMs)
                    else -> it.copy(status = status)
                }
            },
        )

    // ---- rules -------------------------------------------------------------------

    /**
     * Commits a rule-source refresh that started at [fetchStartedAtMs]; every
     * refresh and save (rules page, MCP, sync) goes through here.
     * [expected] is the source as it was in the state when the refresh
     * started (null: a new source, which mustn't exist yet); [result] is the
     * refreshed (or failed) source, built from the definition that was fetched.
     * Null, and nothing changes, when the result no longer applies:
     * - the source was deleted meanwhile;
     * - its definition (link, text, default policy) was edited meanwhile;
     * - a refresh that started later already committed.
     * The current on/off switch and name are kept. updatedAtMs moves only on
     * success: a failed attempt leaves the list (and its order) as it was.
     */
    fun applyRuleSourceRefresh(
        state: PersistedAppState,
        expected: RuleSourceItem?,
        result: RuleSourceItem,
        fetchStartedAtMs: Long,
        succeeded: Boolean,
    ): PersistedAppState? {
        val current = state.ruleSources.firstOrNull { it.id == result.id }
        if (expected == null) {
            if (current != null) return null
        } else {
            if (current == null) return null
            if (RuleSourceManager.definitionKey(current) != RuleSourceManager.definitionKey(expected)) return null
            if ((current.updatedAtMs ?: 0L) > fetchStartedAtMs) return null
        }
        val stored = result.copy(
            name = current?.name ?: result.name,
            enabled = current?.enabled ?: result.enabled,
            systemDefault = current?.systemDefault ?: result.systemDefault,
            updatedAtMs = if (succeeded) fetchStartedAtMs else current?.updatedAtMs,
        )
        return upsertRuleSource(state, stored)
    }

    fun upsertRuleSource(state: PersistedAppState, source: RuleSourceItem): PersistedAppState =
        if (state.ruleSources.any { it.id == source.id }) {
            state.copy(ruleSources = state.ruleSources.map { if (it.id == source.id) source else it })
        } else {
            state.copy(ruleSources = state.ruleSources + source)
        }

    fun setRuleSourceEnabled(state: PersistedAppState, id: String, enabled: Boolean): PersistedAppState? {
        if (state.ruleSources.none { it.id == id }) return null
        return state.copy(
            ruleSources = state.ruleSources.map {
                if (it.id != id) it else it.copy(
                    enabled = enabled,
                    status = if (enabled) (if (it.convertedRules > 0) RuleSourceStatus.Ready else RuleSourceStatus.Idle) else RuleSourceStatus.Disabled,
                )
            },
        )
    }

    /** Removes a rule source; built-in ones are reset instead. (The caller deletes its cached files.) */
    fun removeRuleSource(state: PersistedAppState, id: String): PersistedAppState? {
        val source = state.ruleSources.firstOrNull { it.id == id } ?: return null
        return if (source.systemDefault) {
            upsertRuleSource(
                state,
                source.copy(
                    enabled = true,
                    updatedAt = "未更新",
                    status = RuleSourceStatus.Idle,
                    totalRules = 0,
                    convertedRules = 0,
                    skippedRules = 0,
                    lastError = null,
                ),
            )
        } else {
            state.copy(ruleSources = state.ruleSources.filterNot { it.id == id })
        }
    }

    // ---- settings ----------------------------------------------------------------

    val HeartbeatOptionsMinutes = listOf(2, 5, 10)

    /** Null when [mode] is custom and [customValue] is blank. */
    fun setDns(state: PersistedAppState, mode: AppDnsMode, customValue: String): PersistedAppState? {
        // Saved in ASCII: "8。8。8。8" typed with a Chinese input method is kept as "8.8.8.8".
        val custom = com.mallocgfw.app.model.DnsInput.normalize(customValue)
        if (mode == AppDnsMode.Custom && custom.isBlank()) return null
        return state.copy(settings = state.settings.copy(dnsMode = mode, customDnsValue = if (mode == AppDnsMode.Custom) custom else state.settings.customDnsValue))
    }

    fun setMtu(state: PersistedAppState, mtu: Int): PersistedAppState =
        state.copy(settings = state.settings.copy(vpnMtu = normalizedAppVpnMtu(mtu)))

    fun setProxyQuic(state: PersistedAppState, enabled: Boolean): PersistedAppState =
        state.copy(settings = state.settings.copy(proxyQuicEnabled = enabled))

    fun setHeartbeat(state: PersistedAppState, minutes: Int): PersistedAppState =
        state.copy(settings = state.settings.copy(heartbeatIntervalMinutes = minutes.takeIf { it in HeartbeatOptionsMinutes } ?: 5))

    fun setGlobalProxy(state: PersistedAppState, enabled: Boolean): PersistedAppState =
        state.copy(settings = state.settings.copy(globalProxyEnabled = enabled))

    fun setGeoRoutingRegion(state: PersistedAppState, region: AppGeoRoutingRegion): PersistedAppState =
        state.copy(
            settings = state.settings.copy(geoRoutingRegion = region),
            // The built-in home-sites list is named after the region.
            ruleSources = RuleSourceManager.ensureDefaults(state.ruleSources, region),
        )

    /** Changes the language; a direct-connect region still on the old language's default follows it. */
    fun setLanguage(state: PersistedAppState, language: AppLanguage): PersistedAppState {
        val settings = state.settings
        val region = if (settings.geoRoutingRegion == AppGeoRoutingRegion.defaultForLanguage(settings.language)) {
            AppGeoRoutingRegion.defaultForLanguage(language)
        } else {
            settings.geoRoutingRegion
        }
        return setGeoRoutingRegion(state.copy(settings = settings.copy(language = language)), region)
    }

    fun setProxyMode(state: PersistedAppState, mode: ProxyMode): PersistedAppState = state.copy(proxyMode = mode)

    /**
     * One app's route in per-app mode: [enabled] false means not proxied;
     * otherwise [serverId] is its node, blank for the selected node.
     */
    fun setProtectedAppRoute(state: PersistedAppState, appId: String, enabled: Boolean, serverId: String): PersistedAppState =
        state.copy(
            protectedApps = state.protectedApps.map { app ->
                if (app.id == appId) app.copy(enabled = enabled, serverId = if (enabled) serverId else app.serverId) else app
            },
        )

    fun setProtectedApps(state: PersistedAppState, changes: Map<String, Boolean>): PersistedAppState {
        val apps: List<ProtectedApp> = state.protectedApps.map { app ->
            changes[app.id]?.let { app.copy(enabled = it) } ?: app
        }
        return state.copy(protectedApps = apps)
    }

    fun setStreamingRoutingEnabled(state: PersistedAppState, enabled: Boolean): PersistedAppState =
        state.copy(settings = state.settings.copy(streamingRoutingEnabled = enabled))

    // ---- Tailscale ---------------------------------------------------------------

    fun setTailscaleEnabled(state: PersistedAppState, enabled: Boolean): PersistedAppState =
        state.copy(
            settings = state.settings.copy(
                tailscaleEnabled = enabled,
                tailscaleExitNodeId = if (enabled) state.settings.tailscaleExitNodeId else "",
            ),
        )

    fun setTailscaleExitNode(state: PersistedAppState, nodeId: String): PersistedAppState =
        state.copy(settings = state.settings.copy(tailscaleExitNodeId = nodeId.trim()))

    /** Keeps only valid private subnets, canonical and without duplicates. */
    fun setTailscaleSubnets(state: PersistedAppState, routes: List<String>): PersistedAppState =
        state.copy(settings = state.settings.copy(tailscaleSubnetRoutes = routes.mapNotNull(::normalizePrivateTailscaleSubnet).distinct()))

    private fun nodeNotFound(nodeId: String) =
        tr("找不到节点 $nodeId。", "Node $nodeId not found.", "Узел $nodeId не найден.")
}
