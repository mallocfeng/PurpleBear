package com.mallocgfw.app.model

import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mallocgfw.app.xray.ResourceFetchClient
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml

object ImportParser {
    private const val SUBSCRIPTION_CONNECT_TIMEOUT_MS = 10_000
    private const val SUBSCRIPTION_READ_TIMEOUT_MS = 20_000

    suspend fun buildPreview(input: String): ImportPreview {
        val trimmed = input.trim()
        require(trimmed.isNotBlank()) { "请输入订阅链接或节点 URL。" }

        return when {
            // Several links (or a pasted subscription / Clash body) at once: parse it as content.
            trimmed.lines().count { it.isNotBlank() } > 1 -> buildPastedPreview(trimmed)
            looksLikeHttpProxyShareLink(trimmed) -> buildLocalPreview(trimmed)
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> buildSubscriptionPreview(trimmed)
            ManualNodeFactory.supportsShareLink(trimmed) || MihomoNodes.handlesShareLink(trimmed) -> buildLocalPreview(trimmed)
            else -> runCatching { buildPastedPreview(trimmed) }.getOrElse {
                error("当前仅支持 http(s) 订阅，以及 VLESS / VMess / Trojan / Shadowsocks / SSR / SOCKS / HTTP / Hysteria2 单节点导入。")
            }
        }
    }

    /** Pasted text holding several nodes; they go to the Local group next to single imports. */
    private fun buildPastedPreview(content: String): ImportPreview = buildGroupPreview(
        sourceValue = "pasted",
        groupName = "Local",
        groupType = ServerGroupType.Local,
        autoUpdate = false,
        content = content,
    )

    /** Nodes downloaded from a Clash proxy-provider, with that provider's filters. */
    data class ProviderContent(
        val content: String,
        val filter: ProviderFilter = ProviderFilter(),
        val override: ProviderOverride = ProviderOverride(),
    )

    /** Clash provider `override`: renames and fields forced onto every proxy. */
    data class ProviderOverride(
        val prefix: String = "",
        val suffix: String = "",
        val renames: List<Pair<Regex, String>> = emptyList(),
        val fields: Map<String, Any?> = emptyMap(),
    ) {
        fun rename(name: String): String =
            prefix + renames.fold(name) { acc, (regex, target) -> regex.replace(acc, target) } + suffix

        /** Forced fields on a Clash proxy map (names are handled by [rename]). */
        fun apply(map: Map<*, *>): Map<*, *> {
            if (fields.isEmpty()) return map
            return LinkedHashMap<Any?, Any?>(map).apply { putAll(fields) }
        }

        companion object {
            private val FIELD_KEYS = setOf("skip-cert-verify", "udp", "udp-over-tcp", "up", "down", "tfo", "mptcp", "dialer-proxy")

            fun from(provider: Map<*, *>): ProviderOverride {
                val override = provider["override"] as? Map<*, *> ?: return ProviderOverride()
                return ProviderOverride(
                    prefix = override["additional-prefix"]?.toString().orEmpty(),
                    suffix = override["additional-suffix"]?.toString().orEmpty(),
                    renames = (override["proxy-name"] as? List<*>).orEmpty().mapNotNull { entry ->
                        val rule = entry as? Map<*, *> ?: return@mapNotNull null
                        val regex = runCatching { Regex(rule["pattern"]?.toString().orEmpty()) }.getOrNull() ?: return@mapNotNull null
                        regex to rule["target"]?.toString().orEmpty().replace(Regex("\\$\\{?(\\d+)}?"), "\\$$1")
                    },
                    fields = override.entries.mapNotNull { (k, v) -> k?.toString()?.takeIf { it in FIELD_KEYS }?.let { it to v } }.toMap(),
                )
            }
        }
    }

    /** Provider content with [override] fields applied to its Clash proxies (re-emitted as JSON). */
    private fun overriddenContent(content: String, override: ProviderOverride): String {
        if (override.fields.isEmpty()) return content
        val root = runCatching { Yaml().load<Any?>(content) as? Map<*, *> }.getOrNull() ?: return content
        val proxies = root["proxies"] as? List<*> ?: return content
        val json = JSONArray()
        proxies.forEach { item -> (item as? Map<*, *>)?.let { json.put(MihomoNodes.jsonOf(override.apply(it))) } }
        return JSONObject().put("proxies", json).toString()
    }

    /** Clash `filter` / `exclude-filter` (regexes, "`"-separated) and `exclude-type` ("|"-separated). */
    data class ProviderFilter(
        val include: List<Regex> = emptyList(),
        val exclude: List<Regex> = emptyList(),
        val excludeTypes: Set<String> = emptySet(),
    ) {
        fun keeps(node: ServerNode): Boolean {
            if (include.isNotEmpty() && include.none { it.containsMatchIn(node.name) }) return false
            if (exclude.any { it.containsMatchIn(node.name) }) return false
            return clashType(node) !in excludeTypes
        }

        companion object {
            fun from(provider: Map<*, *>): ProviderFilter = ProviderFilter(
                include = regexes(provider["filter"]),
                exclude = regexes(provider["exclude-filter"]),
                excludeTypes = provider["exclude-type"]?.toString().orEmpty().split('|')
                    .map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank)
                    .map { MihomoNodes.normalizedType(it) ?: it }.toSet(),
            )

            private fun regexes(raw: Any?): List<Regex> = raw?.toString().orEmpty().split('`')
                .filter(String::isNotBlank).mapNotNull { runCatching { Regex(it) }.getOrNull() }

            private fun clashType(node: ServerNode): String {
                node.mihomoProxyJson.takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching { JSONObject(raw).optString("type") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return when (val p = node.protocol.uppercase(Locale.ROOT)) {
                    "SHADOWSOCKS" -> "ss"
                    "SHADOWSOCKSR", "SSR" -> "ssr"
                    "SOCKS", "SOCKS5" -> "socks5"
                    "HTTP", "HTTP PROXY" -> "http"
                    else -> p.lowercase(Locale.ROOT)
                }
            }
        }
    }

    fun buildFilePreview(fileName: String, content: String, providerContents: List<ProviderContent> = emptyList()): ImportPreview {
        val cleanName = fileName.substringBeforeLast(".").ifBlank { "导入文件" }
        return buildGroupPreview(
            sourceValue = fileName,
            groupName = cleanName,
            groupType = ServerGroupType.Subscription,
            autoUpdate = false,
            content = content,
            providerContents = providerContents,
        )
    }

    /** [buildFilePreview] that also downloads the config's Clash proxy-providers. */
    suspend fun buildFilePreviewWithProviders(fileName: String, content: String): ImportPreview =
        buildFilePreview(fileName, content, fetchProviderContents(content))

    fun buildSeedSubscriptionPreview(sourceUrl: String, nodeUrl: String): ImportPreview {
        val groupId = stableGroupId(sourceUrl)
        val groupName = sourceUrl.toHostLabel()
        val group = ServerGroup(
            id = groupId,
            name = groupName,
            type = ServerGroupType.Subscription,
            sourceUrl = sourceUrl,
            updatedAt = "刚刚",
        )
        val nodes = listOf(
            parseNodeLine(nodeUrl, groupId = groupId, sourceLabel = groupName, index = 1)
                ?.let(::markHiddenIfUnsupported)
                ?: error("种子订阅中的单节点链接无法解析。"),
        )
        val visibleNodeCount = nodes.count { !it.hiddenUnsupported }
        val hiddenUnsupportedNodeCount = nodes.count { it.hiddenUnsupported }
        return ImportPreview(
            input = sourceUrl,
            group = group,
            nodes = nodes,
            subscription = SubscriptionItem(
                id = groupId,
                name = groupName,
                type = "订阅",
                nodes = visibleNodeCount,
                updatedAt = "刚刚",
                updatedAtMs = System.currentTimeMillis(),
                nextSync = "手动",
                status = "已导入",
                autoUpdate = true,
                sourceUrl = sourceUrl,
            ),
            summary = buildImportSummary(
                nodeCount = visibleNodeCount,
                autoUpdate = true,
                sourceValue = sourceUrl,
                hiddenUnsupportedNodeCount = hiddenUnsupportedNodeCount,
                hiddenReason = firstHiddenReason(nodes),
            ),
            hiddenUnsupportedNodeCount = hiddenUnsupportedNodeCount,
        )
    }

