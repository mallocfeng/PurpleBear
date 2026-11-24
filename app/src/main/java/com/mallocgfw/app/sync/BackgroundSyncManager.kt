package com.mallocgfw.app.sync

import android.content.Context
import com.mallocgfw.app.core.StateOps
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.CompiledRuleSet
import com.mallocgfw.app.model.ImportPreview
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.SubscriptionItem
import com.mallocgfw.app.xray.AppLogManager
import com.mallocgfw.app.xray.GeoDataManager
import com.mallocgfw.app.xray.GeoDataSnapshot
import com.mallocgfw.app.model.tr

/**
 * What a sync fetched. The network part runs on a snapshot and can take a
 * while; [applyTo] then merges only what it touched into the state as it is
 * when the sync ends, so changes made meanwhile (a deleted subscription, a new
 * import, favorites, per-app settings) are kept.
 */
class SyncReport(
    private val subscriptions: List<SubscriptionFetch>,
    private val ruleSources: List<RuleSourceFetch>,
    val geoDataSnapshot: GeoDataSnapshot,
    val refreshedSubscriptions: Int,
    val refreshedRuleSources: Int,
    val refreshedStreamingRules: Int,
    val failedItems: Int,
    val summary: String,
) {
    /** A subscription as it was fetched: the parsed preview, or null when the fetch failed. */
    class SubscriptionFetch(val subscription: SubscriptionItem, val preview: ImportPreview?, val fetchStartedAtMs: Long)

    /**
     * A rule source as it was when fetched, the refreshed (or failed) result,
     * and the compiled list, not yet written to the cache.
     */
    class RuleSourceFetch(val original: RuleSourceItem, val result: RuleSourceItem, val compiled: CompiledRuleSet?)

    /**
     * [commitRules] writes the compiled list of each rule source that is merged
     * (RuleSourceManager.commitCompiled). It runs inside the state edit, so a
     * source edited meanwhile neither gets the old metadata nor the old list.
     */
    fun applyTo(
        state: PersistedAppState,
        commitRules: (RuleSourceItem, CompiledRuleSet) -> Unit = { _, _ -> },
    ): PersistedAppState {
        var next = state
        subscriptions.forEach { fetch ->
            val preview = fetch.preview
            next = if (preview == null) {
                StateOps.markSubscriptionRefreshFailed(next, fetch.subscription.id, fetch.subscription.sourceUrl, fetch.fetchStartedAtMs)
            } else {
                // Deleted, re-pointed or refreshed again meanwhile: skipped.
                StateOps.applySubscriptionRefresh(
                    next, fetch.subscription.id, fetch.subscription.sourceUrl, preview, fetch.fetchStartedAtMs, connectedServerId = null,
                )?.state
            } ?: next
        }
        ruleSources.forEach { fetch ->
            val current = next.ruleSources.firstOrNull { it.id == fetch.original.id } ?: return@forEach
            // Edited during the sync: the user's edit wins.
            if (!current.sameDefinitionAs(fetch.original)) return@forEach
            fetch.compiled?.let { commitRules(fetch.original, it) }
            next = next.copy(
                ruleSources = next.ruleSources.map { if (it.id == current.id) fetch.result.copy(enabled = it.enabled) else it },
            )
        }
        return next
    }

    private fun RuleSourceItem.sameDefinitionAs(other: RuleSourceItem): Boolean =
        url == other.url && content == other.content && type == other.type &&
            policy == other.policy && sourceKind == other.sourceKind && name == other.name
}

object BackgroundSyncManager {
    /** Fetches subscriptions, rule sources, Geo data and media rules. Apply the result with [SyncReport.applyTo]. */
    suspend fun syncAll(
        context: Context,
        state: PersistedAppState,
    ): SyncReport {
        AppLogManager.append(context, "SYNC", "开始同步订阅、规则和 Geo 资源")
        var refreshedSubscriptions = 0
        var refreshedRuleSources = 0
        var failedItems = 0

        val subscriptionFetches = state.subscriptions
            .filter { it.autoUpdate && !it.sourceUrl.isNullOrBlank() }
            .map { subscription ->
                val startedAtMs = System.currentTimeMillis()
                val preview = runCatching { ImportParser.buildPreview(subscription.sourceUrl.orEmpty()) }
                    .onSuccess {
                        refreshedSubscriptions += 1
                        AppLogManager.append(context, "SYNC", "订阅已同步：${subscription.name}")
                    }
                    .onFailure { error ->
                        failedItems += 1
                        AppLogManager.append(context, "SYNC", "订阅同步失败：${subscription.name}", error)
                    }
                    .getOrNull()
                SyncReport.SubscriptionFetch(subscription, preview, startedAtMs)
            }

        val ruleFetches = state.ruleSources
            .filter { it.enabled }
            .map { source ->
                // Not written to the cache yet: SyncReport.applyTo decides.
                runCatching { RuleSourceManager.refreshSource(context, source, commit = false) }
                    .onSuccess {
                        refreshedRuleSources += 1
                        AppLogManager.append(context, "SYNC", "规则已同步：${source.name}")
                    }
                    .fold(
                        onSuccess = { SyncReport.RuleSourceFetch(source, it.source, it.compiled) },
                        onFailure = { error ->
                            failedItems += 1
                            AppLogManager.append(context, "SYNC", "规则同步失败：${source.name}", error)
                            SyncReport.RuleSourceFetch(source, RuleSourceManager.buildFailureState(source, error), compiled = null)
                        },
                    )
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
                lastError = error.message ?: tr("Geo 资源同步失败。", "Geo data sync failed.", "Не удалось синхронизировать данные Geo."),
            )
        }

        val refreshedStreamingRules = runCatching {
            StreamingMediaManager.refreshAll(context, state.settings)
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
            subscriptions = subscriptionFetches,
            ruleSources = ruleFetches,
            geoDataSnapshot = geoSnapshot,
            refreshedSubscriptions = refreshedSubscriptions,
            refreshedRuleSources = refreshedRuleSources,
            refreshedStreamingRules = refreshedStreamingRules,
            failedItems = failedItems,
            summary = summary,
        )
    }

    private fun buildSummary(
        refreshedSubscriptions: Int,
        refreshedRuleSources: Int,
        refreshedStreamingRules: Int,
        failedItems: Int,
    ): String {
        val base = tr(
            "后台同步完成，订阅 $refreshedSubscriptions 项，规则 $refreshedRuleSources 项，流媒体规则 $refreshedStreamingRules 项",
            "Background sync done: $refreshedSubscriptions subscriptions, $refreshedRuleSources rule sources, $refreshedStreamingRules media rules",
            "Фоновая синхронизация завершена: подписок $refreshedSubscriptions, источников правил $refreshedRuleSources, правил медиа $refreshedStreamingRules",
        )
        val failed = if (failedItems > 0) tr("，失败 $failedItems 项", ", $failedItems failed", ", с ошибкой $failedItems") else ""
        return base + failed + tr("。", ".", ".")
    }
}
