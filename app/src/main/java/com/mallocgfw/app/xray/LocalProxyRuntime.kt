package com.mallocgfw.app.xray

import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/** One loopback proxy listener and the credentials required to use it. */
data class LocalProxyEndpoint(
    val port: Int,
    val username: String,
    val password: String,
) {
    val host: String get() = LocalProxyRuntime.LOOPBACK_HOST

    fun socketAddress(): InetSocketAddress = InetSocketAddress(host, port)

    fun httpProxy(): Proxy = Proxy(Proxy.Type.HTTP, socketAddress())

    fun socksProxy(): Proxy = Proxy(Proxy.Type.SOCKS, socketAddress())

    fun basicProxyAuthorization(): String {
        val token = "$username:$password".toByteArray(StandardCharsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(token)
    }

    // Never print the password, e.g. when a data class lands in a log line.
    override fun toString(): String = "LocalProxyEndpoint(port=$port, username=$username)"
}

/**
 * Ports and credentials for every loopback listener of one core run: Xray's
 * SOCKS and HTTP inbounds, and the mihomo sidecar used for SSR nodes.
 */
data class LocalProxyAllocation(
    val socks: LocalProxyEndpoint,
    val http: LocalProxyEndpoint,
    val ssr: LocalProxyEndpoint,
) {
    companion object {
        /** A throwaway allocation for config previews and tests; never registered. */
        fun ephemeral(): LocalProxyAllocation = LocalProxyRuntime.newAllocation(LocalProxyRuntime::pickFreePorts)
    }
}

/**
 * Loopback listeners used to sit on fixed, unauthenticated ports
 * (10808 / 10809 / 17891), so any app on the device could use them to bypass
 * per-app routing or to detect the proxy and learn its exit IP. Every core
 * start now gets fresh random ports and random credentials, which only this
 * process knows.
 */
object LocalProxyRuntime {
    const val LOOPBACK_HOST = "127.0.0.1"

    // Well-known defaults of PurpleBear and other clients; never reuse them.
    private val RESERVED_PORTS = listOf(1080, 7890, 7891, 10808, 10809, 17891)
    private const val SECRET_BYTES = 24
    private val random = SecureRandom()

    @Volatile
    private var current: LocalProxyAllocation? = null

    fun current(): LocalProxyAllocation? = current

    /** Picks new ports and credentials and makes them the active allocation. */
    fun allocate(portPicker: (count: Int, excluded: List<Int>) -> List<Int> = ::pickFreePorts): LocalProxyAllocation {
        installAuthenticator()
        return newAllocation(portPicker).also { current = it }
    }

    fun clear() {
        current = null
    }

    internal fun newAllocation(portPicker: (count: Int, excluded: List<Int>) -> List<Int>): LocalProxyAllocation {
        val ports = portPicker(3, RESERVED_PORTS)
        require(ports.size == 3 && ports.distinct().size == 3 && ports.all { it in 1..65535 }) {
            "无法分配本地代理端口。"
        }
        return LocalProxyAllocation(
            socks = LocalProxyEndpoint(ports[0], randomSecret(8), randomSecret(SECRET_BYTES)),
            http = LocalProxyEndpoint(ports[1], randomSecret(8), randomSecret(SECRET_BYTES)),
            ssr = LocalProxyEndpoint(ports[2], randomSecret(8), randomSecret(SECRET_BYTES)),
        )
    }

    /** Pure-JVM fallback; production prefers libXray's getFreePorts. */
    internal fun pickFreePorts(count: Int, excluded: List<Int>): List<Int> {
        val sockets = mutableListOf<ServerSocket>()
        try {
            val ports = mutableListOf<Int>()
            while (ports.size < count) {
                val socket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
                sockets += socket
                val port = socket.localPort
                if (port !in excluded && port !in ports) ports += port
            }
            return ports
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private fun randomSecret(bytes: Int): String {
        val buffer = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    @Volatile
    private var authenticatorInstalled = false

    /**
     * The JDK SOCKS client (used by HttpURLConnection with a SOCKS proxy) asks
     * the default Authenticator for RFC 1929 credentials. Answer only for our
     * own loopback listeners so credentials never reach any other host.
     *
     * HttpURLConnection is deliberately not pointed at the HTTP inbound: Xray
     * closes the connection after its 407 challenge and Android's OkHttp 2
     * retries CONNECT on that closed socket, so HTTP proxy auth never completes.
     */
    private fun installAuthenticator() {
        if (authenticatorInstalled) return
        synchronized(this) {
            if (authenticatorInstalled) return
            Authenticator.setDefault(LocalProxyAuthenticator)
            authenticatorInstalled = true
        }
    }

    internal fun credentialsFor(
        host: String?,
        site: InetAddress?,
        port: Int,
        requestorType: Authenticator.RequestorType?,
        protocol: String?,
    ): PasswordAuthentication? {
        val allocation = current ?: return null
        val loopback = site?.isLoopbackAddress == true ||
            host == LOOPBACK_HOST || host.equals("localhost", ignoreCase = true)
        if (!loopback) return null
        val endpoint = when {
            port == allocation.http.port && requestorType == Authenticator.RequestorType.PROXY -> allocation.http
            port == allocation.socks.port && protocol?.startsWith("SOCKS", ignoreCase = true) == true -> allocation.socks
            else -> null
        } ?: return null
        return PasswordAuthentication(endpoint.username, endpoint.password.toCharArray())
    }

    private object LocalProxyAuthenticator : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            return credentialsFor(
                host = requestingHost,
                site = requestingSite,
                port = requestingPort,
                requestorType = requestorType,
                protocol = requestingProtocol,
            )
        }
    }
}
