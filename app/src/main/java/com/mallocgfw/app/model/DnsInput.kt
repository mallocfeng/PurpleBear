package com.mallocgfw.app.model

/**
 * Checks what the user types as a custom DNS server: a plain IP (port 53
 * only: Android's VPN DNS setting takes only an IP and always queries port
 * 53), or an encrypted server written as a URL:
 * - DoH: https://dns.google/dns-query
 * - DoT: tls://dns.google (port 853 unless given)
 * - DoQ: quic://dns.adguard-dns.com (port 853 unless given)
 */
object DnsInput {
    data class Preset(val name: String, val address: String)

    /** An encrypted DNS server. */
    data class EncryptedDns(val kind: Kind, val host: String, val port: Int, val path: String = "") {
        enum class Kind { DoH, DoT, DoQ }

        private val hostPort: String
            get() = (if (host.contains(':')) "[$host]" else host) + ":" + port

        /** The server as Xray's DNS module takes it. DoT's TLS is added by an outbound. */
        val xrayServer: String
            get() = when (kind) {
                Kind.DoH -> "https://$hostPort$path"
                Kind.DoT -> "tcp://$hostPort"
                Kind.DoQ -> "quic+local://$hostPort"
            }

        val label: String
            get() = when (kind) {
                Kind.DoH -> "DoH"
                Kind.DoT -> "DoT"
                Kind.DoQ -> "DoQ"
            }
    }

    val presets = listOf(
        Preset("阿里 DNS", "223.5.5.5"),
        Preset("腾讯 DNSPod", "119.29.29.29"),
        Preset("Google", "8.8.8.8"),
        Preset("Cloudflare", "1.1.1.1"),
        Preset("Quad9", "9.9.9.9"),
    )

    /** Well-known encrypted servers, shown under the address box. */
    val encryptedPresets = listOf(
        Preset("阿里 DoH", "https://dns.alidns.com/dns-query"),
        Preset("腾讯 DoH", "https://doh.pub/dns-query"),
        Preset("Google DoH", "https://dns.google/dns-query"),
        Preset("Cloudflare DoH", "https://cloudflare-dns.com/dns-query"),
        Preset("阿里 DoT", "tls://dns.alidns.com"),
        Preset("腾讯 DoT", "tls://dot.pub"),
        Preset("Google DoT", "tls://dns.google"),
        Preset("Cloudflare DoT", "tls://one.one.one.one"),
        Preset("阿里 DoQ", "quic://dns.alidns.com"),
        Preset("AdGuard DoQ", "quic://dns.adguard-dns.com"),
    )

    /**
     * Full-width characters a Chinese / Japanese input method types in place of
     * ASCII (。 ． ： ／ ０-９ …) become their ASCII forms, so "127。0。0。1" is
     * read, shown and saved as "127.0.0.1". Length is unchanged, so it can run
     * on every keystroke without moving the cursor.
     */
    fun toHalfWidth(raw: String): String {
        if (raw.all { it.code < 0x80 }) return raw
        return buildString(raw.length) {
            raw.forEach { c ->
                append(
                    when (c) {
                        '\u3002', '\uFF61' -> '.' // 。 ｡
                        '\u3000' -> ' '
                        in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()
                        else -> c
                    },
                )
            }
        }
    }

    /** The value as it is checked and saved: ASCII, without surrounding spaces. */
    fun normalize(raw: String): String = toHalfWidth(raw).trim()

