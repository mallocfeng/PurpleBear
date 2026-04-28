package com.mallocgfw.app.model

import android.content.Context
import com.mallocgfw.app.xray.ResourceFetchClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StreamingMediaService(
    val id: String,
    val name: String,
    val subtitle: String,
    val suggestedRegion: String,
    val ruleUrl: String,
)

data class StreamingRoutingSetup(
    val outbounds: List<XrayNamedOutbound> = emptyList(),
    val routingRules: List<XrayRoutingRule> = emptyList(),
)

object StreamingMediaManager {
    private const val RULES_DIR = "streaming-rules"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val STREAMING_RULE_PRIORITY = 1_000

    private fun service(
        id: String,
        name: String,
        subtitle: String,
        suggestedRegion: String,
        ruleFolder: String,
    ): StreamingMediaService {
        return StreamingMediaService(
            id = id,
            name = name,
            subtitle = subtitle,
            suggestedRegion = suggestedRegion,
            ruleUrl = "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Surge/$ruleFolder/$ruleFolder.list",
        )
    }

    val services = listOf(
        service("netflix", "Netflix", "剧集和电影分流规则", "建议香港", "Netflix"),
        service("disney", "Disney+", "Disney / Hulu on Disney 相关域名", "建议美国", "Disney"),
        service("youtube", "YouTube", "视频与会员相关域名", "建议美国或日本", "YouTube"),
        service("youtube_music", "YouTube Music", "音乐播放与账号相关域名", "建议美国或日本", "YouTubeMusic"),
        service("primevideo", "Prime Video", "Prime Video 播放域名", "建议美国", "PrimeVideo"),
        service("amazon_prime_video", "Amazon Prime Video", "Amazon Prime Video 更完整规则", "建议美国", "AmazonPrimeVideo"),
        service("hulu", "Hulu", "Hulu 视频服务分流规则", "建议美国", "Hulu"),
        service("hulu_jp", "Hulu JP", "日本区 Hulu 规则", "建议日本", "HuluJP"),
        service("hulu_usa", "Hulu USA", "美国区 Hulu 规则", "建议美国", "HuluUSA"),
        service("max", "Max", "Max / HBO Max 美国规则", "建议美国", "HBOUSA"),
        service("hbo", "HBO", "HBO 通用规则", "建议美国", "HBO"),
        service("hbo_asia", "HBO Asia", "HBO Asia 区域规则", "建议新加坡", "HBOAsia"),
        service("hbo_hk", "HBO HK", "HBO 香港区规则", "建议香港", "HBOHK"),
        service("spotify", "Spotify", "音乐流媒体与账号接口", "建议美国或新加坡", "Spotify"),
        service("apple_tv", "Apple TV", "Apple TV / TV+ 播放规则", "建议美国", "AppleTV"),
        service("apple_music", "Apple Music", "Apple Music 媒体规则", "建议美国", "AppleMusic"),
        service("bahamut", "Bahamut", "巴哈姆特动画疯规则", "建议台湾", "Bahamut"),
        service("abematv", "AbemaTV", "日本 AbemaTV 视频规则", "建议日本", "AbemaTV"),
        service("bilibili", "BiliBili", "Bilibili 视频服务规则", "建议台湾或香港", "BiliBili"),
        service("bilibili_intl", "BiliBili Intl", "BiliBili 国际版规则", "建议新加坡", "BiliBiliIntl"),
        service("viutv", "ViuTV", "ViuTV 港区流媒体规则", "建议香港", "ViuTV"),
        service("line_tv", "Line TV", "Line TV 视频规则", "建议台湾", "LineTV"),
        service("litv", "LiTV", "LiTV 台湾影视规则", "建议台湾", "LiTV"),
        service("kktv", "KKTV", "KKTV 台湾视频规则", "建议台湾", "KKTV"),
        service("kkbox", "KKBOX", "KKBOX 音乐服务规则", "建议台湾", "KKBOX"),
        service("discovery_plus", "Discovery+", "Discovery+ 流媒体规则", "建议美国", "DiscoveryPlus"),
        service("fox_now", "FOX NOW", "FOX NOW 视频规则", "建议美国", "FOXNOW"),
        service("encore_tvb", "EncoreTVB", "TVB 海外点播规则", "建议香港", "EncoreTVB"),
        service("dazn", "DAZN", "DAZN 体育流媒体规则", "建议日本或德国", "DAZN"),
        service("tidal", "TIDAL", "TIDAL 音乐服务规则", "建议美国", "TIDAL"),
        service("soundcloud", "SoundCloud", "SoundCloud 音乐规则", "建议美国", "SoundCloud"),
        service("pandora", "Pandora", "Pandora 音乐电台规则", "建议美国", "Pandora"),
        service("deezer", "Deezer", "Deezer 音乐服务规则", "建议美国或法国", "Deezer"),
        service("paramount_plus", "Paramount+", "Paramount+ 视频规则", "建议美国", "ParamountPlus"),
        service("peacock", "Peacock", "Peacock 视频规则", "建议美国", "Peacock"),
        service("niconico", "Niconico", "Niconico 视频规则", "建议日本", "Niconico"),
        service("hami_video", "Hami Video", "Hami Video 台湾视频规则", "建议台湾", "HamiVideo"),
        service("tvb", "TVB", "TVB 流媒体规则", "建议香港", "TVB"),
    )

