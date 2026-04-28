package com.mallocgfw.app.sync

import android.content.Context
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.SubscriptionItem
import com.mallocgfw.app.model.mergedSubscriptionGroupNodes
import com.mallocgfw.app.model.subscriptionMergeKey
import com.mallocgfw.app.model.subscriptionNameKey
import com.mallocgfw.app.xray.AppLogManager
import com.mallocgfw.app.xray.GeoDataManager
import com.mallocgfw.app.xray.GeoDataSnapshot

data class SyncReport(
    val state: PersistedAppState,
    val geoDataSnapshot: GeoDataSnapshot,
    val refreshedSubscriptions: Int,
    val refreshedRuleSources: Int,
    val refreshedStreamingRules: Int,
    val failedItems: Int,
    val summary: String,
)

object BackgroundSyncManager {
    suspend fun syncAll(
        context: Context,
        state: PersistedAppState,
    ): SyncReport {
        AppLogManager.append(context, "SYNC", "开始同步订阅、规则和 Geo 资源")
        var nextState = state
        var refreshedSubscriptions = 0
        var refreshedRuleSources = 0
        var failedItems = 0

        state.subscriptions
            .filter { it.autoUpdate && !it.sourceUrl.isNullOrBlank() }
            .forEach { subscription ->
                runCatching {
                    ImportParser.buildPreview(subscription.sourceUrl.orEmpty())
                }.onSuccess { preview ->
                    nextState = applyImport(nextState, preview)
                    nextState = replaceSubscription(
                        nextState,
                        subscription.id,
                    ) { current ->
                        current.copy(
                            status = "已同步",
                            updatedAt = "刚刚",
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                    refreshedSubscriptions += 1
                    AppLogManager.append(context, "SYNC", "订阅已同步：${subscription.name}")
                }.onFailure { error ->
                    nextState = replaceSubscription(
                        nextState,
                        subscription.id,
                    ) { current ->
                        current.copy(status = "刷新失败")
                    }
                    failedItems += 1
                    AppLogManager.append(context, "SYNC", "订阅同步失败：${subscription.name}", error)
                }
            }

        nextState.ruleSources
            .filter { it.enabled }
            .forEach { source ->
                runCatching {
                    RuleSourceManager.refreshSource(context, source)
                }.onSuccess { result ->
                    nextState = replaceRuleSource(nextState, result.source)
                    refreshedRuleSources += 1
                    AppLogManager.append(context, "SYNC", "规则已同步：${source.name}")
                }.onFailure { error ->
                    nextState = replaceRuleSource(
                        nextState,
                        RuleSourceManager.buildFailureState(source, error),
                    )
                    failedItems += 1
                    AppLogManager.append(context, "SYNC", "规则同步失败：${source.name}", error)
                }
            }

        val geoSnapshot = runCatching {
            GeoDataManager.refresh(context)
        }.onSuccess {
            AppLogManager.append(context, "SYNC", "Geo 资源已同步")
        }.onFailure { error ->
            failedItems += 1
            AppLogManager.append(context, "SYNC", "Geo 资源同步失败", error)
        }.getOrElse { error ->
            GeoDataManager.load(context).copy(
                status = com.mallocgfw.app.xray.GeoDataStatus.Failed,
                lastError = error.message ?: "Geo 资源同步失败。",
            )
        }

        val refreshedStreamingRules = runCatching {
            StreamingMediaManager.refreshAll(context)
        }.onSuccess {
            AppLogManager.append(context, "SYNC", "流媒体分流规则已同步")
        }.onFailure { error ->
            failedItems += 1
            AppLogManager.append(context, "SYNC", "流媒体分流规则同步失败", error)
        }.getOrDefault(0)

        val summary = buildSummary(
            refreshedSubscriptions = refreshedSubscriptions,
            refreshedRuleSources = refreshedRuleSources,
            refreshedStreamingRules = refreshedStreamingRules,
            failedItems = failedItems,
        )
        AppLogManager.append(context, "SYNC", summary)
        return SyncReport(
            state = nextState,
            geoDataSnapshot = geoSnapshot,
            refreshedSubscriptions = refreshedSubscriptions,
            refreshedRuleSources = refreshedRuleSources,
            refreshedStreamingRules = refreshedStreamingRules,
            failedItems = failedItems,
            summary = summary,
        )
    }

    private fun applyImport(
        state: PersistedAppState,
        preview: com.mallocgfw.app.model.ImportPreview,
    ): PersistedAppState {
        val serverGroups = state.serverGroups.toMutableList()
        val servers = state.servers.toMutableList()
        val subscriptions = state.subscriptions.toMutableList()
        val selectedBefore = servers.firstOrNull { it.id == state.selectedServerId }

        val existingGroupIndex = serverGroups.indexOfFirst { it.id == preview.group.id }
        val targetGroupId = preview.group.id
        val selectedWasInTargetGroup = selectedBefore?.groupId == targetGroupId
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
            val otherServers = servers.filterNot { it.groupId == targetGroupId }
            servers.clear()
            servers.addAll(otherServers)
            servers.addAll(normalizedPreviewNodes)
        } else {
            normalizedPreviewNodes.forEach { normalizedNode ->
                val existingNodeIndex = servers.indexOfFirst {
                    it.groupId == normalizedNode.groupId && (
                        it.rawUri == normalizedNode.rawUri ||
                            it.subscriptionMergeKey() == normalizedNode.subscriptionMergeKey()
                        )
                }
                if (existingNodeIndex >= 0) {
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
                    servers.add(normalizedNode)
                }
            }
        }
        val normalizedGroupServers = servers.toList().mergedSubscriptionGroupNodes(targetGroupId)
        servers.clear()
        servers.addAll(normalizedGroupServers)
        val mergedNodeCount = servers.count { it.groupId == targetGroupId && !it.hiddenUnsupported }
        val selectedAfter = if (selectedWasInTargetGroup && selectedBefore != null) {
            servers.firstOrNull {
                it.groupId == targetGroupId &&
                    !it.hiddenUnsupported &&
                    it.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
            } ?: servers.firstOrNull {
                it.groupId == targetGroupId &&
                    it.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
            } ?: servers.firstOrNull { it.groupId == targetGroupId && !it.hiddenUnsupported }
                ?: servers.firstOrNull { it.groupId == targetGroupId }
        } else {
            null
        }
        val nextSelectedServerId = selectedAfter?.id ?: state.selectedServerId

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

        return state.copy(
            serverGroups = serverGroups,
            servers = servers,
            subscriptions = subscriptions,
            selectedServerId = nextSelectedServerId,
        )
    }

    private fun replaceSubscription(
        state: PersistedAppState,
        subscriptionId: String,
        transform: (SubscriptionItem) -> SubscriptionItem,
    ): PersistedAppState {
        return state.copy(
            subscriptions = state.subscriptions.map { subscription ->
                if (subscription.id == subscriptionId) transform(subscription) else subscription
            },
        )
    }

    private fun replaceRuleSource(
        state: PersistedAppState,
        updated: RuleSourceItem,
    ): PersistedAppState {
        return state.copy(
            ruleSources = state.ruleSources.map { source ->
                if (source.id == updated.id) updated.copy(enabled = source.enabled) else source
            },
        )
    }

    private fun buildSummary(
        refreshedSubscriptions: Int,
        refreshedRuleSources: Int,
        refreshedStreamingRules: Int,
        failedItems: Int,
    ): String {
        return buildString {
            append("后台同步完成")
            append("，订阅 ")
            append(refreshedSubscriptions)
            append(" 项，规则 ")
            append(refreshedRuleSources)
            append(" 项，流媒体规则 ")
            append(refreshedStreamingRules)
            append(" 项")
            if (failedItems > 0) {
                append("，失败 ")
                append(failedItems)
                append(" 项")
            }
            append('。')
        }
    }
}
