package com.mallocgfw.app.model

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import com.mallocgfw.app.xray.ResourceFetchClient
import org.json.JSONArray
import org.json.JSONObject

object RuleSourceManager {
    private const val RULES_DIR = "rule-sources"
    private const val NODE_POLICY_PREFIX = "NODE:"
    private const val USER_RULE_PRIORITY = 2_000
    private const val SYSTEM_RULE_PRIORITY = 0
    private const val DEFAULT_SHADOWROCKET_ID = "rule_default_shadowrocket"
    private const val DEFAULT_SURGE_ID = "rule_default_surge"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    val defaultSources = listOf(
        RuleSourceItem(
            id = DEFAULT_SHADOWROCKET_ID,
            name = "Shadowrocket 默认规则",
            url = "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Shadowrocket/China/China_Domain.list",
            type = RuleSourceType.Shadowrocket,
            policy = RuleTargetPolicy.Direct,
            enabled = true,
            systemDefault = true,
            updatedAt = "未更新",
            status = RuleSourceStatus.Idle,
            totalRules = 0,
            convertedRules = 0,
            skippedRules = 0,
        ),
        RuleSourceItem(
            id = DEFAULT_SURGE_ID,
            name = "Surge 默认规则",
            url = "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Surge/China/China_Domain.list",
            type = RuleSourceType.Surge,
            policy = RuleTargetPolicy.Direct,
            enabled = true,
            systemDefault = true,
            updatedAt = "未更新",
            status = RuleSourceStatus.Idle,
            totalRules = 0,
            convertedRules = 0,
            skippedRules = 0,
        ),
    )

    fun ensureDefaults(sources: List<RuleSourceItem>): List<RuleSourceItem> {
        val currentById = sources.associateBy { it.id }
        val merged = buildList {
            defaultSources.forEach { default ->
                val existing = currentById[default.id]
                add(
                    if (existing == null) {
                        default
                    } else {
                        existing.copy(
                            url = default.url,
                            type = default.type,
                            policy = default.policy,
                            systemDefault = true,
                        )
                    },
                )
            }
            sources
                .filterNot { it.id == DEFAULT_SHADOWROCKET_ID || it.id == DEFAULT_SURGE_ID }
                .sortedBy { it.name.lowercase() }
                .forEach(::add)
        }
        return merged
    }

    fun createDraft(
        name: String,
        url: String,
        requestedType: RuleSourceType,
    ): RuleSourceItem {
        return RuleSourceItem(
            id = "rule_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            name = name.ifBlank { "自定义规则" },
            url = url.trim(),
            type = requestedType,
            policy = RuleTargetPolicy.Proxy,
            enabled = true,
            systemDefault = false,
            updatedAt = "未更新",
            status = RuleSourceStatus.Idle,
            totalRules = 0,
            convertedRules = 0,
            skippedRules = 0,
            sourceKind = RuleSourceKind.RemoteUrl,
        )
    }

    fun createTextDraft(
        name: String,
        text: String,
        requestedType: RuleSourceType,
    ): RuleSourceItem {
        return RuleSourceItem(
            id = "rule_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            name = name.ifBlank { "本地文本规则" },
            url = "local://manual-rule",
            type = requestedType,
            policy = RuleTargetPolicy.Proxy,
            enabled = true,
            systemDefault = false,
            updatedAt = "未更新",
            status = RuleSourceStatus.Idle,
            totalRules = 0,
            convertedRules = 0,
            skippedRules = 0,
            sourceKind = RuleSourceKind.LocalText,
            content = text,
        )
    }

    fun nodePolicyToken(server: ServerNode): String {
        val encodedKey = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(server.subscriptionMergeKey().toByteArray(StandardCharsets.UTF_8))
        val displayName = server.name.replace(",", " ").replace(Regex("\\s+"), "_").trim()
        return "$NODE_POLICY_PREFIX${server.id}:$encodedKey:$displayName"
    }

    fun missingNodePolicyLabels(text: String, servers: List<ServerNode>): List<String> {
        return extractNodePolicyTokens(text)
            .filter { resolveNodePolicy(it, servers) == null }
            .map { nodePolicyLabel(it) }
            .distinct()
    }

