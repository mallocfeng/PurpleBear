package com.mallocgfw.app.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/**
 * A deliberately tiny HTTP/1.1 server: one POST per connection, fixed limits,
 * no chunked bodies, no keep-alive. Enough for MCP clients and easy to audit.
 */
class McpHttpServer(
    private val handler: suspend (HttpRequest) -> HttpResponse,
    private val maxBodyBytes: Int = 256 * 1024,
    private val maxHeaderBytes: Int = 16 * 1024,
    private val readTimeoutMs: Int = 30_000,
    maxConcurrent: Int = 4,
) {
    /**
     * A listening address. [forwardSecret] marks the loopback port the
     * Tailscale bridge forwards tailnet connections to: each connection then
     * starts with one line naming the tailnet peer, signed with this secret.
     */
    data class Binding(val address: InetSocketAddress, val forwardSecret: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(maxConcurrent)
    private val sockets = mutableListOf<ServerSocket>()

    /** Ports actually bound, in [bindings] order (useful when binding port 0). */
    @Synchronized
    fun start(bindings: List<Binding>): List<Int> {
        val ports = bindings.map { binding ->
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(binding.address, 16)
            sockets += server
            scope.launch { acceptLoop(server, binding) }
            server.localPort
        }
        return ports
    }

    @Synchronized
    fun stop() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        scope.cancel()
    }

    private suspend fun acceptLoop(server: ServerSocket, binding: Binding) {
        while (scope.isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            scope.launch {
                socket.use { s ->
                    // Over the limit: refuse at once instead of queueing.
                    if (!permits.tryAcquire()) {
                        runCatching { write(s, HttpResponse(503, headers = mapOf("Retry-After" to "1"))) }
                        return@use
                    }
                    try {
                        serve(s, binding)
                    } finally {
                        permits.release()
                    }
                }
            }
        }
    }

    private suspend fun serve(socket: Socket, binding: Binding) {
        socket.soTimeout = readTimeoutMs
        val input = BufferedInputStream(socket.getInputStream())
        val response = try {
            var peer: TailnetPeer? = null
            var remote = socket.inetAddress?.hostAddress.orEmpty()
            if (binding.forwardSecret != null) {
                val forwarded = readForwardPreamble(input, binding.forwardSecret)
                    ?: return // not from the bridge: drop silently
                peer = forwarded.first
                remote = forwarded.second
            }
            val request = readRequest(input, remote, peer)
            if (request is HttpResponse) request else handler(request as HttpRequest)
        } catch (_: java.net.SocketTimeoutException) {
            HttpResponse(408)
        } catch (_: IOException) {
            return
        }
        runCatching { write(socket, response) }
    }

    /** Returns the request, or an error response for malformed / oversized input. */
    private fun readRequest(input: InputStream, remote: String, peer: TailnetPeer?): Any {
        val head = readHead(input) ?: return HttpResponse(431)
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(" ")
        if (requestLine.size < 3 || !requestLine[2].startsWith("HTTP/1.")) return HttpResponse(400)
        val headers = HashMap<String, String>()
        lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return HttpResponse(400)
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) return HttpResponse(411)
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0) return HttpResponse(400)
        if (length > maxBodyBytes) return HttpResponse(413)
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw IOException("body ended early")
            read += n
        }
        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = requestLine[1],
            headers = headers,
            body = String(body, Charsets.UTF_8),
            remoteAddress = remote,
            tailnetPeer = peer,
        )
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed")
            out.write(b)
            if (out.size() > maxHeaderBytes) return null
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
    }

    /** "PBTN1 <secret> <base64url json>\n" → (peer, tailnet address of the peer). */
    private fun readForwardPreamble(input: InputStream, secret: String): Pair<TailnetPeer, String>? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || line.size() > 4096) return null
            if (b == '\n'.code) break
            line.write(b)
        }
        val parts = line.toString(Charsets.UTF_8.name()).trim().split(" ")
        if (parts.size != 3 || parts[0] != "PBTN1") return null
        if (!java.security.MessageDigest.isEqual(parts[1].toByteArray(), secret.toByteArray())) return null
        val json = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)) }.getOrNull()
            ?: return null
        val peer = TailnetPeer(
            user = json.optString("user").ifBlank { "unknown" },
            device = json.optString("device").ifBlank { "unknown" },
        )
        return peer to json.optString("addr").ifBlank { "tailnet" }
    }

    private fun write(socket: Socket, response: HttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            response.headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        411 -> "Length Required"
        413 -> "Content Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> "Status"
    }
}
