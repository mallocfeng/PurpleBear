package com.mallocgfw.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.AppSettings
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.StreamingRouteSelection
import com.mallocgfw.app.model.normalizedAppVpnMtu
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.XrayCoreStatus
import java.util.Locale

@Composable
internal fun ActionListRow(
    text: String,
    icon: ImageVector,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBadge(icon = icon, modifier = Modifier.size(40.dp), innerPadding = 10.dp)
            Text(text, color = TextPrimary)
        }
        Icon(Icons.Rounded.MoreHoriz, contentDescription = null, tint = TextSecondary)
    }
}

@Composable
internal fun DotIndicator(active: Boolean) {
    Box(
        modifier = Modifier
            .width(if (active) 28.dp else 10.dp)
            .height(10.dp)
            .clip(CircleShape)
            .background(if (active) Primary else Color.White.copy(alpha = 0.14f)),
    )
}

@Composable
internal fun ModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Primary.copy(alpha = 0.18f) else SurfaceLow)
            .border(
                1.dp,
                if (selected) Primary.copy(alpha = 0.2f) else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(uiText(text), color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
internal fun mainScreenPadding(bottom: Dp = 32.dp): PaddingValues = screenPadding(
    top = 116.dp,
    bottom = bottom,
)

@Composable
internal fun screenPadding(
    top: Dp = 12.dp,
    bottom: Dp = 32.dp,
): PaddingValues {
    return PaddingValues(
        start = 18.dp,
        top = top,
        end = 18.dp,
        bottom = bottom,
    )
}

@Composable
internal fun rememberRetainedLazyListState(): LazyListState {
    return rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
}

internal fun proxyModeText(mode: ProxyMode): String {
    return when (mode) {
        ProxyMode.Smart -> "智能分流"
        ProxyMode.Global -> "全局代理"
        ProxyMode.PerApp -> "分应用代理"
    }
}

internal fun AppSettings.dnsSummary(): String {
    return when (dnsMode) {
        AppDnsMode.System -> "系统 DNS"
        AppDnsMode.Remote -> "远端 DNS 1.1.1.1"
        AppDnsMode.Custom -> customDnsValue.ifBlank { "手动输入" }
    }
}

internal fun AppSettings.heartbeatSummary(): String {
    val minutes = heartbeatIntervalMinutes.takeIf { it in HeartbeatIntervalOptionsMinutes } ?: 5
    return "每 $minutes 分钟检测一次，连续 3 次失败切换备用节点。"
}

internal fun AppSettings.vpnMtuSummary(): String {
    return "当前 ${normalizedAppVpnMtu(vpnMtu)}，更低值可改善移动网、PPPoE 和 QUIC 节点稳定性。"
}

@ExperimentalGetImage
internal fun processQrFrame(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onDetected: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val inputImage = InputImage.fromMediaImage(
        mediaImage,
        imageProxy.imageInfo.rotationDegrees,
    )
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val rawValue = barcodes.firstNotNullOfOrNull { barcode ->
                barcode.rawValue?.takeIf { it.isNotBlank() }
            }
            if (rawValue != null) {
                onDetected(rawValue)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

internal data class QrImageCandidate(
    val rawValue: String,
    val boundingBox: Rect?,
)

internal fun processQrImage(
    context: Context,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageUri: Uri,
    onDetected: (List<QrImageCandidate>) -> Unit,
    onFailure: (Throwable?) -> Unit,
) {
    val inputImage = runCatching {
        InputImage.fromFilePath(context, imageUri)
    }.getOrElse { error ->
        onFailure(error)
        return
    }
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val detections = barcodes.mapNotNull { barcode ->
                val rawValue = barcode.rawValue?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QrImageCandidate(
                    rawValue = rawValue,
                    boundingBox = barcode.boundingBox,
                )
            }
            if (detections.isNotEmpty()) {
                onDetected(detections)
            } else {
                processQrImageFallback(
                    context = context,
                    scanner = scanner,
                    imageUri = imageUri,
                    onDetected = onDetected,
                    onFailure = onFailure,
                )
            }
        }
        .addOnFailureListener { error ->
            processQrImageFallback(
                context = context,
                scanner = scanner,
                imageUri = imageUri,
                onDetected = onDetected,
                onFailure = onFailure,
                originalError = error,
            )
        }
}

private fun processQrImageFallback(
    context: Context,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageUri: Uri,
    onDetected: (List<QrImageCandidate>) -> Unit,
    onFailure: (Throwable?) -> Unit,
    originalError: Throwable? = null,
) {
    val bitmap = runCatching {
        loadBitmapFromUri(context, imageUri)
    }.getOrElse { error ->
        onFailure(originalError ?: error)
        return
    }
    val regions = buildQrFallbackRegions(bitmap.width, bitmap.height)
    val detectedByValue = LinkedHashMap<String, QrImageCandidate>()

    fun finish() {
        if (detectedByValue.isNotEmpty()) {
            onDetected(detectedByValue.values.toList())
        } else {
            onFailure(originalError)
        }
    }

    fun scanRegion(index: Int) {
        if (index >= regions.size) {
            finish()
            return
        }
        val region = regions[index]
        val regionBitmap = if (region.left == 0 && region.top == 0 && region.width == bitmap.width && region.height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, region.left, region.top, region.width, region.height)
        }
        scanner.process(InputImage.fromBitmap(regionBitmap, 0))
            .addOnSuccessListener { barcodes ->
                barcodes.forEach { barcode ->
                    val rawValue = barcode.rawValue?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (detectedByValue.containsKey(rawValue)) return@forEach
                    val adjustedBox = barcode.boundingBox?.let { bounds ->
                        Rect(
                            region.left + bounds.left,
                            region.top + bounds.top,
                            region.left + bounds.right,
                            region.top + bounds.bottom,
                        )
                    }
                    detectedByValue[rawValue] = QrImageCandidate(
                        rawValue = rawValue,
                        boundingBox = adjustedBox,
                    )
                }
            }
            .addOnCompleteListener {
                if (regionBitmap !== bitmap) {
                    regionBitmap.recycle()
                }
                scanRegion(index + 1)
            }
    }

    scanRegion(0)
}

private data class QrFallbackRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

private fun buildQrFallbackRegions(
    imageWidth: Int,
    imageHeight: Int,
): List<QrFallbackRegion> {
    val regions = linkedSetOf<QrFallbackRegion>()

    fun addRegion(left: Int, top: Int, width: Int, height: Int) {
        val safeWidth = width.coerceIn(1, imageWidth)
        val safeHeight = height.coerceIn(1, imageHeight)
        val safeLeft = left.coerceIn(0, imageWidth - safeWidth)
        val safeTop = top.coerceIn(0, imageHeight - safeHeight)
        regions += QrFallbackRegion(safeLeft, safeTop, safeWidth, safeHeight)
    }

    addRegion(0, 0, imageWidth, imageHeight)
    addRegion(0, 0, imageWidth * 2 / 3, imageHeight)
    addRegion(imageWidth / 3, 0, imageWidth * 2 / 3, imageHeight)
    addRegion(0, 0, imageWidth, imageHeight * 2 / 3)
    addRegion(0, imageHeight / 3, imageWidth, imageHeight * 2 / 3)
    addRegion(0, 0, imageWidth * 3 / 4, imageHeight * 3 / 4)
    addRegion(imageWidth / 4, 0, imageWidth * 3 / 4, imageHeight * 3 / 4)
    addRegion(0, imageHeight / 4, imageWidth * 3 / 4, imageHeight * 3 / 4)
    addRegion(imageWidth / 4, imageHeight / 4, imageWidth * 3 / 4, imageHeight * 3 / 4)

    val overlapX = imageWidth / 10
    val overlapY = imageHeight / 10
    val halfWidth = imageWidth / 2 + overlapX
    val halfHeight = imageHeight / 2 + overlapY
    addRegion(0, 0, halfWidth, halfHeight)
    addRegion(imageWidth / 2 - overlapX, 0, halfWidth, halfHeight)
    addRegion(0, imageHeight / 2 - overlapY, halfWidth, halfHeight)
    addRegion(imageWidth / 2 - overlapX, imageHeight / 2 - overlapY, halfWidth, halfHeight)

    return regions.toList()
}

internal fun loadBitmapFromUri(
    context: Context,
    imageUri: Uri,
    maxDimensionPx: Int = 2_048,
): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, imageUri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(
                calculateBitmapSampleSize(
                    width = info.size.width,
                    height = info.size.height,
                    maxDimensionPx = maxDimensionPx,
                ),
            )
            decoder.isMutableRequired = false
        }
    }
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimensionPx = maxDimensionPx,
        )
    }
    val decodedBitmap = context.contentResolver.openInputStream(imageUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, decodeOptions)
    }
    if (decodedBitmap != null) {
        return decodedBitmap
    }
    @Suppress("DEPRECATION")
    return MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
}

