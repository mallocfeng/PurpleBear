package com.mallocgfw.app.ui

import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
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