    fun validateTextRules(
        rawText: String,
        requestedType: RuleSourceType,
    ): RuleValidationResult {
        return runCatching {
            compileText(
                sourceId = "rule_validation",
                rawText = rawText,
                requestedType = requestedType,
            )
        }.fold(
            onSuccess = {
                if (it.skippedRules > 0) {
                    RuleValidationResult(
                        valid = false,
                        message = "有 ${it.skippedRules} 条规则无法识别，请修正后再保存。",
                        errorLineNumbers = it.skippedLineNumbers.toSet(),
                    )
                } else {
                    RuleValidationResult(valid = true)
                }
            },
            onFailure = { error ->
                RuleValidationResult(
                    valid = false,
                    message = error.message ?: "规则格式有错误，请修正后再保存。",
                    errorLineNumbers = (error as? RuleParseException)?.lineNumbers.orEmpty(),
                )
            },
        )
    }

    suspend fun refreshSource(
        context: Context,
        source: RuleSourceItem,
    ): RuleRefreshResult {
        return withContext(Dispatchers.IO) {
            val text = when (source.sourceKind) {
                RuleSourceKind.LocalText -> source.content
                RuleSourceKind.RemoteUrl -> fetchText(source.url)
            }
            val compiled = compileText(
                sourceId = source.id,
                rawText = text,
                requestedType = source.type,
                fallbackPolicy = source.policy,
            )
            saveCompiledRules(context, compiled)
            val updatedSource = source.copy(
                type = if (source.type == RuleSourceType.Auto) compiled.detectedType else source.type,
                updatedAt = "刚刚",
                status = RuleSourceStatus.Ready,
                totalRules = compiled.totalRules,
                convertedRules = compiled.convertedRules,
                skippedRules = compiled.skippedRules,
                lastError = null,
            )
            RuleRefreshResult(updatedSource, compiled)
        }
    }

    fun buildFailureState(source: RuleSourceItem, error: Throwable): RuleSourceItem {
        val status = if (error is RuleParseException) {
            RuleSourceStatus.ParseFailed
        } else {
            RuleSourceStatus.FetchFailed
        }
        return source.copy(
            status = status,
            lastError = error.message ?: "规则更新失败。",
        )
    }

    fun deleteSource(context: Context, source: RuleSourceItem) {
        compiledRuleFile(context, source.id).delete()
    }

    fun loadDetailSummary(context: Context, source: RuleSourceItem): RuleSourceDetailSummary {
        val compiled = loadCompiledRules(context, source.id)
        return RuleSourceDetailSummary(
            metadata = source,
            fullUrl = source.url,
            domainRuleCount = compiled?.domainCount ?: 0,
            ipRuleCount = compiled?.ipCount ?: 0,
            processRuleCount = compiled?.processNames?.size ?: 0,
        )
    }

    fun loadEnabledRoutingRules(
        context: Context,
        sources: List<RuleSourceItem>,
    ): List<XrayRoutingRule> {
        return buildRoutingSetup(context, sources, emptyList()).routingRules
    }

    fun buildRoutingSetup(
        context: Context,
        sources: List<RuleSourceItem>,
        servers: List<ServerNode>,
    ): RuleRoutingSetup {
        val outbounds = mutableListOf<XrayNamedOutbound>()
        val missingPolicies = linkedSetOf<String>()
        val routingRules = sources
            .filter { it.enabled && it.status == RuleSourceStatus.Ready }
            .flatMap { source ->
                val compiled = loadCompiledRules(context, source.id) ?: return@flatMap emptyList()
                compiled.toRoutingRules(
                    fallbackPolicy = source.policy,
                    priority = if (source.systemDefault) SYSTEM_RULE_PRIORITY else USER_RULE_PRIORITY,
                    servers = servers,
                    onResolvedNode = { tag, node ->
                        outbounds += XrayNamedOutbound(tag = tag, node = node)
                    },
                    onMissingNodePolicy = { missingPolicies += nodePolicyLabel(it) },
                )
            }
            .filter { it.hasEntries() }
        return RuleRoutingSetup(
            routingRules = routingRules,
            outbounds = outbounds.distinctBy { it.tag },
            missingNodePolicies = missingPolicies.toList(),
        )
    }

    private fun fetchText(url: String): String {
        return ResourceFetchClient.fetchText(
            url = url,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            label = "规则源",
        )
    }