    private suspend fun buildSubscriptionPreview(sourceUrl: String): ImportPreview {
        val requestProfile = selectSubscriptionRequestProfile(sourceUrl)
        val autoUpdate = shouldAutoRefreshSubscription(sourceUrl)
        suspend fun fetch(profile: ResourceFetchClient.RequestProfile) = withContext(Dispatchers.IO) {
            ResourceFetchClient.fetchText(
                url = sourceUrl,
                connectTimeoutMs = SUBSCRIPTION_CONNECT_TIMEOUT_MS,
                readTimeoutMs = SUBSCRIPTION_READ_TIMEOUT_MS,
                label = "订阅链接",
                requestProfile = profile,
            )
        }
        suspend fun preview(content: String) = buildGroupPreview(
            sourceValue = sourceUrl,
            groupName = sourceUrl.toHostLabel(),
            groupType = ServerGroupType.Subscription,
            autoUpdate = autoUpdate,
            content = content,
            providerContents = fetchProviderContents(content),
        )
        suspend fun attempt(profile: ResourceFetchClient.RequestProfile) = runCatching {
            val content = fetch(profile)
            if (looksLikeWebPage(content)) error(WEB_PAGE_ERROR)
            preview(content)
        }
        val first = attempt(requestProfile)
        if (requestProfile != ResourceFetchClient.RequestProfile.DEFAULT) return first.getOrThrow()
        // One-time (signed snapshot) links can't be fetched twice.
        if (!autoUpdate && first.isSuccess) return first.getOrThrow()
        // Panels pick the format from the User-Agent: unknown clients often get a
        // reduced base64 list (no TUIC, AnyTLS…), a web page or an error, while
        // Clash Meta clients get the full list. Ask both ways, keep the fuller one.
        val second = attempt(ResourceFetchClient.RequestProfile.CLASH_META_SUBSCRIPTION)
        fun usable(result: Result<ImportPreview>) = result.getOrNull()?.nodes?.count { !it.hiddenUnsupported } ?: -1
        return when {
            usable(second) > usable(first) -> second.getOrThrow()
            first.isSuccess -> first.getOrThrow()
            else -> throw first.exceptionOrNull()!!
        }
    }

    private const val WEB_PAGE_ERROR = "订阅地址返回的是网页而不是节点列表，请确认复制的是订阅链接（而不是订阅页面地址）。"

