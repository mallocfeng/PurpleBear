package com.mallocgfw.app.xray

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import com.mallocgfw.app.model.tr

object ResourceFetchClient {
    enum class RequestProfile {
        DEFAULT,
        SHADOWROCKET_SUBSCRIPTION,
        /** Panels (Xboard, Marzban, Remnawave…) answer this with a full Clash Meta config. */
        CLASH_META_SUBSCRIPTION,
    }

    private const val USER_AGENT = "MallocGFW/0.1"
    private const val CLASH_META_USER_AGENT = "clash.meta/1.19.34 (mihomo; PurpleBear)"
    private const val SHADOWROCKET_USER_AGENT = "Shadowrocket/2.2.62 (iPhone; iOS 17.0; Scale/3.00)"
    private const val MAX_REDIRECTS = 5
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_LINE_BYTES = 8 * 1024
    private const val LOCAL_PROXY_PROBE_TIMEOUT_MS = 500
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
            error(tr("$label 返回 HTTP ${response.code}。", "$label returned HTTP ${response.code}.", "$label: ответ HTTP ${response.code}."))
        }
        return response.body.toString(Charsets.UTF_8).also {
            if (it.isBlank()) error(tr("$label 返回为空。", "$label returned nothing.", "$label: пустой ответ."))
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
            error(tr("$label 返回 HTTP ${response.code}。", "$label returned HTTP ${response.code}.", "$label: ответ HTTP ${response.code}."))
        }
        return response.body.also {
            if (it.isEmpty()) error(tr("$label 返回为空。", "$label returned nothing.", "$label: пустой ответ."))
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
        require(redirectCount <= MAX_REDIRECTS) { tr("订阅链接重定向次数过多。", "The subscription link redirected too many times.", "Слишком много перенаправлений ссылки подписки.") }
        return when (requestProfile) {
            RequestProfile.DEFAULT,
            RequestProfile.CLASH_META_SUBSCRIPTION,
            -> fetchResponseWithConnection(url, connectTimeoutMs, readTimeoutMs, requestProfile)
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
                ?: error(tr("订阅链接返回了无法识别的状态行：${rawResponse.statusLine}", "The subscription link returned an unrecognized status line: ${rawResponse.statusLine}", "Ссылка подписки вернула нераспознанную строку состояния: ${rawResponse.statusLine}"))

            if (statusCode in 300..399) {
                val location = rawResponse.headers["location"].orEmpty()
                require(location.isNotBlank()) { tr("订阅链接返回重定向，但没有 location。", "The subscription link returned a redirect without a location.", "Ссылка подписки вернула перенаправление без location.") }
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
            ?: error(tr("订阅链接未返回状态行。", "The subscription link returned no status line.", "Ссылка подписки не вернула строку состояния."))
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
        val plainSocket = openTransportSocket(
            host = host,
            port = port,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            proxy = localHttpProxyEndpointOrNull(),
        )
        return try {
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

    internal fun openTransportSocket(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        proxy: LocalProxyEndpoint?,
    ): Socket {
        val socket = Socket()
        return try {
            socket.soTimeout = readTimeoutMs
            socket.connect(proxy?.socketAddress() ?: InetSocketAddress(host, port), connectTimeoutMs)
            if (proxy != null) {
                establishHttpConnectTunnel(socket, host, port, proxy)
            }
            socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun establishHttpConnectTunnel(socket: Socket, host: String, port: Int, proxy: LocalProxyEndpoint) {
        val authority = if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"
        val request = buildString {
            append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            append("Host: ").append(authority).append("\r\n")
            // Only sent to our own loopback proxy inside CONNECT, never to the origin.
            append("Proxy-Authorization: ").append(proxy.basicProxyAuthorization()).append("\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            append("\r\n")
        }
        socket.outputStream.write(request.toByteArray(StandardCharsets.ISO_8859_1))
        socket.outputStream.flush()

        val responseHeader = String(readUntilHeaderSeparator(socket.inputStream), StandardCharsets.ISO_8859_1)
        val statusLine = responseHeader.lineSequence().firstOrNull().orEmpty()
        val statusCode = statusLine.substringAfter(' ', "")
            .substringBefore(' ')
            .toIntOrNull()
            ?: error(tr("本地 HTTP 代理返回了无法识别的 CONNECT 响应：$statusLine", "The local HTTP proxy returned an unrecognized CONNECT response: $statusLine", "Локальный HTTP-прокси вернул нераспознанный ответ CONNECT: $statusLine"))
        if (statusCode !in 200..299) {
            error(tr("本地 HTTP 代理 CONNECT 返回 HTTP $statusCode。", "The local HTTP proxy CONNECT returned HTTP $statusCode.", "CONNECT через локальный HTTP-прокси вернул HTTP $statusCode."))
        }
    }

    private fun readUntilHeaderSeparator(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        var matchIndex = 0
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException(tr("订阅链接响应头意外结束。", "The subscription response headers ended unexpectedly.", "Заголовки ответа подписки неожиданно оборвались."))
            val byte = next.toByte()
            buffer.write(byte.toInt())
            if (buffer.size() > MAX_HEADER_BYTES) {
                error(tr("订阅链接响应头过大。", "The subscription response headers are too large.", "Заголовки ответа подписки слишком велики."))
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
                error(tr("订阅链接响应体过大。", "The subscription response is too large.", "Ответ подписки слишком велик."))
            }
            output.write(readFixedLengthBody(input, size))
            readAsciiLine(input)
        }
    }

    private fun readFixedLengthBody(input: InputStream, length: Int): ByteArray {
        if (length > MAX_RESPONSE_BYTES) {
            error(tr("订阅链接响应体过大。", "The subscription response is too large.", "Ответ подписки слишком велик."))
        }
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) throw EOFException(tr("订阅链接响应体长度不足。", "The subscription response is shorter than expected.", "Ответ подписки короче ожидаемого."))
            offset += count
        }
        return buffer
    }

    private fun readAsciiLine(input: InputStream): String {
        val output = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException(tr("订阅链接响应行意外结束。", "A subscription response line ended unexpectedly.", "Строка ответа подписки неожиданно оборвалась."))
            if (output.size() > MAX_LINE_BYTES) {
                error(tr("订阅链接响应行过长。", "A subscription response line is too long.", "Строка ответа подписки слишком длинная."))
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
                error(tr("订阅链接响应体过大。", "The subscription response is too large.", "Ответ подписки слишком велик."))
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
        val target = URL(url)
        val localProxy = localAppProxyOrNull()
        val connection = if (localProxy != null) {
            target.openConnection(localProxy)
        } else {
            target.openConnection()
        }
        return (connection as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            when (requestProfile) {
                RequestProfile.DEFAULT -> {
                    setRequestProperty("User-Agent", USER_AGENT)
                    // Without this some stacks send "text/html, …" and panels such as
                    // 3x-ui answer with their browser page instead of the node list.
                    setRequestProperty("Accept", "*/*")
                }
                RequestProfile.CLASH_META_SUBSCRIPTION -> {
                    setRequestProperty("User-Agent", CLASH_META_USER_AGENT)
                    setRequestProperty("Accept", "*/*")
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
            // PurpleBear is excluded from its own TUN in smart/global modes to avoid
            // routing loops. When Xray's local HTTP inbound is alive, the explicit
            // proxy above keeps subscription/rule/Geo traffic on the selected exit.
        }
    }

    internal fun isLocalHttpProxyAvailable(
        endpoint: LocalProxyEndpoint? = LocalProxyRuntime.current()?.http,
    ): Boolean = isListening(endpoint)

    private fun isListening(endpoint: LocalProxyEndpoint?): Boolean {
        endpoint ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(endpoint.socketAddress(), LOCAL_PROXY_PROBE_TIMEOUT_MS)
            }
        }.isSuccess
    }

    /**
     * Proxy for app-level HttpURLConnection requests: the run's authenticated
     * SOCKS inbound (see LocalProxyRuntime for why not the HTTP inbound).
     * Hostnames stay unresolved, so DNS still happens on the proxy side.
     */
    internal fun localAppProxyOrNull(
        endpoint: LocalProxyEndpoint? = LocalProxyRuntime.current()?.socks,
        proxyAvailable: Boolean = isListening(endpoint),
        coreRunning: Boolean = XrayCoreManager.snapshot.value.status == XrayCoreStatus.Running,
    ): Proxy? {
        return selectLocalEndpoint(endpoint, proxyAvailable, coreRunning)?.socksProxy()
    }

    internal fun localHttpProxyOrNull(
        endpoint: LocalProxyEndpoint? = LocalProxyRuntime.current()?.http,
        proxyAvailable: Boolean = isLocalHttpProxyAvailable(endpoint),
        coreRunning: Boolean = XrayCoreManager.snapshot.value.status == XrayCoreStatus.Running,
    ): Proxy? {
        return selectLocalEndpoint(endpoint, proxyAvailable, coreRunning)?.httpProxy()
    }

    /** HTTP inbound for the hand-rolled CONNECT path, which sends auth up front. */
    private fun localHttpProxyEndpointOrNull(): LocalProxyEndpoint? {
        val endpoint = LocalProxyRuntime.current()?.http
        return selectLocalEndpoint(
            endpoint = endpoint,
            proxyAvailable = isListening(endpoint),
            coreRunning = XrayCoreManager.snapshot.value.status == XrayCoreStatus.Running,
        )
    }

    private fun selectLocalEndpoint(
        endpoint: LocalProxyEndpoint?,
        proxyAvailable: Boolean,
        coreRunning: Boolean,
    ): LocalProxyEndpoint? {
        endpoint ?: return null
        // Once the core is marked Running, never silently fall back to a direct
        // physical-network request if the probe races with a proxy shutdown.
        // Using the explicit proxy will fail closed instead of leaking the request.
        return endpoint.takeIf { proxyAvailable || coreRunning }
    }
}
