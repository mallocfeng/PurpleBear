package com.mallocgfw.app.xray

import android.content.Context
import com.mallocgfw.app.model.AppSettings
import com.mallocgfw.app.model.AppStateStore
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
import com.mallocgfw.app.model.tr

object DiagnosticsManager {
    private const val PROXY_TEST_URL = "https://www.gstatic.com/generate_204"
    private const val NETWORK_TIMEOUT_MS = 5_000

    fun pendingSteps(): List<DiagnosticStep> {
        return listOf(
            pending("config", "配置解析"),
            pending("rules", tr("规则资源", "Rule data", "Данные правил")),
            pending("core", tr("Xray 内核", "Xray core", "Ядро Xray")),
            pending("vpn", "VPN 建立"),
            pending("dns", "DNS 解析"),
            pending("reachability", tr("节点连通性", "Node reachability", "Доступность узла")),
            pending("proxy", tr("代理出口", "Proxy exit", "Выход через прокси")),
        )
    }

    suspend fun run(
        context: Context,
        connectionStatus: ConnectionStatus,
        vpnSnapshot: VpnRuntimeSnapshot,
        xraySnapshot: XrayCoreSnapshot,
        server: ServerNode?,
        ruleSources: List<RuleSourceItem>,
        settings: AppSettings = AppStateStore.defaultSettings(),
    ): List<DiagnosticStep> = withContext(Dispatchers.IO) {
        val routingRules = if (settings.globalProxyEnabled) {
            emptyList()
        } else {
            RuleSourceManager.loadEnabledRoutingRules(context, ruleSources, settings.geoRoutingRegion)
        }
        val geoData = GeoDataManager.load(context)

        listOf(
            buildConfigStep(server, settings, ruleSources),
            buildRulesStep(ruleSources, routingRules.size, geoData, settings.globalProxyEnabled),
            buildCoreStep(xraySnapshot),
            buildVpnStep(connectionStatus, vpnSnapshot, server),
            buildDnsStep(server),
            buildReachabilityStep(context, server),
            buildProxyStep(connectionStatus, server),
        )
    }

    private fun buildConfigStep(server: ServerNode?, settings: AppSettings, ruleSources: List<RuleSourceItem>): DiagnosticStep {
        if (server == null) {
            return noNode("config", "配置解析", tr("当前没有可诊断的节点，请先导入并选择一个节点。", "No node to diagnose. Import and select a node first.", "Нет узла для диагностики. Сначала импортируйте и выберите узел."))
        }
        return runCatching {
            val config = XrayConfigFactory.buildVpn(
                server,
                globalProxyEnabled = settings.globalProxyEnabled,
                geoRoutingRegion = settings.geoRoutingRegion,
                homeSitesDirect = RuleSourceManager.homeSitesDirect(ruleSources),
                proxyQuicEnabled = settings.proxyQuicEnabled,
                fallbackDirect = settings.fallbackDirect,
            )
            JSONObject(config)
        }.fold(
            onSuccess = { config ->
                val ruleCount = config.optJSONObject("routing")?.optJSONArray("rules")?.length() ?: 0
                success(
                    "config",
                    "配置解析",
                    tr("已为 ${server.name} 生成 Xray 配置，当前附带 $ruleCount 组路由规则。", "Built the Xray config for ${server.name} with $ruleCount routing rule groups.", "Конфигурация Xray для ${server.name} создана, групп правил маршрутизации: $ruleCount."),
                )
            },
            onFailure = {
                fail(
                    "config",
                    "配置解析",
                    it.message ?: tr("无法生成当前节点的 Xray 配置。", "Couldn't build the Xray config for the current node.", "Не удалось создать конфигурацию Xray для текущего узла."),
                )
            },
        )
    }

