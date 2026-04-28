package com.mallocgfw.app.xray

import android.content.Context
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.DiagnosticStatus
import com.mallocgfw.app.model.DiagnosticStep
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceStatus
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.ServerNode
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object DiagnosticsManager {
    private const val PROXY_TEST_URL = "https://www.gstatic.com/generate_204"
    private const val NETWORK_TIMEOUT_MS = 5_000

    fun pendingSteps(): List<DiagnosticStep> {
        return listOf(
            pending("config", "配置解析"),
            pending("rules", "规则资源"),
            pending("core", "Xray 内核"),
            pending("vpn", "VPN 建立"),
            pending("dns", "DNS 解析"),
            pending("reachability", "节点连通性"),
            pending("proxy", "代理出口"),
        )
    }

    suspend fun run(
        context: Context,
        connectionStatus: ConnectionStatus,
        vpnSnapshot: VpnRuntimeSnapshot,
        xraySnapshot: XrayCoreSnapshot,
        server: ServerNode?,
        ruleSources: List<RuleSourceItem>,
    ): List<DiagnosticStep> = withContext(Dispatchers.IO) {
        val routingRules = RuleSourceManager.loadEnabledRoutingRules(context, ruleSources)
        val geoData = GeoDataManager.load(context)

        listOf(
            buildConfigStep(server, routingRules.size),
            buildRulesStep(ruleSources, routingRules.size, geoData),
            buildCoreStep(xraySnapshot),
            buildVpnStep(connectionStatus, vpnSnapshot, server),
            buildDnsStep(server),
            buildReachabilityStep(context, server),
            buildProxyStep(connectionStatus),
        )
    }

    private fun buildConfigStep(server: ServerNode?, routingRuleCount: Int): DiagnosticStep {
        if (server == null) {
            return fail("config", "配置解析", "当前没有可诊断的节点，请先导入并选择一个节点。")
        }
        return runCatching {
            val config = XrayConfigFactory.buildVpn(server)
            JSONObject(config)
        }.fold(
            onSuccess = {
                success(
                    "config",
                    "配置解析",
                    "已为 ${server.name} 生成 Xray 配置，当前附带 ${routingRuleCount + 3} 组路由规则。",
                )
            },
            onFailure = {
                fail(
                    "config",
                    "配置解析",
                    it.message ?: "无法生成当前节点的 Xray 配置。",
                )
            },
        )
    }

    private fun buildRulesStep(
        ruleSources: List<RuleSourceItem>,
        routingRuleCount: Int,
        geoData: GeoDataSnapshot,
    ): DiagnosticStep {
        val readySources = ruleSources.filter { it.enabled && it.status == RuleSourceStatus.Ready }
        val failedSources = ruleSources.filter { it.enabled && (it.status == RuleSourceStatus.FetchFailed || it.status == RuleSourceStatus.ParseFailed) }
        val geoLabel = if (geoData.status == GeoDataStatus.Ready) {
            "Geo 数据已更新"
        } else {
            "使用内置 Geo 数据"
        }
        val detail = buildString {
            append(geoLabel)
            append("，已启用 ${readySources.size} 个规则源，转换后的自定义路由 ${routingRuleCount} 组。")
            if (failedSources.isNotEmpty()) {
                append(" 另有 ${failedSources.size} 个规则源未就绪。")
            }
        }
        return success("rules", "规则资源", detail)
    }

    private fun buildCoreStep(snapshot: XrayCoreSnapshot): DiagnosticStep {
        return when (snapshot.status) {
            XrayCoreStatus.Running, XrayCoreStatus.Ready -> success(
                "core",
                "Xray 内核",
                snapshot.message ?: "官方 Xray 内核已就绪。",
            )
            XrayCoreStatus.Starting, XrayCoreStatus.Preparing -> pending(
                "core",
                "Xray 内核",
                snapshot.message ?: "Xray 正在准备中。",
            )
            XrayCoreStatus.Failed -> fail(
                "core",
                "Xray 内核",
                snapshot.message ?: "Xray 内核启动失败。",
            )
            XrayCoreStatus.Idle -> fail(
                "core",
                "Xray 内核",
                "内核尚未初始化完成。",
            )
        }
    }

    private fun buildVpnStep(
        connectionStatus: ConnectionStatus,
        snapshot: VpnRuntimeSnapshot,
        server: ServerNode?,
    ): DiagnosticStep {
        return when (connectionStatus) {
            ConnectionStatus.Connected -> success(
                "vpn",
                "VPN 建立",
                "系统 VPN 已建立${server?.name?.let { "，当前节点 $it" } ?: ""}。",
            )
            ConnectionStatus.Connecting, ConnectionStatus.Disconnecting -> pending(
                "vpn",
                "VPN 建立",
                snapshot.message ?: "系统 VPN 状态正在变化中。",
            )
            ConnectionStatus.Disconnected -> fail(
                "vpn",
                "VPN 建立",
                snapshot.message ?: "当前未建立系统 VPN。",
            )
        }
    }

    private fun buildDnsStep(server: ServerNode?): DiagnosticStep {
        if (server == null) {
            return fail("dns", "DNS 解析", "当前没有可检测的节点域名。")
        }
        if (!looksLikeHostname(server.address)) {
            return success("dns", "DNS 解析", "当前节点使用 IP 地址，无需额外 DNS 解析。")
        }
        val startedAt = System.nanoTime()
        return runCatching {
            InetAddress.getAllByName(server.address)
        }.fold(
            onSuccess = { results ->
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                success(
                    "dns",
                    "DNS 解析",
                    "已解析 ${server.address}，返回 ${results.size} 个结果，耗时 ${elapsedMs}ms。",
                )
            },
            onFailure = {
                fail(
                    "dns",
                    "DNS 解析",
                    it.message ?: "当前节点域名解析失败。",
                )
            },
        )
    }

    private suspend fun buildReachabilityStep(context: Context, server: ServerNode?): DiagnosticStep {
        if (server == null) {
            return fail("reachability", "节点连通性", "当前没有可检测的节点。")
        }
        return runCatching {
            NodeLatencyTester.measure(context, server)
        }.fold(
            onSuccess = { latency ->
                val message = if (NodeLatencyTester.requiresCoreProbe(server)) {
                    "已完成节点真实链路探测，${server.name} 可用，耗时 ${latency}ms。"
                } else {
                    "已完成节点 TCP 直连探测，${server.address}:${server.port} 可达，耗时 ${latency}ms。"
                }
                success(
                    "reachability",
                    "节点连通性",
                    message,
                )
            },
            onFailure = {
                val fallbackMessage = if (NodeLatencyTester.requiresCoreProbe(server)) {
                    "当前节点真实链路探测失败。"
                } else {
                    "无法建立到节点的直连 TCP 连接。"
                }
                fail(
                    "reachability",
                    "节点连通性",
                    it.message ?: fallbackMessage,
                )
            },
        )
    }

    private fun buildProxyStep(connectionStatus: ConnectionStatus): DiagnosticStep {
        if (connectionStatus != ConnectionStatus.Connected) {
            return fail("proxy", "代理出口", "当前未连接 VPN，无法验证代理出口链路。")
        }
        val startedAt = System.nanoTime()
        return runCatching {
            val connection = (URL(PROXY_TEST_URL).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            connection.useResponse()
        }.fold(
            onSuccess = { code ->
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                if (code in 200..299) {
                    success(
                        "proxy",
                        "代理出口",
                        "通过当前 VPN 链路访问外网成功，HTTP $code，耗时 ${elapsedMs}ms。",
                    )
                } else {
                    fail(
                        "proxy",
                        "代理出口",
                        "外网探测返回 HTTP $code。",
                    )
                }
            },
            onFailure = {
                fail(
                    "proxy",
                    "代理出口",
                    it.message ?: "无法通过当前代理链路访问外网。",
                )
            },
        )
    }

    private fun pending(key: String, title: String, detail: String = "正在等待检测…"): DiagnosticStep {
        return DiagnosticStep(key = key, title = title, detail = detail, status = DiagnosticStatus.Pending)
    }

    private fun success(key: String, title: String, detail: String): DiagnosticStep {
        return DiagnosticStep(key = key, title = title, detail = detail, status = DiagnosticStatus.Success)
    }

    private fun fail(key: String, title: String, detail: String): DiagnosticStep {
        return DiagnosticStep(key = key, title = title, detail = detail, status = DiagnosticStatus.Failed)
    }

    private fun looksLikeHostname(address: String): Boolean {
        return address.any { it.isLetter() }
    }

    private fun HttpsURLConnection.useResponse(): Int {
        return try {
            responseCode
        } finally {
            disconnect()
        }
    }
}
