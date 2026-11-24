package com.mallocgfw.app.xray

import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayNamedOutbound

/**
 * The nodes the running tunnel was built with: its node, the extra routed
 * outbounds (per-app, media, NODE: rule targets) and each one's pre-proxy
 * chain. Recorded when the core starts, cleared when the tunnel stops.
 *
 * Deleting or refreshing nodes asks this, not the saved settings, whether the
 * VPN still sends traffic through a removed node: a setting changed but not
 * applied yet (say a media route moved off a node) leaves the old outbound in
 * the running core.
 */
object TunnelNodes {
    private class Installed(val serverId: String, val ids: Set<String>)

    @Volatile
    private var installed: Installed? = null

    /** Null while no tunnel was recorded (disconnected, or started before this process). */
    fun current(): Set<String>? = installed?.ids

    /** The node the core was last started on. */
    fun serverId(): String? = installed?.serverId

    fun record(server: ServerNode, additionalOutbounds: List<XrayNamedOutbound>, servers: List<ServerNode>) {
        installed = Installed(server.id, collect(server, additionalOutbounds, servers))
    }

    fun clear() {
        installed = null
    }

    /** Same pre-proxy resolution the config uses (XrayConfigFactory.buildOutboundChain). */
    fun collect(server: ServerNode, additionalOutbounds: List<XrayNamedOutbound>, servers: List<ServerNode>): Set<String> {
        val result = linkedSetOf<String>()
        (listOf(server) + additionalOutbounds.map { it.node }).forEach { root ->
            var current = root
            val visited = mutableSetOf(root.id)
            result += root.id
            repeat(MAX_CHAIN) {
                val front = XrayConfigFactory.resolvePreProxyNode(current, servers, visited) ?: return@forEach
                result += front.id
                visited += front.id
                current = front
            }
        }
        return result
    }

    private const val MAX_CHAIN = 8
}