    private val hostLabel = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$""")

    /** The encrypted server in [raw], or null when it isn't one. */
    fun encryptedOf(raw: String): EncryptedDns? {
        val value = normalize(raw)
        val scheme = value.substringBefore("://", "").lowercase()
        val kind = when (scheme) {
            "https" -> EncryptedDns.Kind.DoH
            "tls" -> EncryptedDns.Kind.DoT
            "quic" -> EncryptedDns.Kind.DoQ
            else -> return null
        }
        val rest = value.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.drop(authority.length)
        if (kind != EncryptedDns.Kind.DoH && path.isNotEmpty() && path != "/") return null
        if (path.any { it.isWhitespace() || it == '#' }) return null
        val (host, port) = splitHostPort(authority) ?: return null
        val defaultPort = if (kind == EncryptedDns.Kind.DoH) 443 else 853
        val server = EncryptedDns(
            kind = kind,
            host = host,
            port = port ?: defaultPort,
            path = if (kind == EncryptedDns.Kind.DoH) path.ifEmpty { "/dns-query" } else "",
        )
        // The DoH URL goes to Xray as it is, and Go's url.Parse refuses the
        // whole config over a bad escape ("…/dns-query%"), so the core wouldn't
        // start. An old saved value that fails here falls back to the system DNS.
        if (kind == EncryptedDns.Kind.DoH && !isValidDohUrl(server.xrayServer)) return null
        return server
    }

    private val percentEscape = Regex("%(?![0-9A-Fa-f]{2})")

    private fun isValidDohUrl(url: String): Boolean {
        if (percentEscape.containsMatchIn(url)) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && !uri.rawAuthority.isNullOrEmpty() && uri.rawFragment == null
    }

    /** Host (domain or IP literal) and optional port of an encrypted server. */
    private fun splitHostPort(authority: String): Pair<String, Int?>? {
        if (authority.isEmpty() || authority.contains('@')) return null
        if (authority.startsWith("[")) {
            val host = authority.substringAfter('[').substringBefore(']', missingDelimiterValue = "")
            if (!isIpv6(host)) return null
            val rest = authority.substringAfter(']', "")
            if (rest.isEmpty()) return host to null
            if (!rest.startsWith(":")) return null
            return portOf(rest.drop(1))?.let { host to it }
        }
        if (authority.count { it == ':' } > 1) return null
        val host = authority.substringBefore(':').lowercase()
        val port = if (authority.contains(':')) portOf(authority.substringAfter(':')) ?: return null else null
        val isDomain = host.length <= 253 && host.contains('.') &&
            host.split('.').all { hostLabel.matches(it) } && !host.split('.').last().all(Char::isDigit)
        if (!isIpv4(host) && !isDomain) return null
        return host to port
    }

    private const val DNS_PORT = 53

    /**
     * The bare IP Android's VPN builder needs, or null for anything that isn't
     * an IP (an old DoH link, say) or that Android refuses. A saved value from
     * older versions with another port still yields its IP: that port was never
     * used anyway. Null makes the VPN fall back to the system DNS.
     */
    fun hostOf(raw: String): String? = parse(normalize(raw))?.first?.takeIf(::isAcceptedByAndroid)

    /** A plain IP (port 53 only) that Android accepts, or an encrypted server URL. */
    fun isValid(raw: String): Boolean {
        if (encryptedOf(raw) != null) return true
        val (host, port) = parse(normalize(raw)) ?: return false
        return (port == null || port == DNS_PORT) && isAcceptedByAndroid(host)
    }

    /** A well-formed IP that Android still refuses as a DNS server (loopback / unspecified). */
    fun isRefusedByAndroid(raw: String): Boolean {
        val (host, _) = parse(normalize(raw)) ?: return false
        return !isAcceptedByAndroid(host)
    }

    /**
     * VpnService.Builder.addDnsServer throws for loopback (127.0.0.0/8, ::1)
     * and unspecified (0.0.0.0, ::) addresses, IPv4-mapped forms included, and
     * the tunnel would fail to start. [host] is an already checked IP literal,
     * so this does no lookup.
     */
    private fun isAcceptedByAndroid(host: String): Boolean {
        val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
        return !address.isLoopbackAddress && !address.isAnyLocalAddress
    }

    /** Host and optional port, or null when the host isn't a valid IP literal. */
    private fun parse(value: String): Pair<String, Int?>? {
        if (value.isEmpty()) return null
        if (value.startsWith("[")) {
            val host = value.substringAfter('[').substringBefore(']', missingDelimiterValue = "")
            val rest = value.substringAfter(']', "")
            if (!isIpv6(host)) return null
            if (rest.isEmpty()) return host to null
            if (!rest.startsWith(":")) return null
            return portOf(rest.drop(1))?.let { host to it }
        }
        if (value.count { it == ':' } > 1) return value.takeIf(::isIpv6)?.let { it to null }
        val host = value.substringBefore(':')
        if (!isIpv4(host)) return null
        if (!value.contains(':')) return host to null
        return portOf(value.substringAfter(':'))?.let { host to it }
    }

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    private fun isIpv4(value: String): Boolean {
        val match = ipv4.matchEntire(value) ?: return false
        return match.groupValues.drop(1).all { part -> part.toInt() in 0..255 && (part == "0" || !part.startsWith("0")) }
    }

    /** Strict hex-and-colon IPv6 (no zone, no embedded IPv4). */
    private fun isIpv6(value: String): Boolean {
        if (value.count { it == ':' } < 2) return false
        if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return false
        val halves = value.split("::")
        if (halves.size > 2) return false
        fun groups(part: String): List<String>? {
            if (part.isEmpty()) return emptyList()
            val items = part.split(':')
            // A lone colon at either end (":1::2", "1::2:") leaves an empty group.
            return items.takeIf { list -> list.all { it.length in 1..4 } }
        }
        return if (halves.size == 2) {
            val head = groups(halves[0]) ?: return false
            val tail = groups(halves[1]) ?: return false
            // "::" stands for at least one zero group.
            head.size + tail.size <= 7
        } else {
            groups(value)?.size == 8
        }
    }

    private fun portOf(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..65535 }
}
