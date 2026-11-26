package com.mallocgfw.app.xray

import com.mallocgfw.app.model.DnsInput
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.NodeCompatibility
import com.mallocgfw.app.model.SsrMihomoConfigFactory
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.AppLanguage
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.RuleTargetPolicy
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayNamedOutbound
import com.mallocgfw.app.model.XrayRoutingRule
import com.mallocgfw.app.model.DEFAULT_APP_VPN_MTU
import com.mallocgfw.app.model.normalizedAppVpnMtu
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.mallocgfw.app.model.tr

object XrayConfigFactory {
    const val DEFAULT_VPN_MTU = DEFAULT_APP_VPN_MTU
    private const val SPEED_TEST_INBOUND_TAG = "speedtest-http-in"
    private const val APP_SOCKS_INBOUND_TAG = "app-socks-in"
    private const val MAGIC_DNS_OUTBOUND_TAG = "magic-dns-out"
    private const val REMOTE_DNS_OUTBOUND_TAG = "remote-dns-out"
    private const val MAGIC_DNS_PASSTHROUGH_TAG = "magic-dns-pass"
    /**
     * Encrypted DNS: the address Android is given (inside the tunnel's /30).
     * Queries to it are answered by Xray's DNS module, which asks the
     * DoH / DoT / DoQ server.
     */
    const val ENCRYPTED_DNS_ADDRESS = "172.19.0.2"
    private const val ENCRYPTED_DNS_OUTBOUND_TAG = "encrypted-dns-out"
    private const val DNS_MODULE_TAG = "dns-module"
    private const val DOT_OUTBOUND_TAG = "dot-out"
    private const val DOT_LOOPBACK_TAG = "dot-loop"
    /** Tailscale's resolver; also the DNS address the VPN advertises for MagicDNS. */
    const val TAILSCALE_RESOLVER = "100.100.100.100"
    private const val NXDOMAIN = 3
    private const val MAX_CHAIN_LENGTH = 8
    private val GOOGLE_PLAY_DOWNLOAD_DOMAINS = listOf(
        "android.clients.google.com",
        "google.com",
        "play.google.com",
        "play.googleapis.com",
        "googleapis.com",
        "gvt1.com",
        "gvt2.com",
        "gvt3.com",
        "ggpht.com",
        "googleusercontent.com",
        "googleusercontent.cn",
        "dl.google.com",
        "dl-ssl.google.com",
    )

    fun build(
        node: ServerNode,
        availableServers: List<ServerNode> = emptyList(),
        routingRules: List<XrayRoutingRule> = emptyList(),
        additionalOutbounds: List<XrayNamedOutbound> = emptyList(),
        logLevel: String = "warning",
        errorLogPath: String? = null,
        accessLogPath: String? = null,
        globalProxyEnabled: Boolean = false,
        geoRoutingRegion: AppGeoRoutingRegion = AppGeoRoutingRegion.defaultForLanguage(AppLanguage.System),
        homeSitesDirect: Boolean = true,
        tailscale: TailscaleRouting? = null,
        localProxy: LocalProxyAllocation = LocalProxyRuntime.current() ?: LocalProxyAllocation.ephemeral(),
    ): String {
        return JSONObject().apply {
            put(
                "log",
                JSONObject().apply {
                    put("loglevel", logLevel)
                    putOpt("error", errorLogPath)
                    putOpt("access", accessLogPath)
                },
            )
            put(
                "inbounds",
                JSONArray().apply {
                    put(socksInbound(localProxy.socks))
                    put(httpInbound(localProxy.http))
                },
            )
            put(
                "outbounds",
                JSONArray().apply {
                    // Nodes that run in the mihomo sidecar, each on its own listener slot.
                    val sidecarNodes = sidecarNodesFor(node, additionalOutbounds.map { it.node }, availableServers)
                    buildOutboundChain(node, "proxy", availableServers, localProxy, sidecarNodes).forEach(::put)
                    additionalOutbounds.forEach { outbound ->
                        buildOutboundChain(outbound.node, outbound.tag, availableServers, localProxy, sidecarNodes).forEach(::put)
                    }
                    tailscale?.let { put(tailscaleOutbound(it)) }
                    put(JSONObject().apply {
                        put("tag", "direct")
                        put("protocol", "freedom")
                    })
                    put(JSONObject().apply {
                        put("tag", "block")
                        put("protocol", "blackhole")
                    })
                },
            )
            put(
                "routing",
                JSONObject().apply {
                    // AsIs: match domains literally, match IP rules only against
                    // IP-literal targets. Keeps routing predictable without
                    // requiring an in-config DNS section to drive IPIfNonMatch.
                    put("domainStrategy", "AsIs")
                    put(
                        "rules",
                        buildRoutingRules(
                            routingRules,
                            globalProxyEnabled = globalProxyEnabled,
                            geoRoutingRegion = geoRoutingRegion,
                            homeSitesDirect = homeSitesDirect,
                            tailscale = tailscale,
                        ),
                    )
                },
            )
        }.toString(2)
    }