    internal fun compileText(
        sourceId: String,
        rawText: String,
        requestedType: RuleSourceType,
        fallbackPolicy: RuleTargetPolicy = RuleTargetPolicy.Proxy,
    ): CompiledRuleSet {
        val lines = rawText.lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) {
                    null
                } else {
                    SourceRuleLine(lineNumber = index + 1, text = line)
                }
            }
            .toList()

        if (lines.isEmpty()) {
            throw RuleParseException("规则文件为空。")
        }

        lines.firstOrNull { it.text.startsWith("payload:", ignoreCase = true) || it.text.startsWith("rules:", ignoreCase = true) }?.let {
            throw RuleParseException(
                message = "当前只支持 Shadowrocket / Surge 文本规则，不支持 YAML。",
                lineNumbers = setOf(it.lineNumber),
            )
        }

        val detectedType = when (requestedType) {
            RuleSourceType.Auto -> detectType(lines.map { it.text })
            else -> requestedType
        }

        val ruleSegments = mutableListOf<MutableCompiledRuleSet>()
        val processNames = linkedSetOf<String>()
        val unsupportedKinds = mutableSetOf<String>()
        val noResolveRules = linkedSetOf<String>()
        var totalRules = 0
        var skippedRules = 0
        var convertedRuleEntries = 0
        val skippedLineNumbers = linkedSetOf<Int>()

        lines.forEach { sourceLine ->
            val normalized = sourceLine.text.substringBefore(" //").trim()
            if (normalized.isBlank()) return@forEach
            if (!normalized.contains(",")) {
                val bucket = ruleSegments.bucketFor(ParsedRulePolicy(fallbackPolicy))
                when {
                    normalized.startsWith(".") -> {
                        totalRules += 1
                        convertedRuleEntries += 1
                        bucket.domainSuffixes += normalized.removePrefix(".")
                    }

                    normalized.contains("/") && normalized.contains(":") -> {
                        totalRules += 1
                        convertedRuleEntries += 1
                        bucket.ipCidrs6 += normalized
                    }

                    normalized.contains("/") && normalized.any(Char::isDigit) -> {
                        totalRules += 1
                        convertedRuleEntries += 1
                        bucket.ipCidrs += normalized
                    }

                    normalized.matches(Regex("^[A-Za-z0-9*_-]+(\\.[A-Za-z0-9*_-]+)+$")) -> {
                        totalRules += 1
                        convertedRuleEntries += 1
                        bucket.fullDomains += normalized
                    }

                    else -> {
                        skippedRules += 1
                        skippedLineNumbers += sourceLine.lineNumber
                        unsupportedKinds += "未知格式"
                    }
                }
                return@forEach
            }
            val parts = normalized.split(",").map { it.trim() }
            val kind = parts.firstOrNull().orEmpty().uppercase()
            val value = parts.getOrNull(1).orEmpty()
            if (value.isBlank()) {
                skippedRules += 1
                skippedLineNumbers += sourceLine.lineNumber
                unsupportedKinds += kind.ifBlank { "未知格式" }
                return@forEach
            }
            totalRules += 1
            val thirdPart = parts.getOrNull(2)
            val thirdPartIsNoResolve = thirdPart.equals("no-resolve", ignoreCase = true)
            val policy = if (thirdPartIsNoResolve) {
                ParsedRulePolicy(fallbackPolicy)
            } else {
                parsePolicy(thirdPart, fallbackPolicy)
            }
            val bucket = ruleSegments.bucketFor(policy)
            val noResolve = parts.drop(if (thirdPartIsNoResolve) 2 else 3)
                .any { it.equals("no-resolve", ignoreCase = true) }
            when (kind) {
                "DOMAIN-SUFFIX" -> {
                    convertedRuleEntries += 1
                    bucket.domainSuffixes += value
                }
                "DOMAIN" -> {
                    convertedRuleEntries += 1
                    bucket.fullDomains += value
                }
                "DOMAIN-KEYWORD" -> {
                    convertedRuleEntries += 1
                    bucket.domainKeywords += value
                }
                "URL-REGEX" -> {
                    if (isDomainRegex(value)) {
                        convertedRuleEntries += 1
                        bucket.domainRegexes += value
                    } else {
                        skippedRules += 1
                        skippedLineNumbers += sourceLine.lineNumber
                        unsupportedKinds += kind
                    }
                }
                "IP-CIDR" -> {
                    convertedRuleEntries += 1
                    bucket.ipCidrs += value
                    if (noResolve) noResolveRules += "$kind,$value"
                }
                "IP-CIDR6" -> {
                    convertedRuleEntries += 1
                    bucket.ipCidrs6 += value
                    if (noResolve) noResolveRules += "$kind,$value"
                }
                "GEOIP" -> {
                    convertedRuleEntries += 1
                    bucket.ipCidrs += "geoip:${value.lowercase()}"
                    if (noResolve) noResolveRules += "$kind,$value"
                }
                "PROCESS-NAME" -> {
                    skippedRules += 1
                    skippedLineNumbers += sourceLine.lineNumber
                    unsupportedKinds += kind
                }
                "USER-AGENT", "IP-ASN", "AND", "OR", "DEST-PORT", "SRC-IP", "IN-PORT" -> {
                    skippedRules += 1
                    skippedLineNumbers += sourceLine.lineNumber
                    unsupportedKinds += kind
                }
                else -> {
                    skippedRules += 1
                    skippedLineNumbers += sourceLine.lineNumber
                    unsupportedKinds += kind.ifBlank { "未知格式" }
                }
            }
        }

        val routingRules = ruleSegments.mapNotNull { bucket ->
            bucket.toCompiledRoutingRule().takeIf { it.hasEntries() }
        }
        val domainSuffixes = linkedSetOf<String>()
        val fullDomains = linkedSetOf<String>()
        val domainKeywords = linkedSetOf<String>()
        val domainRegexes = linkedSetOf<String>()
        val ipCidrs = linkedSetOf<String>()
        val ipCidrs6 = linkedSetOf<String>()
        routingRules.forEach { rule ->
            domainSuffixes += rule.domainSuffixes
            fullDomains += rule.fullDomains
            domainKeywords += rule.domainKeywords
            domainRegexes += rule.domainRegexes
            ipCidrs += rule.ipCidrs
            ipCidrs6 += rule.ipCidrs6
        }
        val convertedRules = convertedRuleEntries
        if (convertedRules == 0) {
            throw RuleParseException("未识别到可转换的 Shadowrocket / Surge 规则。")
        }

        return CompiledRuleSet(
            sourceId = sourceId,
            detectedType = detectedType,
            totalRules = totalRules,
            convertedRules = convertedRules,
            skippedRules = maxOf(skippedRules, totalRules - convertedRules),
            unsupportedKinds = unsupportedKinds.toList().sorted(),
            domainSuffixes = domainSuffixes.toList(),
            fullDomains = fullDomains.toList(),
            domainKeywords = domainKeywords.toList(),
            domainRegexes = domainRegexes.toList(),
            ipCidrs = ipCidrs.toList(),
            ipCidrs6 = ipCidrs6.toList(),
            processNames = processNames.toList(),
            routingRules = routingRules,
            noResolveRules = noResolveRules.toList(),
            skippedLineNumbers = skippedLineNumbers.toList(),
        )
    }

    private fun parsePolicy(rawPolicy: String?, fallbackPolicy: RuleTargetPolicy): ParsedRulePolicy {
        val policy = rawPolicy.orEmpty().trim()
        val normalized = policy.uppercase()
        return when {
            normalized.isBlank() -> ParsedRulePolicy(fallbackPolicy)
            normalized == "DIRECT" -> ParsedRulePolicy(RuleTargetPolicy.Direct)
            normalized == "PROXY" -> ParsedRulePolicy(RuleTargetPolicy.Proxy)
            normalized.startsWith("REJECT") -> ParsedRulePolicy(RuleTargetPolicy.Block)
            policy.startsWith(NODE_POLICY_PREFIX, ignoreCase = true) -> ParsedRulePolicy(
                target = RuleTargetPolicy.Proxy,
                nodePolicy = policy,
            )
            else -> ParsedRulePolicy(RuleTargetPolicy.Proxy)
        }
    }

    private fun isDomainRegex(value: String): Boolean {
        if (value.contains("://") || value.contains("/") || value.contains("?")) return false
        return value.any { it == '.' || it == '$' || it == '^' || it == '*' || it == '+' || it == '[' || it == '(' }
    }

    private fun detectType(lines: List<String>): RuleSourceType {
        val sample = lines.take(min(25, lines.size)).joinToString("\n")
        return when {
            sample.contains("DOMAIN-SUFFIX", ignoreCase = true) ||
                sample.contains("IP-CIDR", ignoreCase = true) -> RuleSourceType.Shadowrocket
            else -> RuleSourceType.Surge
        }
    }

    private fun saveCompiledRules(context: Context, compiled: CompiledRuleSet) {
        val payload = JSONObject().apply {
            put("sourceId", compiled.sourceId)
            put("detectedType", compiled.detectedType.name)
            put("totalRules", compiled.totalRules)
            put("convertedRules", compiled.convertedRules)
            put("skippedRules", compiled.skippedRules)
            put("unsupportedKinds", JSONArray().apply { compiled.unsupportedKinds.forEach(::put) })
            put("domainSuffixes", JSONArray().apply { compiled.domainSuffixes.forEach(::put) })
            put("fullDomains", JSONArray().apply { compiled.fullDomains.forEach(::put) })
            put("domainKeywords", JSONArray().apply { compiled.domainKeywords.forEach(::put) })
            put("domainRegexes", JSONArray().apply { compiled.domainRegexes.forEach(::put) })
            put("ipCidrs", JSONArray().apply { compiled.ipCidrs.forEach(::put) })
            put("ipCidrs6", JSONArray().apply { compiled.ipCidrs6.forEach(::put) })
            put("processNames", JSONArray().apply { compiled.processNames.forEach(::put) })
            put("routingRules", JSONArray().apply { compiled.routingRules.forEach { put(it.toJson()) } })
            put("noResolveRules", JSONArray().apply { compiled.noResolveRules.forEach(::put) })
        }
        compiledRuleFile(context, compiled.sourceId).apply {
            parentFile?.mkdirs()
            writeText(payload.toString())
        }
    }

    private fun loadCompiledRules(context: Context, sourceId: String): CompiledRuleSet? {
        val file = compiledRuleFile(context, sourceId)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            CompiledRuleSet(
                sourceId = json.getString("sourceId"),
                detectedType = RuleSourceType.valueOf(json.optString("detectedType", RuleSourceType.Auto.name)),
                totalRules = json.optInt("totalRules", 0),
                convertedRules = json.optInt("convertedRules", 0),
                skippedRules = json.optInt("skippedRules", 0),
                unsupportedKinds = json.optJSONArray("unsupportedKinds").toStrings(),
                domainSuffixes = json.optJSONArray("domainSuffixes").toStrings(),
                fullDomains = json.optJSONArray("fullDomains").toStrings(),
                domainKeywords = json.optJSONArray("domainKeywords").toStrings(),
                domainRegexes = json.optJSONArray("domainRegexes").toStrings(),
                ipCidrs = json.optJSONArray("ipCidrs").toStrings(),
                ipCidrs6 = json.optJSONArray("ipCidrs6").toStrings(),
                processNames = json.optJSONArray("processNames").toStrings(),
                routingRules = json.optJSONArray("routingRules").toCompiledRoutingRules(),
                noResolveRules = json.optJSONArray("noResolveRules").toStrings(),
            )
        }.getOrNull()
    }

    private fun compiledRuleFile(context: Context, sourceId: String): File {
        return File(context.filesDir, "$RULES_DIR/$sourceId.json")
    }

    private fun JSONArray?.toStrings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
    }

    private fun JSONArray?.toCompiledRoutingRules(): List<CompiledRoutingRule> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = optJSONObject(index) ?: return@List null
            CompiledRoutingRule(
                target = runCatching {
                    RuleTargetPolicy.valueOf(item.optString("target", RuleTargetPolicy.Proxy.name))
                }.getOrDefault(RuleTargetPolicy.Proxy),
                domainSuffixes = item.optJSONArray("domainSuffixes").toStrings(),
                fullDomains = item.optJSONArray("fullDomains").toStrings(),
                domainKeywords = item.optJSONArray("domainKeywords").toStrings(),
                domainRegexes = item.optJSONArray("domainRegexes").toStrings(),
                ipCidrs = item.optJSONArray("ipCidrs").toStrings(),
                ipCidrs6 = item.optJSONArray("ipCidrs6").toStrings(),
                nodePolicy = item.optString("nodePolicy").takeIf { it.isNotBlank() },
            )
        }.filterNotNull().filter { it.hasEntries() }
    }

    private fun CompiledRoutingRule.toJson(): JSONObject {
        return JSONObject().apply {
            put("target", target.name)
            put("domainSuffixes", JSONArray().apply { domainSuffixes.forEach(::put) })
            put("fullDomains", JSONArray().apply { fullDomains.forEach(::put) })
            put("domainKeywords", JSONArray().apply { domainKeywords.forEach(::put) })
            put("domainRegexes", JSONArray().apply { domainRegexes.forEach(::put) })
            put("ipCidrs", JSONArray().apply { ipCidrs.forEach(::put) })
            put("ipCidrs6", JSONArray().apply { ipCidrs6.forEach(::put) })
            putOpt("nodePolicy", nodePolicy)
        }
    }
}

