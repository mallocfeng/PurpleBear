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
        DiagnosticStatus.NoNode -> TextSecondary
    }
    SurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiText(step.title),
                    fontSize = TypeScale.ListTitle,
                    lineHeight = TypeScale.ListTitleLine,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    uiText(step.detail),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            StatusPill(
                text = when (step.status) {
                    DiagnosticStatus.Success -> uiText("通过")
                    DiagnosticStatus.Pending -> uiText("检测中")
                    DiagnosticStatus.Failed -> uiText("失败")
                    DiagnosticStatus.NoNode -> uiText("暂无节点")
                },
                color = accent,
                background = accent.copy(alpha = 0.14f),
            )
        }
    }
}

@Composable
internal fun FeatureList(items: List<FeatureAction>) {
    GroupedCard {
        items.forEachIndexed { index, action ->
            if (index > 0) GroupDivider(startIndent = 56.dp)
            FeatureListRow(action = action)
        }
    }
}

@Composable
internal fun FeatureListRow(action: FeatureAction) {
    ListRow(
        title = action.title,
        value = action.subtitle,
        icon = action.icon,
        onClick = action.onClick,
    )
}

/** A white rounded container for iOS-style grouped rows. */
@Composable
internal fun GroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(SurfaceTone),
        content = content,
    )
}

@Composable
internal fun GroupDivider(startIndent: Dp = 16.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.6.dp)
            .background(DividerColor),
    )
}

/** Small caption shown above a grouped card, like iOS section headers. */
@Composable
internal fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = uiText(text),
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Tinted rounded-square icon used at the start of list rows. */
@Composable
internal fun RowIcon(
    icon: ImageVector,
    tint: Color = Primary,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = if (IsLightTheme) 0.12f else 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Standard grouped-list row: icon · title/subtitle · value · chevron or trailing. */
@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = TextSecondary,
    icon: ImageVector? = null,
    iconTint: Color = Primary,
    titleColor: Color = TextPrimary,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    badge: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 11.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) RowIcon(icon = icon, tint = iconTint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = uiText(title),
                color = if (enabled) titleColor else TextSecondary,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = uiText(subtitle),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (badge) RedDot()
        if (!value.isNullOrBlank()) {
            Text(
                text = uiText(value),
                color = valueColor,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null && showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        SurfaceCard(content = content)
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
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            SettingTitle(title)
            SettingSubtitle(subtitle)
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
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            SettingTitle(title)
            SettingSubtitle(subtitle)
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
    badge: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingTitle(title)
                if (badge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    RedDot()
                }
            }
            SettingSubtitle(subtitle)
        }
        TextAction(text = actionText, enabled = actionEnabled, onClick = onAction)
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
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            SettingTitle(title)
            SettingSubtitle(subtitle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextAction(text = primaryActionText, onClick = onPrimaryAction)
            TextAction(text = secondaryActionText, onClick = onSecondaryAction)
        }
    }
}

@Composable
internal fun SettingInfoRow(
    title: String,
    subtitle: String,
    ) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        SettingTitle(title)
        SettingSubtitle(subtitle)
    }
}

@Composable
private fun SettingTitle(title: String) {
    Text(
        text = uiText(title),
        color = TextPrimary,
        fontSize = TypeScale.ListTitle,
        lineHeight = TypeScale.ListTitleLine,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SettingSubtitle(subtitle: String) {
    if (subtitle.isBlank()) return
    Text(
        text = uiText(subtitle),
        color = TextSecondary,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Compact tonal text button used at the end of setting rows. */
@Composable
internal fun TextAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = uiText(text),
        color = if (enabled) Primary else TextSecondary,
        fontWeight = FontWeight.SemiBold,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (enabled) Primary.copy(alpha = if (IsLightTheme) 0.10f else 0.18f) else ControlSurfaceColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
internal fun MediaRoutingServiceRow(
    service: StreamingMediaService,
    enabled: Boolean,
    selectedNodeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ControlSurfaceColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            Text(
                uiText(service.suggestedRegion),
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = uiText(selectedNodeLabel),
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
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) ControlSurfaceColor else ControlSurfaceColor.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = uiText(title),
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextPrimary else TextSecondary,
            )
            Text(
                text = uiText(subtitle),
                color = TextSecondary,
            )
        }
        if (selected) {
            StatusPill(
                text = uiText("已选"),
                color = Primary,
                background = Primary.copy(alpha = 0.16f),
            )
        } else if (!enabled) {
            StatusPill(
                text = uiText(disabledLabel),
                color = TextSecondary,
                background = ControlSurfaceTrackColor,
            )
        } else {
            OutlinedActionChip(uiText("选择"))
        }
    }
}

@Composable
internal fun SearchField(
    search: String,
    placeholder: String = "搜索服务器、地区或协议…",
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = search,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = TextPrimary,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
        ),
        cursorBrush = SolidColor(Primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(SurfaceTone),
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
                    modifier = Modifier.size(19.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (search.isEmpty()) {
                        Text(
                            text = uiText(placeholder),
                            color = TextSecondary,
                            fontSize = TypeScale.Body,
                            lineHeight = TypeScale.BodyLine,
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "all" to uiText("全部", "All", "Все"),
            "asia" to uiText("亚洲"),
            "latency" to uiText("低延迟"),
            "favorites" to uiText("收藏"),
        ).forEach { (value, label) ->
            ChoiceChip(text = label, selected = filter == value, onClick = { onFilterChange(value) })
        }
    }
}

/** Pill chip: solid accent when selected, white otherwise. */
@Composable
internal fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = if (selected) AccentContentColor else TextPrimary,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Primary else SurfaceTone)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Mutually exclusive options in one track; the active segment is filled with
 * the accent so it is obvious which one is on.
 */
@Composable
internal fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Track colour: [SurfaceLow] inside a card; the card colour on the bare page. */
    trackColor: Color = SurfaceLow,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(trackColor)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                text = label,
                color = if (active) AccentContentColor else TextSecondary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Primary else Color.Transparent)
                    .clickable { if (!active) onSelect(value) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}

