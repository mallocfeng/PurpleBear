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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

object NodeLatencyTester {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val TOTAL_TIMEOUT_MS = 10_000L
    private const val MAX_TARGET_ATTEMPTS = 3
    private const val DIRECT_NETWORK_WAIT_MS = 1_500L
    private const val PROXY_PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val TAG = "NodeLatencyTester"
    private val processBindingLock = Any()

    suspend fun measure(context: Context, node: ServerNode): Int = withContext(Dispatchers.IO) {
        withTimeout(TOTAL_TIMEOUT_MS) {
            if (requiresCoreProbe(node)) {
                return@withTimeout measureViaTemporaryXray(context, node)
            }
            return@withTimeout measureViaDirectPort(context, node)
        }
    }

    suspend fun measureHeartbeat(context: Context, node: ServerNode): Int = withContext(Dispatchers.IO) {
        withTimeout(TOTAL_TIMEOUT_MS) {
            if (requiresCoreProbe(node) && VpnServiceController.isActuallyRunning(context)) {
                if (requiresUdpLikeProbe(node)) {
                    throw UnsupportedOperationException("当前 ${node.protocol}/${node.transport} 节点不支持连接中后台心跳。")
                }
                return@withTimeout measureViaDirectPort(context, node)
            }
            return@withTimeout measure(context, node)
        }
    }

    fun requiresCoreProbe(node: ServerNode): Boolean {
        val protocol = node.protocol.trim().uppercase()
        val transport = node.transport.trim().uppercase()
        if (protocol in setOf("HYSTERIA", "HYSTERIA2")) return true
        if (transport in setOf("GRPC", "KCP", "MKCP")) return true
        if ("HYSTERIA" in transport || "QUIC" in transport) return true
        return false
    }

    private fun requiresUdpLikeProbe(node: ServerNode): Boolean {
        val protocol = node.protocol.trim().uppercase()
        val transport = node.transport.trim().uppercase()
        return protocol in setOf("HYSTERIA", "HYSTERIA2") ||
            transport in setOf("KCP", "MKCP") ||
            "HYSTERIA" in transport ||
            "QUIC" in transport
    }

    private suspend fun measureViaTemporaryXray(context: Context, node: ServerNode): Int {
        if (VpnServiceController.isActuallyRunning(context)) {
            error("当前已连接 VPN，这类节点请先断开当前连接后再检测。")
        }
        val startedAt = System.nanoTime()
        val snapshot = XrayCoreManager.start(context, node).getOrElse { throw it }
        val socksPort = snapshot.localSocksPort ?: XrayConfigFactory.SOCKS_PORT
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val connection = (URL(PROXY_PROBE_URL).openConnection(proxy) as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..399) {
                    error("代理探测返回异常状态码：$responseCode")
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
            ?: error("节点端口无效：${node.port}")
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
                            error("无法绕过当前 VPN 进行节点检测。")
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
        throw (lastError ?: IllegalStateException("无法建立到节点的 TCP 连接。"))
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
            error("当前未找到可用于直连的底层网络，无法在 VPN 开启时直连解析节点域名。")
        }
        val resolved = runCatching {
            (directNetwork?.getAllByName(address) ?: InetAddress.getAllByName(address)).toList()
        }.recoverCatching { error ->
            if (!vpnRunning || directNetwork == null) throw error
            Log.w(TAG, "Direct network DNS lookup failed for $address, retrying with process binding", error)
            resolveByTemporarilyBindingProcess(context, directNetwork, address)
        }.getOrThrow()
        if (resolved.isEmpty()) error("无法解析节点域名。")
        return resolved.sortedByDescending { it is Inet4Address }
    }

    private fun resolveByTemporarilyBindingProcess(
        context: Context,
        network: Network,
        address: String,
    ): List<InetAddress> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: error("无法访问系统网络服务。")
        synchronized(processBindingLock) {
            val originalNetwork = connectivityManager.boundNetworkForProcess
            val changed = originalNetwork != network
            try {
                if (changed && !connectivityManager.bindProcessToNetwork(network)) {
                    error("无法切换到直连网络进行域名解析。")
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