private data class ParsedRulePolicy(
    val target: RuleTargetPolicy,
    val nodePolicy: String? = null,
)

private data class MutableCompiledRuleSet(
    val policy: ParsedRulePolicy,
    val domainSuffixes: LinkedHashSet<String> = linkedSetOf(),
    val fullDomains: LinkedHashSet<String> = linkedSetOf(),
    val domainKeywords: LinkedHashSet<String> = linkedSetOf(),
    val domainRegexes: LinkedHashSet<String> = linkedSetOf(),
    val ipCidrs: LinkedHashSet<String> = linkedSetOf(),
    val ipCidrs6: LinkedHashSet<String> = linkedSetOf(),
) {
    fun toCompiledRoutingRule(): CompiledRoutingRule {
        return CompiledRoutingRule(
            target = policy.target,
            domainSuffixes = domainSuffixes.toList(),
            fullDomains = fullDomains.toList(),
            domainKeywords = domainKeywords.toList(),
            domainRegexes = domainRegexes.toList(),
            ipCidrs = ipCidrs.toList(),
            ipCidrs6 = ipCidrs6.toList(),
            nodePolicy = policy.nodePolicy,
        )
    }
}

private fun MutableList<MutableCompiledRuleSet>.bucketFor(policy: ParsedRulePolicy): MutableCompiledRuleSet {
    val current = lastOrNull()
    if (current?.policy == policy) return current
    return MutableCompiledRuleSet(policy).also(::add)
}