    private fun looksLikeWebPage(content: String): Boolean {
        val head = content.trimStart().take(64).lowercase(Locale.ROOT)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun buildLocalPreview(nodeUrl: String): ImportPreview {
        val node = parseNodeLine(
            raw = nodeUrl,
            groupId = LOCAL_GROUP_ID,
            sourceLabel = "Local",
            index = 1,
        )?.let(::markHiddenIfUnsupported)
            ?: error("当前仅支持可解析的 VLESS / VMess / Trojan / Shadowsocks / SSR / SOCKS / HTTP / Hysteria2 单节点。")
        return ImportPreview(
            input = nodeUrl,
            group = ServerGroup(
                id = LOCAL_GROUP_ID,
                name = "Local",
                type = ServerGroupType.Local,
                updatedAt = "刚刚",
            ),
            nodes = listOf(node),
            summary = buildImportSummary(
                nodeCount = if (node.hiddenUnsupported) 0 else 1,
                autoUpdate = true,
                sourceValue = nodeUrl,
                hiddenUnsupportedNodeCount = if (node.hiddenUnsupported) 1 else 0,
                hiddenReason = firstHiddenReason(listOf(node)),
            ),
            hiddenUnsupportedNodeCount = if (node.hiddenUnsupported) 1 else 0,
        )
    }

    private fun buildGroupPreview(
        sourceValue: String,
        groupName: String,
        groupType: ServerGroupType,
        autoUpdate: Boolean,
        content: String,
        providerContents: List<ProviderContent> = emptyList(),
    ): ImportPreview {
        val sourceId = if (groupType == ServerGroupType.Local) LOCAL_GROUP_ID else stableGroupId(sourceValue)
        val group = ServerGroup(
            id = sourceId,
            name = groupName,
            type = groupType,
            sourceUrl = if (groupType == ServerGroupType.Subscription) sourceValue else null,
            updatedAt = "刚刚",
        )
        skippedTypes.get().clear()
        skippedProviders.get().clear()
        val parsedNodes = (listOf(ProviderContent(content)) + providerContents).flatMap { part ->
            runCatching { parseSubscriptionNodes(overriddenContent(part.content, part.override), groupId = sourceId, sourceLabel = groupName) }
                .getOrElse { if (part.content === content) throw it else emptyList() }
                .map { node -> part.override.rename(node.name).let { if (it == node.name) node else node.copy(name = it) } }
                .filter(part.filter::keeps)
        }.let(::withUniqueIds)
        val skipped = skippedTypes.get().toList()
        val unreadableProviders = skippedProviders.get().toList()
        val nodes = parsedNodes.filterNot(::isDecorativeNode).map(::markHiddenIfUnsupported)
        val visibleNodeCount = nodes.count { !it.hiddenUnsupported }
        val hiddenUnsupportedNodeCount = nodes.count { it.hiddenUnsupported }

        require(nodes.isNotEmpty()) {
            if (skipped.isNotEmpty()) {
                return@require "没有可用节点：${skipped.size} 个节点的协议暂不支持（${skipped.distinct().take(3).joinToString("、")}）。"
            }
            if (groupType == ServerGroupType.Subscription && shouldAutoRefreshSubscription(sourceValue).not()) {
                when {
                    containsRegularClientBlock(content) -> {
                        "当前设备上的订阅请求仍被服务端识别为非 Shadowrocket 客户端，未返回真实节点。"
                    }
                    containsExpiredSnapshotNotice(content) -> {
                        "订阅链接已过期或已失效。该链接只支持一次性使用，请重新打开提供订阅的页面获取最新链接。"
                    }
                    else -> {
                        "订阅链接未返回可用节点。该链接是一次性快照链接，当前返回内容与 Shadowrocket 实际收到的数据不一致。"
                    }
                }
            } else {
                "没有从导入内容中解析出可用节点。"
            }
        }

        return ImportPreview(
            input = sourceValue,
            group = group,
            nodes = nodes,
            subscription = if (groupType == ServerGroupType.Subscription) {
                SubscriptionItem(
                    id = sourceId,
                    name = groupName,
                    type = "订阅",
                    nodes = visibleNodeCount,
                    updatedAt = "刚刚",
                    updatedAtMs = System.currentTimeMillis(),
                    nextSync = if (autoUpdate) "手动" else "文件导入",
                    status = "已导入",
                    autoUpdate = autoUpdate,
                    sourceUrl = if (sourceValue.startsWith("http")) sourceValue else null,
                )
            } else {
                null
            },
            summary = buildImportSummary(
                nodeCount = visibleNodeCount,
                autoUpdate = autoUpdate,
                sourceValue = sourceValue,
                hiddenUnsupportedNodeCount = hiddenUnsupportedNodeCount,
                hiddenReason = firstHiddenReason(nodes),
            ) + skippedSummary(skipped) + providerSummary(unreadableProviders),
            hiddenUnsupportedNodeCount = hiddenUnsupportedNodeCount,
        )
    }

    /** Proxy entries of a type no bundled core runs, collected while parsing. */
    private val skippedTypes: ThreadLocal<MutableList<String>> = ThreadLocal.withInitial { mutableListOf() }

    private val skippedProviders: ThreadLocal<MutableList<String>> = ThreadLocal.withInitial { mutableListOf() }

    private fun providerSummary(names: List<String>): String {
        if (names.isEmpty()) return ""
        return " 另有 ${names.size} 个本地文件提供者无法读取（${names.distinct().take(3).joinToString("、")}）。"
    }

    private fun skippedSummary(types: List<String>): String {
        if (types.isEmpty()) return ""
        return " 另有 ${types.size} 个节点的协议暂不支持（${types.distinct().take(3).joinToString("、")}）。"
    }

    /** Provider files can repeat indexes; keep node ids unique within one import. */
    private fun withUniqueIds(nodes: List<ServerNode>): List<ServerNode> {
        val seen = mutableSetOf<String>()
        return nodes.map { node ->
            var id = node.id
            var n = 2
            while (!seen.add(id)) id = "${node.id}_${n++}"
            if (id == node.id) node else node.copy(id = id)
        }
    }

    /** Downloads the HTTP proxy-providers a Clash config points at (inline ones are parsed directly). */
    private suspend fun fetchProviderContents(content: String): List<ProviderContent> {
        if (!Regex("(?m)^proxy-providers\\s*:").containsMatchIn(content)) return emptyList()
        val providers = runCatching { (Yaml().load<Any?>(content) as? Map<*, *>)?.get("proxy-providers") as? Map<*, *> }
            .getOrNull() ?: return emptyList()
        val targets = providers.values.mapNotNull { provider ->
            val map = provider as? Map<*, *> ?: return@mapNotNull null
            val url = map["url"]?.toString()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            Triple(url, ProviderFilter.from(map), ProviderOverride.from(map))
        }.distinctBy { it.first }.take(MAX_PROVIDERS)
        return withContext(Dispatchers.IO) {
            targets.mapNotNull { (url, filter, override) ->
                runCatching {
                    ResourceFetchClient.fetchText(
                        url = url,
                        connectTimeoutMs = SUBSCRIPTION_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = SUBSCRIPTION_READ_TIMEOUT_MS,
                        label = "订阅提供者",
                        requestProfile = ResourceFetchClient.RequestProfile.DEFAULT,
                    )
                }.getOrNull()?.let { ProviderContent(it, filter, override) }
            }
        }
    }

    private const val MAX_PROVIDERS = 20

    private fun buildImportSummary(
        nodeCount: Int,
        autoUpdate: Boolean,
        sourceValue: String,
        hiddenUnsupportedNodeCount: Int,
        hiddenReason: String? = null,
    ): String {
        val base = buildString {
            append("解析完成，共检测到 $nodeCount 个可用节点。")
            if (hiddenUnsupportedNodeCount > 0) {
                append(" 已隐藏 $hiddenUnsupportedNodeCount 个当前内核无法使用的节点")
                append(hiddenReason?.let { "（$it）。" } ?: "。")
            }
        }
        if (!sourceValue.startsWith("http", ignoreCase = true) || autoUpdate) {
            return base
        }
        return "$base 该链接包含时效签名，已按静态快照导入，不参与后续刷新。"
    }

    private fun markHiddenIfUnsupported(node: ServerNode): ServerNode {
        return node.copy(hiddenUnsupported = NodeCompatibility.coreIncompatibility(node) != null)
    }

    private fun firstHiddenReason(nodes: List<ServerNode>): String? {
        return nodes.asSequence().filter { it.hiddenUnsupported }
            .mapNotNull(NodeCompatibility::coreIncompatibility).firstOrNull()
    }

    private fun shouldAutoRefreshSubscription(sourceUrl: String): Boolean {
        val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return true
        val query = parseQuery(uri.rawQuery)
        if (query.isEmpty()) return true
        val listFormat = query["list"]?.firstOrNull()?.lowercase(Locale.ROOT)
        if (listFormat == "shadowrocket") return false
        val signatureKeys = setOf("sig", "sign", "signature", "token", "auth", "key")
        val timeKeys = setOf("t", "ts", "timestamp", "expires", "expire", "exp", "e")
        val hasSignature = query.keys.any { key ->
            val normalized = key.lowercase(Locale.ROOT)
            normalized in signatureKeys || normalized.endsWith("sig") || normalized.endsWith("token")
        }
        val hasExpiry = query.keys.any { key ->
            val normalized = key.lowercase(Locale.ROOT)
            normalized in timeKeys || normalized.contains("expire")
        }
        return !(hasSignature && hasExpiry)
    }

    /**
     * Clash/mihomo `fingerprint` pins the server certificate by SHA-256,
     * which maps to Xray's pinnedPeerCertSha256. Ignore values that are not
     * a SHA-256 hex digest (some configs misuse it for uTLS fingerprints).
     */
    private fun Map<*, *>.clashCertificatePin(): String {
        val value = string("fingerprint")?.trim().orEmpty().replace(":", "")
        return value.takeIf { it.length == 64 && it.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' } }
            .orEmpty()
    }

    private fun selectSubscriptionRequestProfile(sourceUrl: String): ResourceFetchClient.RequestProfile {
        val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return ResourceFetchClient.RequestProfile.DEFAULT
        val query = parseQuery(uri.rawQuery)
        val listFormat = query["list"]?.firstOrNull()?.lowercase(Locale.ROOT)
        return when (listFormat) {
            "shadowrocket" -> ResourceFetchClient.RequestProfile.SHADOWROCKET_SUBSCRIPTION
            else -> ResourceFetchClient.RequestProfile.DEFAULT
        }
    }

    private fun parseSubscriptionNodes(
        content: String,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val payload = normalizeSubscriptionPayload(content)
        parseJsonSubscription(payload, groupId, sourceLabel).takeIf { it.isNotEmpty() }?.let { return it }
        parseYamlSubscription(payload, groupId, sourceLabel).takeIf { it.isNotEmpty() }?.let { return it }
        val wireGuardSections = MihomoNodes.surgeWireGuardSections(payload)
        return decodeLineBasedSubscription(payload)
            .mapIndexedNotNull { index, raw ->
                MihomoNodes.surgeWireGuardLine(raw, wireGuardSections)
                    ?.let { parseClashProxyMap(it, groupId, sourceLabel, index + 1) }
                    ?: parseNodeLine(raw, groupId = groupId, sourceLabel = sourceLabel, index = index + 1)
            }
    }

    private fun normalizeSubscriptionPayload(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return trimmed
        return when {
            looksStructuredPayload(trimmed) -> trimmed
            trimmed.contains("://") -> trimmed
            else -> {
                val cleaned = trimmed.replace("\\s+".toRegex(), "")
                runCatching { decodeBase64Flexible(cleaned, "订阅内容") }.getOrElse { error ->
                    // Surge / Loon / Quantumult X configs are plain "name = type, …" lines.
                    val isConf = trimmed.lineSequence().any { MihomoNodes.fromConfLine(it) != null } ||
                        MihomoNodes.surgeWireGuardSections(trimmed).isNotEmpty()
                    if (isConf) trimmed else throw error
                }
            }
        }
    }

    private fun decodeLineBasedSubscription(payload: String): List<String> {
        return payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun looksStructuredPayload(raw: String): Boolean {
        val trimmed = raw.trimStart()
        return trimmed.startsWith("{") ||
            trimmed.startsWith("[") ||
            Regex("(?m)^proxies\\s*:").containsMatchIn(raw) ||
            Regex("(?m)^proxy-providers\\s*:").containsMatchIn(raw) ||
            Regex("(?m)^(outbounds|endpoints)\\s*:").containsMatchIn(raw) ||
            Regex("(?m)^servers\\s*:").containsMatchIn(raw)
    }

    private fun parseNodeLine(
        raw: String,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode? {
        return when {
            // Links only mihomo can run (TUIC, AnyTLS, Hysteria v1, WireGuard, SS plugins).
            MihomoNodes.handlesShareLink(raw) || MihomoNodes.isShadowsocksPluginLink(raw) -> {
                val map = MihomoNodes.fromShareLink(raw) ?: return null
                parseClashProxyMap(map, groupId, sourceLabel, index)?.let { node ->
                    node.copy(id = "${groupId}_${index}_${stableSuffix(raw)}", rawUri = raw.trim())
                }
            }
            raw.startsWith("vmess://", ignoreCase = true) || raw.startsWith("vless://", ignoreCase = true) -> {
                runCatching {
                    // Fails here, at import, for links no core can run (e.g. QUIC transport).
                    ManualNodeFactory.prefillFromShareLink(raw)
                    if (raw.startsWith("vmess://", ignoreCase = true)) {
                        parseVmessNode(raw, groupId, sourceLabel, index)
                    } else {
                        parseVlessNode(raw, groupId, sourceLabel, index)
                    }
                }.getOrElse { skipType(raw.substringBefore("://") + "（" + (it.message ?: "格式错误") + "）"); null }
            }
            ManualNodeFactory.supportsShareLink(raw) -> {
                parseShareLinkNode(raw, groupId, sourceLabel, index)
                    ?: run { skipType(raw.substringBefore("://")); null }
            }
            // Surge / Loon / Quantumult X proxy lines.
            else -> MihomoNodes.fromConfLine(raw)?.let { parseClashProxyMap(it, groupId, sourceLabel, index) }
        }
    }

    private fun parseShareLinkNode(
        raw: String,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode? {
        return runCatching {
            val node = ManualNodeFactory.buildNodeFromShareLink(raw)
            val normalizedName = node.name.ifBlank { "$sourceLabel #$index" }
            node.copy(
                id = "${groupId}_${index}_${stableSuffix(raw)}",
                groupId = groupId,
                name = normalizedName,
                code = buildCode(node.address, index),
                subscription = sourceLabel,
                description = if (groupId == LOCAL_GROUP_ID) "本地导入单节点" else "来自订阅组 $sourceLabel",
                rawUri = raw.trim(),
            )
        }.getOrNull()
    }

    private fun looksLikeHttpProxyShareLink(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        return !uri.userInfo.isNullOrBlank() || !uri.rawFragment.isNullOrBlank()
    }

    private fun parseJsonSubscription(
        payload: String,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val trimmed = payload.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        return runCatching {
            if (trimmed.startsWith("{")) {
                parseJsonRoot(JSONObject(payload), groupId, sourceLabel)
            } else {
                parseJsonArray(JSONArray(payload), groupId, sourceLabel)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseJsonRoot(
        root: JSONObject,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val nodes = mutableListOf<ServerNode>()
        root.optJSONArray("servers")?.let { nodes += parseSip008Array(it, groupId, sourceLabel) }
        root.optJSONArray("proxies")?.let { proxies ->
            val maps = (0 until proxies.length()).mapNotNull { proxies.optJSONObject(it)?.toMap() }
            val groups = root.optJSONArray("proxy-groups")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.toMap() } }.orEmpty()
            nodes += withClashContext(maps, groups) { parseClashProxyArray(proxies, groupId, sourceLabel) }
        }
        // Xray / v2rayN full config: one node per proxy outbound, named by "remarks".
        val outbounds = root.optJSONArray("outbounds")
        if (outbounds != null && (0 until outbounds.length()).any { outbounds.optJSONObject(it)?.has("protocol") == true }) {
            val byTag = mutableMapOf<String, ServerNode>()
            val dialers = mutableListOf<Pair<ServerNode, String>>()
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val node = parseXrayOutbound(outbound, root.optString("remarks"), groupId, sourceLabel, i + 1) ?: continue
                nodes += node
                outbound.optString("tag").takeIf { it.isNotBlank() }?.let { byTag[it] = node }
                outbound.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")
                    ?.takeIf { it.isNotBlank() }?.let { dialers += node to it }
            }
            // sockopt.dialerProxy between two proxy outbounds → the app's pre-proxy chain.
            dialers.forEach { (node, tag) ->
                val front = byTag[tag] ?: return@forEach
                val index = nodes.indexOfFirst { it.id == node.id }
                if (index >= 0 && front.id != node.id) nodes[index] = nodes[index].copy(preProxyNodeId = front.id)
            }
            return nodes
        }
        root.optJSONArray("outbounds")?.let { nodes += parseSingBoxOutboundArray(it, groupId, sourceLabel) }
        // sing-box 1.11+ keeps WireGuard under "endpoints".
        root.optJSONArray("endpoints")?.let { nodes += parseSingBoxOutboundArray(it, groupId, sourceLabel) }
        return nodes
    }

    private fun parseJsonArray(
        array: JSONArray,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val nodes = mutableListOf<ServerNode>()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is String -> parseNodeLine(item, groupId, sourceLabel, i + 1)?.let(nodes::add)
                is JSONObject -> {
                    // An array of full Xray configs, or of Clash proxy maps.
                    val wrapped = if (item.has("outbounds")) item else JSONObject().put("proxies", JSONArray().put(item))
                    val batch = parseJsonRoot(wrapped, groupId, sourceLabel)
                    val ids = batch.associate { it.id to "${it.id}_$i" }
                    nodes += batch.map { node ->
                        node.copy(id = ids.getValue(node.id), preProxyNodeId = ids[node.preProxyNodeId] ?: node.preProxyNodeId)
                    }
                }
            }
        }
        return nodes
    }

    private fun parseYamlSubscription(
        payload: String,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        if (!Regex("(?m)^(proxies|proxy-providers|outbounds|endpoints|servers)\\s*:").containsMatchIn(payload)) return emptyList()
        return runCatching {
            val root = Yaml().load<Any?>(payload)
            when (root) {
                is Map<*, *> -> parseYamlRoot(root, groupId, sourceLabel)
                is List<*> -> parseYamlList(root, groupId, sourceLabel)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseYamlRoot(
        root: Map<*, *>,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val nodes = mutableListOf<ServerNode>()
        (root["servers"] as? List<*>)?.let { nodes += parseSip008List(it, groupId, sourceLabel) }
        (root["proxies"] as? List<*>)?.let { proxies ->
            val groups = (root["proxy-groups"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            nodes += withClashContext(proxies.filterIsInstance<Map<*, *>>(), groups) { parseClashProxyList(proxies, groupId, sourceLabel) }
        }
        // Providers with an inline payload; HTTP providers are fetched by fetchProviderContents.
        (root["proxy-providers"] as? Map<*, *>)?.forEach { (providerName, provider) ->
            val map = provider as? Map<*, *> ?: return@forEach
            val url = map["url"]?.toString().orEmpty()
            if (map["payload"] == null && !url.startsWith("http://") && !url.startsWith("https://")) {
                // A local file on the machine that wrote the config; not readable here.
                skippedProviders.get().add(providerName.toString())
                return@forEach
            }
            val filter = ProviderFilter.from(map)
            val override = ProviderOverride.from(map)
            (map["payload"] as? List<*>)?.let { payload ->
                nodes += parseClashProxyList(payload.map { (it as? Map<*, *>)?.let(override::apply) ?: it }, groupId, sourceLabel)
                    .map { node -> node.copy(name = override.rename(node.name)) }
                    .filter(filter::keeps)
            }
        }
        (root["outbounds"] as? List<*>)?.let { nodes += parseSingBoxOutboundList(it, groupId, sourceLabel) }
        (root["endpoints"] as? List<*>)?.let { nodes += parseSingBoxOutboundList(it, groupId, sourceLabel) }
        return nodes
    }

    private fun parseYamlList(
        entries: List<*>,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val nodes = mutableListOf<ServerNode>()
        entries.forEachIndexed { index, item ->
            when (item) {
                is String -> parseNodeLine(item, groupId, sourceLabel, index + 1)?.let(nodes::add)
                is Map<*, *> -> parseClashProxyMap(item, groupId, sourceLabel, index + 1)?.let(nodes::add)
            }
        }
        return nodes
    }

    private fun parseSip008Array(
        servers: JSONArray,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        return buildList {
            for (i in 0 until servers.length()) {
                val item = servers.optJSONObject(i) ?: continue
                parseSip008Map(item.toMap(), groupId, sourceLabel, i + 1)?.let(::add)
            }
        }
    }

    private fun parseSip008List(
        servers: List<*>,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        return buildList {
            servers.forEachIndexed { index, item ->
                parseSip008Map(item as? Map<*, *> ?: return@forEachIndexed, groupId, sourceLabel, index + 1)?.let(::add)
            }
        }
    }

    private fun parseSip008Map(
        item: Map<*, *>,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode? {
        val address = item.string("server") ?: return null
        val port = item.intLike("server_port")?.toString() ?: return null
        val method = item.string("method") ?: return null
        val password = item.string("password") ?: return null
        val plugin = item.string("plugin").orEmpty()
        val baseDraft = ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.SHADOWSOCKS).copy(
            nodeName = item.string("remarks").orEmpty(),
            address = address,
            port = port,
            password = password,
            shadowsocksMethod = shadowsocksMethod(method),
        )
        val draft = applyShadowsocksPluginOptions(
            draft = baseDraft,
            plugin = plugin,
            options = parsePluginOptions(item.string("plugin_opts")),
        ) ?: return null
        return buildImportedNode(draft, groupId, sourceLabel, index)
    }

    private fun parseClashProxyArray(
        proxies: JSONArray,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        return buildList {
            for (i in 0 until proxies.length()) {
                parseClashProxyMap(proxies.optJSONObject(i)?.toMap() ?: continue, groupId, sourceLabel, i + 1)?.let(::add)
            }
        }
    }

    private fun parseClashProxyList(
        proxies: List<*>,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        return buildList {
            proxies.forEachIndexed { index, item ->
                parseClashProxyMap(item as? Map<*, *> ?: return@forEachIndexed, groupId, sourceLabel, index + 1)?.let(::add)
            }
        }
    }

    private fun parseClashProxyMap(
        item: Map<*, *>,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode? {
        val type = item.string("type")?.lowercase(Locale.ROOT) ?: return null
        if (type in NON_PROXY_TYPES) return null
        val draft = runCatching { when (type) {
            "ss", "shadowsocks" -> buildClashShadowsocksDraft(item)
            "ssr", "shadowsocksr" -> buildClashShadowsocksRDraft(item)
            "vmess" -> buildClashVmessDraft(item)
            "vless" -> buildClashVlessDraft(item)
            "trojan" -> buildClashTrojanDraft(item)
            "socks5", "socks" -> buildClashSocksDraft(item)
            "http", "https" -> buildClashHttpDraft(item, tls = type == "https")
            "hysteria2", "hy2" -> buildClashHysteria2Draft(item)
            "wireguard" -> buildClashWireGuardDraft(item)
            else -> null
        } }.getOrNull()
        // Prefer Xray; anything it can't run goes to mihomo as the original Clash map.
        val xrayNode = draft?.takeUnless { needsMihomo(type, item) }?.let { buildImportedNode(it, groupId, sourceLabel, index) }
        // (SSR already has its own editable node that runs in the sidecar.)
        if (xrayNode != null && NodeCompatibility.coreIncompatibility(xrayNode) == null &&
            (ManualNodeFactory.isSsrNode(xrayNode) || !NodeCompatibility.runsInSidecar(xrayNode))
        ) {
            return xrayNode
        }
        return buildMihomoNode(item, groupId, sourceLabel, index) ?: xrayNode ?: run { skipType(type); null }
    }

    /** Clash features Xray can't express: port hopping and dialer-proxy chains. */
    private fun needsMihomo(type: String, item: Map<*, *>): Boolean =
        (type in setOf("hysteria2", "hy2") && !item.string("ports").isNullOrBlank()) ||
            !item.string("dialer-proxy").isNullOrBlank()

    private val NON_PROXY_TYPES = setOf("direct", "reject", "dns", "pass", "compatible", "select", "url-test", "fallback", "load-balance", "relay")

    private fun skipType(type: String) {
        skippedTypes.get().add(type)
    }

    /** A node that runs in the mihomo sidecar, from a Clash/mihomo proxy map. */
    private fun buildMihomoNode(
        item: Map<*, *>,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode? {
        val name = item.string("name").orEmpty().ifBlank { "$sourceLabel #$index" }
        val config = MihomoNodes.sanitize(item, name) ?: return null
        item.string("dialer-proxy")?.let { attachDialer(config, it, depth = 0) }
        val address = config.optString("server")
        val type = config.optString("type")
        return ServerNode(
            id = "${groupId}_${index}_${stableSuffix(name)}",
            groupId = groupId,
            name = name,
            code = buildCode(address, index),
            subscription = sourceLabel,
            region = inferRegion(name, address),
            latencyMs = 0,
            protocol = MihomoNodes.protocolLabel(type),
            security = MihomoNodes.securityLabel(config),
            transport = MihomoNodes.transportLabel(config),
            description = if (groupId == LOCAL_GROUP_ID) "本地导入单节点" else "来自订阅组 $sourceLabel",
            address = address,
            port = config.opt("port")?.toString().orEmpty(),
            flow = config.optString("flow").ifBlank { "无" },
            stable = false,
            favorite = false,
            rawUri = "",
            mihomoProxyJson = config.toString(),
        )
    }

    /**
     * Xray's classic `vnext` / `servers` + `users` layout → the flat layout the
     * app writes (address, port and the first user's fields side by side).
     */
    private fun flattenXraySettings(settings: JSONObject): JSONObject {
        if (settings.has("address")) return settings
        val endpoint = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0)
            ?: return settings
        val flat = JSONObject()
        settings.keys().forEach { key -> if (key != "vnext" && key != "servers") flat.put(key, settings.get(key)) }
        endpoint.keys().forEach { key -> if (key != "users") flat.put(key, endpoint.get(key)) }
        endpoint.optJSONArray("users")?.optJSONObject(0)?.let { user ->
            user.keys().forEach { key -> if (!flat.has(key)) flat.put(key, user.get(key)) }
        }
        return flat
    }

    /** Clash proxies and proxy-groups of the config being parsed, for dialer-proxy lookups. */
    private val clashContext = ThreadLocal<Map<String, Map<*, *>>?>()

    private fun <T> withClashContext(proxies: List<Map<*, *>>, groups: List<Map<*, *>>, block: () -> T): T {
        val previous = clashContext.get()
        clashContext.set((groups + proxies).mapNotNull { map -> map.string("name")?.let { it to map } }.toMap())
        return try { block() } finally { clashContext.set(previous) }
    }

    /**
     * Embeds the dialer-proxy front node (a proxy, or a group's first member)
     * as "pb-dialer"; MihomoProxy expands it into a mihomo chain at run time.
     */
    private fun attachDialer(config: JSONObject, frontName: String, depth: Int) {
        if (depth > 3) return
        var target = clashContext.get()?.get(frontName) ?: return
        var hops = 0
        while (target["proxies"] is List<*> && target["server"] == null && hops++ < 4) {
            val first = (target["proxies"] as List<*>).firstOrNull()?.toString() ?: return
            target = clashContext.get()?.get(first) ?: return
        }
        val front = MihomoNodes.sanitize(target, "front") ?: return
        target.string("dialer-proxy")?.let { attachDialer(front, it, depth + 1) }
        config.put(MihomoProxy.DIALER_KEY, front)
    }

    /** One outbound of an Xray / v2rayN JSON config. */
    private fun parseXrayOutbound(
        outbound: JSONObject,
        remarks: String,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode? {
        val protocol = outbound.optString("protocol").lowercase(Locale.ROOT)
        if (protocol in setOf("freedom", "blackhole", "dns", "loopback", "")) return null
        val tag = outbound.optString("tag").takeUnless { it.isBlank() || it in setOf("proxy", "out", "outbound", "agentout") }
        val name = tag ?: remarks.ifBlank { "$sourceLabel #$index" }
        val settings = flattenXraySettings(outbound.optJSONObject("settings") ?: JSONObject())
        val flatOutbound = JSONObject(outbound.toString()).put("settings", settings)
        val endpoint = if (settings.has("address")) settings else
            settings.optJSONArray("vnext")?.optJSONObject(0) ?: settings.optJSONArray("servers")?.optJSONObject(0)
                ?: settings.optJSONArray("peers")?.optJSONObject(0)
        val endpointAddress = endpoint?.optString("address").orEmpty()
            .ifBlank { endpoint?.optString("endpoint").orEmpty().substringBeforeLast(':') }
        val endpointPort = endpoint?.optInt("port")?.takeIf { it > 0 }?.toString()
            ?: endpoint?.optString("endpoint").orEmpty().substringAfterLast(':', "")
        val probe = ServerNode(
            id = "probe", groupId = groupId, name = name, code = "", subscription = sourceLabel, region = "",
            latencyMs = 0, protocol = protocol.uppercase(Locale.ROOT), security = "", transport = "", description = "",
            address = endpointAddress, port = endpointPort, flow = "", stable = false, favorite = false, rawUri = "",
            outboundJson = flatOutbound.toString(),
        )
        val xrayNode = runCatching { ManualNodeFactory.prefillFromServerNode(probe) }.getOrNull()
            ?.let { buildImportedNode(it.copy(nodeName = name), groupId, sourceLabel, index) }
        if (xrayNode != null && NodeCompatibility.coreIncompatibility(xrayNode) == null) return xrayNode
        return MihomoNodes.fromXrayOutbound(outbound, name)?.let { buildMihomoNode(it, groupId, sourceLabel, index) }
            ?: xrayNode ?: run { skipType(protocol); null }
    }

    private fun parseSingBoxOutboundArray(
        outbounds: JSONArray,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val items = (0 until outbounds.length()).map { outbounds.optJSONObject(it)?.toMap() }
        return parseSingBoxOutboundList(items, groupId, sourceLabel)
    }

    private fun parseSingBoxOutboundList(
        outbounds: List<*>,
        groupId: String,
        sourceLabel: String,
    ): List<ServerNode> {
        val byTag = outbounds.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            map["tag"]?.toString()?.let { it to map }
        }.toMap()
        return buildList {
            outbounds.forEachIndexed { index, item ->
                parseSingBoxOutboundMap(item as? Map<*, *> ?: return@forEachIndexed, groupId, sourceLabel, index + 1, byTag)?.let(::add)
            }
        }
    }

    private fun parseSingBoxOutboundMap(
        item: Map<*, *>,
        groupId: String,
        sourceLabel: String,
        index: Int,
        byTag: Map<String, Map<*, *>> = emptyMap(),
    ): ServerNode? {
        val type = item.string("type")?.lowercase(Locale.ROOT) ?: return null
        // Shadowsocks over shadow-tls (detour) and plugins: only mihomo runs these.
        val detourType = item.string("detour")?.let(byTag::get)?.string("type")
        val hopping = type == "hysteria2" && (item["server_ports"] as? List<*>)?.isNotEmpty() == true
        if (hopping || (type == "shadowsocks" && (detourType == "shadowtls" || !item.string("plugin").isNullOrBlank()))) {
            return MihomoNodes.fromSingBox(item, byTag)?.let { parseClashProxyMap(it, groupId, sourceLabel, index) }
        }
        val draft = runCatching { when (type) {
            "shadowsocks" -> buildSingBoxShadowsocksDraft(item)
            "vmess" -> buildSingBoxVmessDraft(item)
            "vless" -> buildSingBoxVlessDraft(item)
            "trojan" -> buildSingBoxTrojanDraft(item)
            "socks" -> buildSingBoxSocksDraft(item)
            "http" -> buildSingBoxHttpDraft(item)
            "hysteria2" -> buildSingBoxHysteria2Draft(item)
            "wireguard" -> buildSingBoxWireGuardDraft(item)
            "shadowtls", "direct", "block", "dns", "selector", "urltest" -> return null
            else -> null
        } }.getOrNull() ?: return MihomoNodes.fromSingBox(item, byTag)?.let { parseClashProxyMap(it, groupId, sourceLabel, index) }
            ?: run { skipType(type); null }
        return buildImportedNode(draft, groupId, sourceLabel, index)
    }

    private fun buildImportedNode(
        draft: LocalNodeDraft,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode? {
        return runCatching {
            val node = ManualNodeFactory.buildServerNode(draft)
            val name = node.name.ifBlank { "$sourceLabel #$index" }
            node.copy(
                id = "${groupId}_${index}_${stableSuffix(name)}",
                groupId = groupId,
                name = name,
                code = buildCode(node.address, index),
                subscription = sourceLabel,
                description = if (groupId == LOCAL_GROUP_ID) "本地导入单节点" else "来自订阅组 $sourceLabel",
            )
        }.getOrNull()
    }

    private fun buildClashShadowsocksDraft(item: Map<*, *>): LocalNodeDraft? {
        val baseDraft = ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.SHADOWSOCKS).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            password = item.string("password").orEmpty(),
            shadowsocksMethod = shadowsocksMethod(item.string("cipher")),
            shadowsocksUot = item.boolLike("udp-over-tcp") == true,
        )
        return applyShadowsocksPluginOptions(
            draft = baseDraft,
            plugin = item.string("plugin").orEmpty(),
            options = item.map("plugin-opts") ?: emptyMap<String, Any?>(),
        )
    }

    private fun buildClashShadowsocksRDraft(item: Map<*, *>): LocalNodeDraft? {
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.SHADOWSOCKSR).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            password = item.string("password").orEmpty(),
            ssrMethod = item.string("cipher").orEmpty().ifBlank { "aes-256-cfb" },
            ssrProtocol = item.string("protocol").orEmpty().ifBlank { "origin" },
            ssrProtocolParam = item.string("protocol-param").orEmpty(),
            ssrObfs = item.string("obfs").orEmpty().ifBlank { "plain" },
            ssrObfsParam = item.string("obfs-param").orEmpty(),
            ssrUdp = item.boolLike("udp") ?: true,
            security = LocalNodeSecurity.NONE,
        )
    }

    private fun buildClashVmessDraft(item: Map<*, *>): LocalNodeDraft? {
        val ws = item.map("ws-opts")
        val grpc = item.map("grpc-opts")
        val http = item.map("http-opts")
        val reality = item.map("reality-opts")
        val transport = transportFromType(item.string("network"))
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.VMESS).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            userId = item.string("uuid").orEmpty(),
            vmessSecurity = item.string("cipher").orEmpty().ifBlank { "auto" },
            transport = transport,
            security = when {
                reality != null -> LocalNodeSecurity.REALITY
                item.boolLike("tls") == true -> LocalNodeSecurity.TLS
                else -> LocalNodeSecurity.NONE
            },
            host = ws?.headersHost().orEmpty().ifBlank { item.string("servername").orEmpty() },
            path = ws?.string("path").orEmpty().ifBlank { http?.string("path").orEmpty() },
            serviceName = grpc?.string("grpc-service-name").orEmpty(),
            serverName = item.string("servername").orEmpty().ifBlank { item.string("sni").orEmpty() },
            fingerprint = item.string("client-fingerprint").orEmpty(),
            alpn = item.csv("alpn"),
            allowInsecure = item.boolLike("skip-cert-verify") == true,
            pinnedPeerCertSha256 = item.clashCertificatePin(),
            realityPublicKey = reality?.string("public-key").orEmpty(),
            realityShortId = reality?.string("short-id").orEmpty(),
        )
    }

    private fun buildClashVlessDraft(item: Map<*, *>): LocalNodeDraft? {
        val ws = item.map("ws-opts")
        val grpc = item.map("grpc-opts")
        val http = item.map("http-opts")
        val reality = item.map("reality-opts")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.VLESS).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            userId = item.string("uuid").orEmpty(),
            vlessFlow = item.string("flow").orEmpty(),
            transport = transportFromType(item.string("network")),
            security = when {
                reality != null -> LocalNodeSecurity.REALITY
                item.boolLike("tls") == true -> LocalNodeSecurity.TLS
                else -> LocalNodeSecurity.NONE
            },
            host = ws?.headersHost().orEmpty(),
            path = ws?.string("path").orEmpty().ifBlank { http?.string("path").orEmpty() },
            serviceName = grpc?.string("grpc-service-name").orEmpty(),
            serverName = item.string("servername").orEmpty().ifBlank { item.string("sni").orEmpty() },
            fingerprint = item.string("client-fingerprint").orEmpty(),
            alpn = item.csv("alpn"),
            allowInsecure = item.boolLike("skip-cert-verify") == true,
            pinnedPeerCertSha256 = item.clashCertificatePin(),
            realityPublicKey = reality?.string("public-key").orEmpty(),
            realityShortId = reality?.string("short-id").orEmpty(),
        )
    }

    private fun buildClashTrojanDraft(item: Map<*, *>): LocalNodeDraft? {
        val ws = item.map("ws-opts")
        val grpc = item.map("grpc-opts")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.TROJAN).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            password = item.string("password").orEmpty(),
            transport = transportFromType(item.string("network")),
            security = LocalNodeSecurity.TLS,
            host = ws?.headersHost().orEmpty(),
            path = ws?.string("path").orEmpty(),
            serviceName = grpc?.string("grpc-service-name").orEmpty(),
            serverName = item.string("servername").orEmpty().ifBlank { item.string("sni").orEmpty() },
            fingerprint = item.string("client-fingerprint").orEmpty(),
            alpn = item.csv("alpn"),
            allowInsecure = item.boolLike("skip-cert-verify") == true,
            pinnedPeerCertSha256 = item.clashCertificatePin(),
        )
    }