    private fun buildRulesStep(
        ruleSources: List<RuleSourceItem>,
        routingRuleCount: Int,
        geoData: GeoDataSnapshot,
        globalProxyEnabled: Boolean,
    ): DiagnosticStep {
        // A failed refresh keeps the last good list in use, so count what has rules.
        val readySources = ruleSources.filter { it.enabled && (it.status == RuleSourceStatus.Ready || it.convertedRules > 0) }
        val failedSources = ruleSources.filter { it.enabled && (it.status == RuleSourceStatus.FetchFailed || it.status == RuleSourceStatus.ParseFailed) }
        val geoLabel = if (geoData.status == GeoDataStatus.Ready) {
            tr("Geo 数据已更新", "Geo data updated", "Данные Geo обновлены")
        } else {
            tr("使用内置 Geo 数据", "Using built-in Geo data", "Используются встроенные данные Geo")
        }
        val detail = buildString {
            append(geoLabel)
            if (globalProxyEnabled) {
                append(tr("，全局代理已开启，规则源和 Geo 直连规则暂不参与路由；局域网/私网仍直连。", ". Global proxy is on, so rule sources and Geo direct rules aren't used for routing; LAN and private addresses still go direct.", ". Глобальный прокси включён: источники правил и прямые правила Geo не участвуют в маршрутизации, локальная и частная сеть по-прежнему идут напрямую."))
            } else {
                append(tr("，已启用 ${readySources.size} 个规则源，转换后的自定义路由 ${routingRuleCount} 组。", ". ${readySources.size} rule sources on, ${routingRuleCount} custom route groups after conversion.", ". Включено источников правил: ${readySources.size}, групп пользовательских маршрутов после преобразования: ${routingRuleCount}."))
            }
            if (failedSources.isNotEmpty()) {
                append(tr(" 另有 ${failedSources.size} 个规则源未就绪。", " ${failedSources.size} more rule sources aren't ready.", " Ещё не готово источников правил: ${failedSources.size}."))
            }
        }
        return success("rules", tr("规则资源", "Rule data", "Данные правил"), detail)
    }

    private fun buildCoreStep(snapshot: XrayCoreSnapshot): DiagnosticStep {
        return when (snapshot.status) {
            XrayCoreStatus.Running, XrayCoreStatus.Ready -> success(
                "core",
                tr("Xray 内核", "Xray core", "Ядро Xray"),
                snapshot.message ?: tr("官方 Xray 内核已就绪。", "The official Xray core is ready.", "Официальное ядро Xray готово."),
            )
            XrayCoreStatus.Starting, XrayCoreStatus.Preparing -> pending(
                "core",
                tr("Xray 内核", "Xray core", "Ядро Xray"),
                snapshot.message ?: tr("Xray 正在准备中。", "Xray is getting ready.", "Xray готовится."),
            )
            XrayCoreStatus.Failed -> fail(
                "core",
                tr("Xray 内核", "Xray core", "Ядро Xray"),
                snapshot.message ?: tr("Xray 内核启动失败。", "The Xray core failed to start.", "Не удалось запустить ядро Xray."),
            )
            XrayCoreStatus.Idle -> fail(
                "core",
                tr("Xray 内核", "Xray core", "Ядро Xray"),
                tr("内核尚未初始化完成。", "The core hasn't finished initializing.", "Ядро ещё не инициализировано."),
            )
        }
    }

    private fun buildVpnStep(
        connectionStatus: ConnectionStatus,
        snapshot: VpnRuntimeSnapshot,
        server: ServerNode?,
    ): DiagnosticStep {
        if (server == null && connectionStatus == ConnectionStatus.Disconnected) {
            return noNode("vpn", "VPN 建立", tr("还没有节点，导入节点后才能连接。", "No node yet; import one to connect.", "Узлов пока нет; импортируйте узел, чтобы подключиться."))
        }
        return when (connectionStatus) {
            ConnectionStatus.Connected -> success(
                "vpn",
                "VPN 建立",
                tr("系统 VPN 已建立${server?.name?.let { "，当前节点 $it" } ?: ""}。", "System VPN is up${server?.name?.let { ", current node $it" } ?: ""}.", "Системный VPN установлен${server?.name?.let { ", текущий узел $it" } ?: ""}."),
            )
            ConnectionStatus.Connecting, ConnectionStatus.Disconnecting -> pending(
                "vpn",
                "VPN 建立",
                snapshot.message ?: tr("系统 VPN 状态正在变化中。", "The system VPN state is changing.", "Состояние системного VPN меняется."),
            )
            ConnectionStatus.Disconnected -> fail(
                "vpn",
                "VPN 建立",
                snapshot.message ?: tr("当前未建立系统 VPN。", "The system VPN isn't up.", "Системный VPN не установлен."),
            )
        }
    }