private val nodePolicyRegex = Regex("""NODE:([^,\s]+)""")

private fun extractNodePolicyTokens(text: String): List<String> {
    return nodePolicyRegex.findAll(text)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .toList()
}

private fun resolveNodePolicy(policy: String, servers: List<ServerNode>): ServerNode? {
    if (!policy.startsWith("NODE:", ignoreCase = true)) return null
    val payload = policy.substringAfter("NODE:")
    val parts = payload.split(":", limit = 3)
    val nodeId = parts.getOrNull(0).orEmpty()
    val mergeKey = parts.getOrNull(1)
        ?.let { encoded ->
            runCatching {
                String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
            }.getOrNull()
        }
        .orEmpty()
    return servers.firstOrNull { it.id == nodeId && !it.hiddenUnsupported }
        ?: servers.firstOrNull { mergeKey.isNotBlank() && it.subscriptionMergeKey() == mergeKey && !it.hiddenUnsupported }
}

private fun nodePolicyLabel(policy: String): String {
    if (!policy.startsWith("NODE:", ignoreCase = true)) return policy
    return policy.substringAfter("NODE:")
        .split(":", limit = 3)
        .getOrNull(2)
        ?.takeIf { it.isNotBlank() }
        ?: policy
}

data class RuleRefreshResult(
    val source: RuleSourceItem,
    val compiled: CompiledRuleSet,
)

