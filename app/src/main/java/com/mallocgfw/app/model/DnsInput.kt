package com.mallocgfw.app.model

/**
 * Checks what the user types as a custom DNS server: an IP, optionally with
 * port 53. Android's VPN DNS setting takes only an IP and always queries
 * port 53, so other ports are refused instead of being silently ignored.
 */
object DnsInput {
    data class Preset(val name: String, val address: String)

    val presets = listOf(
        Preset("阿里 DNS", "223.5.5.5"),
        Preset("腾讯 DNSPod", "119.29.29.29"),
        Preset("Google", "8.8.8.8"),
        Preset("Cloudflare", "1.1.1.1"),
        Preset("Quad9", "9.9.9.9"),
    )

    private const val DNS_PORT = 53

    /**
     * The bare IP Android's VPN builder needs, or null for anything that isn't
     * an IP (an old DoH link, say). A saved value from older versions with
     * another port still yields its IP: that port was never used anyway.
     */
    fun hostOf(raw: String): String? = parse(raw.trim())?.first

    /** 8.8.8.8, 8.8.8.8:53, 2001:4860:4860::8888, [2001:4860:4860::8888]:53 */
    fun isValid(raw: String): Boolean {
        val (_, port) = parse(raw.trim()) ?: return false
        return port == null || port == DNS_PORT
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
