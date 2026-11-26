package com.mallocgfw.app.mcp

import com.mallocgfw.app.core.RuleAnalysisReport
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.InactiveReason
import com.mallocgfw.app.model.RouteTarget
import com.mallocgfw.app.model.RuleEntryResult
import com.mallocgfw.app.model.RuleEntryStatus
import com.mallocgfw.app.model.RuleLayer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The rule analysis ([RuleAnalysisReport]) as JSON for the analyze_rules MCP
 * tool: the matching order, what each active rule group decides, what higher
 * rules take from it, and why the inactive ones don't count.
 */
internal object RuleAnalysisJson {
    /** Layers in matching order, first match wins. */
    val MATCHING_ORDER = listOf(
        "top_priority", "per_app", "custom", "media", "default", "lan_direct", "region_direct", "fallback",
    )

    fun build(report: RuleAnalysisReport): JSONObject {
        val active = report.active
        val inactive = report.inactive
        return JSONObject()
            .put("routing_mode", if (report.globalMode) "global" else "rule")
            .put("per_app_mode", report.perAppMode)
            .put("main_node", report.mainNodeName?.let(Redactor::name) ?: JSONObject.NULL)
            .put("direct_region", regionKey(report.region))
            .put("region_sites_direct", report.homeSitesDirect && !report.globalMode)
            .put("foreign_quic_blocked", report.quicBlocked)
            .put("tailscale", report.tailscaleEnabled)
            .put("fallback", targetJson(report.fallback))
            .put("matching_order", JSONArray(MATCHING_ORDER))
            .put(
                "summary",
                JSONObject()
                    .put("in_effect", active.count { it.status == RuleEntryStatus.Effective || it.status == RuleEntryStatus.Redundant })
                    .put("overridden", active.count { it.status == RuleEntryStatus.Partial || it.status == RuleEntryStatus.Overridden })
                    .put("inactive", inactive.size),
            )
            .put("active", JSONArray().apply { active.forEach { put(activeJson(it, report)) } })
            .put("inactive", JSONArray().apply { inactive.forEach { put(inactiveJson(it)) } })
            .put(
                "notes",
                "Domains are compared with domains and IP ranges with IP ranges; which IP a domain resolves to isn't known. " +
                    "Regex entries can't be checked for overlap. Based on saved settings and downloaded lists: changes apply after a reconnect.",
            )
    }

    fun summaryText(report: RuleAnalysisReport): String {
        val active = report.active
        val overridden = active.count { it.status == RuleEntryStatus.Partial || it.status == RuleEntryStatus.Overridden }
        return "${active.size} rule groups active ($overridden partly or fully overridden), ${report.inactive.size} not in effect."
    }

    private fun activeJson(entry: RuleEntryResult, report: RuleAnalysisReport): JSONObject {
        val json = JSONObject()
            .put("order", entry.order)
            .put("id", entry.input.id)
            .put("layer", layerKey(entry.input.layer))
            .put("name", entryName(entry))
            .put("status", statusKey(entry.status))
            .put("entries", entry.matcherCount)
            .put("in_effect", entry.effectiveCount)
            .put("overridden", entry.overriddenCount)
            .put("duplicates", entry.redundantCount)
            .put("unchecked_regex", entry.unanalyzedCount)
            .put(
                "targets",
                JSONArray().apply {
                    entry.effectiveTargets.forEach { (target, count) -> put(targetJson(target).put("count", count)) }
                },
            )
            .put(
                "overridden_by",
                JSONArray().apply {
                    entry.lostTo.forEach { conflict ->
                        put(
                            JSONObject()
                                .put("id", conflict.winnerId)
                                .put("order", report.entries.firstOrNull { it.input.id == conflict.winnerId }?.order ?: 0)
                                .put("name", Redactor.name(conflict.winnerName))
                                .put("layer", layerKey(conflict.winnerLayer))
                                .put("goes", targetJson(conflict.winnerTarget))
                                .put("instead_of", targetJson(conflict.loserTarget))
                                .put("count", conflict.count)
                                .put("examples", JSONArray(conflict.examples)),
                        )
                    }
                },
            )
        if (entry.input.apps.isNotEmpty()) json.put("apps", JSONArray(entry.input.apps))
        if (entry.input.missingNodes.isNotEmpty()) json.put("missing_nodes", JSONArray(entry.input.missingNodes.map(Redactor::name)))
        return json
    }

    private fun inactiveJson(entry: RuleEntryResult) = JSONObject()
        .put("id", entry.input.id)
        .put("layer", layerKey(entry.input.layer))
        .put("name", entryName(entry))
        .put("reason", reasonKey(entry.input.inactiveReason))

    private fun entryName(entry: RuleEntryResult): String = when (entry.input.layer) {
        RuleLayer.Top -> "top-priority rules"
        RuleLayer.PerApp -> Redactor.name(entry.input.name.ifBlank { "deleted node" })
        else -> Redactor.name(entry.input.name)
    }

    fun targetJson(target: RouteTarget): JSONObject {
        val json = JSONObject().put(
            "target",
            when (target.kind) {
                RouteTarget.Kind.Direct -> "direct"
                RouteTarget.Kind.Proxy -> "proxy"
                RouteTarget.Kind.Node -> "node"
                RouteTarget.Kind.Block -> "block"
                RouteTarget.Kind.Tailscale -> "tailscale"
            },
        )
        target.nodeName?.let { json.put("node", Redactor.name(it)) }
        return json
    }

    fun layerKey(layer: RuleLayer): String = when (layer) {
        RuleLayer.Top -> "top_priority"
        RuleLayer.PerApp -> "per_app"
        RuleLayer.Custom -> "custom"
        RuleLayer.Streaming -> "media"
        RuleLayer.Default -> "default"
    }

    private fun statusKey(status: RuleEntryStatus): String = when (status) {
        RuleEntryStatus.Effective -> "in_effect"
        RuleEntryStatus.Partial -> "partly_overridden"
        RuleEntryStatus.Overridden -> "overridden"
        RuleEntryStatus.Redundant -> "duplicate"
        RuleEntryStatus.Inactive -> "inactive"
    }

    private fun reasonKey(reason: InactiveReason?): String = when (reason) {
        InactiveReason.Disabled -> "switched_off"
        InactiveReason.GlobalMode -> "skipped_in_global_mode"
        InactiveReason.NotDownloaded -> "list_not_downloaded"
        InactiveReason.NodeMissing -> "node_missing"
        InactiveReason.NotPerAppMode -> "per_app_mode_off"
        InactiveReason.ReplacedByGeoData -> "replaced_by_geo_data"
        InactiveReason.StreamingOff -> "media_routing_off"
        null -> "unknown"
    }

    private fun regionKey(region: AppGeoRoutingRegion): String = when (region) {
        AppGeoRoutingRegion.China -> "china"
        AppGeoRoutingRegion.English -> "us"
        AppGeoRoutingRegion.Russia -> "russia"
    }
}