private fun calculateBitmapSampleSize(
    width: Int,
    height: Int,
    maxDimensionPx: Int,
): Int {
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    while (width / sampleSize > maxDimensionPx || height / sampleSize > maxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}

internal fun XrayCoreStatus.label(): String {
    return when (this) {
        XrayCoreStatus.Idle -> "未初始化"
        XrayCoreStatus.Preparing -> "准备中"
        XrayCoreStatus.Ready -> "已就绪"
        XrayCoreStatus.Starting -> "启动中"
        XrayCoreStatus.Running -> "运行中"
        XrayCoreStatus.Failed -> "失败"
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

internal fun formatRelativeSyncTime(
    updatedAtMs: Long?,
    fallback: String,
    nowMs: Long,
): String {
    val ts = updatedAtMs ?: return fallback
    val deltaSeconds = ((nowMs - ts).coerceAtLeast(0L)) / 1_000L
    return when {
        deltaSeconds < 60L -> "${deltaSeconds}s"
        deltaSeconds < 3_600L -> "${deltaSeconds / 60L}m"
        deltaSeconds < 86_400L -> "${deltaSeconds / 3_600L}h"
        else -> "${deltaSeconds / 86_400L}d"
    }
}

internal fun mediaRoutingSelectionLabel(
    serviceId: String,
    selections: List<StreamingRouteSelection>,
    currentServer: ServerNode?,
    servers: List<ServerNode>,
): String {
    val customServerId = selections.firstOrNull { it.serviceId == serviceId }?.serverId.orEmpty()
    if (customServerId.isBlank()) {
        return currentServer?.name ?: "默认节点"
    }
    return servers.firstOrNull { it.id == customServerId }?.name
        ?: currentServer?.takeIf { it.id == customServerId }?.name
        ?: "默认节点"
}

internal data class FeatureAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

internal data class ServerSection(
    val group: ServerGroup,
    val servers: List<ServerNode>,
    val hiddenUnsupportedNodeCount: Int = 0,
)

internal fun Context.resolveDisplayName(uri: Uri): String? {
    return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
}