internal val CardRadius = 20.dp

/**
 * Plain card: solid surface on the neutral canvas. [background] is only for
 * the rare highlighted card; leave it null for the standard look.
 */
@Composable
internal fun SurfaceCard(
    modifier: Modifier = Modifier,
    background: Brush? = null,
    border: BorderStroke? = null,
    compact: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(CardRadius),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(background ?: SolidColor(SurfaceTone), shape)
                .padding(if (compact) 12.dp else 16.dp),
            content = content,
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
                    text = uiText(title),
                    fontSize = TypeScale.CardTitle,
                    lineHeight = TypeScale.CardTitleLine,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(uiText(subtitle), color = TextSecondary)
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
            Eyebrow(uiText(label))
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
                text = uiText(label),
                color = TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = uiText(value),
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
                text = when (selection) {
                    "未设置", "不支持" -> uiText(selection)
                    else -> selection
                },
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
    if (server.mihomoProxyJson.isNotBlank()) {
        rows += "运行内核" to "mihomo（兼容内核）"
        runCatching { flattenJsonParameters(prefix = "配置", value = JSONObject(server.mihomoProxyJson), rows = rows) }
        return rows
    }
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
    Text(
        text = uiText(text),
        color = TextSecondary,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ControlSurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
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
            .clip(RoundedCornerShape(16.dp))
            .background(PendingBannerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = uiText("待切换节点"),
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
            )
            Text(
                text = serverName,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextAction(text = "去切换", onClick = onClick)
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
    // Three buttons in one row truncate their labels; give the primary
    // action its own full-width row and pair the secondary ones below.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactPrimaryActionButton(
            text = primaryText,
            enabled = true,
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = AccentContentColor,
            disabledContainerColor = ControlSurfaceStrongColor,
            disabledContentColor = TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            text = uiText(text),
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun CompactOutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ControlSurfaceStrongColor,
            contentColor = TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            text = uiText(text),
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            fontWeight = FontWeight.SemiBold,
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
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = AccentContentColor,
        ),
    ) {
        Text(uiText(text), fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Body)
    }
}

@Composable
internal fun OutlinedActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ControlSurfaceStrongColor,
            contentColor = TextPrimary,
            disabledContainerColor = ControlSurfaceColor,
            disabledContentColor = TextSecondary,
        ),
    ) {
        Text(uiText(text), fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Body)
    }
}

@Composable
internal fun StatusPill(
    text: String,
    color: Color,
    background: Color,
    compact: Boolean = false,
) {
    Text(
        text = uiText(text),
        color = color,
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 5.dp),
    )
}

@Composable
internal fun OutlinedActionChip(
    text: String,
    compact: Boolean = false,
) {
    Text(
        text = uiText(text),
        color = TextPrimary,
        fontWeight = FontWeight.Medium,
        fontSize = if (compact) TypeScale.Tiny else TypeScale.Meta,
        lineHeight = if (compact) TypeScale.TinyLine else TypeScale.MetaLine,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(ControlSurfaceColor)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 3.dp else 6.dp),
    )
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
            text = uiText(text),
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
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "toggle-thumb-offset",
    )
    Box(
        modifier = Modifier
            .width(51.dp)
            .height(31.dp)
            .clip(CircleShape)
            .background(if (checked) Primary else ControlSurfaceTrackColor)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(27.dp)
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
    RowIcon(icon = icon)
}

/** Small red "something new here" dot. */
@Composable
internal fun RedDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(Color(0xFFFF3B30)),
    )
}

@Composable
internal fun IconButtonCard(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = TextPrimary,
    background: Color = SurfaceTone,
    borderColor: Color = Color.Transparent,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
internal fun CompactIconAction(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = tint.alpha * 0.35f),
            modifier = Modifier.size(19.dp),
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
            text = uiText(text),
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
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = uiText(title),
            fontSize = TypeScale.SectionTitle,
            lineHeight = TypeScale.SectionTitleLine,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = uiText(subtitle),
                color = TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                textAlign = align,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(
        text = uiText(text),
        color = TextSecondary,
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
        fontWeight = FontWeight.Medium,
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
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun TwoColumnInfoGrid(
    items: List<Pair<String, String>>,
    compact: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { (label, value) ->
                    InfoMini(label = label, value = value, modifier = Modifier.weight(1f))
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

