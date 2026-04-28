package com.mallocgfw.app.model

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import com.mallocgfw.app.xray.ResourceFetchClient
import org.json.JSONArray
import org.json.JSONObject

object RuleSourceManager {
    private const val RULES_DIR = "rule-sources"
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
        )
    }

    suspend fun refreshSource(
        context: Context,
        source: RuleSourceItem,
    ): RuleRefreshResult {
        return withContext(Dispatchers.IO) {
            val text = fetchText(source.url)
            val compiled = compileText(
                sourceId = source.id,
                rawText = text,
                requestedType = source.type,
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
        return sources
            .filter { it.enabled && it.status == RuleSourceStatus.Ready }
            .mapNotNull { source ->
                val compiled = loadCompiledRules(context, source.id) ?: return@mapNotNull null
                XrayRoutingRule(
                    target = source.policy,
                    domainSuffixes = compiled.domainSuffixes,
                    fullDomains = compiled.fullDomains,
                    domainKeywords = compiled.domainKeywords,
                    ipCidrs = compiled.ipCidrs,
                    ipCidrs6 = compiled.ipCidrs6,
                )
            }
            .filter { it.hasEntries() }
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
    ): CompiledRuleSet {
        val lines = rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("#") || it.startsWith("//") || it.startsWith(";") }
            .toList()

        if (lines.isEmpty()) {
            throw RuleParseException("规则文件为空。")
        }

        if (lines.any { it.startsWith("payload:", ignoreCase = true) || it.startsWith("rules:", ignoreCase = true) }) {
            throw RuleParseException("当前只支持 Shadowrocket / Surge 文本规则，不支持 YAML。")
        }

        val detectedType = when (requestedType) {
            RuleSourceType.Auto -> detectType(lines)
            else -> requestedType
        }

        val domainSuffixes = linkedSetOf<String>()
        val fullDomains = linkedSetOf<String>()
        val domainKeywords = linkedSetOf<String>()
        val ipCidrs = linkedSetOf<String>()
        val ipCidrs6 = linkedSetOf<String>()
        val processNames = linkedSetOf<String>()
        val unsupportedKinds = mutableSetOf<String>()
        var totalRules = 0
        var skippedRules = 0

        lines.forEach { line ->
            val normalized = line.substringBefore(" //").trim()
            if (normalized.isBlank()) return@forEach
            if (!normalized.contains(",")) {
                when {
                    normalized.startsWith(".") -> {
                        totalRules += 1
                        domainSuffixes += normalized.removePrefix(".")
                    }

                    normalized.contains("/") && normalized.contains(":") -> {
                        totalRules += 1
                        ipCidrs6 += normalized
                    }

                    normalized.contains("/") && normalized.any(Char::isDigit) -> {
                        totalRules += 1
                        ipCidrs += normalized
                    }

                    normalized.matches(Regex("^[A-Za-z0-9*_-]+(\\.[A-Za-z0-9*_-]+)+$")) -> {
                        totalRules += 1
                        fullDomains += normalized
                    }

                    else -> {
                        skippedRules += 1
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
                unsupportedKinds += kind.ifBlank { "未知格式" }
                return@forEach
            }
            totalRules += 1
            when (kind) {
                "DOMAIN-SUFFIX" -> domainSuffixes += value
                "DOMAIN" -> fullDomains += value
                "DOMAIN-KEYWORD" -> domainKeywords += value
                "IP-CIDR" -> ipCidrs += value
                "IP-CIDR6" -> ipCidrs6 += value
                "PROCESS-NAME" -> {
                    skippedRules += 1
                    unsupportedKinds += kind
                }
                "URL-REGEX", "USER-AGENT", "IP-ASN", "AND", "OR", "DEST-PORT", "SRC-IP", "IN-PORT" -> {
                    skippedRules += 1
                    unsupportedKinds += kind
                }
                else -> {
                    skippedRules += 1
                    unsupportedKinds += kind.ifBlank { "未知格式" }
                }
            }
        }

        val convertedRules = domainSuffixes.size + fullDomains.size + domainKeywords.size + ipCidrs.size + ipCidrs6.size
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
            ipCidrs = ipCidrs.toList(),
            ipCidrs6 = ipCidrs6.toList(),
            processNames = processNames.toList(),
        )
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
            put("ipCidrs", JSONArray().apply { compiled.ipCidrs.forEach(::put) })
            put("ipCidrs6", JSONArray().apply { compiled.ipCidrs6.forEach(::put) })
            put("processNames", JSONArray().apply { compiled.processNames.forEach(::put) })
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
                ipCidrs = json.optJSONArray("ipCidrs").toStrings(),
                ipCidrs6 = json.optJSONArray("ipCidrs6").toStrings(),
                processNames = json.optJSONArray("processNames").toStrings(),
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
}

data class RuleRefreshResult(
    val source: RuleSourceItem,
    val compiled: CompiledRuleSet,
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
    val ipCidrs: List<String>,
    val ipCidrs6: List<String>,
    val processNames: List<String>,
) {
    val domainCount: Int get() = domainSuffixes.size + fullDomains.size + domainKeywords.size
    val ipCount: Int get() = ipCidrs.size + ipCidrs6.size
}

data class XrayRoutingRule(
    val target: RuleTargetPolicy,
    val domainSuffixes: List<String>,
    val fullDomains: List<String>,
    val domainKeywords: List<String>,
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
            ipCidrs.isNotEmpty() ||
            ipCidrs6.isNotEmpty()
    }
}

data class XrayNamedOutbound(
    val tag: String,
    val node: ServerNode,
)

class RuleParseException(message: String) : IllegalArgumentException(message)
