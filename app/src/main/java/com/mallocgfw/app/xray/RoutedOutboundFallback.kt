package com.mallocgfw.app.xray

import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayNamedOutbound
import com.mallocgfw.app.model.XrayRoutingRule

/**
 * Nodes Xray can't run (Snell, TUIC, SSR …) all go into one mihomo sidecar,
 * the main node's included. A routed node (per-app, media, NODE: rule) that
 * the sidecar refuses would fail the whole start and keep the main node off
 * too. When a start fails, the VPN service tries again without those routed
 * outbounds; their traffic then follows the rule's policy (the main node).
 */
object RoutedOutboundFallback {
    /**
     * The routed outbounds that put a node into the sidecar beyond what the
     * main node (and its pre-proxy chain) needs there.
     */
    fun sidecarOutbounds(
        server: ServerNode,
        extras: List<XrayNamedOutbound>,
        servers: List<ServerNode>,
    ): List<XrayNamedOutbound> {
        val mainSidecar = XrayConfigFactory.sidecarNodesFor(server, emptyList(), servers).map { it.id }.toSet()
        return extras.filter { extra ->
            XrayConfigFactory.sidecarNodesFor(server, listOf(extra.node), servers).any { it.id !in mainSidecar }
        }
    }

    /** [rules] with the dropped outbounds' rules sent to their policy target instead (main node, direct, block). */
    fun withoutOutbounds(rules: List<XrayRoutingRule>, dropped: List<XrayNamedOutbound>): List<XrayRoutingRule> {
        val tags = dropped.map { it.tag }.toSet()
        return rules.map { if (it.outboundTag in tags) it.copy(outboundTag = null) else it }
    }
}
