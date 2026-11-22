package com.mallocgfw.app.model

/** Checks what the user types as a custom DNS server: an IP, optionally with a port. */
object DnsInput {
    data class Preset(val name: String, val address: String)

    val presets = listOf(
        Preset("阿里 DNS", "223.5.5.5"),
        Preset("腾讯 DNSPod", "119.29.29.29"),
        Preset("Google", "8.8.8.8"),
        Preset("Cloudflare", "1.1.1.1"),
        Preset("Quad9", "9.9.9.9"),
    )

    /**
     * The bare IP Android's VPN builder needs (it always uses port 53), or
     * null for anything that isn't a valid entry, e.g. an old DoH link.
     */
    fun hostOf(raw: String): String? {
        val value = raw.trim()
        if (!isValid(value)) return null
        return when {
            value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
            value.count { it == ':' } > 1 -> value
            else -> value.substringBefore(':')
        }
    }

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** 8.8.8.8, 8.8.8.8:53, 2001:4860:4860::8888, [2001:4860:4860::8888]:53 */
    fun isValid(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("[")) {
            val host = value.substringAfter('[').substringBefore(']')
            val rest = value.substringAfter(']', "")
            val portOk = rest.isEmpty() || (rest.startsWith(":") && isPort(rest.drop(1)))
            return isIpv6(host) && portOk
        }
        if (value.count { it == ':' } > 1) return isIpv6(value)
        val host = value.substringBefore(':')
        val port = value.substringAfter(':', "")
        return isIpv4(host) && (port.isEmpty() && !value.endsWith(":") || isPort(port))
    }

    private fun isIpv4(value: String): Boolean {
        val match = ipv4.matchEntire(value) ?: return false
        return match.groupValues.drop(1).all { part -> part.toInt() in 0..255 && (part == "0" || !part.startsWith("0")) }
    }

    private fun isIpv6(value: String): Boolean {
        if (value.isEmpty() || value.count { it == ':' } < 2) return false
        if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return false
        if (value.contains(":::") || value.split("::").size > 2) return false
        val groups = value.split(':').filter { it.isNotEmpty() }
        return groups.all { it.length <= 4 } && groups.size <= 8 && (value.contains("::") || groups.size == 8)
    }

    private fun isPort(value: String): Boolean = value.toIntOrNull()?.let { it in 1..65535 } == true
}
