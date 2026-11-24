package com.mallocgfw.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AppStateStore {
    private const val PREFS_NAME = "mallocgfw_state"
    private const val UNREADABLE_STATE_BACKUP_KEY = "persisted_app_state_unreadable_backup"

    /**
     * Before 0.9.0.2 an English interface switched the direct region to the
     * US. English now keeps China direct; anyone left on the US region by
     * that rule (English or System language) is moved back to China once.
     */
    private const val REGION_DEFAULT_MIGRATION = "migration_region_china_default_v1"

    internal fun migrateEnglishRegion(state: PersistedAppState): PersistedAppState {
        val settings = state.settings
        // Only English / System users got the US region automatically; Chinese users chose it.
        val automatic = settings.language == AppLanguage.English || settings.language == AppLanguage.System
        if (settings.geoRoutingRegion != AppGeoRoutingRegion.English || !automatic) return state
        return state.copy(settings = settings.copy(geoRoutingRegion = AppGeoRoutingRegion.China))
    }
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
            language = AppLanguage.System,
            geoRoutingRegion = AppGeoRoutingRegion.defaultForLanguage(AppLanguage.System),
            globalProxyEnabled = false,
            streamingRoutingEnabled = false,
            streamingSelections = emptyList(),
            heartbeatIntervalMinutes = 5,
            vpnMtu = DEFAULT_APP_VPN_MTU,
            tailscaleEnabled = false,
            tailscaleAuthKey = "",
            tailscaleControlUrl = DEFAULT_TAILSCALE_CONTROL_URL,
            tailscaleSubnetRoutes = emptyList(),
            tailscaleExitNodeId = "",
            tailscaleAlwaysUseDerp = false,
            proxyQuicEnabled = false,
        )
    }

    fun load(context: Context): PersistedAppState {
        return loadWithStartupCleanup(context).state
    }

    fun loadWithStartupCleanup(context: Context): LoadedAppState {
        return synchronized(stateLock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(STATE_KEY, null)
            if (raw == null) {
                // A fresh install already has the current defaults: the one-time
                // migration must not undo what this user changes later.
                prefs.edit()
                    .putBoolean(RuleSourceManager.DIRECT_DEFAULTS_MIGRATION, true)
                    .putBoolean(REGION_DEFAULT_MIGRATION, true)
                    .apply()
                return@synchronized LoadedAppState(defaults())
            }
            val parsed = runCatching {
                parseState(JSONObject(raw))
            }.getOrElse { error ->
                // Starting over must not silently destroy the user's nodes:
                // keep the unreadable copy so it can be recovered.
                android.util.Log.e("AppStateStore", "Saved state is unreadable; starting from defaults", error)
                prefs.edit().putString(UNREADABLE_STATE_BACKUP_KEY, raw).apply()
                defaults()
            }
            var normalized = parsed.normalized()
            if (!prefs.getBoolean(RuleSourceManager.DIRECT_DEFAULTS_MIGRATION, false)) {
                normalized = normalized.copy(ruleSources = RuleSourceManager.enableDirectDefaults(normalized.ruleSources))
                prefs.edit().putBoolean(RuleSourceManager.DIRECT_DEFAULTS_MIGRATION, true).apply()
            }
            if (!prefs.getBoolean(REGION_DEFAULT_MIGRATION, false)) {
                normalized = migrateEnglishRegion(normalized).normalized()
                prefs.edit().putBoolean(REGION_DEFAULT_MIGRATION, true).apply()
            }
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

    /** The form [save] writes, for comparing states from different sources. */
    fun normalize(state: PersistedAppState): PersistedAppState = state.normalized()

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
        val normalizedServers = ImportParser.withUniqueIds(
            servers.map { server ->
                if (server.groupId in localGroupIds) {
                    server.copy(groupId = ImportParser.LOCAL_GROUP_ID, subscription = "Local")
                } else {
                    server
                }
            },
        )
        val filteredServers = normalizedServers
            .filter { server -> server.groupId in validGroupIds }
            .map { server ->
                // Re-evaluated on every load: the bundled core decides what can run
                // (this also re-shows REALITY + gRPC nodes hidden by older versions).
                val hidden = NodeCompatibility.coreIncompatibility(server) != null
                if (server.hiddenUnsupported == hidden) server else server.copy(hiddenUnsupported = hidden)
            }
        val visibleServers = filteredServers.filterNot { it.hiddenUnsupported }
        val visibleServerMap = visibleServers.associateBy { it.id }
        val normalizedPreProxyServers = filteredServers.map { server ->
            val normalizedPreProxyId = server.preProxyNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::isRunnable) == true &&
                    ManualNodeFactory.supportsPreProxy(server)
            }.orEmpty()
            val normalizedFallbackId = server.fallbackNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::isRunnable) == true &&
                    ManualNodeFactory.isRunnable(server)
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
        val normalizedRuleSources = RuleSourceManager.ensureDefaults(ruleSources, settings.geoRoutingRegion).map { source ->
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
                    normalizedServerId == StreamingMediaManager.DIRECT -> selection.copy(serverId = normalizedServerId)
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
                tailscaleAuthKey = settings.tailscaleAuthKey.trim(),
                tailscaleControlUrl = settings.tailscaleControlUrl.trim()
                    .ifBlank { DEFAULT_TAILSCALE_CONTROL_URL },
                tailscaleSubnetRoutes = settings.tailscaleSubnetRoutes
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct(),
                tailscaleExitNodeId = settings.tailscaleExitNodeId.trim(),
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
            if (existing == null) preset else preset.copy(enabled = existing.enabled, serverId = existing.serverId)
        }
    }

    /** internal for tests (save / load round trip). */
    internal fun parseState(json: JSONObject): PersistedAppState {
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

    internal fun serializeState(state: PersistedAppState): JSONObject {
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
                        put("mihomoProxyJson", server.mihomoProxyJson)
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
                        source.updatedAtMs?.let { put("updatedAtMs", it) }
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
                        put("serverId", app.serverId)
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
                put("language", state.settings.language.name)
                put("geoRoutingRegion", state.settings.geoRoutingRegion.name)
                put("globalProxyEnabled", state.settings.globalProxyEnabled)
                put("streamingRoutingEnabled", state.settings.streamingRoutingEnabled)
                put("heartbeatIntervalMinutes", state.settings.heartbeatIntervalMinutes)
                put("vpnMtu", normalizedAppVpnMtu(state.settings.vpnMtu))
                put("tailscaleEnabled", state.settings.tailscaleEnabled)
                put("tailscaleAuthKey", state.settings.tailscaleAuthKey)
                put("tailscaleControlUrl", state.settings.tailscaleControlUrl)
                put("tailscaleSubnetRoutes", JSONArray(state.settings.tailscaleSubnetRoutes))
                put("tailscaleExitNodeId", state.settings.tailscaleExitNodeId)
                put("tailscaleAlwaysUseDerp", state.settings.tailscaleAlwaysUseDerp)
                put("proxyQuicEnabled", state.settings.proxyQuicEnabled)
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
        val language = optString("language").toEnumOrDefault(defaults.language)
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
            language = language,
            geoRoutingRegion = optString("geoRoutingRegion")
                .toEnumOrDefault(AppGeoRoutingRegion.defaultForLanguage(language)),
            globalProxyEnabled = optBoolean("globalProxyEnabled", defaults.globalProxyEnabled),
            streamingRoutingEnabled = optBoolean("streamingRoutingEnabled", defaults.streamingRoutingEnabled),
            streamingSelections = optJSONArray("streamingSelections").toStreamingSelections(),
            heartbeatIntervalMinutes = optInt("heartbeatIntervalMinutes", defaults.heartbeatIntervalMinutes)
                .takeIf { it in setOf(2, 5, 10) } ?: defaults.heartbeatIntervalMinutes,
            vpnMtu = normalizedAppVpnMtu(optInt("vpnMtu", defaults.vpnMtu)),
            tailscaleEnabled = optBoolean("tailscaleEnabled", defaults.tailscaleEnabled),
            tailscaleAuthKey = optString("tailscaleAuthKey", defaults.tailscaleAuthKey),
            tailscaleControlUrl = optString("tailscaleControlUrl", defaults.tailscaleControlUrl),
            tailscaleSubnetRoutes = optJSONArray("tailscaleSubnetRoutes").toStrings(),
            tailscaleExitNodeId = optString("tailscaleExitNodeId", defaults.tailscaleExitNodeId),
            tailscaleAlwaysUseDerp = optBoolean("tailscaleAlwaysUseDerp", defaults.tailscaleAlwaysUseDerp),
            proxyQuicEnabled = optBoolean("proxyQuicEnabled", defaults.proxyQuicEnabled),
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
                mihomoProxyJson = item.optString("mihomoProxyJson", ""),
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
                serverId = item.optString("serverId", ""),
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
                updatedAtMs = item.optLong("updatedAtMs", 0L).takeIf { it > 0L },
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
