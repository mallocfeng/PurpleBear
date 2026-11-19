package com.mallocgfw.app.xray

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceFetchClientTest {
    private val endpoint = LocalProxyEndpoint(port = 43_210, username = "user", password = "secret")

    @Test
    fun localProxyIsSelectedOnlyWhenItsInboundIsAvailable() {
        assertNull(ResourceFetchClient.localHttpProxyOrNull(endpoint, proxyAvailable = false, coreRunning = false))

        val proxy = ResourceFetchClient.localHttpProxyOrNull(endpoint, proxyAvailable = true, coreRunning = false)
        assertNotNull(proxy)
        assertEquals(Proxy.Type.HTTP, proxy?.type())
        val address = proxy?.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(endpoint.port, address.port)
    }

    @Test
    fun runningCoreFailsClosedThroughLocalProxyWhenProbeRaces() {
        val proxy = ResourceFetchClient.localHttpProxyOrNull(endpoint, proxyAvailable = false, coreRunning = true)

        assertNotNull(proxy)
        assertEquals(Proxy.Type.HTTP, proxy?.type())
    }

    @Test
    fun appRequestsUseTheAuthenticatedSocksInbound() {
        val proxy = ResourceFetchClient.localAppProxyOrNull(endpoint, proxyAvailable = true, coreRunning = false)

        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        assertEquals(endpoint.port, (proxy?.address() as InetSocketAddress).port)
        assertNull(ResourceFetchClient.localAppProxyOrNull(endpoint, proxyAvailable = false, coreRunning = false))
    }

    @Test
    fun noAllocatedProxyMeansDirectConnection() {
        assertNull(ResourceFetchClient.localHttpProxyOrNull(endpoint = null, proxyAvailable = true, coreRunning = true))
    }

    @Test
    fun transportSocketEstablishesHttpConnectTunnel() {
        withFakeHttpProxy("HTTP/1.1 200 Connection Established\r\n\r\n") { proxyAddress, request ->
            ResourceFetchClient.openTransportSocket(
                host = "subscription.example",
                port = 443,
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
                proxy = endpoint.copy(port = proxyAddress.port),
            ).use { socket ->
                assertTrue(socket.isConnected)
            }

            assertEquals("CONNECT subscription.example:443 HTTP/1.1", request.get()?.firstOrNull())
            assertTrue(request.get().orEmpty().contains("Host: subscription.example:443"))
            // Basic base64("user:secret")
            assertTrue(request.get().orEmpty().contains("Proxy-Authorization: Basic dXNlcjpzZWNyZXQ="))
        }
    }

    @Test
    fun transportSocketRejectsFailedHttpConnect() {
        withFakeHttpProxy("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n") { proxyAddress, _ ->
            val error = runCatching {
                ResourceFetchClient.openTransportSocket(
                    host = "subscription.example",
                    port = 443,
                    connectTimeoutMs = 2_000,
                    readTimeoutMs = 2_000,
                    proxy = endpoint.copy(port = proxyAddress.port),
                )
            }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error?.message.orEmpty().contains("HTTP 403"))
        }
    }

    private fun withFakeHttpProxy(
        response: String,
        block: (InetSocketAddress, AtomicReference<List<String>?>) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val request = AtomicReference<List<String>?>(null)
            val serverError = AtomicReference<Throwable?>(null)
            val worker = thread(name = "fake-http-proxy") {
                runCatching {
                    server.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                        val lines = buildList {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                add(line)
                            }
                        }
                        request.set(lines)
                        client.getOutputStream().apply {
                            write(response.toByteArray(StandardCharsets.ISO_8859_1))
                            flush()
                        }
                    }
                }.onFailure(serverError::set)
            }

            try {
                block(InetSocketAddress("127.0.0.1", server.localPort), request)
            } finally {
                worker.join(3_000)
                assertTrue("Fake proxy worker did not finish", !worker.isAlive)
                serverError.get()?.let { throw AssertionError("Fake proxy failed", it) }
            }
        }
    }
}