    private fun buildClashSocksDraft(item: Map<*, *>): LocalNodeDraft? {
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.SOCKS).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            username = item.string("username").orEmpty(),
            password = item.string("password").orEmpty(),
        )
    }

    private fun buildClashHttpDraft(item: Map<*, *>, tls: Boolean): LocalNodeDraft? {
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.HTTP).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            username = item.string("username").orEmpty(),
            password = item.string("password").orEmpty(),
            security = if (tls || item.boolLike("tls") == true) LocalNodeSecurity.TLS else LocalNodeSecurity.NONE,
            serverName = item.string("servername").orEmpty(),
            allowInsecure = item.boolLike("skip-cert-verify") == true,
            pinnedPeerCertSha256 = item.clashCertificatePin(),
        )
    }

    private fun buildClashHysteria2Draft(item: Map<*, *>): LocalNodeDraft? {
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.HYSTERIA).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            hysteriaAuth = item.string("password").orEmpty().ifBlank { item.string("auth").orEmpty() },
            serverName = item.string("sni").orEmpty(),
            alpn = item.csv("alpn"),
            allowInsecure = item.boolLike("skip-cert-verify") == true,
            pinnedPeerCertSha256 = item.clashCertificatePin(),
            hysteriaObfsPassword = item.string("obfs-password").orEmpty()
                .takeIf { item.string("obfs").orEmpty().equals("salamander", ignoreCase = true) }.orEmpty(),
        )
    }

    private fun buildClashWireGuardDraft(item: Map<*, *>): LocalNodeDraft? {
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.WIREGUARD).copy(
            nodeName = item.string("name").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("port")?.toString().orEmpty(),
            wireGuardSecretKey = item.string("private-key").orEmpty(),
            wireGuardPublicKey = item.string("public-key").orEmpty().ifBlank { item.string("peer-public-key").orEmpty() },
            wireGuardLocalAddresses = listOfNotNull(item.string("ip"), item.string("ipv6")).filter { it.isNotBlank() }.joinToString(", "),
            wireGuardAllowedIps = item.list("allowed-ips").joinToString(", "),
            wireGuardReserved = item.list("reserved").joinToString(","),
            wireGuardMtu = item.intLike("mtu")?.toString().orEmpty(),
        )
    }

    private fun buildSingBoxShadowsocksDraft(item: Map<*, *>): LocalNodeDraft? {
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.SHADOWSOCKS).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("server_port")?.toString().orEmpty(),
            password = item.string("password").orEmpty(),
            shadowsocksMethod = shadowsocksMethod(item.string("method")),
        )
    }

    private fun buildSingBoxVmessDraft(item: Map<*, *>): LocalNodeDraft? {
        val tls = item.map("tls")
        val transport = item.map("transport")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.VMESS).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("server_port")?.toString().orEmpty(),
            userId = item.string("uuid").orEmpty(),
            vmessSecurity = item.string("security").orEmpty().ifBlank { "auto" },
            transport = transportFromType(transport?.string("type")),
            security = if (tls?.boolLike("enabled") == true) {
                if (tls.map("reality")?.boolLike("enabled") == true) LocalNodeSecurity.REALITY else LocalNodeSecurity.TLS
            } else {
                LocalNodeSecurity.NONE
            },
            host = transport?.string("host").orEmpty().ifBlank { transport?.headersHost().orEmpty() },
            path = transport?.string("path").orEmpty(),
            serviceName = transport?.string("service_name").orEmpty(),
            serverName = tls?.string("server_name").orEmpty(),
            fingerprint = tls?.map("utls")?.string("fingerprint").orEmpty(),
            alpn = tls?.csv("alpn").orEmpty(),
            allowInsecure = tls?.boolLike("insecure") == true,
            realityPublicKey = tls?.map("reality")?.string("public_key").orEmpty(),
            realityShortId = tls?.map("reality")?.string("short_id").orEmpty(),
        )
    }

    private fun buildSingBoxVlessDraft(item: Map<*, *>): LocalNodeDraft? {
        val tls = item.map("tls")
        val transport = item.map("transport")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.VLESS).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("server_port")?.toString().orEmpty(),
            userId = item.string("uuid").orEmpty(),
            vlessFlow = item.string("flow").orEmpty(),
            transport = transportFromType(transport?.string("type")),
            security = if (tls?.boolLike("enabled") == true) {
                if (tls.map("reality")?.boolLike("enabled") == true) LocalNodeSecurity.REALITY else LocalNodeSecurity.TLS
            } else {
                LocalNodeSecurity.NONE
            },
            host = transport?.headersHost().orEmpty().ifBlank { transport?.string("host").orEmpty() },
            path = transport?.string("path").orEmpty(),
            serviceName = transport?.string("service_name").orEmpty(),
            serverName = tls?.string("server_name").orEmpty(),
            fingerprint = tls?.map("utls")?.string("fingerprint").orEmpty(),
            alpn = tls?.csv("alpn").orEmpty(),
            allowInsecure = tls?.boolLike("insecure") == true,
            realityPublicKey = tls?.map("reality")?.string("public_key").orEmpty(),
            realityShortId = tls?.map("reality")?.string("short_id").orEmpty(),
        )
    }

    private fun buildSingBoxTrojanDraft(item: Map<*, *>): LocalNodeDraft? {
        val tls = item.map("tls")
        val transport = item.map("transport")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.TROJAN).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("server_port")?.toString().orEmpty(),
            password = item.string("password").orEmpty(),
            transport = transportFromType(transport?.string("type")),
            security = LocalNodeSecurity.TLS,
            host = transport?.headersHost().orEmpty(),
            path = transport?.string("path").orEmpty(),
            serviceName = transport?.string("service_name").orEmpty(),
            serverName = tls?.string("server_name").orEmpty(),
            fingerprint = tls?.map("utls")?.string("fingerprint").orEmpty(),
            alpn = tls?.csv("alpn").orEmpty(),
            allowInsecure = tls?.boolLike("insecure") == true,
        )
    }

    private fun buildSingBoxSocksDraft(item: Map<*, *>): LocalNodeDraft? {
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.SOCKS).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("server_port")?.toString().orEmpty(),
            username = item.string("username").orEmpty(),
            password = item.string("password").orEmpty(),
        )
    }

    private fun buildSingBoxHttpDraft(item: Map<*, *>): LocalNodeDraft? {
        val tls = item.map("tls")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.HTTP).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("server_port")?.toString().orEmpty(),
            username = item.string("username").orEmpty(),
            password = item.string("password").orEmpty(),
            security = if (tls?.boolLike("enabled") == true) LocalNodeSecurity.TLS else LocalNodeSecurity.NONE,
            serverName = tls?.string("server_name").orEmpty(),
            allowInsecure = tls?.boolLike("insecure") == true,
        )
    }

    private fun buildSingBoxHysteria2Draft(item: Map<*, *>): LocalNodeDraft? {
        val tls = item.map("tls")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.HYSTERIA).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server").orEmpty(),
            port = item.intLike("server_port")?.toString().orEmpty(),
            hysteriaAuth = item.string("password").orEmpty(),
            serverName = tls?.string("server_name").orEmpty(),
            alpn = tls?.csv("alpn").orEmpty(),
            allowInsecure = tls?.boolLike("insecure") == true,
            hysteriaObfsPassword = item.map("obfs")
                ?.takeIf { it.string("type").orEmpty().equals("salamander", ignoreCase = true) }
                ?.string("password").orEmpty(),
        )
    }

    /**
     * sing-box has three WireGuard layouts: the legacy outbound (server /
     * server_port / peer_public_key at the top level), the legacy multi-peer
     * outbound (peers[].server), and the 1.11+ endpoint (`endpoints`, with
     * address = local addresses and peers[].address / port).
     */
    private fun buildSingBoxWireGuardDraft(item: Map<*, *>): LocalNodeDraft? {
        val peer = item.listMaps("peers").firstOrNull()
        val isEndpoint = item.string("server").isNullOrBlank() && peer?.string("address") != null
        val localAddresses = if (isEndpoint) item.list("address") else item.list("local_address")
        return ManualNodeFactory.applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.WIREGUARD).copy(
            nodeName = item.string("tag").orEmpty(),
            address = item.string("server") ?: peer?.string("server") ?: peer?.string("address").orEmpty(),
            port = (item.intLike("server_port") ?: peer?.intLike("server_port") ?: peer?.intLike("port"))?.toString().orEmpty(),
            wireGuardSecretKey = item.string("private_key").orEmpty(),
            wireGuardPublicKey = item.string("peer_public_key") ?: peer?.string("public_key").orEmpty(),
            wireGuardLocalAddresses = localAddresses.joinToString(", "),
            wireGuardAllowedIps = peer?.list("allowed_ips")?.joinToString(", ").orEmpty(),
            wireGuardReserved = (peer?.list("reserved")?.takeIf { it.isNotEmpty() } ?: item.list("reserved")).joinToString(","),
            wireGuardMtu = item.intLike("mtu")?.toString().orEmpty(),
        )
    }

    private fun applyShadowsocksPluginOptions(
        draft: LocalNodeDraft,
        plugin: String,
        options: Map<*, *>,
    ): LocalNodeDraft? {
        val trimmedPlugin = plugin.trim()
        if (trimmedPlugin.isBlank()) return draft
        if (!trimmedPlugin.equals("v2ray-plugin", ignoreCase = true)) return null
        val mode = options.string("mode").orEmpty().ifBlank { "websocket" }
        if (mode !in listOf("websocket", "ws")) return null
        val tlsEnabled = options.boolLike("tls") == true || options.string("tls")?.equals("true", ignoreCase = true) == true
        return draft.copy(
            transport = LocalNodeTransport.WS,
            security = if (tlsEnabled) LocalNodeSecurity.TLS else LocalNodeSecurity.NONE,
            host = options.string("host").orEmpty(),
            path = options.string("path").orEmpty(),
            serverName = options.string("host").orEmpty(),
        )
    }

    private fun parseVlessNode(
        rawUrl: String,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode {
        val uri = URI(rawUrl)
        val query = parseQuery(uri.rawQuery)
        val address = uri.host.orEmpty()
        val fragmentName = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
        val name = fragmentName ?: "$sourceLabel #$index"

        return ServerNode(
            id = "${groupId}_${index}_${stableSuffix(name)}",
            groupId = groupId,
            name = name,
            code = buildCode(address, index),
            subscription = sourceLabel,
            region = inferRegion(name, address),
            latencyMs = 0,
            protocol = (uri.scheme ?: "vless").uppercase(Locale.ROOT),
            security = query["security"]?.uppercase(Locale.ROOT) ?: "NONE",
            transport = query["type"]?.uppercase(Locale.ROOT) ?: "TCP",
            description = if (groupId == LOCAL_GROUP_ID) "本地导入单节点" else "来自订阅组 $sourceLabel",
            address = address,
            port = if (uri.port == -1) "443" else uri.port.toString(),
            flow = query["flow"].orEmpty(),
            stable = false,
            favorite = false,
            rawUri = rawUrl,
        )
    }

    private fun parseVmessNode(
        rawUrl: String,
        groupId: String,
        sourceLabel: String,
        index: Int,
    ): ServerNode {
        val body = rawUrl.removePrefix("vmess://")
        val authPart = body.substringBefore("?")
        val queryPart = body.substringAfter("?", "")
        val decodedAuth = runCatching {
            Base64.getDecoder().decode(padBase64(authPart)).decodeToString()
        }.getOrDefault(authPart)

        val params = parseQuery(queryPart)
        val decodedJson = decodedAuth.trim()
        // v2rayN-style links keep the name in the JSON "ps" field.
        val jsonName = if (decodedJson.startsWith("{")) {
            runCatching { org.json.JSONObject(decodedJson).optString("ps") }.getOrNull()
        } else {
            null
        }
        val name = params["remark"]?.ifBlank { null }
            ?: params["remarks"]?.ifBlank { null }
            ?: params["ps"]?.ifBlank { null }
            ?: jsonName?.trim()?.ifBlank { null }
            ?: "$sourceLabel #$index"

        return if (decodedJson.startsWith("{")) {
            val server = jsonField(decodedJson, "add").orEmpty()
            val port = jsonField(decodedJson, "port").orEmpty().ifBlank { "443" }
            ServerNode(
                id = "${groupId}_${index}_${stableSuffix(name)}",
                groupId = groupId,
                name = name,
                code = buildCode(server, index),
                subscription = sourceLabel,
                region = inferRegion(name, server),
                latencyMs = 0,
                protocol = "VMESS",
                security = jsonField(decodedJson, "tls")?.uppercase(Locale.ROOT) ?: "AUTO",
                transport = jsonField(decodedJson, "net")?.uppercase(Locale.ROOT) ?: "TCP",
                description = "来自订阅组 $sourceLabel",
                address = server,
                port = port,
                flow = "alterId=${jsonField(decodedJson, "aid") ?: "0"}",
                stable = false,
                favorite = false,
                rawUri = rawUrl,
            )
        } else {
            val hostPort = decodedAuth.substringAfter("@", decodedAuth)
            val server = hostPort.substringBeforeLast(":", hostPort)
            val port = hostPort.substringAfterLast(":", "443")
            val obfs = params["obfs"]?.uppercase(Locale.ROOT) ?: "TCP"
            val tls = if (params["tls"] == "1") "TLS" else "AUTO"
            ServerNode(
                id = "${groupId}_${index}_${stableSuffix(name)}",
                groupId = groupId,
                name = name,
                code = buildCode(server, index),
                subscription = sourceLabel,
                region = inferRegion(name, server),
                latencyMs = 0,
                protocol = "VMESS",
                security = tls,
                transport = obfs,
                description = "来自订阅组 $sourceLabel",
                address = server,
                port = port,
                flow = "alterId=${params["alterId"] ?: "0"}",
                stable = false,
                favorite = false,
                rawUri = rawUrl,
            )
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                val key = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                key to value
            }
            .toMap()
    }

    private fun stableGroupId(sourceUrl: String): String = "sub_${stableHash(sourceUrl)}"

    private fun stableHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)

    private fun decodeBase64Flexible(value: String, label: String): String {
        val normalized = padBase64(value.trim())
        return runCatching {
            Base64.getMimeDecoder().decode(normalized).toString(Charsets.UTF_8)
        }.recoverCatching {
            Base64.getUrlDecoder().decode(normalized).toString(Charsets.UTF_8)
        }.getOrElse {
            error("$label 不是可识别的 Base64 编码。")
        }
    }

    private fun parsePluginOptions(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';')
            .map(String::trim)
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) {
                    entry to "true"
                } else {
                    entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
                }
            }
    }

    private fun shadowsocksMethod(raw: String?): LocalNodeShadowsocksMethod {
        return LocalNodeShadowsocksMethod.entries.firstOrNull {
            it.wireValue.equals(raw?.trim(), ignoreCase = true)
        } ?: LocalNodeShadowsocksMethod.CHACHA20_POLY1305
    }

    private fun transportFromType(raw: String?): LocalNodeTransport {
        return when (val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "ws", "websocket" -> LocalNodeTransport.WS
            "grpc" -> LocalNodeTransport.GRPC
            "http", "h2" -> LocalNodeTransport.HTTP2
            "httpupgrade" -> LocalNodeTransport.HTTP_UPGRADE
            "xhttp" -> LocalNodeTransport.XHTTP
            "kcp", "mkcp" -> LocalNodeTransport.KCP
            "quic", "h3" -> error("暂不支持 $value 传输方式")
            else -> LocalNodeTransport.TCP
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        return keys().asSequence().associateWith { key ->
            when (val value = opt(key)) {
                JSONObject.NULL -> null
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            }
        }
    }

    private fun JSONArray.toList(): List<Any?> {
        return buildList {
            for (i in 0 until length()) {
                add(
                    when (val value = opt(i)) {
                        JSONObject.NULL -> null
                        is JSONObject -> value.toMap()
                        is JSONArray -> value.toList()
                        else -> value
                    },
                )
            }
        }
    }

    private fun Map<*, *>.string(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

    private fun Map<*, *>.intLike(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun Map<*, *>.boolLike(key: String): Boolean? = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase(Locale.ROOT)) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    private fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>

    private fun Map<*, *>.list(key: String): List<String> {
        val value = this[key] ?: return emptyList()
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            is String -> value.split(",").map(String::trim).filter(String::isNotBlank)
            else -> emptyList()
        }
    }

    private fun Map<*, *>.listMaps(key: String): List<Map<*, *>> {
        return (this[key] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
    }

    private fun Map<*, *>.csv(key: String): String {
        return when (val value = this[key]) {
            is List<*> -> value.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.joinToString(",")
            is String -> value
            else -> ""
        }
    }

    private fun Map<*, *>.headersHost(): String {
        val headers = map("headers") ?: return ""
        return headers.string("Host").orEmpty().ifBlank { headers.string("host").orEmpty() }
    }

    private fun jsonField(json: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun isDecorativeNode(node: ServerNode): Boolean {
        val source = "${node.name} ${node.address}".lowercase(Locale.ROOT)
        return listOf(
            "剩余流量",
            "距离下次重置",
            "套餐到期",
            "订阅链接已过期",
            "仅支持一次性使用",
            "请刷新页面获取最新链接",
            "关闭自动更新",
            "ednovas cloud",
            "user group",
            "user guide",
            "help.",
            "https://",
            "http://",
            "127.0.0.1",
            "expire@127.0.0.1",
        ).any(source::contains)
    }

    private fun containsRegularClientBlock(content: String): Boolean {
        val normalized = content.lowercase(Locale.ROOT)
        return normalized.contains("请使用正规客户端订阅") ||
            normalized.contains("使用正规客户端")
    }

    private fun containsExpiredSnapshotNotice(content: String): Boolean {
        val normalized = content.lowercase(Locale.ROOT)
        return normalized.contains("订阅链接已过期") ||
            normalized.contains("仅支持一次性使用") ||
            normalized.contains("请刷新页面获取最新链接")
    }

    private fun stableSuffix(input: String): String = input
        .lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString("")
        .trim('_')
        .take(24)
        .ifBlank { "imported" }

    private fun buildCode(address: String, index: Int): String {
        val prefix = address
            .substringBefore(".")
            .take(3)
            .uppercase(Locale.ROOT)
            .ifBlank { "LOC" }
        return "$prefix-${index.toString().padStart(2, '0')}"
    }

    private fun inferRegion(name: String, address: String): String {
        val source = "$name $address".lowercase(Locale.ROOT)
        return when {
            listOf("jp", "japan", "tokyo", "日本").any(source::contains) -> "日本"
            listOf("hk", "hongkong", "hong kong", "香港").any(source::contains) -> "香港"
            listOf("sg", "singapore", "新加坡").any(source::contains) -> "新加坡"
            listOf("us", "america", "losangeles", "united states", "美国").any(source::contains) -> "北美"
            listOf("uk", "eu", "frankfurt", "london", "sweden", "zurich", "欧洲").any(source::contains) -> "欧洲"
            else -> "未标注"
        }
    }

    private fun String.toHostLabel(): String {
        val url = runCatching { URL(this) }.getOrNull() ?: return "订阅组"
        val host = url.host.orEmpty().ifBlank { "订阅组" }
        val pathToken = url.path
            .split('/')
            .lastOrNull { it.isNotBlank() }
            ?.take(10)
            .orEmpty()
        return if (pathToken.isBlank()) host else "$host · $pathToken"
    }

    const val LOCAL_GROUP_ID = "local_group"
}
