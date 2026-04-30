package com.mallocgfw.app.ui

import android.graphics.drawable.Icon
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mallocgfw.app.model.DiagnosticStatus
import com.mallocgfw.app.model.DiagnosticStep
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.StreamingMediaService
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.PrimaryStrong
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.Surface as SurfaceTone
import com.mallocgfw.app.ui.theme.SurfaceBright
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun DiagnosticsScreenPadding(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
internal fun DiagnosticCard(step: DiagnosticStep) {
    val accent = when (step.status) {
        DiagnosticStatus.Success -> Success
        DiagnosticStatus.Pending -> Primary
        DiagnosticStatus.Failed -> Error
    }
    SurfaceCard(
        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.title,
                    fontSize = TypeScale.CardTitle,
                    lineHeight = TypeScale.CardTitleLine,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(step.detail, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            StatusPill(
                text = when (step.status) {
                    DiagnosticStatus.Success -> "通过"
                    DiagnosticStatus.Pending -> "检测中"
                    DiagnosticStatus.Failed -> "失败"
                },
                color = accent,
                background = accent.copy(alpha = 0.14f),
            )
        }
    }
}

@Composable
internal fun FeatureList(items: List<FeatureAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { action ->
            FeatureListRow(action = action)
        }
    }
}

@Composable
internal fun FeatureListRow(action: FeatureAction) {
    SurfaceCard(
        modifier = Modifier.clickable(onClick = action.onClick),
        compact = true,
        shape = RoundedCornerShape(16.dp),
        background = Brush.verticalGradient(listOf(SurfaceLow, SurfaceLow)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ControlSurfaceStrongColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    action.icon,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = action.title,
                    color = TextPrimary,
                    fontSize = TypeScale.ListTitle,
                    lineHeight = TypeScale.ListTitleLine,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action.subtitle,
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun PromoCard(
    title: String,
    subtitle: String,
    buttonText: String?,
    onClick: () -> Unit,
) {
    SurfaceCard(
        background = Brush.linearGradient(
            listOf(PromoGradientStartColor, PromoGradientEndColor),
        ),
    ) {
        Eyebrow("解锁超低延迟")
        Text(
            text = title,
            fontSize = TypeScale.CardTitle,
            lineHeight = TypeScale.CardTitleLine,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(subtitle, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
        if (buttonText != null) {
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryActionButton(text = buttonText, onClick = onClick)
        }
    }
}

@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    SurfaceCard {
        Text(
            text = title,
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
            )
            Text(subtitle, color = TextSecondary)
        }
        TogglePill(checked = checked)
    }
}

@Composable
internal fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
            )
            Text(subtitle, color = TextSecondary)
        }
        TogglePill(checked = checked, onClick = onToggle)
    }
}

@Composable
internal fun SettingActionRow(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 18.dp)) {
            Text(
                text = title,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
            )
            Text(subtitle, color = TextSecondary)
        }
        Text(
            text = actionText,
            color = if (actionEnabled) Primary else TextSecondary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(enabled = actionEnabled, onClick = onAction),
        )
    }
}

@Composable
internal fun SettingDualActionRow(
    title: String,
    subtitle: String,
    primaryActionText: String,
    onPrimaryAction: () -> Unit,
    secondaryActionText: String,
    onSecondaryAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
            )
            Text(subtitle, color = TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = primaryActionText,
                color = Primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onPrimaryAction),
            )
            Text(
                text = secondaryActionText,
                color = Primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onSecondaryAction),
            )
        }
    }
}

@Composable
internal fun SettingInfoRow(
    title: String,
    subtitle: String,
    ) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = TypeScale.ListTitle,
            lineHeight = TypeScale.ListTitleLine,
            fontWeight = FontWeight.Bold,
        )
        Text(subtitle, color = TextSecondary)
    }
}

@Composable
internal fun MediaRoutingServiceRow(
    service: StreamingMediaService,
    enabled: Boolean,
    selectedNodeLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiniIcon(Icons.Rounded.LiveTv)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = service.name,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
            )
            Text("${service.subtitle} · ${service.suggestedRegion}", color = TextSecondary)
        }
        Text(
            text = selectedNodeLabel,
            color = if (enabled) TextPrimary else TextSecondary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(112.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun MediaRoutingNodeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    disabledLabel: String = "不可用",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.035f else 0.018f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextPrimary else TextSecondary,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
            )
        }
        if (selected) {
            StatusPill(
                text = "已选",
                color = Primary,
                background = Primary.copy(alpha = 0.16f),
            )
        } else if (!enabled) {
            StatusPill(
                text = disabledLabel,
                color = TextSecondary,
                background = ControlSurfaceTrackColor,
            )
        } else {
            OutlinedActionChip("选择")
        }
    }
}