    fun buildVpn(
        node: ServerNode,
        availableServers: List<ServerNode> = emptyList(),
        routingRules: List<XrayRoutingRule> = emptyList(),
        additionalOutbounds: List<XrayNamedOutbound> = emptyList(),
        logLevel: String = "warning",
        errorLogPath: String? = null,
        accessLogPath: String? = null,
        vpnMtu: Int = DEFAULT_VPN_MTU,
        /** Real resolver ("host:port") when the tunnel advertises [TAILSCALE_RESOLVER] for MagicDNS. */
        magicDnsUpstream: String? = null,
        globalProxyEnabled: Boolean = false,
        geoRoutingRegion: AppGeoRoutingRegion = AppGeoRoutingRegion.defaultForLanguage(AppLanguage.System),
        homeSitesDirect: Boolean = true,
        tailscale: TailscaleRouting? = null,
        /** Let foreign QUIC go through the proxy (good for UDP nodes such as Hysteria2 and TUIC). */
        proxyQuicEnabled: Boolean = false,
        /** The DNS server Android was given (bare IP); its port-53 traffic is pinned, see [dnsRules]. */
        dnsServer: String? = null,
        /** Remote DNS: send it as DNS-over-TCP through the proxy, so nodes without UDP still resolve. */
        dnsOverProxy: Boolean = false,
        /** Encrypted custom DNS; [dnsServer] is then [ENCRYPTED_DNS_ADDRESS]. */
        encryptedDns: DnsInput.EncryptedDns? = null,
        localProxy: LocalProxyAllocation = LocalProxyRuntime.current() ?: LocalProxyAllocation.ephemeral(),
    ): String {
        val normalizedMtu = normalizedAppVpnMtu(vpnMtu)
        return JSONObject().apply {
            put(
                "log",
                JSONObject().apply {
                    put("loglevel", logLevel)
                    putOpt("error", errorLogPath)
                    putOpt("access", accessLogPath)
                },
            )
            put(
                "inbounds",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("tag", "tun-in")
                            put("protocol", "tun")
                            put("settings", JSONObject().apply {
                                put("name", "xray0")
                                put("MTU", normalizedMtu)
                            })
                            put("sniffing", sniffing())
                        },
                    )
                    put(httpInbound(localProxy.http, SPEED_TEST_INBOUND_TAG))
                    put(socksInbound(localProxy.socks, APP_SOCKS_INBOUND_TAG))
                },
            )
            put(
                "outbounds",
                JSONArray().apply {
                    // Nodes that run in the mihomo sidecar, each on its own listener slot.
                    val sidecarNodes = sidecarNodesFor(node, additionalOutbounds.map { it.node }, availableServers)
                    buildOutboundChain(node, "proxy", availableServers, localProxy, sidecarNodes).forEach(::put)
                    additionalOutbounds.forEach { outbound ->
                        buildOutboundChain(outbound.node, outbound.tag, availableServers, localProxy, sidecarNodes).forEach(::put)
                    }
                    tailscale?.let { put(tailscaleOutbound(it)) }
                    magicDnsUpstream?.let { upstream -> magicDnsOutbounds(upstream, tailscale).forEach(::put) }
                    if (dnsServer != null && dnsOverProxy) {
                        put(remoteDnsOutbound(dnsServer, if (tailscale?.routeAllTraffic == true) "tailscale" else "proxy"))
                    }
                    encryptedDns?.let { dns ->
                        val magicDnsHosts = tailscale?.takeIf { magicDnsUpstream != null && it.resolvesMagicDns }
                        encryptedDnsOutbounds(dns, magicDnsHosts).forEach(::put)
                    }
                    put(JSONObject().apply {
                        put("tag", "direct")
                        put("protocol", "freedom")
                    })
                    put(JSONObject().apply {
                        put("tag", "block")
                        put("protocol", "blackhole")
                    })
                },
            )
            put(
                "routing",
                JSONObject().apply {
                    // AsIs: match domains literally, match IP rules only against
                    // IP-literal targets. Keeps routing predictable without
                    // requiring an in-config DNS section to drive IPIfNonMatch.
                    put("domainStrategy", "AsIs")
                    put(
                        "rules",
                        buildRoutingRules(
                            routingRules,
                            forceSpeedTestToProxy = true,
                            forceUdp443Fallback = true,
                            blockForeignUdp443 = !proxyQuicEnabled,
                            globalProxyEnabled = globalProxyEnabled,
                            geoRoutingRegion = geoRoutingRegion,
                            homeSitesDirect = homeSitesDirect,
                            tailscale = tailscale,
                            magicDns = magicDnsUpstream != null,
                            dnsServer = dnsServer,
                            dnsOverProxy = dnsOverProxy,
                            encryptedDns = encryptedDns,
                        ),
                    )
                },
            )
            val magicDnsHosts = tailscale?.takeIf { magicDnsUpstream != null && it.resolvesMagicDns }
            when {
                encryptedDns != null -> put("dns", encryptedDnsSettings(encryptedDns, magicDnsHosts))
                magicDnsHosts != null -> put("dns", magicDnsSettings(magicDnsHosts))
            }
        }.toString(2)
    }

    private fun buildRoutingRules(
        routingRules: List<XrayRoutingRule>,
        forceSpeedTestToProxy: Boolean = false,
        forceUdp443Fallback: Boolean = false,
        blockForeignUdp443: Boolean = forceUdp443Fallback,
        globalProxyEnabled: Boolean = false,
        geoRoutingRegion: AppGeoRoutingRegion = AppGeoRoutingRegion.defaultForLanguage(AppLanguage.System),
        homeSitesDirect: Boolean = true,
        tailscale: TailscaleRouting? = null,
        magicDns: Boolean = false,
        dnsServer: String? = null,
        dnsOverProxy: Boolean = false,
        encryptedDns: DnsInput.EncryptedDns? = null,
    ): JSONArray {
        val rules = JSONArray()

        if (magicDns) {
            // System DNS (sent to the advertised resolver address) passes the
            // MagicDNS filter first; see magicDnsOutbounds. Apps that use their
            // own resolver are not touched. Only tun-in, so queries handed back
            // through the loopback are routed normally instead of looping.
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put("tun-in"))
                    put("ip", JSONArray().put(TAILSCALE_RESOLVER))
                    put("port", "53")
                    put("outboundTag", MAGIC_DNS_OUTBOUND_TAG)
                },
            )
        }

        dnsServer?.let { server ->
            // DNS to the configured server must not depend on the normal rules:
            // routed through a node without UDP, every lookup would fail.
            // System / custom DNS go direct; remote DNS becomes TCP through the proxy.
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().put(server))
                    put("port", "53")
                    put(
                        "outboundTag",
                        when {
                            encryptedDns != null -> ENCRYPTED_DNS_OUTBOUND_TAG
                            dnsOverProxy -> REMOTE_DNS_OUTBOUND_TAG
                            else -> "direct"
                        },
                    )
                },
            )
        }

        if (encryptedDns?.kind == DnsInput.EncryptedDns.Kind.DoT) {
            // The DNS module sends DoT queries as plain DNS-over-TCP; this
            // outbound adds the TLS, then hands the connection back to routing
            // (see encryptedDnsOutbounds), so the server goes direct or
            // through the proxy like any other site.
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put(DNS_MODULE_TAG))
                    put("network", "tcp")
                    put("port", encryptedDns.port.toString())
                    put("outboundTag", DOT_OUTBOUND_TAG)
                },
            )
        }

        if (forceSpeedTestToProxy) {
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply {
                        put(SPEED_TEST_INBOUND_TAG)
                        put(APP_SOCKS_INBOUND_TAG)
                    })
                    put("outboundTag", "proxy")
                },
            )
        }

        addGooglePlayDownloadRules(
            rules = rules,
            forceUdp443Fallback = forceUdp443Fallback,
        )

        tailscale?.let { config ->
            // This must remain above user, geoip:private, and global fallback
            // rules. It is the embedded equivalent of Shadowrocket's
            // TAILSCALE policy target.
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray().put("domain:ts.net"))
                    put("outboundTag", "tailscale")
                },
            )
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put(
                        "ip",
                        JSONArray().apply {
                            put("100.64.0.0/10")
                            put("fd7a:115c:a1e0::/48")
                            config.subnetRoutes.forEach(::put)
                        },
                    )
                    put("outboundTag", "tailscale")
                },
            )
        }

        if (blockForeignUdp443) {
            // QUIC to the proxy is dropped so apps fall back to TCP (QUIC
            // sniffing is best-effort, so this also covers Play downloads
            // whose domain was not extracted). QUIC to the home region (WeChat
            // images and videos, for example) is left to the direct rules.
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("network", "udp")
                    put("port", "443")
                    if (!globalProxyEnabled) {
                        put("ip", JSONArray().put("geoip:!${geoRoutingRegion.geoipCode}"))
                    }
                    put("outboundTag", "block")
                },
            )
        }

        // Global mode skips rule sources and media routing, but an app's own node
        // and the top-priority rules still apply.
        val effectiveRoutingRules = if (globalProxyEnabled) {
            routingRules.filter { it.processes.isNotEmpty() || it.priority >= RuleSourceManager.TOP_RULE_PRIORITY }
        } else {
            routingRules
        }

        effectiveRoutingRules
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<XrayRoutingRule>> { it.value.priority }
                    .thenBy { it.index },
            )
            .forEach { indexedRule ->
                val rule = indexedRule.value
                val outboundTag = rule.outboundTag ?: when (rule.target) {
                    RuleTargetPolicy.Direct -> "direct"
                    RuleTargetPolicy.Proxy -> "proxy"
                    RuleTargetPolicy.Block -> "block"
                }
                if (rule.processes.isNotEmpty()) {
                    // Per-app node: Xray asks Android which app owns each connection.
                    rules.put(
                        JSONObject().apply {
                            put("type", "field")
                            put("process", JSONArray(rule.processes))
                            put("outboundTag", outboundTag)
                        },
                    )
                }
                val domains = JSONArray().apply {
                    rule.domainSuffixes.forEach { put("domain:$it") }
                    rule.fullDomains.forEach { put("full:$it") }
                    rule.domainKeywords.forEach { put("keyword:$it") }
                    rule.domainRegexes.forEach { put("regexp:$it") }
                }
                if (domains.length() > 0) {
                    rules.put(
                        JSONObject().apply {
                            put("type", "field")
                            put("domain", domains)
                            put("outboundTag", outboundTag)
                        },
                    )
                }
                val ips = JSONArray().apply {
                    rule.ipCidrs.forEach(::put)
                    rule.ipCidrs6.forEach(::put)
                }
                if (ips.length() > 0) {
                    rules.put(
                        JSONObject().apply {
                            put("type", "field")
                            put("ip", ips)
                            put("outboundTag", outboundTag)
                        },
                    )
                }
            }

        // Keep private-network traffic direct after explicit user and service
        // rules have had a chance to match.
        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().apply {
                    put("geoip:private")
                })
                put("outboundTag", "direct")
            },
        )

        if (!globalProxyEnabled) {
            // The region's site list follows the built-in home-sites switch.
            geoRoutingRegion.geositeCode?.takeIf { homeSitesDirect }?.let { geositeCode ->
                rules.put(
                    JSONObject().apply {
                        put("type", "field")
                        put("domain", JSONArray().apply {
                            put("geosite:$geositeCode")
                        })
                        put("outboundTag", "direct")
                    },
                )
            }
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().apply {
                        put("geoip:${geoRoutingRegion.geoipCode}")
                    })
                    put("outboundTag", "direct")
                },
            )
        }

        // Explicit fallback so the default outbound never depends on the order
        // of the outbounds array (streaming/pre-proxy chains can re-order it).
        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("network", "tcp,udp")
                put("outboundTag", if (tailscale?.routeAllTraffic == true) "tailscale" else "proxy")
            },
        )

        return rules
    }

    /**
     * MagicDNS inside the VPN. Android sends all DNS to one server and has no
     * split DNS, so tunnel DNS goes through Xray's `dns` outbound:
     * - peer names under the tailnet suffix are hijacked and answered by the DNS
     *   module ([magicDnsSettings]); other names under the suffix get NXDOMAIN;
     * - every other query is forwarded untouched. Its dialer is a loopback that
     *   hands the query back to routing, so it takes exactly the path it took
     *   without MagicDNS (private resolver direct, remote resolver via proxy, …).
     */
    /** Forwards DNS queries to [server] over TCP through [via] (the proxy), whatever the node's UDP support. */
    private fun remoteDnsOutbound(server: String, via: String) = JSONObject().apply {
        put("tag", REMOTE_DNS_OUTBOUND_TAG)
        put("protocol", "dns")
        put(
            "settings",
            JSONObject().apply {
                put("network", "tcp")
                put("address", server)
                put("port", 53)
                // Forward every query. Without this rule the dns outbound
                // answers A/AAAA itself with Xray's built-in resolver (which
                // has no servers on Android, so lookups fail) and rejects the rest.
                put("rules", JSONArray().put(JSONObject().put("action", "direct")))
            },
        )
        put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", via)))
    }

    private fun magicDnsOutbounds(upstream: String, tailscale: TailscaleRouting?): List<JSONObject> {
        val (upstreamHost, upstreamPort) = splitHostPort(upstream)
        val rules = JSONArray()
        tailscale?.takeIf { it.resolvesMagicDns }?.let { config ->
            if (config.magicDnsHosts.isNotEmpty()) {
                rules.put(
                    JSONObject()
                        .put("action", "hijack")
                        .put("domain", JSONArray(config.magicDnsHosts.keys.map { "full:$it" })),
                )
            }
            // Other names in the tailnet do not exist: answer at once instead of
            // leaking them to the regular resolver.
            rules.put(
                JSONObject()
                    .put("action", "return")
                    .put("rCode", NXDOMAIN)
                    .put("domain", JSONArray().put("domain:${config.magicDnsSuffix}")),
            )
        }
        rules.put(JSONObject().put("action", "direct"))
        return listOf(
            JSONObject().apply {
                put("tag", MAGIC_DNS_OUTBOUND_TAG)
                put("protocol", "dns")
                put(
                    "settings",
                    JSONObject().apply {
                        // Passed-on queries go to the real resolver, not the advertised address.
                        put("address", upstreamHost)
                        put("port", upstreamPort)
                        put("rules", rules)
                    },
                )
                put(
                    "streamSettings",
                    JSONObject().put("sockopt", JSONObject().put("dialerProxy", MAGIC_DNS_PASSTHROUGH_TAG)),
                )
            },
            JSONObject().apply {
                put("tag", MAGIC_DNS_PASSTHROUGH_TAG)
                put("protocol", "loopback")
                put("settings", JSONObject().put("inboundTag", MAGIC_DNS_PASSTHROUGH_TAG))
            },
        )
    }

    /** "1.2.3.4:53" / "[2001:db8::1]:53" / "1.2.3.4" → host and port. */
    private fun splitHostPort(value: String): Pair<String, Int> {
        val trimmed = value.trim()
        return if (trimmed.startsWith("[")) {
            trimmed.substringAfter('[').substringBefore(']') to
                (trimmed.substringAfter("]:", "").toIntOrNull() ?: 53)
        } else if (trimmed.count { it == ':' } == 1) {
            trimmed.substringBefore(':') to (trimmed.substringAfter(':').toIntOrNull() ?: 53)
        } else {
            trimmed to 53
        }
    }

    /**
     * Peer names come from the Tailscale status; static hosts answer every record
     * type themselves (no AAAA for a v4-only peer gives an empty answer), so the
     * DNS module needs no upstream. Asking 100.100.100.100 over the tailnet
     * instead was tried: the userspace node does not serve it to SOCKS clients
     * and every lookup waited for timeouts.
     */
    /**
     * Xray's DNS module for encrypted DNS. DoH and DoT queries are routed like
     * other traffic (a domestic server goes direct, a foreign one through the
     * proxy); DoQ is always dialed directly (Xray only supports it that way).
     * With MagicDNS on, tailnet names are answered here too.
     */
    private fun encryptedDnsSettings(dns: DnsInput.EncryptedDns, magicDns: TailscaleRouting?): JSONObject =
        JSONObject().apply {
            put("tag", DNS_MODULE_TAG)
            put("servers", JSONArray().put(dns.xrayServer))
            put("queryStrategy", "UseIP")
            magicDns?.let { config ->
                put(
                    "hosts",
                    JSONObject().apply {
                        config.magicDnsHosts.forEach { (name, addresses) -> put("full:$name", JSONArray(addresses)) }
                    },
                )
            }
        }

    /**
     * The dns outbound answers A/AAAA from the DNS module. Xray's DNS module
     * only resolves addresses, so other query types (HTTPS, SVCB, TXT, SRV, MX …)
     * can't go through it:
     * - DoT: they are forwarded as they are, as DNS-over-TCP through the same
     *   TLS outbound the module uses, so every type gets a real, encrypted answer;
     * - DoH / DoQ: Xray has no transport to forward a raw query over them, so
     *   they get an empty answer (the settings screen says so).
     * DoT adds a TLS outbound that dials through a loopback back into routing.
     */
    fun encryptedDnsForwardsAllQueryTypes(dns: DnsInput.EncryptedDns): Boolean =
        dns.kind == DnsInput.EncryptedDns.Kind.DoT

    private fun encryptedDnsOutbounds(dns: DnsInput.EncryptedDns, magicDns: TailscaleRouting?): List<JSONObject> = buildList {
        add(
            JSONObject().apply {
                put("tag", ENCRYPTED_DNS_OUTBOUND_TAG)
                put("protocol", "dns")
                if (encryptedDnsForwardsAllQueryTypes(dns)) {
                    put(
                        "settings",
                        JSONObject().apply {
                            put("network", "tcp")
                            put("address", dns.host)
                            put("port", dns.port)
                            put(
                                "rules",
                                JSONArray().apply {
                                    // A / AAAA: the DNS module (caching, MagicDNS hosts).
                                    put(JSONObject().put("action", "hijack").put("qType", "1,28"))
                                    // Tailnet names stay inside: no other records, no leak to the server.
                                    magicDns?.let { config ->
                                        put(
                                            JSONObject()
                                                .put("action", "return")
                                                .put("domain", JSONArray().put("domain:${config.magicDnsSuffix}")),
                                        )
                                    }
                                    put(JSONObject().put("action", "direct"))
                                },
                            )
                        },
                    )
                    put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", DOT_OUTBOUND_TAG)))
                }
            },
        )
        if (dns.kind == DnsInput.EncryptedDns.Kind.DoT) {
            add(
                JSONObject().apply {
                    put("tag", DOT_OUTBOUND_TAG)
                    put("protocol", "freedom")
                    put(
                        "streamSettings",
                        JSONObject().apply {
                            put("security", "tls")
                            put("tlsSettings", JSONObject().put("serverName", dns.host))
                            put("sockopt", JSONObject().put("dialerProxy", DOT_LOOPBACK_TAG))
                        },
                    )
                },
            )
            add(
                JSONObject().apply {
                    put("tag", DOT_LOOPBACK_TAG)
                    put("protocol", "loopback")
                    put("settings", JSONObject().put("inboundTag", DOT_LOOPBACK_TAG))
                },
            )
        }
    }

    private fun magicDnsSettings(config: TailscaleRouting): JSONObject = JSONObject().apply {
        put("tag", "magic-dns")
        put(
            "hosts",
            JSONObject().apply {
                config.magicDnsHosts.forEach { (name, addresses) -> put("full:$name", JSONArray(addresses)) }
            },
        )
        put("queryStrategy", "UseIP")
    }

    private fun tailscaleOutbound(config: TailscaleRouting): JSONObject {
        return JSONObject().apply {
            put("tag", "tailscale")
            put("protocol", "socks")
            put(
                "settings",
                JSONObject().apply {
                    put(
                        "servers",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("address", config.socksHost)
                                    put("port", config.socksPort)
                                    put(
                                        "users",
                                        JSONArray().apply {
                                            put(
                                                JSONObject().apply {
                                                    put("user", config.socksUsername)
                                                    put("pass", config.socksPassword)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun addGooglePlayDownloadRules(
        rules: JSONArray,
        forceUdp443Fallback: Boolean,
    ) {
        val domains = JSONArray().apply {
            GOOGLE_PLAY_DOWNLOAD_DOMAINS.forEach { put("domain:$it") }
        }
        if (forceUdp443Fallback) {
            // Sniffed Play QUIC falls back to TCP. Unsniffed QUIC is caught by
            // the UDP/443 block after the Tailscale rules.
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(domains.toString()))
                    put("network", "udp")
                    put("port", "443")
                    put("outboundTag", "block")
                },
            )
        }
        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("domain", domains)
                put("outboundTag", "proxy")
            },
        )
    }

    private fun socksInbound(endpoint: LocalProxyEndpoint, tag: String = "socks-in"): JSONObject {
        return JSONObject().apply {
            put("tag", tag)
            put("listen", endpoint.host)
            put("port", endpoint.port)
            put("protocol", "socks")
            put(
                "settings",
                JSONObject().apply {
                    put("auth", "password")
                    put("accounts", JSONArray().put(endpoint.account()))
                    put("udp", true)
                },
            )
            put("sniffing", sniffing())
        }
    }

    private fun httpInbound(endpoint: LocalProxyEndpoint, tag: String = "http-in"): JSONObject {
        return JSONObject().apply {
            put("tag", tag)
            put("listen", endpoint.host)
            put("port", endpoint.port)
            put("protocol", "http")
            put(
                "settings",
                JSONObject().apply {
                    put("accounts", JSONArray().put(endpoint.account()))
                },
            )
            put("sniffing", sniffing())
        }
    }

    private fun sniffing(): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            put("destOverride", JSONArray().apply {
                put("http")
                put("tls")
                put("quic")
            })
        }
    }

    private fun LocalProxyEndpoint.account(): JSONObject {
        return JSONObject().apply {
            put("user", username)
            put("pass", password)
        }
    }

    /**
     * Nodes that run in the mihomo sidecar for one run, in listener-slot order:
     * the node, the routed outbounds, then pre-proxy fronts of their chains.
     * The core manager starts the sidecar from the same list, so slots match.
     */
    fun sidecarNodesFor(
        node: ServerNode,
        additional: List<ServerNode>,
        availableServers: List<ServerNode>,
    ): List<ServerNode> {
        // The same chains buildOutboundChain builds, so every front that
        // runs in the sidecar gets a listener.
        val fronts = (listOf(node) + additional).flatMap { root -> preProxyChain(root, availableServers).fronts }
        return SsrMihomoConfigFactory.sidecarNodes(node, additional + fronts)
    }

    /**
     * A minimal config that only runs [front] (with its own pre-proxy chain)
     * behind the authenticated SOCKS inbound of [localProxy]. Used briefly
     * before the real start to fetch the certificate of a node that sits
     * behind [front] and skips certificate verification.
     */
    fun buildPinFetchConfig(
        front: ServerNode,
        availableServers: List<ServerNode>,
        localProxy: LocalProxyAllocation,
        sidecarNodes: List<ServerNode> = emptyList(),
    ): String {
        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            // No sniffing: it would replace the node's address with the TLS SNI.
            put("inbounds", JSONArray().put(socksInbound(localProxy.socks).apply { remove("sniffing") }))
            put(
                "outbounds",
                JSONArray().apply {
                    // The first outbound is the default one. A front run by the sidecar is
                    // reached through its listener, so the sidecar must already be up.
                    buildOutboundChain(front, "proxy", availableServers, localProxy, sidecarNodes).forEach(::put)
                },
            )
        }.toString(2)
    }

    private val probeAllocation by lazy { LocalProxyAllocation.ephemeral() }

    /**
     * The outbounds of [node] and its pre-proxy chain, tagged "proxy" first,
     * for a latency probe in a separate core (LatencyProbeClient). Null when
     * the probe can't stand in for the real run: a node or front that runs in
     * the mihomo sidecar (its listener only exists while that sidecar does),
     * a chain that can't run, or settings the outbound can't be built from.
     */
    fun buildProbeOutbounds(node: ServerNode, availableServers: List<ServerNode>): String? {
        val chain = preProxyChain(node, availableServers)
        if (chain.problem != null) return null
        if ((listOf(node) + chain.fronts).any { NodeCompatibility.runsInSidecar(it) }) return null
        return runCatching {
            JSONObject().put(
                "outbounds",
                JSONArray().apply {
                    // No sidecar node in this chain, so the allocation is never read.
                    buildOutboundChain(node, "proxy", availableServers, probeAllocation, emptyList()).forEach(::put)
                },
            ).toString()
        }.getOrNull()
    }

    private fun proxyOutbound(
        node: ServerNode,
        tag: String,
        localProxy: LocalProxyAllocation,
        sidecarNodes: List<ServerNode>,
    ): JSONObject {
        // Nodes Xray can't run (see NodeCompatibility) live in the mihomo sidecar;
        // point the outbound at that node's authenticated loopback listener. The
        // sidecar is started from the same node list, so the two never disagree.
        val outbound = if (NodeCompatibility.runsInSidecar(node)) {
            SsrMihomoConfigFactory.localSocksOutbound(SsrMihomoConfigFactory.endpointFor(node, sidecarNodes, localProxy))
        } else {
            NodeCompatibility.coreIncompatibility(node)?.let { reason -> error("${node.name}：$reason。") }
            ManualNodeFactory.buildOutboundConfig(node)
        }
        return outbound?.apply {
            put("tag", tag)
        } ?: error(tr("当前节点缺少可用的 Xray 出站配置。", "The current node has no usable Xray outbound config.", "У текущего узла нет пригодной исходящей конфигурации Xray."))
    }

    private fun buildOutboundChain(
        node: ServerNode,
        tag: String,
        availableServers: List<ServerNode>,
        localProxy: LocalProxyAllocation,
        sidecarNodes: List<ServerNode>,
    ): List<JSONObject> {
        val chain = preProxyChain(node, availableServers)
        // A loop or an over-long chain never runs with a hop left out.
        chain.problem?.let { error("${node.name}：$it") }
        return buildList {
            var outbound = proxyOutbound(node, tag, localProxy, sidecarNodes)
            var outboundTag = tag
            add(outbound)
            chain.fronts.forEach { front ->
                val frontTag = "${outboundTag}_pre_${front.id.takeLast(6)}"
                attachDialerProxy(outbound, frontTag)
                outbound = proxyOutbound(front, frontTag, localProxy, sidecarNodes)
                outboundTag = frontTag
                add(outbound)
            }
        }
    }

    /**
     * A node's pre-proxy chain: its fronts in dial order (nearest the node
     * first), or why it can't run ([problem]: a loop, or more than
     * [MAX_CHAIN_LENGTH] fronts). The one reading of the chain for the
     * outbounds, the sidecar listeners, the tunnel's node set and the checks
     * when a pre-proxy is set or imported.
     */
    data class PreProxyChain(val fronts: List<ServerNode>, val problem: String? = null)

    fun preProxyChain(node: ServerNode, availableServers: List<ServerNode>): PreProxyChain {
        val fronts = mutableListOf<ServerNode>()
        val path = mutableListOf(node)
        var current = node
        while (true) {
            val front = when (val next = nextPreProxy(current, availableServers)) {
                NextPreProxy.None -> return PreProxyChain(fronts)
                // Never dialed directly instead: the node needs that front.
                is NextPreProxy.Unusable -> return PreProxyChain(fronts, next.reason)
                is NextPreProxy.Front -> next.node
            }
            if (path.any { it.id == front.id }) {
                val loop = (path.dropWhile { it.id != front.id } + front).joinToString(" → ") { it.name }
                return PreProxyChain(fronts, tr("前置代理循环引用（$loop），无法连接", "The pre-proxy chain loops ($loop) and can't connect", "Цепочка предварительных прокси зациклена ($loop), подключение невозможно"))
            }
            if (fronts.size >= MAX_CHAIN_LENGTH) {
                return PreProxyChain(fronts, tr("前置代理链超过 $MAX_CHAIN_LENGTH 个节点，无法连接", "The pre-proxy chain has more than $MAX_CHAIN_LENGTH nodes and can't connect", "В цепочке предварительных прокси больше $MAX_CHAIN_LENGTH узлов, подключение невозможно"))
            }
            fronts += front
            path += front
            current = front
        }
    }

    private sealed interface NextPreProxy {
        data object None : NextPreProxy
        data class Front(val node: ServerNode) : NextPreProxy
        /** The node names a front that can't run (gone, hidden, unsupported). */
        data class Unusable(val reason: String) : NextPreProxy
    }

    /**
     * The node [node] dials through. A front it names that is missing, hidden
     * or can't run is [NextPreProxy.Unusable]: the chain is refused rather
     * than run without it (the node would dial its server directly).
     */
    private fun nextPreProxy(node: ServerNode, availableServers: List<ServerNode>): NextPreProxy {
        val targetId = node.preProxyNodeId.trim()
        if (targetId.isBlank()) return NextPreProxy.None
        // The node itself must be an Xray node (it dials through the front with
        // dialerProxy); the front may run anywhere, including the mihomo sidecar,
        // whose loopback listener is an ordinary SOCKS outbound here. Saved
        // links of other nodes are cleared on load (StateOps.normalizeNodeLinks).
        if (!ManualNodeFactory.supportsPreProxy(node)) return NextPreProxy.None
        val target = availableServers.firstOrNull { it.id == targetId }
            ?: return NextPreProxy.Unusable(
                tr("前置节点已不存在，无法连接", "Its pre-proxy no longer exists, so it can't connect", "Предварительного прокси больше нет, подключение невозможно"),
            )
        if (target.hiddenUnsupported || !ManualNodeFactory.isRunnable(target)) {
            val why = NodeCompatibility.coreIncompatibility(target)?.let { "（$it）" }.orEmpty()
            return NextPreProxy.Unusable(
                tr(
                    "前置节点 ${target.name} 不受支持$why，无法连接",
                    "Its pre-proxy ${target.name} isn't supported$why, so it can't connect",
                    "Предварительный прокси ${target.name} не поддерживается$why, подключение невозможно",
                ),
            )
        }
        return NextPreProxy.Front(target)
    }

    private fun attachDialerProxy(
        outbound: JSONObject,
        dialerProxyTag: String,
    ) {
        val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject().also {
            outbound.put("streamSettings", it)
        }
        val sockopt = streamSettings.optJSONObject("sockopt") ?: JSONObject().also {
            streamSettings.put("sockopt", it)
        }
        sockopt.put("dialerProxy", dialerProxyTag)
    }

    private fun buildVlessOutbound(rawUri: String, tag: String): JSONObject {
        val uri = URI(rawUri)
        val query = parseQuery(uri.rawQuery)
        val streamSettings = buildCommonStreamSettings(
            network = query["type"] ?: "tcp",
            security = query["security"] ?: "none",
            host = query["host"],
            path = query["path"] ?: query["spx"],
            serviceName = query["serviceName"] ?: query["serviceName".lowercase(Locale.ROOT)],
            grpcMode = query["mode"],
            grpcAuthority = query["authority"],
            serverName = query["sni"],
            fingerprint = query["fp"],
            alpn = query["alpn"],
            shortId = query["sid"],
            publicKey = query["pbk"],
            spiderX = query["spx"],
            kcpSeed = query["seed"],
            kcpHeaderType = query["headerType"],
        )

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "vless")
            put(
                "settings",
                JSONObject().apply {
                    put(
                        "vnext",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("address", uri.host.orEmpty())
                                    put("port", if (uri.port == -1) 443 else uri.port)
                                    put(
                                        "users",
                                        JSONArray().apply {
                                            put(
                                                JSONObject().apply {
                                                    put("id", uri.userInfo.orEmpty())
                                                    put("encryption", query["encryption"] ?: "none")
                                                    query["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put("streamSettings", streamSettings)
        }
    }

    private fun buildVmessOutbound(rawUri: String, tag: String): JSONObject {
        val body = rawUri.removePrefix("vmess://")
        val authPart = body.substringBefore("?")
        val queryPart = body.substringAfter("?", "")
        val decoded = runCatching {
            Base64.getDecoder().decode(padBase64(authPart)).decodeToString()
        }.getOrElse {
            error(tr("当前 vmess 链接格式无法生成 Xray 配置。", "Couldn't build an Xray config from this vmess link.", "Не удалось создать конфигурацию Xray из этой ссылки vmess."))
        }.trim()

        val legacyParams = parseQuery(queryPart)
        val vmess = if (decoded.startsWith("{")) {
            val json = JSONObject(decoded)
            VmessConfig(
                address = json.optString("add"),
                port = json.optInt("port", 443),
                userId = json.optString("id"),
                alterId = json.optInt("aid", legacyParams["alterId"]?.toIntOrNull() ?: 0),
                securityMethod = json.optString("scy").ifBlank { "auto" },
                network = json.optString("net").ifBlank { "tcp" },
                security = when (val tls = json.optString("tls").lowercase(Locale.ROOT)) {
                    "tls" -> "tls"
                    "" -> "none"
                    else -> tls
                },
                host = json.optString("host").ifBlank { null },
                path = json.optString("path").ifBlank { null },
                serviceName = json.optString("path").ifBlank { null },
                serverName = json.optString("sni").ifBlank { null },
                fingerprint = json.optString("fp").ifBlank { null },
                alpn = json.optString("alpn").ifBlank { null },
                kcpSeed = null,
                kcpHeaderType = null,
                legacyKcp = false,
            )
        } else {
            parseLegacyVmess(decoded, legacyParams)
        }

        val streamSettings = buildCommonStreamSettings(
            network = vmess.network,
            security = vmess.security,
            host = vmess.host,
            path = vmess.path,
            serviceName = vmess.serviceName,
            grpcMode = null,
            grpcAuthority = null,
            serverName = vmess.serverName,
            fingerprint = vmess.fingerprint,
            alpn = vmess.alpn,
            shortId = null,
            publicKey = null,
            spiderX = null,
            kcpSeed = vmess.kcpSeed,
            kcpHeaderType = vmess.kcpHeaderType,
            legacyKcp = vmess.legacyKcp,
        )

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "vmess")
            put(
                "settings",
                JSONObject().apply {
                    put(
                        "vnext",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("address", vmess.address)
                                    put("port", vmess.port)
                                    put(
                                        "users",
                                        JSONArray().apply {
                                            put(
                                                JSONObject().apply {
                                                    put("id", vmess.userId)
                                                    put("alterId", vmess.alterId)
                                                    put("security", vmess.securityMethod)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put("streamSettings", streamSettings)
        }
    }

    private fun parseLegacyVmess(decoded: String, params: Map<String, String>): VmessConfig {
        val securityMethod = decoded.substringBefore(":", "auto").ifBlank { "auto" }
        val credentialPart = decoded.substringAfter(":", decoded)
        val userId = credentialPart.substringBefore("@").takeIf { it.isNotBlank() }
            ?: error(tr("vmess 节点缺少用户 ID。", "The vmess node has no user ID.", "У узла vmess нет ID пользователя."))
        val hostPort = credentialPart.substringAfter("@", "")
        val address = hostPort.substringBeforeLast(":", hostPort).takeIf { it.isNotBlank() }
            ?: error(tr("vmess 节点缺少服务端地址。", "The vmess node has no server address.", "У узла vmess нет адреса сервера."))
        val port = hostPort.substringAfterLast(":", "443").toIntOrNull() ?: 443

        val network = when (params["obfs"]?.lowercase(Locale.ROOT) ?: params["type"]?.lowercase(Locale.ROOT)) {
            null, "", "none", "tcp" -> "tcp"
            "websocket" -> "ws"
            "mkcp" -> "kcp"
            else -> params["obfs"]?.lowercase(Locale.ROOT) ?: params["type"]!!.lowercase(Locale.ROOT)
        }
        val security = when (params["tls"]?.lowercase(Locale.ROOT)) {
            "1", "true", "tls" -> "tls"
            else -> params["security"]?.lowercase(Locale.ROOT) ?: "none"
        }
        val obfsParamJson = params["obfsParam"]
            ?.takeIf { it.trim().startsWith("{") }
            ?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }
        val host = params["host"]
            ?: params["obfsParam"]?.takeUnless { it.trim().startsWith("{") }
            ?: params["peer"]
            ?: params["sni"]
        val path = params["path"] ?: params["serviceName"]
        val serviceName = params["serviceName"] ?: params["path"]

        return VmessConfig(
            address = address,
            port = port,
            userId = userId,
            alterId = params["alterId"]?.toIntOrNull() ?: 0,
            securityMethod = securityMethod,
            network = network,
            security = security,
            host = host?.takeIf { it.isNotBlank() },
            path = path?.takeIf { it.isNotBlank() },
            serviceName = serviceName?.takeIf { it.isNotBlank() },
            serverName = (params["peer"] ?: params["sni"])?.takeIf { it.isNotBlank() },
            fingerprint = params["fp"]?.takeIf { it.isNotBlank() },
            alpn = params["alpn"]?.takeIf { it.isNotBlank() },
            kcpSeed = obfsParamJson?.optString("seed")?.takeIf { !it.isNullOrBlank() },
            kcpHeaderType = obfsParamJson?.optString("header")?.takeIf { !it.isNullOrBlank() },
            legacyKcp = network == "kcp",
        )
    }

    private fun buildCommonStreamSettings(
        network: String,
        security: String,
        host: String?,
        path: String?,
        serviceName: String?,
        grpcMode: String?,
        grpcAuthority: String?,
        serverName: String?,
        fingerprint: String?,
        alpn: String?,
        shortId: String?,
        publicKey: String?,
        spiderX: String?,
        kcpSeed: String?,
        kcpHeaderType: String?,
        legacyKcp: Boolean = false,
    ): JSONObject {
        val normalizedNetwork = when (network.lowercase(Locale.ROOT)) {
            "mkcp" -> "kcp"
            else -> network.lowercase(Locale.ROOT)
        }
        val normalizedSecurity = security.lowercase(Locale.ROOT)

        return JSONObject().apply {
            put("network", normalizedNetwork)
            put("security", normalizedSecurity)

            when (normalizedNetwork) {
                "ws" -> put(
                    "wsSettings",
                    JSONObject().apply {
                        path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                        host?.takeIf { it.isNotBlank() }?.let {
                            put(
                                "headers",
                                JSONObject().apply {
                                    put("Host", it)
                                },
                            )
                        }
                    },
                )

                "grpc" -> put(
                    "grpcSettings",
                    JSONObject().apply {
                        serviceName?.takeIf { it.isNotBlank() }?.let { put("serviceName", it) }
                        grpcAuthority?.takeIf { it.isNotBlank() }?.let { put("authority", it) }
                        when (grpcMode?.lowercase(Locale.ROOT)) {
                            "multi" -> put("multiMode", true)
                            "gun" -> put("multiMode", false)
                        }
                    },
                )

                "httpupgrade" -> put(
                    "httpupgradeSettings",
                    JSONObject().apply {
                        host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                        path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                    },
                )

                "xhttp" -> put(
                    "xhttpSettings",
                    JSONObject().apply {
                        host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                        path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                    },
                )

                "http", "h2" -> put(
                    "httpSettings",
                    JSONObject().apply {
                        host?.takeIf { it.isNotBlank() }?.let {
                            put("host", JSONArray().put(it))
                        }
                        path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                    },
                )

                "kcp" -> put(
                    "kcpSettings",
                    JSONObject().apply {
                        // Newer Xray versions moved legacy mKCP header/seed camouflage
                        // into finalmask. Keep KCP transport settings minimal here.
                    },
                )
            }

            val finalMaskUdpEntries = mutableListOf<JSONObject>()
            if (legacyKcp && normalizedNetwork == "kcp") {
                kcpHeaderType?.lowercase(Locale.ROOT)?.ifBlank { null }?.let { headerType ->
                    finalMaskUdpEntries += JSONObject().apply {
                        put("type", "header-$headerType")
                    }
                }
                if (kcpSeed.isNullOrBlank()) {
                    finalMaskUdpEntries += JSONObject().apply {
                        put("type", "mkcp-original")
                    }
                } else {
                    finalMaskUdpEntries += JSONObject().apply {
                        put("type", "mkcp-aes128gcm")
                        put(
                            "settings",
                            JSONObject().apply {
                                put("password", kcpSeed)
                            },
                        )
                    }
                }
            }
            if (finalMaskUdpEntries.isNotEmpty()) {
                put(
                    "finalmask",
                    JSONObject().apply {
                        put(
                            "udp",
                            JSONArray().apply {
                                finalMaskUdpEntries.forEach(::put)
                            },
                        )
                    },
                )
            }

            when (normalizedSecurity) {
                "tls" -> put(
                    "tlsSettings",
                    JSONObject().apply {
                        serverName?.takeIf { it.isNotBlank() }?.let { put("serverName", it) }
                        fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                        val effectiveAlpn = when {
                            !alpn.isNullOrBlank() -> alpn
                            normalizedNetwork == "grpc" -> "h2,http/1.1"
                            else -> null
                        }
                        effectiveAlpn?.takeIf { it.isNotBlank() }?.let {
                            put(
                                "alpn",
                                JSONArray().apply {
                                    it.split(",").map(String::trim).filter(String::isNotBlank).forEach(::put)
                                },
                            )
                        }
                    },
                )

                "reality" -> put(
                    "realitySettings",
                    JSONObject().apply {
                        serverName?.takeIf { it.isNotBlank() }?.let { put("serverName", it) }
                        fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                        if (normalizedNetwork == "grpc") {
                            put(
                                "alpn",
                                JSONArray().apply {
                                    (
                                        alpn?.split(",")?.map(String::trim)?.filter(String::isNotBlank)
                                            ?: listOf("h2", "http/1.1")
                                    )
                                        .forEach(::put)
                                },
                            )
                        }
                        publicKey?.takeIf { it.isNotBlank() }?.let { put("publicKey", it) }
                        shortId?.takeIf { it.isNotBlank() }?.let { put("shortId", it) }
                        spiderX?.takeIf { it.isNotBlank() }?.let { put("spiderX", it) }
                    },
                )
            }
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

    private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)

    private data class VmessConfig(
        val address: String,
        val port: Int,
        val userId: String,
        val alterId: Int,
        val securityMethod: String,
        val network: String,
        val security: String,
        val host: String?,
        val path: String?,
        val serviceName: String?,
        val serverName: String?,
        val fingerprint: String?,
        val alpn: String?,
        val kcpSeed: String?,
        val kcpHeaderType: String?,
        val legacyKcp: Boolean,
    )
}
