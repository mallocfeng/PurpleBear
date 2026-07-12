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
)
