package com.mallocgfw.app.xray

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val SNAPSHOT_POLL_INTERVAL_MS = 50L
private const val SNAPSHOT_TAG = "DirectNetworkSnapshot"

private data class DirectNetworkState(
    val validated: Boolean,
    val preferred: Boolean,
    val order: Int,
)

internal suspend fun snapshotDirectNetworks(
    connectivityManager: ConnectivityManager,
    waitMs: Long,
): List<Network> {
    val stateLock = Any()
    val states = linkedMapOf<Network, DirectNetworkState>()
    var nextOrder = 0

    fun update(
        network: Network?,
        capabilities: NetworkCapabilities? = network?.let(connectivityManager::getNetworkCapabilities),
        preferred: Boolean = false,
    ) {
        if (network == null) return
        synchronized(stateLock) {
            if (capabilities == null) {
                states.remove(network)
                return
            }
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                states.remove(network)
                return
            }
            val existing = states[network]
            states[network] = DirectNetworkState(
                validated = existing?.validated == true ||
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                preferred = existing?.preferred == true || preferred,
                order = existing?.order ?: nextOrder++,
            )
        }
    }

    fun orderedStates(): List<Map.Entry<Network, DirectNetworkState>> {
        return synchronized(stateLock) {
            states.entries.map { it.toPair() }
        }.sortedWith(
            compareByDescending<Pair<Network, DirectNetworkState>> { it.second.validated }
                .thenByDescending { it.second.preferred }
                .thenBy { it.second.order },
        ).map { java.util.AbstractMap.SimpleEntry(it.first, it.second) }
    }

    fun hasValidatedNetwork(): Boolean = orderedStates().any { it.value.validated }

    update(connectivityManager.activeNetwork, preferred = true)

    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            update(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            update(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            synchronized(stateLock) {
                states.remove(network)
            }
        }
    }

    return try {
        connectivityManager.registerNetworkCallback(request, callback)
        withTimeoutOrNull(waitMs) {
            while (!hasValidatedNetwork()) {
                delay(SNAPSHOT_POLL_INTERVAL_MS)
            }
        }
        orderedStates().map { it.key }
    } catch (error: Throwable) {
        Log.w(SNAPSHOT_TAG, "Unable to collect direct network snapshot", error)
        orderedStates().map { it.key }
    } finally {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
