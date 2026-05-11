package com.mallocgfw.app.xray

import android.content.Context
import android.os.Build
import android.util.Log
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.SsrMihomoConfigFactory
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SsrRuntimeManager {
    private const val TAG = "SsrRuntimeManager"
    private const val ASSET_NAME = "mihomo"
    private val runtimeLock = Any()
    private var process: Process? = null
    private var activeConfigKey: String? = null

    suspend fun startIfNeeded(context: Context, server: ServerNode): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val endpoint = ManualNodeFactory.ssrEndpointFromServer(server)
            if (endpoint == null) {
                stopNow()
                return@withContext Result.success(Unit)
            }
            val configKey = endpoint.toString()
            synchronized(runtimeLock) {
                val currentProcess = process
                if (activeConfigKey == configKey && currentProcess?.isAlive == true) {
                    return@withContext Result.success(Unit)
                }
                stopLocked()
                runCatching {
                    val runtimeDir = File(context.filesDir, "mihomo-runtime").apply { mkdirs() }
                    val binary = installBinary(context, runtimeDir)
                    val configFile = File(runtimeDir, "ssr.yaml").apply {
                        writeText(SsrMihomoConfigFactory.buildConfig(endpoint))
                    }
                    val started = ProcessBuilder(
                        binary.absolutePath,
                        "-d",
                        runtimeDir.absolutePath,
                        "-f",
                        configFile.absolutePath,
                    )
                        .directory(runtimeDir)
                        .redirectErrorStream(true)
                        .start()
                    process = started
                    activeConfigKey = configKey
                    drainLogs(started)
                    Thread.sleep(180L)
                    if (!started.isAlive) {
                        activeConfigKey = null
                        process = null
                        error("mihomo sidecar 启动后立即退出。")
                    }
                }
            }
        }
    }

    fun stopNow() {
        synchronized(runtimeLock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        activeConfigKey = null
        process?.let { running ->
            runCatching { running.destroy() }
            runCatching {
                if (running.isAlive) {
                    running.destroyForcibly()
                }
            }
        }
        process = null
    }

    private fun installBinary(context: Context, runtimeDir: File): File {
        val abi = resolveAssetAbi()
        val assetPath = "mihomo/$abi/$ASSET_NAME"
        val target = File(runtimeDir, "$ASSET_NAME-$abi")
        try {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: FileNotFoundException) {
            throw IllegalStateException("SSR 需要打包 mihomo sidecar：请将可执行文件放到 assets/$assetPath。", error)
        }
        if (!target.setExecutable(true, true)) {
            error("无法设置 mihomo sidecar 可执行权限。")
        }
        return target
    }

    private fun resolveAssetAbi(): String {
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64-v8a"
                else -> null
            }
        } ?: error("当前设备 ABI 暂未打包 mihomo sidecar。")
    }

    private fun drainLogs(started: Process) {
        Thread {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, line) }
                }
            }
        }.apply {
            name = "mihomo-sidecar-log"
            isDaemon = true
            start()
        }
    }
}
