package com.mallocgfw.app.xray

import java.net.InetAddress

/**
 * A CIDR prefix with its host bits cleared. Plain Kotlin instead of
 * android.net.IpPrefix, whose public constructor needs API 33 (minSdk is 26).
 */
internal class SubnetPrefix private constructor(
    private val bytes: ByteArray,
    val prefixLength: Int,
) {
    val address: InetAddress = InetAddress.getByAddress(bytes)

    fun contains(other: InetAddress): Boolean {
        val candidate = other.address
        if (candidate.size != bytes.size) return false
        return masked(candidate, prefixLength).contentEquals(bytes)
    }

    fun isSameFamily(other: SubnetPrefix): Boolean = bytes.size == other.bytes.size

    override fun equals(other: Any?): Boolean =
        other is SubnetPrefix && prefixLength == other.prefixLength && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + prefixLength

    override fun toString(): String = "${address.hostAddress}/$prefixLength"

    companion object {
        fun of(address: InetAddress, prefixLength: Int): SubnetPrefix? {
            val raw = address.address
            if (prefixLength !in 0..(raw.size * 8)) return null
            return SubnetPrefix(masked(raw, prefixLength), prefixLength)
        }

        private fun masked(raw: ByteArray, prefixLength: Int): ByteArray {
            return ByteArray(raw.size) { index ->
                val bitsInByte = (prefixLength - index * 8).coerceIn(0, 8)
                val mask = (0xFF shl (8 - bitsInByte)) and 0xFF
                (raw[index].toInt() and mask).toByte()
            }
        }
    }
}

private val allowedPrivatePrefixes = listOf(
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "fc00::/7",
).mapNotNull(::parseTailscaleSubnetPrefix)

internal fun parseTailscaleSubnetPrefix(value: String): SubnetPrefix? {
    val trimmed = value.trim()
    val separator = trimmed.lastIndexOf('/')
    if (separator <= 0 || separator == trimmed.lastIndex) return null
    val addressText = trimmed.substring(0, separator)
    if ('.' !in addressText && ':' !in addressText) return null
    val prefixLength = trimmed.substring(separator + 1).toIntOrNull() ?: return null
    val address = runCatching { InetAddress.getByName(addressText) }.getOrNull() ?: return null
    return SubnetPrefix.of(address, prefixLength)
}

/** Returns a canonical private CIDR, or null when the input is unsafe/invalid. */
internal fun normalizePrivateTailscaleSubnet(value: String): String? {
    val prefix = parseTailscaleSubnetPrefix(value) ?: return null
    val allowed = allowedPrivatePrefixes.any { privatePrefix ->
        privatePrefix.isSameFamily(prefix) &&
            privatePrefix.contains(prefix.address) &&
            prefix.prefixLength >= privatePrefix.prefixLength
    }
    return prefix.toString().takeIf { allowed }
}
