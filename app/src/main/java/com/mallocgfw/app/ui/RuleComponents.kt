package com.mallocgfw.app.ui

import android.graphics.drawable.Icon
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceKind
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.PrimaryStrong
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.Surface as SurfaceTone
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.GeoDataSnapshot
import com.mallocgfw.app.xray.GeoDataStatus

@Composable
internal fun RuleSourceSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = TypeScale.CardTitle,
            lineHeight = TypeScale.CardTitleLine,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun RuleSourceCard(
    source: RuleSourceItem,
    selected: Boolean,
    updating: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRefresh: () -> Unit,
) {
    SurfaceCard(
        compact = true,
        modifier = Modifier.clickable(onClick = onClick),
        border = if (selected) BorderStroke(1.dp, Secondary.copy(alpha = 0.35f)) else null,
        background = Brush.verticalGradient(
            colors = if (selected) listOf(SurfaceHigh, SurfaceLow) else listOf(SurfaceLow, SurfaceTone),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = if (source.systemDefault) "系统内置" else "用户添加",
                        color = if (source.systemDefault) Primary else Secondary,
                        background = if (source.systemDefault) Primary.copy(alpha = 0.14f) else Secondary.copy(alpha = 0.14f),
                        compact = true,
                    )
                    OutlinedActionChip(
                        if (source.sourceKind == RuleSourceKind.LocalText) "本地文本" else source.type.displayName(),
                        compact = true,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = source.name,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (source.sourceKind == RuleSourceKind.LocalText) {
                        "手动输入 Shadowrocket / Surge 文本规则"
                    } else {
                        source.url
                    },
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (source.sourceKind != RuleSourceKind.LocalText) {
                    Text(
                        text = "最近更新时间：${source.updatedAt}",
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = TypeScale.Tiny,
                        lineHeight = TypeScale.TinyLine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusPill(
                    text = if (updating) "更新中" else source.status.displayName(),
                    color = if (updating) Primary else source.status.accent(),
                    background = if (updating) Primary.copy(alpha = 0.14f) else source.status.accent().copy(alpha = 0.14f),
                    compact = true,
                )
                Spacer(modifier = Modifier.height(10.dp))
                TogglePill(checked = source.enabled, onClick = onToggleEnabled)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "共 ${source.totalRules} 条 · 已转换 ${source.convertedRules} 条 · 跳过 ${source.skippedRules} 条",
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.sourceKind != RuleSourceKind.LocalText) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                )
                {
                    Text(
                        text = if (updating) "处理中…" else "立即更新",
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.clickable(onClick = onRefresh),
                    )
                }
            }
        }
    }
}

@Composable
internal fun GeoDataCard(
    snapshot: GeoDataSnapshot,
    updating: Boolean,
    onRefresh: () -> Unit,
) {
    SurfaceCard(
        background = Brush.verticalGradient(listOf(SurfaceHigh, SurfaceLow)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = "基础资源",
                        color = Primary,
                        background = Primary.copy(alpha = 0.14f),
                    )
                    OutlinedActionChip("geoip.dat + geosite.dat", compact = true)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Geo 数据更新",
                    fontSize = TypeScale.CardTitle,
                    lineHeight = TypeScale.CardTitleLine,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "当前来源：${snapshot.sourceLabel}",
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "最近更新时间：${snapshot.updatedAt}",
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            StatusPill(
                text = when {
                    updating -> "更新中"
                    snapshot.status == GeoDataStatus.Ready -> "已就绪"
                    snapshot.status == GeoDataStatus.Failed -> "失败"
                    else -> "内置"
                },
                color = when {
                    updating -> Primary
                    snapshot.status == GeoDataStatus.Ready -> Success
                    snapshot.status == GeoDataStatus.Failed -> Error
                    else -> Secondary
                },
                background = when {
                    updating -> Primary.copy(alpha = 0.14f)
                    snapshot.status == GeoDataStatus.Ready -> Success.copy(alpha = 0.14f)
                    snapshot.status == GeoDataStatus.Failed -> Error.copy(alpha = 0.14f)
                    else -> Secondary.copy(alpha = 0.14f)
                },
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "geoip.dat ${formatBytes(snapshot.geoipBytes)} · geosite.dat ${formatBytes(snapshot.geositeBytes)}",
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
        )
        if (!snapshot.lastError.isNullOrBlank()) {
            Text(
                text = snapshot.lastError,
                color = Error,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = if (updating) "处理中…" else "更新资源",
                color = Primary,
                fontWeight = FontWeight.Bold,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                modifier = Modifier.clickable(onClick = onRefresh),
            )
        }
    }
}

@Composable
internal fun EmptyRulesCard() {
    SurfaceCard(compact = true) {
        Text(
            text = "还没有自定义规则源",
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = "可继续使用系统默认规则。",
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
internal fun AddRuleFab(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Primary, PrimaryStrong)))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.AddCircle,
                contentDescription = null,
                tint = AccentContentColor,
                modifier = Modifier.size(19.dp),
            )
            Text(
                "添加规则",
                color = AccentContentColor,
                fontWeight = FontWeight.Bold,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
            )
        }
    }
}
