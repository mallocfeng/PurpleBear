package com.mallocgfw.app.xray

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.mallocgfw.app.model.ServerNode
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import com.mallocgfw.app.model.tr

object NodeLatencyTester {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val TOTAL_TIMEOUT_MS = 10_000L
    private const val MAX_TARGET_ATTEMPTS = 3
    private const val MAX_PARALLEL_DIRECT_PROBES = 8

    /** Plain HTTP: the time is the node's own handshake plus one round trip, with no second TLS handshake inside. */
    private const val PROBE_URL = "http://www.gstatic.com/generate_204"
    private const val PROBE_TIMEOUT_SECONDS = 6
    private const val PROBE_COALESCE_MS = 40L
    private const val DIRECT_NETWORK_WAIT_MS = 1_500L
    private const val PROXY_PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val TAG = "NodeLatencyTester"
    private val processBindingLock = Any()

    /** UDP-based protocols: a TCP connect to their port proves nothing. */
    private val UDP_PROTOCOLS = setOf("HYSTERIA", "HYSTERIA2", "TUIC", "WIREGUARD", "SHADOWQUIC", "MASQUE")

    /**
     * One temporary core at a time: there is a single Xray instance, so two
     * probes at once would stop each other's core and read each other's
     * latency (testing a whole group starts them together).
     */
    private val temporaryCoreMutex = Mutex()

    /** Direct TCP probes at once; a whole group no longer opens hundreds of connections together. */
    private val directProbePermits = Semaphore(MAX_PARALLEL_DIRECT_PROBES)

    /**
     * A user-started test.
     *
     * Nodes Xray runs are timed for real: one request through the node (and
     * its pre-proxy chain) in a separate core ([realProbe]), so a node that
     * accepts connections but no longer works shows as failed, and it works
     * while the VPN is connected, UDP-based nodes included.
     *
     * Nodes the compatibility core runs, and any node when the probe can't be
     * run, use the older tests: a TCP connection to the server, or a
     * temporary core for UDP-like nodes (which needs the VPN off). Those are
     * queued (see above); the time limit only counts once the probe starts.
     */
    suspend fun measure(context: Context, node: ServerNode): Int = withContext(Dispatchers.IO) {
        probeConfig(context, node)?.let { config ->
            try {
                return@withContext realProbe(context, config)
            } catch (error: LatencyProbeClient.ProbeUnavailable) {
                Log.w(TAG, "Real probe unavailable for ${node.name}, using the fallback test", error)
            }
        }
        if (requiresCoreProbe(node)) {
            temporaryCoreMutex.withLock { withTimeout(TOTAL_TIMEOUT_MS) { measureViaTemporaryXray(context, node) } }
        } else {
            directProbePermits.withPermit { withTimeout(TOTAL_TIMEOUT_MS) { measureViaDirectPort(context, node) } }
        }
    }

    /** The VPN service's heartbeat: never queued behind user-started tests. */
    suspend fun measureHeartbeat(context: Context, node: ServerNode): Int = withContext(Dispatchers.IO) {
        if (requiresCoreProbe(node)) {
            if (!VpnServiceController.isActuallyRunning(context)) {
                return@withContext temporaryCoreMutex.withLock { withTimeout(TOTAL_TIMEOUT_MS) { measureViaTemporaryXray(context, node) } }
            }
            if (requiresUdpLikeProbe(node)) {
                throw UnsupportedOperationException(heartbeatFallbackUnsupportedMessage(node))
            }
        }
        withTimeout(TOTAL_TIMEOUT_MS) { measureViaDirectPort(context, node) }
    }

    /**
     * Whether testing [node] needs the VPN off: only UDP-like nodes that the
     * real probe can't cover (they run in the compatibility core).
     */
    fun requiresDisconnectToTest(context: Context, node: ServerNode): Boolean =
        requiresCoreProbe(node) && VpnServiceController.isActuallyRunning(context) && probeConfig(context, node) == null

    private fun probeConfig(context: Context, node: ServerNode): String? = runCatching {
        XrayConfigFactory.buildProbeOutbounds(node, com.mallocgfw.app.core.AppController.load(context).servers)
    }.getOrNull()

    // ---- real probe: requests coalesced into batches of LatencyProbeClient.MAX_BATCH ----

    private class ProbeRequest(val config: String, val result: CompletableDeferred<Int>)

    private val probeQueue = Channel<ProbeRequest>(Channel.UNLIMITED)
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeWorkerStarted = AtomicBoolean(false)

    private suspend fun realProbe(context: Context, config: String): Int {
        val request = ProbeRequest(config, CompletableDeferred())
        startProbeWorker(context.applicationContext)
        probeQueue.send(request)
        return request.result.await()
    }

