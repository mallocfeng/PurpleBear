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
        /** The node to reconnect to, when the connected node was replaced. */
        val reconnectServer: ServerNode? = null,
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
     * by protocol, address, port and name. Ids of removed nodes are not reused.
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
            val id = ImportParser.uniqueNodeId(node.id, takenIds)
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
            // id of an unrelated existing one. Settle each node's final id first
            // (existing match, or a free id), then remap links inside the batch.
            val takenIds = servers.map { it.id }.toMutableSet()
            val existingIndexes = mutableMapOf<String, Int>()
            val finalIds = mutableMapOf<String, String>()
            normalizedPreviewNodes.forEach { normalizedNode ->
                val existingNodeIndex = servers.indexOfFirst { it.isSameImportedNode(normalizedNode) }
                if (existingNodeIndex >= 0) {
                    existingIndexes[normalizedNode.id] = existingNodeIndex
                    finalIds[normalizedNode.id] = servers[existingNodeIndex].id
                } else {
                    val id = ImportParser.uniqueNodeId(normalizedNode.id, takenIds)
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
        val selectedAfter = if (selectedWasInTargetGroup && selectedBefore != null) {
            mergedServers.firstOrNull {
                it.groupId == targetGroupId &&
                    !it.hiddenUnsupported &&
                    it.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
            } ?: mergedServers.firstOrNull {
                it.groupId == targetGroupId && it.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
            } ?: mergedServers.firstOrNull { it.groupId == targetGroupId && !it.hiddenUnsupported }
                ?: mergedServers.firstOrNull { it.groupId == targetGroupId }
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

        val nextState = normalizeStreamingSelections(
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
        return when {
            selectedAfter != null && selectedBefore != null && !selectedNameStillExists -> ImportOutcome(
                state = nextState,
                selectedServerChanged = true,
                message = tr(
                    "原选中节点 ${selectedBefore.name} 已不存在，已切换到 ${selectedAfter.name}。",
                    "The selected node ${selectedBefore.name} no longer exists; switched to ${selectedAfter.name}.",
                    "Выбранного узла ${selectedBefore.name} больше нет, выбран ${selectedAfter.name}.",
                ),
                reconnectServer = if (selectedWasConnected) selectedAfter else null,
            )
            selectedAfter != null && selectedWasConnected -> ImportOutcome(state = nextState, reconnectServer = selectedAfter)
            else -> ImportOutcome(state = nextState)
        }
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
        return normalizeStreamingSelections(
            state.copy(
                servers = servers,
                recentImports = state.recentImports.filterNot { it.contains(node.name) },
                selectedServerId = if (state.selectedServerId == nodeId) servers.firstOrNull()?.id.orEmpty() else state.selectedServerId,
            ),
        )
    }

    /** Removes a subscription group and its nodes. Local groups can't be removed. */
    fun removeSubscriptionGroup(state: PersistedAppState, groupId: String): PersistedAppState? {
        val group = state.serverGroups.firstOrNull { it.id == groupId } ?: return null
        if (group.type != ServerGroupType.Subscription) return null
        val removedNodeIds = state.servers.filter { it.groupId == groupId }.map { it.id }.toSet()
        val servers = normalizeNodeLinks(state.servers.filterNot { it.groupId == groupId })
        return normalizeStreamingSelections(
            state.copy(
                serverGroups = state.serverGroups.filterNot { it.id == groupId },
                subscriptions = state.subscriptions.filterNot { it.id == groupId },
                servers = servers,
                recentImports = state.recentImports.filterNot { it.contains(group.name) },
                selectedServerId = if (state.selectedServerId in removedNodeIds) servers.firstOrNull()?.id.orEmpty() else state.selectedServerId,
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
        val custom = customValue.trim()
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