    fun serviceById(serviceId: String): StreamingMediaService? {
        return services.firstOrNull { it.id == serviceId }
    }

    suspend fun refreshAll(context: Context): Int {
        return withContext(Dispatchers.IO) {
            services.count { service ->
                runCatching { refreshRules(context, service) }.isSuccess
            }
        }
    }

    suspend fun buildRoutingSetup(
        context: Context,
        settings: AppSettings,
        selectedServerId: String,
        servers: List<ServerNode>,
    ): StreamingRoutingSetup {
        if (!settings.streamingRoutingEnabled) return StreamingRoutingSetup()
        return withContext(Dispatchers.IO) {
            val serverById = servers.associateBy { it.id }
            val selections = settings.streamingSelections.associateBy { it.serviceId }
            val outbounds = mutableListOf<XrayNamedOutbound>()
            val rules = mutableListOf<XrayRoutingRule>()

            services.forEach { service ->
                val preferredServerId = selections[service.id]?.serverId.orEmpty()
                val preferredNode = serverById[preferredServerId]
                val selectedNode = serverById[selectedServerId] ?: return@forEach
                val resolvedNode = when {
                    preferredServerId.isBlank() -> selectedNode
                    preferredNode == null -> selectedNode
                    else -> preferredNode
                }
                val resolvedServerId = resolvedNode.id
                val node = resolvedNode
                val outboundTag = if (resolvedServerId == selectedServerId) {
                    "proxy"
                } else {
                    "stream_${service.id}"
                }
                val compiled = runCatching { ensureRules(context, service) }.getOrNull() ?: return@forEach
                if (outboundTag != "proxy") {
                    outbounds += XrayNamedOutbound(
                        tag = outboundTag,
                        node = node,
                    )
                }
                rules += XrayRoutingRule(
                    target = RuleTargetPolicy.Proxy,
                    domainSuffixes = compiled.domainSuffixes,
                    fullDomains = compiled.fullDomains,
                    domainKeywords = compiled.domainKeywords,
                    ipCidrs = compiled.ipCidrs,
                    ipCidrs6 = compiled.ipCidrs6,
                    outboundTag = outboundTag,
                    priority = STREAMING_RULE_PRIORITY,
                )
            }

            StreamingRoutingSetup(
                outbounds = outbounds.distinctBy { it.tag },
                routingRules = rules.filter { it.hasEntries() },
            )
        }
    }

    private fun ensureRules(
        context: Context,
        service: StreamingMediaService,
    ): CompiledRuleSet {
        val cached = loadCompiledRules(context, service.id)
        val cacheFresh = cached != null &&
            (System.currentTimeMillis() - compiledRuleFile(context, service.id).lastModified()) <= CACHE_TTL_MS
        if (cacheFresh) {
            return cached!!
        }
        return runCatching {
            refreshRules(context, service)
        }.getOrElse {
            cached ?: throw it
        }
    }

    private fun refreshRules(
        context: Context,
        service: StreamingMediaService,
    ): CompiledRuleSet {
        val text = ResourceFetchClient.fetchText(
            url = service.ruleUrl,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            label = service.name,
        )
        val compiled = RuleSourceManager.compileText(
            sourceId = "stream_${service.id}",
            rawText = text,
            requestedType = RuleSourceType.Surge,
        )
        saveCompiledRules(context, service.id, compiled)
        return compiled
    }

    private fun saveCompiledRules(
        context: Context,
        serviceId: String,
        compiled: CompiledRuleSet,
    ) {
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
        compiledRuleFile(context, serviceId).apply {
            parentFile?.mkdirs()
            writeText(payload.toString())
        }
    }

    private fun loadCompiledRules(
        context: Context,
        serviceId: String,
    ): CompiledRuleSet? {
        val file = compiledRuleFile(context, serviceId)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            CompiledRuleSet(
                sourceId = json.optString("sourceId", "stream_$serviceId"),
                detectedType = RuleSourceType.valueOf(json.optString("detectedType", RuleSourceType.Surge.name)),
                totalRules = json.optInt("totalRules", 0),
                convertedRules = json.optInt("convertedRules", 0),
                skippedRules = json.optInt("skippedRules", 0),
                unsupportedKinds = json.optJSONArray("unsupportedKinds").toStringList(),
                domainSuffixes = json.optJSONArray("domainSuffixes").toStringList(),
                fullDomains = json.optJSONArray("fullDomains").toStringList(),
                domainKeywords = json.optJSONArray("domainKeywords").toStringList(),
                ipCidrs = json.optJSONArray("ipCidrs").toStringList(),
                ipCidrs6 = json.optJSONArray("ipCidrs6").toStringList(),
                processNames = json.optJSONArray("processNames").toStringList(),
            )
        }.getOrNull()
    }

    private fun compiledRuleFile(context: Context, serviceId: String): File {
        return File(File(context.filesDir, RULES_DIR).apply { mkdirs() }, "$serviceId.json")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
    }
}
