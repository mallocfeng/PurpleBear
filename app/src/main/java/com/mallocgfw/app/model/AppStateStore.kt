package com.mallocgfw.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AppStateStore {
    private const val PREFS_NAME = "mallocgfw_state"
    private const val STATE_KEY = "persisted_app_state_v1"
    private val stateLock = Any()

    fun defaultSettings(): AppSettings {
        return AppSettings(
            autoConnectOnLaunch = false,
            autoReconnect = false,
            dailyAutoUpdate = false,
            showUpdateNotifications = false,
            lightThemeEnabled = false,
            onboardingCompleted = false,
            dnsMode = AppDnsMode.System,
            customDnsValue = "",
            logLevel = AppLogLevel.Warning,
            lastConnectedServerId = "",
            resumeConnectionOnLaunch = false,
            globalProxyEnabled = false,
            streamingRoutingEnabled = false,
            streamingSelections = emptyList(),
            heartbeatIntervalMinutes = 5,
            vpnMtu = DEFAULT_APP_VPN_MTU,
        )
    }

    fun load(context: Context): PersistedAppState {
        return loadWithStartupCleanup(context).state
    }

    fun loadWithStartupCleanup(context: Context): LoadedAppState {
        return synchronized(stateLock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(STATE_KEY, null) ?: return@synchronized LoadedAppState(defaults())
            val parsed = runCatching {
                parseState(JSONObject(raw))
            }.getOrElse {
                defaults()
            }
            val normalized = parsed.normalized()
            val newlyHiddenUnsupportedNodeCount = normalized.servers.count { normalizedNode ->
                normalizedNode.hiddenUnsupported &&
                    parsed.servers.none { existing ->
                        existing.id == normalizedNode.id && existing.hiddenUnsupported
                    }
            }
            if (normalized != parsed) {
                save(context, normalized)
            }
            LoadedAppState(
                state = normalized,
                newlyHiddenUnsupportedNodeCount = newlyHiddenUnsupportedNodeCount,
            )
        }
    }

    fun saveBackgroundSync(
        context: Context,
        baseState: PersistedAppState,
        syncedState: PersistedAppState,
    ): Boolean {
        return synchronized(stateLock) {
            val latest = readState(context)
            if (latest != baseState.normalized()) {
                return@synchronized false
            }
            writeState(context, syncedState)
            true
        }
    }

    fun save(context: Context, state: PersistedAppState) {
        synchronized(stateLock) {
            writeState(context, state)
        }
    }

    private fun readState(context: Context): PersistedAppState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(STATE_KEY, null) ?: return defaults()
        return runCatching {
            parseState(JSONObject(raw)).normalized()
        }.getOrDefault(defaults())
    }

    private fun writeState(context: Context, state: PersistedAppState) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(STATE_KEY, serializeState(state.normalized()).toString()).apply()
    }

    private fun defaults(): PersistedAppState {
        return PersistedAppState(
            serverGroups = FakeRepository.serverGroups,
            servers = FakeRepository.servers,
            subscriptions = FakeRepository.subscriptions,
            ruleSources = RuleSourceManager.defaultSources,
            recentImports = FakeRepository.recentImports,
            protectedApps = FakeRepository.protectedApps,
            selectedServerId = FakeRepository.servers.firstOrNull()?.id.orEmpty(),
            proxyMode = ProxyMode.Smart,
            settings = defaultSettings(),
        )
    }

    private fun PersistedAppState.normalized(): PersistedAppState {
        val localGroups = serverGroups.filter { it.type == ServerGroupType.Local }
        val canonicalLocalGroup = (
            localGroups.firstOrNull { it.id == ImportParser.LOCAL_GROUP_ID }
                ?: localGroups.firstOrNull()
                ?: ServerGroup(
                    id = ImportParser.LOCAL_GROUP_ID,
                    name = "Local",
                    type = ServerGroupType.Local,
                    updatedAt = "未导入",
                )
            ).copy(
            id = ImportParser.LOCAL_GROUP_ID,
            name = "Local",
            type = ServerGroupType.Local,
        )
        val localGroupIds = localGroups.map { it.id }.toSet()
        val ensuredGroups = buildList {
            add(canonicalLocalGroup)
            serverGroups
                .filter { it.type != ServerGroupType.Local }
                .distinctBy { it.id }
                .forEach(::add)
        }
        val validGroupIds = ensuredGroups.map { it.id }.toSet()
        val normalizedServers = servers.map { server ->
            if (server.groupId in localGroupIds) {
                server.copy(groupId = ImportParser.LOCAL_GROUP_ID, subscription = "Local")
            } else {
                server
            }
        }
        val filteredServers = normalizedServers
            .filter { server -> server.groupId in validGroupIds }
            .map { server ->
                if (server.hiddenUnsupported || !server.isUnsupportedGrpcRealityNode()) {
                    server
                } else {
                    server.copy(hiddenUnsupported = true)
                }
            }
        val visibleServers = filteredServers.filterNot { it.hiddenUnsupported }
        val visibleServerMap = visibleServers.associateBy { it.id }
        val normalizedPreProxyServers = filteredServers.map { server ->
            val normalizedPreProxyId = server.preProxyNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::supportsPreProxy) == true &&
                    ManualNodeFactory.supportsPreProxy(server)
            }.orEmpty()
            val normalizedFallbackId = server.fallbackNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::supportsPreProxy) == true &&
                    ManualNodeFactory.supportsPreProxy(server)
            }.orEmpty()
            if (normalizedPreProxyId == server.preProxyNodeId && normalizedFallbackId == server.fallbackNodeId) {
                server
            } else {
                server.copy(
                    preProxyNodeId = normalizedPreProxyId,
                    fallbackNodeId = normalizedFallbackId,
                )
            }
        }
        val normalizedVisibleServers = normalizedPreProxyServers.filterNot { it.hiddenUnsupported }
        val filteredSubscriptions = subscriptions
            .filter { subscription ->
                ensuredGroups.any { it.id == subscription.id && it.type == ServerGroupType.Subscription }
            }
            .map { subscription ->
                subscription.copy(nodes = normalizedVisibleServers.count { it.groupId == subscription.id })
            }
        val normalizedRuleSources = RuleSourceManager.ensureDefaults(ruleSources).map { source ->
            if (source.status == RuleSourceStatus.Updating && source.convertedRules == 0) {
                source.copy(status = RuleSourceStatus.Idle, lastError = null)
            } else {
                source
            }
        }
        val normalizedSelectedServerId = selectedServerId.takeIf { id ->
            normalizedVisibleServers.any { it.id == id }
        } ?: normalizedVisibleServers.firstOrNull()?.id.orEmpty()
        val normalizedProtectedApps = normalizeProtectedApps(protectedApps)
        val validServiceIds = StreamingMediaManager.services.map { it.id }.toSet()
        val normalizedStreamingSelections = settings.streamingSelections
            .filter { it.serviceId in validServiceIds }
            .mapNotNull { selection ->
                val normalizedServerId = selection.serverId.trim()
                when {
                    normalizedServerId.isBlank() -> selection.copy(serverId = "")
                    normalizedVisibleServers.any { it.id == normalizedServerId } -> selection.copy(serverId = normalizedServerId)
                    else -> null
                }
            }
            .distinctBy { it.serviceId }
        return copy(
            serverGroups = ensuredGroups,
            servers = normalizedPreProxyServers,
            subscriptions = filteredSubscriptions,
            ruleSources = normalizedRuleSources,
            recentImports = recentImports.take(5),
            protectedApps = normalizedProtectedApps,
            selectedServerId = normalizedSelectedServerId,
            settings = settings.copy(
                customDnsValue = settings.customDnsValue.trim(),
                lastConnectedServerId = settings.lastConnectedServerId.takeIf { id ->
                    normalizedVisibleServers.any { it.id == id }
                }.orEmpty(),
                streamingSelections = normalizedStreamingSelections,
            ),
        )
    }

    private fun normalizeProtectedApps(current: List<ProtectedApp>): List<ProtectedApp> {
        if (current.isEmpty()) return FakeRepository.protectedApps
        val byId = current.associateBy { it.id }
        val byName = current.associateBy { it.name.lowercase() }
        return FakeRepository.protectedApps.map { preset ->
            val existing = byId[preset.id]
                ?: byName[preset.name.lowercase()]
                ?: when (preset.name) {
                    "Telegram" -> byId["telegram"]
                    "X" -> byId["x"]
                    "YouTube" -> byId["youtube"]
                    else -> null
                }
            if (existing == null) preset else preset.copy(enabled = existing.enabled)
        }
    }

    private fun parseState(json: JSONObject): PersistedAppState {
        return PersistedAppState(
            serverGroups = json.optJSONArray("serverGroups").toServerGroups(),
            servers = json.optJSONArray("servers").toServerNodes(),
            subscriptions = json.optJSONArray("subscriptions").toSubscriptions(),
            ruleSources = json.optJSONArray("ruleSources").toRuleSources(),
            recentImports = json.optJSONArray("recentImports").toStrings(),
            protectedApps = json.optJSONArray("protectedApps").toProtectedApps(),
            selectedServerId = json.optString("selectedServerId"),
            proxyMode = json.optString("proxyMode").toEnumOrDefault(ProxyMode.Smart),
            settings = json.optJSONObject("settings").toSettings(),
        )
    }

    private fun serializeState(state: PersistedAppState): JSONObject {
        return JSONObject().apply {
            put("serverGroups", JSONArray().apply {
                state.serverGroups.forEach { group ->
                    put(JSONObject().apply {
                        put("id", group.id)
                        put("name", group.name)
                        put("type", group.type.name)
                        putOpt("sourceUrl", group.sourceUrl)
                        put("updatedAt", group.updatedAt)
                    })
                }
            })
            put("servers", JSONArray().apply {
                state.servers.forEach { server ->
                    put(JSONObject().apply {
                        put("id", server.id)
                        put("groupId", server.groupId)
                        put("name", server.name)
                        put("code", server.code)
                        put("subscription", server.subscription)
                        put("region", server.region)
                        put("latencyMs", server.latencyMs)
                        put("protocol", server.protocol)
                        put("security", server.security)
                        put("transport", server.transport)
                        put("description", server.description)
                        put("address", server.address)
                        put("port", server.port)
                        put("flow", server.flow)
                        put("stable", server.stable)
                        put("favorite", server.favorite)
                        put("rawUri", server.rawUri)
                        put("outboundJson", server.outboundJson)
                        put("hiddenUnsupported", server.hiddenUnsupported)
                        put("preProxyNodeId", server.preProxyNodeId)
                        put("fallbackNodeId", server.fallbackNodeId)
                    })
                }
            })
            put("subscriptions", JSONArray().apply {
                state.subscriptions.forEach { subscription ->
                    put(JSONObject().apply {
                        put("id", subscription.id)
                        put("name", subscription.name)
                        put("type", subscription.type)
                        put("nodes", subscription.nodes)
                        put("updatedAt", subscription.updatedAt)
                        putOpt("updatedAtMs", subscription.updatedAtMs)
                        put("nextSync", subscription.nextSync)
                        put("status", subscription.status)
                        put("autoUpdate", subscription.autoUpdate)
                        putOpt("sourceUrl", subscription.sourceUrl)
                    })
                }
            })
            put("ruleSources", JSONArray().apply {
                state.ruleSources.forEach { source ->
                    put(JSONObject().apply {
                        put("id", source.id)
                        put("name", source.name)
                        put("url", source.url)
                        put("type", source.type.name)
                        put("policy", source.policy.name)
                        put("enabled", source.enabled)
                        put("systemDefault", source.systemDefault)
                        put("updatedAt", source.updatedAt)
                        put("status", source.status.name)
                        put("totalRules", source.totalRules)
                        put("convertedRules", source.convertedRules)
                        put("skippedRules", source.skippedRules)
                        putOpt("lastError", source.lastError)
                        put("sourceKind", source.sourceKind.name)
                        put("content", source.content)
                    })
                }
            })
            put("recentImports", JSONArray().apply {
                state.recentImports.forEach { put(it) }
            })
            put("protectedApps", JSONArray().apply {
                state.protectedApps.forEach { app ->
                    put(JSONObject().apply {
                        put("id", app.id)
                        put("name", app.name)
                        put("category", app.category)
                        put("enabled", app.enabled)
                    })
                }
            })
            put("selectedServerId", state.selectedServerId)
            put("proxyMode", state.proxyMode.name)
            put("settings", JSONObject().apply {
                put("autoConnectOnLaunch", state.settings.autoConnectOnLaunch)
                put("autoReconnect", state.settings.autoReconnect)
                put("dailyAutoUpdate", state.settings.dailyAutoUpdate)
                put("showUpdateNotifications", state.settings.showUpdateNotifications)
                put("lightThemeEnabled", state.settings.lightThemeEnabled)
                put("onboardingCompleted", state.settings.onboardingCompleted)
                put("dnsMode", state.settings.dnsMode.name)
                put("customDnsValue", state.settings.customDnsValue)
                put("logLevel", state.settings.logLevel.name)
                put("lastConnectedServerId", state.settings.lastConnectedServerId)
                put("resumeConnectionOnLaunch", state.settings.resumeConnectionOnLaunch)
                put("globalProxyEnabled", state.settings.globalProxyEnabled)
                put("streamingRoutingEnabled", state.settings.streamingRoutingEnabled)
                put("heartbeatIntervalMinutes", state.settings.heartbeatIntervalMinutes)
                put("vpnMtu", normalizedAppVpnMtu(state.settings.vpnMtu))
                put("streamingSelections", JSONArray().apply {
                    state.settings.streamingSelections.forEach { selection ->
                        put(JSONObject().apply {
                            put("serviceId", selection.serviceId)
                            put("serverId", selection.serverId)
                        })
                    }
                })
            })
        }
    }

    private fun JSONObject?.toSettings(): AppSettings {
        val defaults = defaultSettings()
        if (this == null) return defaults
        return AppSettings(
            autoConnectOnLaunch = optBoolean("autoConnectOnLaunch", defaults.autoConnectOnLaunch),
            autoReconnect = optBoolean("autoReconnect", defaults.autoReconnect),
            dailyAutoUpdate = optBoolean("dailyAutoUpdate", defaults.dailyAutoUpdate),
            showUpdateNotifications = optBoolean("showUpdateNotifications", defaults.showUpdateNotifications),
            lightThemeEnabled = optBoolean("lightThemeEnabled", defaults.lightThemeEnabled),
            onboardingCompleted = optBoolean("onboardingCompleted", defaults.onboardingCompleted),
            dnsMode = optString("dnsMode").toEnumOrDefault(defaults.dnsMode),
            customDnsValue = optString("customDnsValue", defaults.customDnsValue),
            logLevel = optString("logLevel").toEnumOrDefault(defaults.logLevel),
            lastConnectedServerId = optString("lastConnectedServerId", defaults.lastConnectedServerId),
            resumeConnectionOnLaunch = optBoolean("resumeConnectionOnLaunch", defaults.resumeConnectionOnLaunch),
            globalProxyEnabled = optBoolean("globalProxyEnabled", defaults.globalProxyEnabled),
            streamingRoutingEnabled = optBoolean("streamingRoutingEnabled", defaults.streamingRoutingEnabled),
            streamingSelections = optJSONArray("streamingSelections").toStreamingSelections(),
            heartbeatIntervalMinutes = optInt("heartbeatIntervalMinutes", defaults.heartbeatIntervalMinutes)
                .takeIf { it in setOf(2, 5, 10) } ?: defaults.heartbeatIntervalMinutes,
            vpnMtu = normalizedAppVpnMtu(optInt("vpnMtu", defaults.vpnMtu)),
        )
    }

    private fun JSONArray?.toServerGroups(): List<ServerGroup> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = getJSONObject(index)
            ServerGroup(
                id = item.getString("id"),
                name = item.getString("name"),
                type = item.optString("type").toEnumOrDefault(ServerGroupType.Local),
                sourceUrl = item.optNullableString("sourceUrl"),
                updatedAt = item.optString("updatedAt", "刚刚"),
            )
        }
    }

    private fun JSONArray?.toServerNodes(): List<ServerNode> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = getJSONObject(index)
            ServerNode(
                id = item.getString("id"),
                groupId = item.getString("groupId"),
                name = item.getString("name"),
                code = item.getString("code"),
                subscription = item.getString("subscription"),
                region = item.optString("region", "未知"),
                latencyMs = item.optInt("latencyMs", 0),
                protocol = item.optString("protocol", "vless"),
                security = item.optString("security", "none"),
                transport = item.optString("transport", "tcp"),
                description = item.optString("description", ""),
                address = item.optString("address", ""),
                port = item.optString("port", ""),
                flow = item.optString("flow", "无"),
                stable = item.optBoolean("stable", true),
                favorite = item.optBoolean("favorite", false),
                rawUri = item.optString("rawUri", ""),
                outboundJson = item.optString("outboundJson", ""),
                hiddenUnsupported = item.optBoolean("hiddenUnsupported", false),
                preProxyNodeId = item.optString("preProxyNodeId", ""),
                fallbackNodeId = item.optString("fallbackNodeId", ""),
            )
        }
    }

    private fun JSONArray?.toSubscriptions(): List<SubscriptionItem> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = getJSONObject(index)
            SubscriptionItem(
                id = item.getString("id"),
                name = item.getString("name"),
                type = item.optString("type", "订阅"),
                nodes = item.optInt("nodes", 0),
                updatedAt = item.optString("updatedAt", "刚刚"),
                updatedAtMs = item.takeIf { it.has("updatedAtMs") }?.optLong("updatedAtMs"),
                nextSync = item.optString("nextSync", "未设置"),
                status = item.optString("status", "已导入"),
                autoUpdate = item.optBoolean("autoUpdate", false),
                sourceUrl = item.optNullableString("sourceUrl"),
            )
        }
    }

    private fun JSONArray?.toProtectedApps(): List<ProtectedApp> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = getJSONObject(index)
            ProtectedApp(
                id = item.getString("id"),
                name = item.getString("name"),
                category = item.optString("category", ""),
                enabled = item.optBoolean("enabled", false),
            )
        }
    }

    private fun JSONArray?.toRuleSources(): List<RuleSourceItem> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = getJSONObject(index)
            RuleSourceItem(
                id = item.getString("id"),
                name = item.optString("name", "规则源"),
                url = item.optString("url", ""),
                type = item.optString("type").toEnumOrDefault(RuleSourceType.Auto),
                policy = item.optString("policy").toEnumOrDefault(RuleTargetPolicy.Proxy),
                enabled = item.optBoolean("enabled", true),
                systemDefault = item.optBoolean("systemDefault", false),
                updatedAt = item.optString("updatedAt", "未更新"),
                status = item.optString("status").toEnumOrDefault(RuleSourceStatus.Idle),
                totalRules = item.optInt("totalRules", 0),
                convertedRules = item.optInt("convertedRules", 0),
                skippedRules = item.optInt("skippedRules", 0),
                lastError = item.optNullableString("lastError"),
                sourceKind = item.optString("sourceKind").toEnumOrDefault(RuleSourceKind.RemoteUrl),
                content = item.optString("content", ""),
            )
        }
    }

    private fun JSONArray?.toStrings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
    }

    private fun JSONArray?.toStreamingSelections(): List<StreamingRouteSelection> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = getJSONObject(index)
            StreamingRouteSelection(
                serviceId = item.optString("serviceId", ""),
                serverId = item.optString("serverId", ""),
            )
        }.filter { it.serviceId.isNotBlank() }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeUnless { it.isBlank() }
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T {
        return runCatching { enumValueOf<T>(this) }.getOrDefault(default)
    }
}
