package com.mallocgfw.app.xray

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
object AppLogManager {
    private const val LOGCAT_TAG = "PurpleBear"
    private const val LOG_DIR = "logs"
    private const val APP_LOG = "app.log"
    private const val XRAY_ERROR_LOG = "xray-error.log"
    private const val XRAY_ACCESS_LOG = "xray-access.log"
    private const val MAX_FILE_BYTES = 256 * 1024
    private val logLock = Any()
    // Single-thread dispatcher so file writes never run concurrently with each
    // other but the calling thread is never blocked on disk I/O.
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun append(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val line = buildString {
            append(timestamp())
            append(" [")
            append(tag)
            append("] ")
            append(message)
            throwable?.message?.takeIf { it.isNotBlank() }?.let {
                append(" :: ")
                append(it)
            }
        }
        Log.i(LOGCAT_TAG, line)
        val appContext = context.applicationContext ?: context
        writerScope.launch {
            runCatching {
                synchronized(logLock) {
                    writeLine(appLogFile(appContext), line)
                }
            }.onFailure { Log.w(LOGCAT_TAG, "Unable to persist log line", it) }
        }
    }

    fun xrayErrorLogFile(context: Context): File = ensureLogFile(context, XRAY_ERROR_LOG).also(::trimAsync)

    fun xrayAccessLogFile(context: Context): File = ensureLogFile(context, XRAY_ACCESS_LOG).also(::trimAsync)

    fun trimAllLogs(context: Context) {
        val appContext = context.applicationContext ?: context
        writerScope.launch {
            synchronized(logLock) {
                logFiles(appContext).forEach { (_, file) -> trimIfNeeded(file) }
            }
        }
    }

    fun readRecentLogs(
        context: Context,
        maxCharsPerFile: Int = 4_000,
    ): String {
        return synchronized(logLock) {
            trimAllLogs(context)
            logFiles(context).joinToString("\n\n") { (title, file) ->
                buildString {
                    append("===== ")
                    append(title)
                    append(" =====\n")
                    append(readTail(file, maxCharsPerFile).ifBlank { "暂无日志。" })
                }
            }
        }
    }

    private fun appLogFile(context: Context): File = ensureLogFile(context, APP_LOG)

    private fun logFiles(context: Context): List<Pair<String, File>> {
        return listOf(
            "应用日志" to appLogFile(context),
            "Xray 错误日志" to ensureLogFile(context, XRAY_ERROR_LOG),
            "Xray 访问日志" to ensureLogFile(context, XRAY_ACCESS_LOG),
        )
    }

    private fun ensureLogFile(context: Context, name: String): File {
        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        return File(dir, name).apply {
            if (!exists()) {
                parentFile?.mkdirs()
                createNewFile()
            }
        }
    }

    private fun writeLine(file: File, line: String) {
        trimIfNeeded(file)
        file.appendText("$line\n")
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_FILE_BYTES) return
        val keepBytes = MAX_FILE_BYTES / 2
        val trimmed = ByteArray(keepBytes)
        RandomAccessFile(file, "r").use { input ->
            input.seek((input.length() - keepBytes).coerceAtLeast(0L))
            input.readFully(trimmed)
        }
        file.writeBytes(trimmed)
    }

    private fun trimAsync(file: File) {
        writerScope.launch {
            synchronized(logLock) {
                runCatching { trimIfNeeded(file) }
                    .onFailure { Log.w(LOGCAT_TAG, "Unable to trim log file ${file.name}", it) }
            }
        }
    }

    private fun readTail(file: File, maxChars: Int): String {
        if (!file.exists() || maxChars <= 0) return ""
        val maxBytes = (maxChars * 4L).coerceAtLeast(1L)
        val length = file.length()
        val bytesToRead = minOf(length, maxBytes).toInt()
        val bytes = ByteArray(bytesToRead)
        RandomAccessFile(file, "r").use { input ->
            input.seek((length - bytesToRead).coerceAtLeast(0L))
            input.readFully(bytes)
        }
        return String(bytes, Charsets.UTF_8).takeLast(maxChars).trim()
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }
}
