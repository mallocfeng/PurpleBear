package com.mallocgfw.app.model

import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * A node as text another client (or another PurpleBear) can import:
 * - its share link: the one it was imported from, or one written from its
 *   settings (VLESS, VMess, Trojan, Shadowsocks, Hysteria2);
 * - else a Clash `proxies:` entry, for nodes the compatibility core runs;
 * - else an Xray JSON config with its outbound.
 *
 * A written link is only offered when it parses back into the same outbound,
 * so what is shared is the node as it runs here, never an approximation.
 * A node's pre-proxy and fallback are settings of this app and aren't shared.
 */
object NodeShare {
    enum class Kind { Link, ClashYaml, XrayJson }

    class Shared(val text: String, val kind: Kind)

    fun of(node: ServerNode): Shared? {
        node.rawUri.trim().takeIf { it.contains("://") && '\n' !in it }?.let { return Shared(it, Kind.Link) }
        if (node.mihomoProxyJson.isNotBlank()) {
            val proxy = NodeCompatibility.mihomoProxy(node) ?: return null
            return Shared("proxies:\n" + proxy.yamlLines.joinToString("\n") + "\n", Kind.ClashYaml)
        }
        val outbound = runCatching { ManualNodeFactory.inspectOutbound(node) }.getOrNull() ?: return null
        writtenLink(node, outbound)?.let { return Shared(it, Kind.Link) }
        val shared = JSONObject(outbound.toString()).put("tag", "proxy")
        return Shared(JSONObject().put("outbounds", JSONArray().put(shared)).toString(2), Kind.XrayJson)
    }

    /** A share link written from [node]'s settings, or null when none says the same thing. */
    internal fun writtenLink(node: ServerNode, outbound: JSONObject): String? {
        val draft = runCatching { ManualNodeFactory.prefillFromServerNode(node) }.getOrNull() ?: return null
        val link = runCatching { linkOf(draft.copy(nodeName = node.name)) }.getOrNull() ?: return null
        val again = runCatching { ManualNodeFactory.inspectOutbound(ManualNodeFactory.buildNodeFromShareLink(link)) }.getOrNull()
            ?: return null
        return link.takeIf { shape(again) == shape(outbound) }
    }

    private fun linkOf(draft: LocalNodeDraft): String? = when (draft.protocol) {
        LocalNodeProtocol.VLESS -> "vless://${enc(draft.userId)}@${hostPort(draft)}?" +
            query(listOf("encryption" to draft.vlessEncryption, "flow" to draft.vlessFlow) + streamParams(draft)) + name(draft)
        LocalNodeProtocol.TROJAN -> "trojan://${enc(draft.password)}@${hostPort(draft)}?" +
            query(streamParams(draft)) + name(draft)
        LocalNodeProtocol.VMESS -> vmessLink(draft)
        LocalNodeProtocol.SHADOWSOCKS -> shadowsocksLink(draft)
        LocalNodeProtocol.HYSTERIA -> "hysteria2://${enc(draft.hysteriaAuth)}@${hostPort(draft)}?" + query(
            listOf(
                "sni" to draft.serverName,
                "alpn" to draft.alpn,
                "obfs" to if (draft.hysteriaObfsPassword.isNotBlank()) "salamander" else "",
                "obfs-password" to draft.hysteriaObfsPassword,
                "pcs" to draft.pinnedPeerCertSha256,
                "vcn" to draft.verifyPeerCertByName,
                "insecure" to if (draft.allowInsecure) "1" else "",
            ),
        ) + name(draft)
        // SOCKS / HTTP / WireGuard / SSR: shared as they were imported, or as a config.
        else -> null
    }

    private fun streamParams(draft: LocalNodeDraft): List<Pair<String, String>> {
        val kcp = draft.transport == LocalNodeTransport.KCP
        return listOf(
            "type" to draft.transport.wireValue,
            "security" to draft.security.wireValue,
            "host" to draft.host,
            "path" to draft.path,
            "serviceName" to draft.serviceName,
            "sni" to draft.serverName,
            "fp" to draft.fingerprint,
            "alpn" to draft.alpn,
            "pcs" to draft.pinnedPeerCertSha256,
            "vcn" to draft.verifyPeerCertByName,
            "pbk" to draft.realityPublicKey,
            "sid" to draft.realityShortId,
            "spx" to draft.realitySpiderX,
            "headerType" to if (kcp) draft.kcpHeaderType else "",
            "seed" to if (kcp) draft.kcpSeed else "",
            "allowInsecure" to if (draft.allowInsecure) "1" else "",
        )
    }

    /** The v2rayN JSON form. */
    private fun vmessLink(draft: LocalNodeDraft): String {
        val kcp = draft.transport == LocalNodeTransport.KCP
        val json = JSONObject()
            .put("v", "2")
            .put("ps", draft.nodeName)
            .put("add", draft.address)
            .put("port", draft.port)
            .put("id", draft.userId)
            .put("aid", "0")
            .put("scy", draft.vmessSecurity)
            .put("net", draft.transport.wireValue)
            .put("type", if (kcp) draft.kcpHeaderType else "none")
            .put("host", draft.host)
            .put(
                "path",
                when (draft.transport) {
                    LocalNodeTransport.GRPC -> draft.serviceName
                    LocalNodeTransport.KCP -> draft.kcpSeed
                    else -> draft.path
                },
            )
            .put("tls", if (draft.security == LocalNodeSecurity.NONE) "" else draft.security.wireValue)
            .put("sni", draft.serverName)
            .put("alpn", draft.alpn)
            .put("fp", draft.fingerprint)
        return "vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** SIP002, for a plain Shadowsocks node (a transport or plugin can't be said in it). */
    private fun shadowsocksLink(draft: LocalNodeDraft): String? {
        if (draft.transport != LocalNodeTransport.TCP || draft.security != LocalNodeSecurity.NONE) return null
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${draft.shadowsocksMethod.wireValue}:${draft.password}".toByteArray(Charsets.UTF_8))
        return "ss://$userInfo@${hostPort(draft)}${name(draft)}"
    }

    private fun hostPort(draft: LocalNodeDraft): String {
        val host = draft.address.trim()
        return (if (':' in host && !host.startsWith("[")) "[$host]" else host) + ":" + draft.port.trim()
    }

    private fun name(draft: LocalNodeDraft): String = if (draft.nodeName.isBlank()) "" else "#" + enc(draft.nodeName)

    private fun query(pairs: List<Pair<String, String>>): String =
        pairs.filter { it.second.isNotBlank() }.joinToString("&") { (key, value) -> "$key=${enc(value)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** What the outbound connects with: protocol, settings and stream, keys in a fixed order. */
    private fun shape(outbound: JSONObject): String = JSONObject()
        .put("protocol", outbound.optString("protocol"))
        .put("settings", canonical(outbound.opt("settings")))
        .put("streamSettings", canonical(outbound.opt("streamSettings")))
        .toString()

    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().apply { value.keys().asSequence().sorted().forEach { put(it, canonical(value.opt(it))) } }
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(canonical(value.opt(i))) }
        else -> value
    }
}
