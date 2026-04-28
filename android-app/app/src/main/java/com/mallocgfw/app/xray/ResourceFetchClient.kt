package com.mallocgfw.app.xray

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object ResourceFetchClient {
    enum class RequestProfile {
        DEFAULT,
        SHADOWROCKET_SUBSCRIPTION,
    }

    private const val USER_AGENT = "MallocGFW/0.1"
    private const val SHADOWROCKET_USER_AGENT = "Shadowrocket/2.2.62 (iPhone; iOS 17.0; Scale/3.00)"
    private const val MAX_REDIRECTS = 5
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_LINE_BYTES = 8 * 1024
    // Subscription / rule payloads are tiny in practice (kB-MB). Cap below the
    // old 64MB so a hostile or misbehaving server can't pin tens of megabytes
    // of `ByteArrayOutputStream` in memory before we notice. Geo .dat files
    // are pulled with the same client and currently top out around 22MB, so
    // 32MB leaves comfortable headroom while still bounding the worst case.
    private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    private val HEADER_SEPARATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

    fun fetchText(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        label: String,
        requestProfile: RequestProfile = RequestProfile.DEFAULT,
    ): String {
        val response = fetchResponse(url, connectTimeoutMs, readTimeoutMs, requestProfile)
        if (response.code !in 200..299) {
            error("$label 返回 HTTP ${response.code}。")
        }
        return response.body.toString(Charsets.UTF_8).also {
            if (it.isBlank()) error("$label 返回为空。")
        }
    }

    fun fetchBytes(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        label: String,
        requestProfile: RequestProfile = RequestProfile.DEFAULT,
    ): ByteArray {
        val response = fetchResponse(url, connectTimeoutMs, readTimeoutMs, requestProfile)
        if (response.code !in 200..299) {
            error("$label 返回 HTTP ${response.code}。")
        }
        return response.body.also {
            if (it.isEmpty()) error("$label 返回为空。")
        }
    }

    private data class HttpResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun fetchResponse(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestProfile: RequestProfile,
        redirectCount: Int = 0,
    ): HttpResponse {
        require(redirectCount <= MAX_REDIRECTS) { "订阅链接重定向次数过多。" }
        return when (requestProfile) {
            RequestProfile.DEFAULT -> fetchResponseWithConnection(url, connectTimeoutMs, readTimeoutMs, requestProfile)
            RequestProfile.SHADOWROCKET_SUBSCRIPTION -> fetchResponseWithShadowrocketHttp1(url, connectTimeoutMs, readTimeoutMs, redirectCount)
        }
    }

    private fun fetchResponseWithConnection(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestProfile: RequestProfile,
    ): HttpResponse {
        val connection = openConnection(url, connectTimeoutMs, readTimeoutMs, requestProfile)
        return try {
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readBytesLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            HttpResponse(
                code = code,
                headers = connection.headerFields
                    .mapNotNull { (key, values) ->
                        key?.lowercase()?.let { it to values?.firstOrNull().orEmpty() }
                    }
                    .toMap(),
                body = body,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchResponseWithShadowrocketHttp1(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        redirectCount: Int,
    ): HttpResponse {
        val target = URL(url)
        if (!target.protocol.equals("https", ignoreCase = true)) {
            return fetchResponseWithConnection(url, connectTimeoutMs, readTimeoutMs, RequestProfile.SHADOWROCKET_SUBSCRIPTION)
        }
        val port = if (target.port > 0) target.port else 443
        val socket = openTlsSocket(target.host, port, connectTimeoutMs, readTimeoutMs)
        socket.use { sslSocket ->
            val requestTarget = buildString {
                append(target.path.ifBlank { "/" })
                target.query?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
            }
            val request = buildString {
                append("GET ").append(requestTarget).append(" HTTP/1.1\r\n")
                append("Host: ").append(target.host).append("\r\n")
                append("User-Agent: ").append(SHADOWROCKET_USER_AGENT).append("\r\n")
                append("Accept: */*\r\n")
                append("Accept-Language: zh-CN,zh-Hans;q=0.9\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            sslSocket.outputStream.write(request.toByteArray(StandardCharsets.UTF_8))
            sslSocket.outputStream.flush()

            val rawResponse = readHttpResponse(sslSocket.inputStream)
            val statusCode = rawResponse.statusLine.substringAfter(' ', "")
                .substringBefore(' ')
                .toIntOrNull()
                ?: error("订阅链接返回了无法识别的状态行：${rawResponse.statusLine}")

            if (statusCode in 300..399) {
                val location = rawResponse.headers["location"].orEmpty()
                require(location.isNotBlank()) { "订阅链接返回重定向，但没有 location。" }
                return fetchResponseWithShadowrocketHttp1(
                    url = URL(target, location).toString(),
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    redirectCount = redirectCount + 1,
                )
            }

            return HttpResponse(
                code = statusCode,
                headers = rawResponse.headers,
                body = maybeDecompressBody(rawResponse.headers, rawResponse.body),
            )
        }
    }

    /**
     * The hand-rolled HTTP/1.1 path doesn't go through HttpURLConnection's
     * automatic content-decoding, so if the server compressed the response
     * (which some subscription endpoints do regardless of Accept-Encoding) we
     * have to inflate it ourselves. The decompression result is also bounded
     * by [MAX_RESPONSE_BYTES] to defang gzip bombs.
     */
    private fun maybeDecompressBody(headers: Map<String, String>, body: ByteArray): ByteArray {
        val encoding = headers["content-encoding"]?.lowercase()?.trim().orEmpty()
        return when (encoding) {
            "", "identity" -> body
            "gzip", "x-gzip" -> ByteArrayInputStream(body).use { src ->
                GZIPInputStream(src).use { decoded -> readBytesLimited(decoded, MAX_RESPONSE_BYTES) }
            }
            "deflate" -> ByteArrayInputStream(body).use { src ->
                InflaterInputStream(src).use { decoded -> readBytesLimited(decoded, MAX_RESPONSE_BYTES) }
            }
            else -> body
        }
    }

    private data class RawHttpResponse(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun readHttpResponse(input: InputStream): RawHttpResponse {
        val headerBytes = readUntilHeaderSeparator(input)
        val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
        val headerLines = headerText.split("\r\n")
        val statusLine = headerLines.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: error("订阅链接未返回状态行。")
        val headers = headerLines.drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else {
                    line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
                }
            }
            .toMap()
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> {
                readChunkedBody(input)
            }
            headers["content-length"] != null -> {
                readFixedLengthBody(input, headers["content-length"]!!.toInt())
            }
            else -> readBytesLimited(input, MAX_RESPONSE_BYTES)
        }
        return RawHttpResponse(statusLine = statusLine, headers = headers, body = body)
    }

    private fun openTlsSocket(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): SSLSocket {
        val plainSocket = Socket()
        return try {
            plainSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plainSocket, host, port, true) as SSLSocket
            try {
                sslSocket.soTimeout = readTimeoutMs
                sslSocket.useClientMode = true
                sslSocket.startHandshake()
                sslSocket
            } catch (error: Throwable) {
                sslSocket.close()
                throw error
            }
        } catch (error: Throwable) {
            runCatching { plainSocket.close() }
            throw error
        }
    }

    private fun readUntilHeaderSeparator(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        var matchIndex = 0
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException("订阅链接响应头意外结束。")
            val byte = next.toByte()
            buffer.write(byte.toInt())
            if (buffer.size() > MAX_HEADER_BYTES) {
                error("订阅链接响应头过大。")
            }
            if (byte == HEADER_SEPARATOR[matchIndex]) {
                matchIndex += 1
                if (matchIndex == HEADER_SEPARATOR.size) {
                    return buffer.toByteArray().dropLast(HEADER_SEPARATOR.size).toByteArray()
                }
            } else {
                matchIndex = if (byte == HEADER_SEPARATOR[0]) 1 else 0
            }
        }
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input).substringBefore(';').trim()
            val size = sizeLine.toInt(16)
            if (size == 0) {
                readAsciiLine(input)
                return output.toByteArray()
            }
            if (output.size() + size > MAX_RESPONSE_BYTES) {
                error("订阅链接响应体过大。")
            }
            output.write(readFixedLengthBody(input, size))
            readAsciiLine(input)
        }
    }

    private fun readFixedLengthBody(input: InputStream, length: Int): ByteArray {
        if (length > MAX_RESPONSE_BYTES) {
            error("订阅链接响应体过大。")
        }
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) throw EOFException("订阅链接响应体长度不足。")
            offset += count
        }
        return buffer
    }

    private fun readAsciiLine(input: InputStream): String {
        val output = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException("订阅链接响应行意外结束。")
            if (output.size() > MAX_LINE_BYTES) {
                error("订阅链接响应行过长。")
            }
            if (next == '\r'.code) {
                val lineFeed = input.read()
                if (lineFeed == '\n'.code) {
                    return output.toString(StandardCharsets.ISO_8859_1.name())
                }
                output.write(next)
                if (lineFeed >= 0) output.write(lineFeed)
            } else {
                output.write(next)
            }
        }
    }

    private fun readBytesLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > maxBytes) {
                error("订阅链接响应体过大。")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun openConnection(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestProfile: RequestProfile,
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            when (requestProfile) {
                RequestProfile.DEFAULT -> {
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                RequestProfile.SHADOWROCKET_SUBSCRIPTION -> {
                    setRequestProperty("User-Agent", SHADOWROCKET_USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                    setRequestProperty("Connection", "close")
                    if (this is HttpsURLConnection) {
                        instanceFollowRedirects = false
                    }
                }
            }
            // Intentionally do not protect these sockets. When the app VPN is active,
            // resource refreshes should stay inside the current proxy/VPN path.
        }
    }
}