    private fun buildDnsStep(server: ServerNode?): DiagnosticStep {
        if (server == null) {
            return noNode("dns", "DNS 解析", tr("当前没有可检测的节点域名。", "No node domain to check.", "Нет домена узла для проверки."))
        }
        if (!looksLikeHostname(server.address)) {
            return success("dns", "DNS 解析", tr("当前节点使用 IP 地址，无需额外 DNS 解析。", "The current node uses an IP address; no DNS lookup needed.", "Текущий узел использует IP-адрес, DNS-запрос не нужен."))
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
                    tr("已解析 ${server.address}，返回 ${results.size} 个结果，耗时 ${elapsedMs}ms。", "Resolved ${server.address}: ${results.size} results, ${elapsedMs}ms.", "${server.address} разрешён: результатов ${results.size}, ${elapsedMs} мс."),
                )
            },
            onFailure = {
                fail(
                    "dns",
                    "DNS 解析",
                    it.message ?: tr("当前节点域名解析失败。", "Couldn't resolve the current node's domain.", "Не удалось разрешить домен текущего узла."),
                )
            },
        )
    }

    private suspend fun buildReachabilityStep(context: Context, server: ServerNode?): DiagnosticStep {
        if (server == null) {
            return noNode("reachability", tr("节点连通性", "Node reachability", "Доступность узла"), tr("当前没有可检测的节点。", "No node to check.", "Нет узла для проверки."))
        }
        return runCatching {
            NodeLatencyTester.measure(context, server)
        }.fold(
            onSuccess = { latency ->
                val message = if (NodeLatencyTester.requiresCoreProbe(server)) {
                    tr("已完成节点真实链路探测，${server.name} 可用，耗时 ${latency}ms。", "Real path probe done: ${server.name} is reachable, ${latency}ms.", "Проверка реального соединения завершена: ${server.name} доступен, ${latency} мс.")
                } else {
                    tr("已完成节点 TCP 直连探测，${server.address}:${server.port} 可达，耗时 ${latency}ms。", "Direct TCP probe done: ${server.address}:${server.port} is reachable, ${latency}ms.", "Прямая TCP-проверка завершена: ${server.address}:${server.port} доступен, ${latency} мс.")
                }
                success(
                    "reachability",
                    tr("节点连通性", "Node reachability", "Доступность узла"),
                    message,
                )
            },
            onFailure = {
                val fallbackMessage = if (NodeLatencyTester.requiresCoreProbe(server)) {
                    tr("当前节点真实链路探测失败。", "The real path probe to the current node failed.", "Проверка реального соединения с текущим узлом не удалась.")
                } else {
                    tr("无法建立到节点的直连 TCP 连接。", "Couldn't open a direct TCP connection to the node.", "Не удалось установить прямое TCP-соединение с узлом.")
                }
                fail(
                    "reachability",
                    tr("节点连通性", "Node reachability", "Доступность узла"),
                    it.message ?: fallbackMessage,
                )
            },
        )
    }

    private fun buildProxyStep(connectionStatus: ConnectionStatus, server: ServerNode?): DiagnosticStep {
        if (server == null && connectionStatus != ConnectionStatus.Connected) {
            return noNode("proxy", tr("代理出口", "Proxy exit", "Выход через прокси"), tr("还没有节点，暂不检测代理出口。", "No node yet, so the proxy exit isn't checked.", "Узлов пока нет, поэтому выход через прокси не проверяется."))
        }
        if (connectionStatus != ConnectionStatus.Connected) {
            return fail("proxy", tr("代理出口", "Proxy exit", "Выход через прокси"), tr("当前未连接 VPN，无法验证代理出口链路。", "VPN isn't connected, so the proxy exit can't be checked.", "VPN не подключён, поэтому выход через прокси проверить нельзя."))
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
                        tr("代理出口", "Proxy exit", "Выход через прокси"),
                        tr("通过当前 VPN 链路访问外网成功，HTTP $code，耗时 ${elapsedMs}ms。", "Reached the internet through the current VPN: HTTP $code, ${elapsedMs}ms.", "Доступ в интернет через текущий VPN есть: HTTP $code, ${elapsedMs} мс."),
                    )
                } else {
                    fail(
                        "proxy",
                        tr("代理出口", "Proxy exit", "Выход через прокси"),
                        tr("外网探测返回 HTTP $code。", "The internet probe returned HTTP $code.", "Проверка доступа в интернет вернула HTTP $code."),
                    )
                }
            },
            onFailure = {
                fail(
                    "proxy",
                    tr("代理出口", "Proxy exit", "Выход через прокси"),
                    it.message ?: tr("无法通过当前代理链路访问外网。", "Couldn't reach the internet through the current proxy.", "Не удалось выйти в интернет через текущий прокси."),
                )
            },
        )
    }

    private fun pending(key: String, title: String, detail: String = tr("正在等待检测…", "Waiting to check…", "Ожидание проверки…")): DiagnosticStep {
        return DiagnosticStep(key = key, title = title, detail = detail, status = DiagnosticStatus.Pending)
    }

    private fun success(key: String, title: String, detail: String): DiagnosticStep {
        return DiagnosticStep(key = key, title = title, detail = detail, status = DiagnosticStatus.Success)
    }

    private fun noNode(key: String, title: String, detail: String): DiagnosticStep {
        return DiagnosticStep(key = key, title = title, detail = detail, status = DiagnosticStatus.NoNode)
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