@Composable
internal fun SearchField(
    search: String,
    placeholder: String = "搜索服务器、地区或协议…",
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = search,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = TextPrimary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(Primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, Primary.copy(alpha = 0.10f), shape),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (search.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TextSecondary,
                            fontSize = TypeScale.Meta,
                            lineHeight = TypeScale.MetaLine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
internal fun FilterRow(
    filter: String,
    onFilterChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(
            "all" to "所有位置",
            "asia" to "亚洲",
            "latency" to "低延迟",
            "favorites" to "收藏",
        ).forEach { (value, label) ->
            FilterChip(
                selected = filter == value,
                onClick = { onFilterChange(value) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary.copy(alpha = 0.18f),
                    selectedLabelColor = TextPrimary,
                    containerColor = SurfaceLow,
                    labelColor = TextPrimary,
                ),
            )
        }
    }
}

@Composable
internal fun SurfaceCard(
    modifier: Modifier = Modifier,
    background: Brush = Brush.verticalGradient(listOf(SurfaceLow, SurfaceTone)),
    border: BorderStroke? = null,
    compact: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
        border = border ?: if (IsLightTheme) BorderStroke(0.8.dp, CardOutlineColor) else null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(background, shape)
                .padding(if (compact) 12.dp else 20.dp),
            content = content,
        )
    }
}

@Composable
internal fun HomeMetricsCard(
    durationValue: String,
    downloadRate: String,
    downloadSubtitle: String,
    uploadRate: String,
    uploadSubtitle: String,
    latencyTesting: Boolean,
    onRetestLatency: () -> Unit,
) {
    SurfaceCard(
        shape = RoundedCornerShape(28.dp),
        background = Brush.verticalGradient(listOf(SurfaceLow, SurfaceTone)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Eyebrow("连接时长")
                    CompactIconAction(
                        icon = Icons.Rounded.Sync,
                        tint = if (latencyTesting) Primary else TextSecondary,
                        onClick = onRetestLatency,
                    )
                }
                Text(
                    text = durationValue,
                    fontSize = TypeScale.LargeMetric,
                    lineHeight = TypeScale.LargeMetricLine,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CompactMetricRow(
                    label = "节点下载",
                    value = downloadRate,
                    subtitle = downloadSubtitle,
                    modifier = Modifier.weight(1f),
                )
                CompactMetricRow(
                    label = "节点上传",
                    value = uploadRate,
                    subtitle = uploadSubtitle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun CompactMetricRow(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Eyebrow(label)
        Text(
            text = value,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = TypeScale.Tiny,
            lineHeight = TypeScale.TinyLine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun InfoRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
) {
    SurfaceCard {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBadge(icon = icon, modifier = Modifier.size(48.dp), innerPadding = 12.dp)
            Column {
                Text(
                    text = title,
                    fontSize = TypeScale.CardTitle,
                    lineHeight = TypeScale.CardTitleLine,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(subtitle, color = TextSecondary)
            }
        }
    }
}

@Composable
internal fun DetailRow(
    label: String,
    value: String,
    trailing: String,
    trailingClickable: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Eyebrow(label)
            Text(
                text = value,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
            )
        }
        if (trailingClickable != null) {
            Text(
                text = trailing,
                color = Primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = trailingClickable),
            )
        } else {
            OutlinedActionChip(trailing)
        }
    }
}

@Composable
internal fun DetailMenuRow(
    label: String,
    value: String,
    selection: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, top = 12.dp, end = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 18.dp)) {
            Text(
                text = label,
                color = TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextPrimary else TextSecondary,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = selection,
                color = if (enabled) Primary else TextSecondary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 168.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = if (enabled) Primary else TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun ParameterReadRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(label)
        SelectionContainer {
            Text(
                text = value.ifBlank { "--" },
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        }
    }
}

internal fun buildNodeParameterRows(server: ServerNode): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    rows += "名称" to server.name
    rows += "协议类型" to server.protocol
    rows += "安全层" to server.security
    rows += "传输层" to server.transport
    rows += "接入地址" to "${server.address}:${server.port}"
    rows += "来源" to server.subscription
    server.flow.takeIf { it.isNotBlank() }?.let { rows += "Flow / 附加字段" to it }
    server.rawUri.takeIf { it.isNotBlank() }?.let { rows += "分享链接" to it }
    if (server.outboundJson.isBlank()) {
        return rows
    }
    return runCatching {
        val outbound = JSONObject(server.outboundJson)
        flattenJsonParameters(prefix = "配置", value = outbound, rows = rows)
        rows
    }.getOrElse {
        rows + ("配置 JSON" to server.outboundJson)
    }
}

internal fun flattenJsonParameters(
    prefix: String,
    value: Any?,
    rows: MutableList<Pair<String, String>>,
) {
    when (value) {
        null -> return
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            if (keys.isEmpty()) {
                rows += prefix to "{}"
                return
            }
            keys.forEach { key ->
                flattenJsonParameters("$prefix.$key", value.opt(key), rows)
            }
        }

        is JSONArray -> {
            if (value.length() == 0) {
                rows += prefix to "[]"
                return
            }
            for (index in 0 until value.length()) {
                flattenJsonParameters("$prefix[$index]", value.opt(index), rows)
            }
        }

        else -> rows += prefix to value.toString()
    }
}

