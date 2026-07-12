package com.mallocgfw.app.tailscale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Process-safe client for [TailscaleBridgeService]. */
object TailscaleBridgeClient {
    private val bindMutex = Mutex()

    @Volatile
    private var bridge: ITailscaleBridge? = null

    private var connection: ServiceConnection? = null

    suspend fun start(context: Context, config: String): String = service(context).start(config)

    suspend fun status(context: Context): String = service(context).status()

    suspend fun stop(context: Context): String = service(context).stop()

    suspend fun setExitNode(context: Context, nodeId: String): String = service(context).setExitNode(nodeId)

    suspend fun logout(context: Context, stateDir: String): String = service(context).logout(stateDir)

    private suspend fun service(context: Context): ITailscaleBridge {
        bridge?.let { return it }
        return bindMutex.withLock {
            bridge ?: bind(context.applicationContext)
        }
    }

    private suspend fun bind(context: Context): ITailscaleBridge = suspendCancellableCoroutine { continuation ->
        val nextConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val nextBridge = ITailscaleBridge.Stub.asInterface(binder)
                bridge = nextBridge
                if (continuation.isActive) continuation.resume(nextBridge)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bridge = null
            }

            override fun onBindingDied(name: ComponentName) {
                bridge = null
            }
        }
        connection = nextConnection
        val bound = context.bindService(
            Intent(context, TailscaleBridgeService::class.java),
            nextConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound && continuation.isActive) {
            connection = null
            continuation.resumeWithException(IllegalStateException("无法启动 Tailscale 服务进程"))
        }
    }
}
