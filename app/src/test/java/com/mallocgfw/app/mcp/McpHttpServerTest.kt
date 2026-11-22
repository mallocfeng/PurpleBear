package com.mallocgfw.app.mcp

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

class McpHttpServerTest {
    private val seen = mutableListOf<HttpRequest>()
    private val server = McpHttpServer(handler = { request ->
        synchronized(seen) { seen += request }
        HttpResponse.json(200, JSONObject().put("echo", request.body))
    }, maxBodyBytes = 1024)

    @After
    fun tearDown() = server.stop()

    private fun exchange(port: Int, raw: ByteArray): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(raw)
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun request(body: String, extra: String = "") =
        ("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n$extra" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()

    @Test
    fun servesOneRequestPerConnection() {
        val port = server.start(listOf(McpHttpServer.Binding(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)))).single()
        val response = exchange(port, request("""{"a":1}""", "Authorization: Bearer t\r\n"))
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("Connection: close"))
        assertTrue(response.contains("""{\"a\":1}"""))
        assertEquals("Bearer t", seen.single().header("Authorization"))
    }

    @Test
    fun oversizedBodiesGet413WithoutReachingTheHandler() {
        val port = server.start(listOf(McpHttpServer.Binding(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)))).single()
        val response = exchange(port, "POST /mcp HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray())
        assertTrue(response.startsWith("HTTP/1.1 413"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun forwardedConnectionsNeedTheBridgeSecret() {
        val secret = "0123456789abcdef0123"
        val port = server.start(listOf(McpHttpServer.Binding(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), forwardSecret = secret))).single()
        val peer = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"user":"me@example.com","device":"my-mac","addr":"100.64.0.2"}""".toByteArray())
        val ok = exchange(port, "PBTN1 $secret $peer\n".toByteArray() + request("{}"))
        assertTrue(ok.startsWith("HTTP/1.1 200"))
        val request = seen.single()
        assertEquals("100.64.0.2", request.remoteAddress)
        assertEquals(TailnetPeer("me@example.com", "my-mac"), request.tailnetPeer)

        val forged = exchange(port, "PBTN1 wrong-secret-xxxxxxxx $peer\n".toByteArray() + request("{}"))
        assertEquals("", forged)
        val plain = exchange(port, request("{}"))
        assertEquals("", plain)
        assertEquals(1, seen.size)
    }
}
