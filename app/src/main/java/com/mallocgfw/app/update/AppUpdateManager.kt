package com.mallocgfw.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mallocgfw.app.xray.XrayConfigFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class AppUpdateStatus {
    Idle,
    Checking,
    Latest,
    Available,
    Downloading,
    Downloaded,
    Failed,
}

data class AppUpdateUiState(
    val status: AppUpdateStatus = AppUpdateStatus.Idle,
    val info: AppUpdateInfo? = null,
    val message: String? = null,
    val downloadedApkPath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

data class AppUpdateInfo(
    val tagName: String,
    val releaseName: String,
    val versionName: String,
    val versionCode: Int?,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String?,
) {
    fun isNewerThan(currentVersionCode: Int, currentVersionName: String): Boolean {
        val remoteCode = versionCode
        if (remoteCode != null) return remoteCode > currentVersionCode
        return compareSemanticVersion(versionName, currentVersionName) > 0
    }
}

object AppUpdateManager {
    private const val PREFS_NAME = "purplebear_updates"
    private const val LAST_AUTO_CHECK_DATE_KEY = "last_auto_check_date"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/mallocfeng/PurpleBear/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 6
    private const val USER_AGENT = "PurpleBear-Updater/1.0"

    fun shouldRunAutoCheckToday(context: Context): Boolean {
        val today = LocalDate.now().toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(LAST_AUTO_CHECK_DATE_KEY, null) != today
    }

    fun markAutoCheckToday(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_AUTO_CHECK_DATE_KEY, LocalDate.now().toString())
            .apply()
    }

    fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    fun currentVersionName(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName.orEmpty()
    }

    fun downloadedFile(path: String?): File? {
        return path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
    }

    fun installDownloadedApk(context: Context, file: File): String? {
        if (!file.exists() || file.length() == 0L) return "安装包不存在，请重新下载。"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return "请先允许 PurpleBear 安装未知来源应用，然后返回继续安装。"
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return null
    }

    suspend fun checkLatest(): AppUpdateInfo {
        val response = withContext(Dispatchers.IO) {
            val connection = openMetadataConnection(LATEST_RELEASE_URL)
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("GitHub Release 返回 HTTP $code。")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        return parseLatestRelease(response)
    }

    suspend fun downloadApk(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File {
        return withContext(Dispatchers.IO) {
            if (!isLocalProxyAvailable()) {
                error("请先连接代理后再下载更新。")
            }
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(updatesDir, sanitizeFileName("${info.tagName}-${info.apkName}"))
            val partial = File(updatesDir, "${target.name}.download")
            if (target.exists() && target.length() > 0L && verifyDownloadedApk(target, info)) {
                withContext(Dispatchers.Main.immediate) {
                    onProgress(target.length(), target.length())
                }
                return@withContext target
            }
            partial.delete()

            val connection = openDownloadConnection(info.apkDownloadUrl)
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("安装包下载返回 HTTP $code。")
                val expectedBytes = info.apkSizeBytes.takeIf { it > 0L }
                    ?: connection.contentLengthLong.takeIf { it > 0L }
                    ?: 0L
                var downloaded = 0L
                var lastReported = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= 512 * 1024L) {
                                lastReported = downloaded
                                withContext(Dispatchers.Main.immediate) {
                                    onProgress(downloaded, expectedBytes)
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    onProgress(downloaded, expectedBytes)
                }
                if (expectedBytes > 0L && partial.length() != expectedBytes) {
                    error("安装包大小不完整，请重新下载。")
                }
                if (!verifyDownloadedApk(partial, info)) {
                    error("安装包校验失败，请重新下载。")
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                target
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openMetadataConnection(url: String): HttpURLConnection {
        val connection = if (isLocalProxyAvailable()) {
            URL(url).openConnection(localProxy())
        } else {
            URL(url).openConnection()
        } as HttpURLConnection
        return connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private fun openDownloadConnection(url: String, redirectCount: Int = 0): HttpURLConnection {
        require(redirectCount <= MAX_REDIRECTS) { "安装包下载重定向次数过多。" }
        val connection = (URL(url).openConnection(localProxy()) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location").orEmpty()
            connection.disconnect()
            require(location.isNotBlank()) { "安装包下载重定向缺少 Location。" }
            return openDownloadConnection(URL(URL(url), location).toString(), redirectCount + 1)
        }
        return connection
    }

    private fun parseLatestRelease(rawJson: String): AppUpdateInfo {
        val release = JSONObject(rawJson)
        val assets = release.optJSONArray("assets") ?: error("GitHub Release 没有安装包。")
        val apkAsset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { asset ->
                val name = asset.optString("name")
                val type = asset.optString("content_type")
                name.endsWith(".apk", ignoreCase = true) ||
                    type == "application/vnd.android.package-archive"
            }
            ?: error("GitHub Release 没有 APK 安装包。")

        val tagName = release.optString("tag_name")
        val body = release.optString("body")
        return AppUpdateInfo(
            tagName = tagName,
            releaseName = release.optString("name").ifBlank { tagName },
            versionName = tagName.removePrefix("v").removePrefix("V"),
            versionCode = parseVersionCode(body),
            body = body,
            htmlUrl = release.optString("html_url"),
            publishedAt = release.optString("published_at"),
            apkName = apkAsset.optString("name"),
            apkDownloadUrl = apkAsset.optString("browser_download_url"),
            apkSizeBytes = apkAsset.optLong("size", 0L),
            apkSha256 = apkAsset.optString("digest")
                .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.lowercase(Locale.US),
        )
    }

    private fun parseVersionCode(body: String): Int? {
        return Regex("""versionCode\s*[=:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun verifyDownloadedApk(file: File, info: AppUpdateInfo): Boolean {
        val expectedHash = info.apkSha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHash, ignoreCase = true)
    }

    private fun isLocalProxyAvailable(): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", XrayConfigFactory.HTTP_PORT),
                    500,
                )
            }
        }.isSuccess
    }

    private fun localProxy(): Proxy {
        return Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress("127.0.0.1", XrayConfigFactory.HTTP_PORT),
        )
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }
}

private fun compareSemanticVersion(left: String, right: String): Int {
    val leftParts = left.versionParts()
    val rightParts = right.versionParts()
    val count = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until count) {
        val a = leftParts.getOrElse(index) { 0 }
        val b = rightParts.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private fun String.versionParts(): List<Int> {
    return trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .split('.')
        .mapNotNull { it.toIntOrNull() }
}
