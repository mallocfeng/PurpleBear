package com.mallocgfw.app.xray

/**
 * Runtime-only connection details for the embedded Tailscale userspace node.
 * These values are intentionally never persisted with the app preferences.
 */
data class TailscaleRouting(
    val socksHost: String,
    val socksPort: Int,
    val socksUsername: String,
    val socksPassword: String,
    val subnetRoutes: List<String> = emptyList(),
    val routeAllTraffic: Boolean = false,
    /** MagicDNS suffix of the tailnet (e.g. `tail1234.ts.net`), without dots at the ends. */
    val magicDnsSuffix: String = "",
    /** MagicDNS names of the peers and their Tailscale addresses. */
    val magicDnsHosts: Map<String, List<String>> = emptyMap(),
) {
    /** Names under the suffix can be answered inside the VPN (see XrayConfigFactory.buildVpn). */
    val resolvesMagicDns: Boolean get() = magicDnsSuffix.isNotBlank()
}