    private fun startProbeWorker(context: Context) {
        if (!probeWorkerStarted.compareAndSet(false, true)) return
        probeScope.launch {
            for (first in probeQueue) {
                // Testing a group asks for all its nodes at once: let them arrive.
                delay(PROBE_COALESCE_MS)
                val batch = mutableListOf(first)
                while (batch.size < LatencyProbeClient.MAX_BATCH) {
                    batch += probeQueue.tryReceive().getOrNull() ?: break
                }
                val results = runCatching {
                    LatencyProbeClient.pingBatch(
                        context,
                        batch.map { LatencyProbeClient.Item(it.config) },
                        PROBE_TIMEOUT_SECONDS,
                        PROBE_URL,
                    )
                }
                batch.forEachIndexed { index, request ->
                    val outcome = results.map { it[index] }
                    val result = outcome.getOrNull()
                    when {
                        result == null -> request.result.completeExceptionally(
                            outcome.exceptionOrNull() as? LatencyProbeClient.ProbeUnavailable
                                ?: LatencyProbeClient.ProbeUnavailable("probe failed", outcome.exceptionOrNull()),
                        )
                        result.delayMs != null -> request.result.complete(result.delayMs)
                        result.timedOut -> request.result.completeExceptionally(
                            IllegalStateException(tr("节点在 $PROBE_TIMEOUT_SECONDS 秒内没有响应。", "The node didn't answer within $PROBE_TIMEOUT_SECONDS s.", "Узел не ответил за $PROBE_TIMEOUT_SECONDS с.")),
                        )
                        // The outbound couldn't even be built in the probe core:
                        // not a verdict on the node, so the fallback test decides.
                        isProbeSetupError(result.error) -> request.result.completeExceptionally(
                            LatencyProbeClient.ProbeUnavailable(result.error.orEmpty()),
                        )
                        else -> request.result.completeExceptionally(
                            IllegalStateException(
                                tr("经节点访问测试地址失败", "The test request through the node failed", "Тестовый запрос через узел не прошёл") +
                                    // Go puts the request in front: Head "http://…": <the reason>.
                                    result.error?.replace(Regex("^[A-Za-z]+ \"[^\"]*\": "), "")
                                        ?.takeIf { it.isNotBlank() }?.let { "：${it.take(120)}" }.orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** libXray's messages for a config it couldn't prepare (xray/ping_batch.go). */
    internal fun isProbeSetupError(error: String?): Boolean {
        val text = error?.lowercase().orEmpty()
        return listOf("failed to build outbound", "ping config", "ping xray json", "outbound tag", "depends on", "dependency cycle")
            .any { it in text }
    }

    fun requiresCoreProbe(node: ServerNode): Boolean {
        val protocol = node.protocol.trim().uppercase()
        val transport = node.transport.trim().uppercase()
        if (protocol in UDP_PROTOCOLS) return true
        if (transport in setOf("GRPC", "KCP", "MKCP")) return true
        if ("HYSTERIA" in transport || "QUIC" in transport) return true
        return false
    }

    fun supportsHeartbeatFallback(node: ServerNode): Boolean = !requiresUdpLikeProbe(node)

    fun heartbeatFallbackUnsupportedMessage(node: ServerNode): String {
        return tr("当前 ${node.protocol}/${node.transport} 节点无法在连接中后台做可靠心跳，因此不支持自动切备用节点。", "${node.protocol}/${node.transport} nodes can't be checked reliably in the background while connected, so automatic fallback isn't supported.", "Узлы ${node.protocol}/${node.transport} нельзя надёжно проверять в фоне во время подключения, поэтому автоматическое переключение на резервный узел не поддерживается.")
    }

    private fun requiresUdpLikeProbe(node: ServerNode): Boolean {
        val protocol = node.protocol.trim().uppercase()
        val transport = node.transport.trim().uppercase()
        return protocol in UDP_PROTOCOLS ||
            transport in setOf("KCP", "MKCP") ||
            "HYSTERIA" in transport ||
            "QUIC" in transport
    }

    private suspend fun measureViaTemporaryXray(context: Context, node: ServerNode): Int {
        if (VpnServiceController.isActuallyRunning(context)) {
            error(tr("当前已连接 VPN，这类节点请先断开当前连接后再检测。", "VPN is connected. Disconnect first to test this kind of node.", "VPN подключён. Чтобы проверить такой узел, сначала отключитесь."))
        }
        val startedAt = System.nanoTime()
        XrayCoreManager.start(context, node).getOrElse { throw it }
        return try {
            // Authenticated SOCKS inbound of the temporary core; the JDK SOCKS
            // client gets credentials from LocalProxyRuntime's scoped Authenticator.
            val proxy = LocalProxyRuntime.current()?.socks?.socksProxy()
                ?: error(tr("临时 Xray 的本地代理未就绪。", "The temporary Xray local proxy isn't ready.", "Локальный прокси временного Xray не готов."))
            val connection = (URL(PROXY_PROBE_URL).openConnection(proxy) as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..399) {
                    error(tr("代理探测返回异常状态码：$responseCode", "The proxy probe returned an unexpected status code: $responseCode", "Проверка прокси вернула неожиданный код: $responseCode"))
                }
            } finally {
                connection.disconnect()
            }
            ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(1)
        } finally {
            XrayCoreManager.stop()
        }
    }

    private suspend fun measureViaDirectPort(context: Context, node: ServerNode): Int {
        val startedAt = System.nanoTime()
        val host = node.address.trim().trim('[', ']')
        val port = node.port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error(tr("节点端口无效：${node.port}", "Invalid node port: ${node.port}", "Неверный порт узла: ${node.port}"))
        val vpnRunning = VpnServiceController.isActuallyRunning(context)
        val directNetwork = if (vpnRunning) findDirectNetwork(context) else null
        val targets = resolveTargets(context, host, directNetwork, vpnRunning)
        var lastError: Throwable? = null

        for (target in targets.take(MAX_TARGET_ATTEMPTS)) {
            runCatching {
                Log.d(
                    TAG,
                    "Testing ${node.name} via ${target.hostAddress}:$port, " +
                        "directNetwork=${directNetwork != null}, vpnRunning=$vpnRunning",
                )
                val socket = if (vpnRunning && directNetwork != null) {
                    directNetwork.socketFactory.createSocket()
                } else {
                    Socket().apply {
                        if (vpnRunning && !VpnServiceController.protectSocket(this)) {
                            error(tr("无法绕过当前 VPN 进行节点检测。", "Couldn't bypass the current VPN to test the node.", "Не удалось обойти текущий VPN для проверки узла."))
                        }
                    }
                }
                socket.use {
                    socket.connect(
                        InetSocketAddress(target, port),
                        CONNECT_TIMEOUT_MS,
                    )
                }
            }.onSuccess {
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
                Log.d(TAG, "Latency success for ${node.name}: ${elapsedMs}ms")
                return elapsedMs.coerceAtLeast(1)
            }.onFailure {
                Log.w(TAG, "Latency attempt failed for ${node.name} via ${target.hostAddress}:${node.port}", it)
                lastError = it
            }
        }
        throw (lastError ?: IllegalStateException(tr("无法建立到节点的 TCP 连接。", "Couldn't open a TCP connection to the node.", "Не удалось установить TCP-соединение с узлом.")))
    }

    private suspend fun findDirectNetwork(context: Context): Network? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return snapshotDirectNetworks(connectivityManager, DIRECT_NETWORK_WAIT_MS).firstOrNull()
    }

    private fun resolveTargets(
        context: Context,
        address: String,
        directNetwork: Network?,
        vpnRunning: Boolean,
    ): List<InetAddress> {
        if (looksLikeIpLiteral(address)) {
            return listOf(InetAddress.getByName(address))
        }
        if (vpnRunning && directNetwork == null) {
            error(tr("当前未找到可用于直连的底层网络，无法在 VPN 开启时直连解析节点域名。", "No underlying network for direct connections, so the node domain can't be resolved while VPN is on.", "Нет базовой сети для прямого подключения: при включённом VPN домен узла разрешить нельзя."))
        }
        val resolved = runCatching {
            (directNetwork?.getAllByName(address) ?: InetAddress.getAllByName(address)).toList()
        }.recoverCatching { error ->
            if (!vpnRunning || directNetwork == null) throw error
            Log.w(TAG, "Direct network DNS lookup failed for $address, retrying with process binding", error)
            resolveByTemporarilyBindingProcess(context, directNetwork, address)
        }.getOrThrow()
        if (resolved.isEmpty()) error(tr("无法解析节点域名。", "Couldn't resolve the node domain.", "Не удалось разрешить домен узла."))
        return resolved.sortedByDescending { it is Inet4Address }
    }

    private fun resolveByTemporarilyBindingProcess(
        context: Context,
        network: Network,
        address: String,
    ): List<InetAddress> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: error(tr("无法访问系统网络服务。", "Couldn't access the system network service.", "Нет доступа к сетевой службе системы."))
        synchronized(processBindingLock) {
            val originalNetwork = connectivityManager.boundNetworkForProcess
            val changed = originalNetwork != network
            try {
                if (changed && !connectivityManager.bindProcessToNetwork(network)) {
                    error(tr("无法切换到直连网络进行域名解析。", "Couldn't switch to the direct network to resolve the domain.", "Не удалось переключиться на прямую сеть для разрешения домена."))
                }
                return InetAddress.getAllByName(address).toList()
            } finally {
                if (changed) {
                    connectivityManager.bindProcessToNetwork(originalNetwork)
                }
            }
        }
    }

    private fun looksLikeIpLiteral(value: String): Boolean {
        return value.contains(':') || value.all { it.isDigit() || it == '.' }
    }
}
