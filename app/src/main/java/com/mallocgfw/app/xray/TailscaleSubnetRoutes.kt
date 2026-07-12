package com.mallocgfw.app.xray

import android.net.IpPrefix
import java.net.InetAddress

private val allowedPrivatePrefixes = listOf(
    parseTailscaleSubnetPrefix("10.0.0.0/8")!!,
    parseTailscaleSubnetPrefix("172.16.0.0/12")!!,
    parseTailscaleSubnetPrefix("192.168.0.0/16")!!,
    parseTailscaleSubnetPrefix("fc00::/7")!!,
)

internal fun parseTailscaleSubnetPrefix(value: String): IpPrefix? {
    val trimmed = value.trim()
    val separator = trimmed.lastIndexOf('/')
    if (separator <= 0 || separator == trimmed.lastIndex) return null
    val addressText = trimmed.substring(0, separator)
    if ('.' !in addressText && ':' !in addressText) return null
    val prefixLength = trimmed.substring(separator + 1).toIntOrNull() ?: return null
    val address = runCatching { InetAddress.getByName(addressText) }.getOrNull() ?: return null
    if (prefixLength !in 0..(address.address.size * 8)) return null
    return runCatching { IpPrefix(address, prefixLength) }.getOrNull()
}

/** Returns a canonical private CIDR, or null when the input is unsafe/invalid. */
internal fun normalizePrivateTailscaleSubnet(value: String): String? {
    val prefix = parseTailscaleSubnetPrefix(value) ?: return null
    val allowed = allowedPrivatePrefixes.any { privatePrefix ->
        privatePrefix.address.javaClass == prefix.address.javaClass &&
            privatePrefix.contains(prefix.address) &&
            prefix.prefixLength >= privatePrefix.prefixLength
    }
    return prefix.toString().takeIf { allowed }
}