data class RuleValidationResult(
    val valid: Boolean,
    val message: String? = null,
    val errorLineNumbers: Set<Int> = emptySet(),
)

data class RuleRoutingSetup(
    val routingRules: List<XrayRoutingRule> = emptyList(),
    val outbounds: List<XrayNamedOutbound> = emptyList(),
    val missingNodePolicies: List<String> = emptyList(),
)

data class CompiledRuleSet(
    val sourceId: String,
    val detectedType: RuleSourceType,
    val totalRules: Int,
    val convertedRules: Int,
    val skippedRules: Int,
    val unsupportedKinds: List<String>,
    val domainSuffixes: List<String>,
    val fullDomains: List<String>,
    val domainKeywords: List<String>,
    val domainRegexes: List<String> = emptyList(),
    val ipCidrs: List<String>,
    val ipCidrs6: List<String>,
    val processNames: List<String>,
    val routingRules: List<CompiledRoutingRule> = emptyList(),
    val noResolveRules: List<String> = emptyList(),
    val skippedLineNumbers: List<Int> = emptyList(),
) {
    val domainCount: Int get() = domainSuffixes.size + fullDomains.size + domainKeywords.size + domainRegexes.size
    val ipCount: Int get() = ipCidrs.size + ipCidrs6.size

    fun toRoutingRules(
        fallbackPolicy: RuleTargetPolicy,
        priority: Int = 0,
        servers: List<ServerNode> = emptyList(),
        onResolvedNode: (String, ServerNode) -> Unit = { _, _ -> },
        onMissingNodePolicy: (String) -> Unit = {},
    ): List<XrayRoutingRule> {
        val groupedRules = routingRules.takeIf { it.isNotEmpty() }
            ?: listOf(
                CompiledRoutingRule(
                    target = fallbackPolicy,
                    domainSuffixes = domainSuffixes,
                    fullDomains = fullDomains,
                    domainKeywords = domainKeywords,
                    domainRegexes = domainRegexes,
                    ipCidrs = ipCidrs,
                    ipCidrs6 = ipCidrs6,
                ),
            )
        return groupedRules.map {
            val resolvedNode = it.nodePolicy?.let { policy -> resolveNodePolicy(policy, servers) }
            val outboundTag = resolvedNode?.let { node ->
                "rule_node_${node.id.takeLast(8)}".also { tag -> onResolvedNode(tag, node) }
            }
            if (it.nodePolicy != null && resolvedNode == null) {
                onMissingNodePolicy(it.nodePolicy)
            }
            XrayRoutingRule(
                target = it.target,
                domainSuffixes = it.domainSuffixes,
                fullDomains = it.fullDomains,
                domainKeywords = it.domainKeywords,
                domainRegexes = it.domainRegexes,
                ipCidrs = it.ipCidrs,
                ipCidrs6 = it.ipCidrs6,
                outboundTag = outboundTag,
                priority = priority,
            )
        }
    }
}

