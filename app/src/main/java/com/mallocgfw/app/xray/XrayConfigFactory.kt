package com.mallocgfw.app.xray

import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayNamedOutbound
import com.mallocgfw.app.model.XrayRoutingRule
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigFactory {
    const val SOCKS_PORT = 10808
    const val HTTP_PORT = 10809
    const val VPN_MTU = 1500

    fun build(
        node: ServerNode,
        availableServers: List<ServerNode> = emptyList(),
        routingRules: List<XrayRoutingRule> = emptyList(),
        additionalOutbounds: List<XrayNamedOutbound> = emptyList(),
        logLevel: String = "warning",
        errorLogPath: String? = null,
        accessLogPath: String? = null,
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
                    put(socksInbound())
                    put(httpInbound())
                },
            )
            put(
                "outbounds",
                JSONArray().apply {
                    buildOutboundChain(node, "proxy", availableServers).forEach(::put)
                    additionalOutbounds.forEach { outbound ->
                        buildOutboundChain(outbound.node, outbound.tag, availableServers).forEach(::put)
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
                    put("rules", buildRoutingRules(routingRules))
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
                    put(
                        JSONObject().apply {
                            put("tag", "tun-in")
                            put("protocol", "tun")
                            put("settings", JSONObject().apply {
                                put("name", "xray0")
                                put("MTU", VPN_MTU)
                            })
                            put("sniffing", sniffing())
                        },
                    )
                },
            )
            put(
                "outbounds",
                JSONArray().apply {
                    buildOutboundChain(node, "proxy", availableServers).forEach(::put)
                    additionalOutbounds.forEach { outbound ->
                        buildOutboundChain(outbound.node, outbound.tag, availableServers).forEach(::put)
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
                    put("rules", buildRoutingRules(routingRules))
                },
            )
        }.toString(2)
    }

    private fun buildRoutingRules(routingRules: List<XrayRoutingRule>): JSONArray {
        val rules = JSONArray()

        // Keep private-network traffic direct, but let streaming and user rules
        // match before broad CN direct fallbacks.
        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().apply {
                    put("geoip:private")
                })
                put("outboundTag", "direct")
            },
        )

        routingRules
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<XrayRoutingRule>> { it.value.priority }
                    .thenBy { it.index },
            )
            .forEach { indexedRule ->
            val rule = indexedRule.value
            val outboundTag = rule.outboundTag ?: when (rule.target.name) {
                "Direct" -> "direct"
                else -> "proxy"
            }
            val domains = JSONArray().apply {
                rule.domainSuffixes.forEach { put("domain:$it") }
                rule.fullDomains.forEach { put("full:$it") }
                rule.domainKeywords.forEach { put("keyword:$it") }
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

        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray().apply {
                    put("geosite:cn")
                })
                put("outboundTag", "direct")
            },
        )
        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().apply {
                    put("geoip:cn")
                })
                put("outboundTag", "direct")
            },
        )

        // Explicit fallback so the default outbound never depends on the order
        // of the outbounds array (streaming/pre-proxy chains can re-order it).
        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("network", "tcp,udp")
                put("outboundTag", "proxy")
            },
        )

        return rules
    }

    private fun socksInbound(): JSONObject {
        return JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", SOCKS_PORT)
            put("protocol", "socks")
            put(
                "settings",
                JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                },
            )
            put("sniffing", sniffing())
        }
    }

    private fun httpInbound(): JSONObject {
        return JSONObject().apply {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", HTTP_PORT)
            put("protocol", "http")
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

    private fun proxyOutbound(node: ServerNode, tag: String): JSONObject {
        return ManualNodeFactory.buildOutboundConfig(node)?.apply {
            put("tag", tag)
        } ?: error("当前节点缺少可用的 Xray 出站配置。")
    }

    private fun buildOutboundChain(
        node: ServerNode,
        tag: String,
        availableServers: List<ServerNode>,
        visitedNodeIds: Set<String> = emptySet(),
    ): List<JSONObject> {
        val outbound = proxyOutbound(node, tag)
        val nextVisited = visitedNodeIds + node.id
        val preProxyNode = resolvePreProxyNode(
            node = node,
            availableServers = availableServers,
            visitedNodeIds = nextVisited,
        ) ?: return listOf(outbound)
        val preProxyTag = "${tag}_pre_${preProxyNode.id.takeLast(6)}"
        attachDialerProxy(outbound, preProxyTag)
        return buildList {
            add(outbound)
            addAll(buildOutboundChain(preProxyNode, preProxyTag, availableServers, nextVisited))
        }
    }

    private fun resolvePreProxyNode(
        node: ServerNode,
        availableServers: List<ServerNode>,
        visitedNodeIds: Set<String>,
    ): ServerNode? {
        val targetId = node.preProxyNodeId.trim()
        if (targetId.isBlank()) return null
        val target = availableServers.firstOrNull { candidate ->
            candidate.id == targetId && !candidate.hiddenUnsupported
        } ?: return null
        if (target.id == node.id || target.id in visitedNodeIds) return null
        if (!ManualNodeFactory.supportsPreProxy(node) || !ManualNodeFactory.supportsPreProxy(target)) return null
        return target
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
            error("当前 vmess 链接格式无法生成 Xray 配置。")
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
            ?: error("vmess 节点缺少用户 ID。")
        val hostPort = credentialPart.substringAfter("@", "")
        val address = hostPort.substringBeforeLast(":", hostPort).takeIf { it.isNotBlank() }
            ?: error("vmess 节点缺少服务端地址。")
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
