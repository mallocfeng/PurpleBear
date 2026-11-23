package com.mallocgfw.app.model

import android.content.Context
import com.mallocgfw.app.xray.ResourceFetchClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where a streaming service is mainly used; drives the country tabs of the media routing list. */
enum class StreamingRegion(val flag: String, val zh: String, val en: String, val ru: String) {
    US("🇺🇸", "美国", "US", "США"),
    Japan("🇯🇵", "日本", "Japan", "Япония"),
    HongKong("🇭🇰", "香港", "Hong Kong", "Гонконг"),
    Taiwan("🇹🇼", "台湾", "Taiwan", "Тайвань"),
    Singapore("🇸🇬", "新加坡", "Singapore", "Сингапур"),
    UK("🇬🇧", "英国", "UK", "Великобритания"),
    Global("🌐", "全球", "Global", "Весь мир"),
}

data class StreamingMediaService(
    val id: String,
    val name: String,
    val subtitle: String,
    val suggestedRegion: String,
    val ruleUrl: String,
    val regions: List<StreamingRegion> = emptyList(),
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
        vararg regions: StreamingRegion,
    ): StreamingMediaService {
        return StreamingMediaService(
            id = id,
            name = name,
            subtitle = subtitle,
            suggestedRegion = suggestedRegion,
            ruleUrl = "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Surge/$ruleFolder/$ruleFolder.list",
            regions = regions.toList(),
        )
    }

    private val G = StreamingRegion.Global
    private val US = StreamingRegion.US
    private val JP = StreamingRegion.Japan
    private val HK = StreamingRegion.HongKong
    private val TW = StreamingRegion.Taiwan
    private val SG = StreamingRegion.Singapore
    private val UK = StreamingRegion.UK

    // Ids are stored in users' selections: never rename one.
    val services = listOf(
        // Global
        service("netflix", "Netflix", "剧集和电影分流规则", "建议香港", "Netflix", G),
        service("disney", "Disney+", "Disney / Hulu on Disney 相关域名", "建议美国", "Disney", G, US),
        service("youtube", "YouTube", "视频与会员相关域名", "建议美国或日本", "YouTube", G),
        service("youtube_music", "YouTube Music", "音乐播放与账号相关域名", "建议美国或日本", "YouTubeMusic", G),
        service("tiktok", "TikTok", "TikTok 国际版短视频规则", "建议美国或日本", "TikTok", G, US, JP, SG),
        service("twitch", "Twitch", "Twitch 直播规则", "建议美国", "Twitch", G, US),
        service("primevideo", "Prime Video", "Prime Video 播放域名", "建议美国", "PrimeVideo", G, US),
        service("amazon_prime_video", "Amazon Prime Video", "Amazon Prime Video 更完整规则", "建议美国", "AmazonPrimeVideo", G, US),
        service("apple_tv", "Apple TV", "Apple TV / TV+ 播放规则", "建议美国", "AppleTV", G, US),
        service("apple_music", "Apple Music", "Apple Music 媒体规则", "建议美国", "AppleMusic", G),
        service("spotify", "Spotify", "音乐流媒体与账号接口", "建议美国或新加坡", "Spotify", G),
        service("dazn", "DAZN", "DAZN 体育流媒体规则", "建议日本或德国", "DAZN", G, JP),
        service("tidal", "TIDAL", "TIDAL 音乐服务规则", "建议美国", "TIDAL", G),
        service("deezer", "Deezer", "Deezer 音乐服务规则", "建议美国或法国", "Deezer", G),
        service("qobuz", "Qobuz", "Qobuz 无损音乐规则", "建议美国或法国", "Qobuz", G),
        service("soundcloud", "SoundCloud", "SoundCloud 音乐规则", "建议美国", "SoundCloud", G),
        // United States
        service("hulu", "Hulu", "Hulu 视频服务分流规则", "建议美国", "Hulu", US),
        service("hulu_usa", "Hulu USA", "美国区 Hulu 规则", "建议美国", "HuluUSA", US),
        service("max", "HBO Max", "HBO Max（曾用名 Max）美国规则", "建议美国", "HBOUSA", US),
        service("hbo", "HBO", "HBO 通用规则", "建议美国", "HBO", US),
        service("paramount_plus", "Paramount+", "Paramount+ 视频规则", "建议美国", "ParamountPlus", US),
        service("peacock", "Peacock", "Peacock 视频规则", "建议美国", "Peacock", US),
        service("discovery_plus", "Discovery+", "Discovery+ 流媒体规则", "建议美国", "DiscoveryPlus", US),
        service("espn", "ESPN", "ESPN 体育直播规则", "建议美国", "Espn", US),
        service("fubo", "Fubo", "Fubo 体育与电视直播规则", "建议美国", "FuboTV", US),
        service("sling", "Sling TV", "Sling TV 电视直播规则", "建议美国", "Sling", US),
        service("fox_now", "FOX NOW", "FOX NOW 视频规则", "建议美国", "FOXNOW", US),
        service("pandora", "Pandora", "Pandora 音乐电台规则", "建议美国", "Pandora", US),
        // Japan
        service("abematv", "AbemaTV", "日本 AbemaTV 视频规则", "建议日本", "AbemaTV", JP),
        service("tver", "TVer", "日本民放电视台点播规则", "建议日本", "TVer", JP),
        service("niconico", "Niconico", "Niconico 视频规则", "建议日本", "Niconico", JP),
        service("hulu_jp", "Hulu JP", "日本区 Hulu 规则", "建议日本", "HuluJP", JP),
        service("dmm", "DMM TV", "DMM 视频与动画规则", "建议日本", "DMM", JP),
        // Hong Kong
        service("tvb", "TVB", "TVB 流媒体规则", "建议香港", "TVB", HK),
        service("mytvsuper", "myTV SUPER", "TVB myTV SUPER 规则", "建议香港", "myTVSUPER", HK),
        service("encore_tvb", "EncoreTVB", "TVB 海外点播规则", "建议香港", "EncoreTVB", HK),
        service("viutv", "ViuTV", "ViuTV 港区流媒体规则", "建议香港", "ViuTV", HK),
        service("now_e", "Now E", "Now E 港区视频规则", "建议香港", "NowE", HK),
        service("hbo_hk", "HBO HK", "HBO 香港区规则", "建议香港", "HBOHK", HK),
        service("joox", "JOOX", "JOOX 音乐服务规则", "建议香港", "JOOX", HK),
        // Taiwan and Hong Kong
        service("bilibili", "BiliBili", "Bilibili 港澳台番剧规则", "建议台湾或香港", "BiliBili", TW, HK),
        service("kkbox", "KKBOX", "KKBOX 音乐服务规则", "建议台湾", "KKBOX", TW, HK),
        // Taiwan
        service("bahamut", "Bahamut", "巴哈姆特动画疯规则", "建议台湾", "Bahamut", TW),
        service("kktv", "KKTV", "KKTV 台湾视频规则", "建议台湾", "KKTV", TW),
        service("line_tv", "Line TV", "Line TV 视频规则", "建议台湾", "LineTV", TW),
        service("litv", "LiTV", "LiTV 台湾影视规则", "建议台湾", "LiTV", TW),
        service("friday", "friDay 影音", "friDay 影音台湾视频规则", "建议台湾", "friDay", TW),
        service("hami_video", "Hami Video", "Hami Video 台湾视频规则", "建议台湾", "HamiVideo", TW),
        // Singapore and Southeast Asia
        service("bilibili_intl", "BiliBili Intl", "BiliBili 国际版规则", "建议新加坡", "BiliBiliIntl", SG),
        service("iqiyi_intl", "iQIYI 国际版", "爱奇艺国际版（iQ.com）规则", "建议新加坡", "iQIYIIntl", SG, TW),
        service("wetv", "WeTV", "腾讯视频海外版规则", "建议新加坡", "WeTV", SG),
        service("mewatch", "meWATCH", "新加坡 Mediacorp 点播规则", "建议新加坡", "MeWatch", SG),
        service("hbo_asia", "HBO Asia", "HBO Asia 区域规则", "建议新加坡", "HBOAsia", SG, HK, TW),
        // United Kingdom
        service("bbc", "BBC iPlayer", "BBC iPlayer 与 BBC 网站规则", "建议英国", "BBC", UK),
        service("itv", "ITVX", "ITV 点播规则", "建议英国", "ITV", UK),
        service("britbox", "BritBox", "BritBox 英剧规则", "建议英国", "BritboxUK", UK),
        service("sky_go", "Sky Go", "Sky Go 直播与点播规则", "建议英国", "SkyGO", UK),
    )

    fun services(region: StreamingRegion?): List<StreamingMediaService> =
        if (region == null) services else services.filter { region in it.regions }.sortedBy { if (it.regions.first() == region) 0 else 1 }

    fun serviceById(serviceId: String): StreamingMediaService? {
        return services.firstOrNull { it.id == serviceId }
    }

    private val _unappliedServices = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Services with a route chosen whose rules couldn't be downloaded (and had
     * no cache) when the running tunnel was built: their traffic follows the
     * normal rules until a retry succeeds. Shown in the UI and over MCP.
     */
    val unappliedServices: StateFlow<Set<String>> = _unappliedServices.asStateFlow()

    fun clearUnapplied() {
        _unappliedServices.value = emptySet()
    }

    /**
     * Downloads the rules of the services in [unappliedServices] again (through
     * the running proxy). True when at least one now has rules, so a reconnect
     * will apply it.
     */
    suspend fun retryUnapplied(context: Context): Boolean = withContext(Dispatchers.IO) {
        _unappliedServices.value
            .mapNotNull(::serviceById)
            .count { service -> runCatching { refreshRules(context, service) }.isSuccess } > 0
    }

    /** Selection value meaning "go direct, not through any node". */
    const val DIRECT = "DIRECT"

    /** Services the user sent through a chosen node or set to go direct. */
    fun routedServices(settings: AppSettings): List<StreamingMediaService> {
        val chosen = settings.streamingSelections.filter { it.serverId.isNotBlank() }.map { it.serviceId }.toSet()
        return services.filter { it.id in chosen }
    }

    /** Refreshes the rule lists of the services that have a chosen node. */
    suspend fun refreshAll(context: Context, settings: AppSettings): Int {
        return withContext(Dispatchers.IO) {
            routedServices(settings).count { service ->
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
        if (!settings.streamingRoutingEnabled) {
            _unappliedServices.value = emptySet()
            return StreamingRoutingSetup()
        }
        return withContext(Dispatchers.IO) {
            val unapplied = mutableSetOf<String>()
            val serverById = servers.associateBy { it.id }
            val selections = settings.streamingSelections.associateBy { it.serviceId }
            val outbounds = mutableListOf<XrayNamedOutbound>()
            val rules = mutableListOf<XrayRoutingRule>()

            // Only services with a chosen node (or set to direct) get a rule.
            // The rest follow the normal rules, so their domains in the home
            // region (Bilibili, Tencent video) still go direct.
            routedServices(settings).forEach { service ->
                val choice = selections[service.id]?.serverId.orEmpty()
                val node = if (choice == DIRECT) null else serverById[choice] ?: return@forEach
                val outboundTag = when {
                    node == null -> "direct"
                    node.id == selectedServerId -> "proxy"
                    else -> "stream_${service.id}"
                }
                val compiled = runCatching { ensureRules(context, service) }.getOrNull() ?: run {
                    unapplied += service.id
                    return@forEach
                }
                if (node != null && outboundTag != "proxy") {
                    outbounds += XrayNamedOutbound(
                        tag = outboundTag,
                        node = node,
                    )
                }
                rules += XrayRoutingRule(
                    target = if (node == null) RuleTargetPolicy.Direct else RuleTargetPolicy.Proxy,
                    domainSuffixes = compiled.domainSuffixes,
                    fullDomains = compiled.fullDomains,
                    domainKeywords = compiled.domainKeywords,
                    ipCidrs = compiled.ipCidrs,
                    ipCidrs6 = compiled.ipCidrs6,
                    outboundTag = outboundTag,
                    priority = STREAMING_RULE_PRIORITY,
                )
            }

            _unappliedServices.value = unapplied
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
            put("domainRegexes", JSONArray().apply { compiled.domainRegexes.forEach(::put) })
            put("ipCidrs", JSONArray().apply { compiled.ipCidrs.forEach(::put) })
            put("ipCidrs6", JSONArray().apply { compiled.ipCidrs6.forEach(::put) })
            put("processNames", JSONArray().apply { compiled.processNames.forEach(::put) })
            put("routingRules", JSONArray().apply {
                compiled.routingRules.forEach { rule ->
                    put(JSONObject().apply {
                        put("target", rule.target.name)
                        put("domainSuffixes", JSONArray().apply { rule.domainSuffixes.forEach(::put) })
                        put("fullDomains", JSONArray().apply { rule.fullDomains.forEach(::put) })
                        put("domainKeywords", JSONArray().apply { rule.domainKeywords.forEach(::put) })
                        put("domainRegexes", JSONArray().apply { rule.domainRegexes.forEach(::put) })
                        put("ipCidrs", JSONArray().apply { rule.ipCidrs.forEach(::put) })
                        put("ipCidrs6", JSONArray().apply { rule.ipCidrs6.forEach(::put) })
                    })
                }
            })
            put("noResolveRules", JSONArray().apply { compiled.noResolveRules.forEach(::put) })
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
                domainRegexes = json.optJSONArray("domainRegexes").toStringList(),
                ipCidrs = json.optJSONArray("ipCidrs").toStringList(),
                ipCidrs6 = json.optJSONArray("ipCidrs6").toStringList(),
                processNames = json.optJSONArray("processNames").toStringList(),
                routingRules = json.optJSONArray("routingRules").toCompiledRoutingRules(),
                noResolveRules = json.optJSONArray("noResolveRules").toStringList(),
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

    private fun JSONArray?.toCompiledRoutingRules(): List<CompiledRoutingRule> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val item = optJSONObject(index) ?: return@List null
            CompiledRoutingRule(
                target = runCatching {
                    RuleTargetPolicy.valueOf(item.optString("target", RuleTargetPolicy.Proxy.name))
                }.getOrDefault(RuleTargetPolicy.Proxy),
                domainSuffixes = item.optJSONArray("domainSuffixes").toStringList(),
                fullDomains = item.optJSONArray("fullDomains").toStringList(),
                domainKeywords = item.optJSONArray("domainKeywords").toStringList(),
                domainRegexes = item.optJSONArray("domainRegexes").toStringList(),
                ipCidrs = item.optJSONArray("ipCidrs").toStringList(),
                ipCidrs6 = item.optJSONArray("ipCidrs6").toStringList(),
            )
        }.filterNotNull().filter { it.hasEntries() }
    }
}