@Composable
internal fun NoteBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ControlSurfaceColor)
            .padding(16.dp),
    ) {
        Text(text, color = TextSecondary)
    }
}

@Composable
internal fun PendingSwitchBanner(
    serverName: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(PendingBannerColor)
            .border(1.dp, Primary.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "已选中待切换节点",
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = serverName,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "去首页切换",
            color = Primary,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
internal fun ButtonRow(
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit,
    tertiaryText: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CompactPrimaryActionButton(
            text = primaryText,
            enabled = true,
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
        )
        CompactOutlinedActionButton(
            text = secondaryText,
            onClick = onSecondary,
            modifier = Modifier.weight(1f),
        )
        if (tertiaryText != null && onTertiary != null) {
            CompactOutlinedActionButton(
                text = tertiaryText,
                onClick = onTertiary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun CompactButtonRow(
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit,
    primaryEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CompactPrimaryActionButton(
            text = primaryText,
            enabled = primaryEnabled,
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
        )
        CompactOutlinedActionButton(
            text = secondaryText,
            onClick = onSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun CompactPrimaryActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = AccentContentColor,
            disabledContainerColor = ControlSurfaceStrongColor,
            disabledContentColor = TextSecondary,
        ),
        contentPadding = PaddingValues(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    if (enabled) {
                        Brush.linearGradient(listOf(Primary, PrimaryStrong))
                    } else {
                        Brush.linearGradient(listOf(ControlSurfaceStrongColor, ControlSurfaceStrongColor))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CompactOutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        border = BorderStroke(1.dp, Primary.copy(alpha = 0.16f)),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            text = text,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = AccentContentColor,
        ),
        contentPadding = PaddingValues(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Brush.linearGradient(listOf(Primary, PrimaryStrong))),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
internal fun OutlinedActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        border = BorderStroke(1.dp, Primary.copy(alpha = 0.16f)),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatusPill(
    text: String,
    color: Color,
    background: Color,
    compact: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 5.dp else 8.dp,
            ),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = if (compact) TypeScale.Tiny else TypeScale.Meta,
            lineHeight = if (compact) TypeScale.TinyLine else TypeScale.MetaLine,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OutlinedActionChip(
    text: String,
    compact: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, Primary.copy(alpha = 0.16f), CircleShape)
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 6.dp else 7.dp,
            ),
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) TypeScale.Tiny else TypeScale.Meta,
            lineHeight = if (compact) TypeScale.TinyLine else TypeScale.MetaLine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DetectActionChip(
    text: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
        )
    }
}

@Composable
internal fun TogglePill(
    checked: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "toggle-thumb-offset",
    )
    Box(
        modifier = Modifier
            .width(50.dp)
            .clip(CircleShape)
            .background(if (checked) Primary.copy(alpha = 0.75f) else ControlSurfaceTrackColor)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
internal fun GlassBadge(
    icon: ImageVector,
    modifier: Modifier,
    innerPadding: Dp,
    gradient: Brush = Brush.linearGradient(listOf(Primary.copy(alpha = 0.18f), Primary.copy(alpha = 0.08f))),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .border(1.dp, ControlSurfaceColor, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(innerPadding),
        )
    }
}

@Composable
internal fun MiniIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ControlSurfaceStrongColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary)
    }
}

@Composable
internal fun IconButtonCard(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = TextPrimary,
    background: Color = ControlSurfaceColor,
    borderColor: Color = Color.Transparent,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
internal fun CompactIconAction(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
internal fun FloatingTag(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceBright.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
        Text(text, color = TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SmallPill(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(ControlSurfaceColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String,
    align: TextAlign = TextAlign.Start,
) {
    Column {
        Text(
            text = title,
            fontSize = TypeScale.PageTitle,
            lineHeight = TypeScale.PageTitleLine,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            textAlign = align,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    )
}

@Composable
internal fun InfoMini(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    Column(modifier = modifier) {
        Eyebrow(label)
        Text(
            text = value,
            fontSize = TypeScale.CardTitle,
            lineHeight = TypeScale.CardTitleLine,
            fontWeight = FontWeight.ExtraBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TwoColumnInfoGrid(
    items: List<Pair<String, String>>,
    compact: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowItems.forEach { (label, value) ->
                    Box(modifier = Modifier.weight(1f)) {
                        SurfaceCard(
                            compact = compact,
                            background = Brush.verticalGradient(listOf(ControlSurfaceStrongColor, ControlSurfaceColor)),
                        ) {
                            Eyebrow(label)
                            Text(
                                text = value,
                                fontSize = if (compact) TypeScale.Body else TypeScale.CardTitle,
                                lineHeight = if (compact) TypeScale.BodyLine else TypeScale.CardTitleLine,
                                fontWeight = if (compact) FontWeight.Bold else FontWeight.ExtraBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
