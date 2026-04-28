package com.mallocgfw.app.xray

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class GeoDataStatus {
    Idle,
    Updating,
    Ready,
    Failed,
}

data class GeoDataSnapshot(
    val status: GeoDataStatus,
    val updatedAt: String,
    val sourceLabel: String,
    val geoipBytes: Long,
    val geositeBytes: Long,
    val lastError: String? = null,
)

object GeoDataManager {
    private const val PREFS_NAME = "mallocgfw_geodata"
    private const val UPDATED_AT_KEY = "updated_at"
    private const val SOURCE_LABEL_KEY = "source_label"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val GEOIP_URL = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
    private const val GEOSITE_URL = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"

    fun load(context: Context): GeoDataSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val geoip = cacheFile(context, "geoip.dat")
        val geosite = cacheFile(context, "geosite.dat")
        val hasCache = geoip.exists() && geoip.length() > 0 && geosite.exists() && geosite.length() > 0
        val bundledSizes = if (hasCache) null else bundledAssetSizes(context)
        val hasBundledAssets = bundledSizes != null && bundledSizes.first > 0L && bundledSizes.second > 0L
        return GeoDataSnapshot(
            status = if (hasCache || hasBundledAssets) GeoDataStatus.Ready else GeoDataStatus.Idle,
            updatedAt = prefs.getString(UPDATED_AT_KEY, null) ?: if (hasCache) "已更新" else "内置资源",
            sourceLabel = prefs.getString(SOURCE_LABEL_KEY, null) ?: if (hasCache) "本地已更新" else "内置资源",
            geoipBytes = if (hasCache) geoip.length() else bundledSizes?.first ?: 0L,
            geositeBytes = if (hasCache) geosite.length() else bundledSizes?.second ?: 0L,
        )
    }

    suspend fun refresh(context: Context): GeoDataSnapshot {
        return withContext(Dispatchers.IO) {
            val geoipBytes = fetchBytes(GEOIP_URL)
            val geositeBytes = fetchBytes(GEOSITE_URL)
            saveBytes(cacheFile(context, "geoip.dat"), geoipBytes)
            saveBytes(cacheFile(context, "geosite.dat"), geositeBytes)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(UPDATED_AT_KEY, "刚刚")
                .putString(SOURCE_LABEL_KEY, "v2fly 最新资源")
                .apply()
            load(context).copy(
                status = GeoDataStatus.Ready,
                updatedAt = "刚刚",
                sourceLabel = "v2fly 最新资源",
            )
        }
    }

    fun installIntoRuntime(
        context: Context,
        runtimeDir: File,
        assetAbi: String,
    ) {
        val targetGeoip = File(runtimeDir, "geoip.dat")
        val targetGeosite = File(runtimeDir, "geosite.dat")
        val cachedGeoip = cacheFile(context, "geoip.dat")
        val cachedGeosite = cacheFile(context, "geosite.dat")

        if (cachedGeoip.exists() && cachedGeoip.length() > 0) {
            cachedGeoip.copyTo(targetGeoip, overwrite = true)
        } else if (!targetGeoip.exists() || targetGeoip.length() == 0L) {
            copyAsset(context, "xray/$assetAbi/geoip.dat", targetGeoip)
        }

        if (cachedGeosite.exists() && cachedGeosite.length() > 0) {
            cachedGeosite.copyTo(targetGeosite, overwrite = true)
        } else if (!targetGeosite.exists() || targetGeosite.length() == 0L) {
            copyAsset(context, "xray/$assetAbi/geosite.dat", targetGeosite)
        }
    }

    private fun cacheFile(context: Context, name: String): File {
        return File(context.filesDir, "geodata/$name")
    }

    private fun saveBytes(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        target.outputStream().use { it.write(bytes) }
    }

    private fun fetchBytes(url: String): ByteArray {
        return ResourceFetchClient.fetchBytes(
            url = url,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            label = "Geo 资源",
        )
    }

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun bundledAssetSizes(context: Context): Pair<Long, Long>? {
        val assetAbi = resolveAssetAbi() ?: return null
        val geoipBytes = assetLength(context, "xray/$assetAbi/geoip.dat")
        val geositeBytes = assetLength(context, "xray/$assetAbi/geosite.dat")
        return geoipBytes to geositeBytes
    }

    private fun assetLength(context: Context, assetPath: String): Long {
        return runCatching {
            context.assets.openFd(assetPath).use { it.length }
        }.getOrElse {
            runCatching {
                context.assets.open(assetPath).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                    }
                    total
                }
            }.getOrDefault(0L)
        }
    }

    private fun resolveAssetAbi(): String? {
        // Mirrors XrayCoreManager.resolveAssetAbi(). Keep them in sync if the
        // packaged ABI list ever grows.
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64-v8a"
                else -> null
            }
        }
    }
}