data class CompiledRoutingRule(
    val target: RuleTargetPolicy,
    val domainSuffixes: List<String>,
    val fullDomains: List<String>,
    val domainKeywords: List<String>,
    val domainRegexes: List<String> = emptyList(),
    val ipCidrs: List<String>,
    val ipCidrs6: List<String>,
    val nodePolicy: String? = null,
) {
    fun hasEntries(): Boolean {
        return domainSuffixes.isNotEmpty() ||
            fullDomains.isNotEmpty() ||
            domainKeywords.isNotEmpty() ||
            domainRegexes.isNotEmpty() ||
            ipCidrs.isNotEmpty() ||
            ipCidrs6.isNotEmpty()
    }
}

data class XrayRoutingRule(
    val target: RuleTargetPolicy,
    val domainSuffixes: List<String>,
    val fullDomains: List<String>,
    val domainKeywords: List<String>,
    val domainRegexes: List<String> = emptyList(),
    val ipCidrs: List<String>,
    val ipCidrs6: List<String>,
    val outboundTag: String? = null,
    val balancerTag: String? = null,
    val priority: Int = 0,
) {
    fun hasEntries(): Boolean {
        return domainSuffixes.isNotEmpty() ||
            fullDomains.isNotEmpty() ||
            domainKeywords.isNotEmpty() ||
            domainRegexes.isNotEmpty() ||
            ipCidrs.isNotEmpty() ||
            ipCidrs6.isNotEmpty()
    }
}

data class XrayNamedOutbound(
    val tag: String,
    val node: ServerNode,
)

private data class SourceRuleLine(
    val lineNumber: Int,
    val text: String,
)

class RuleParseException(
    message: String,
    val lineNumbers: Set<Int> = emptySet(),
) : IllegalArgumentException(message)
