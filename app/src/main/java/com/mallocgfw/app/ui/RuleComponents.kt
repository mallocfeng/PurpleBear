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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.RuleTargetPolicy
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceKind
import com.mallocgfw.app.model.RuleSourceStatus
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
import com.mallocgfw.app.ui.theme.Warning
import com.mallocgfw.app.xray.GeoDataSnapshot
import com.mallocgfw.app.xray.GeoDataStatus

@Composable
internal fun RuleSourceSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = uiText(title),
            fontSize = TypeScale.CardTitle,
            lineHeight = TypeScale.CardTitleLine,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = uiText(subtitle),
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
    region: AppGeoRoutingRegion = AppGeoRoutingRegion.China,
) {
    // Outside China the home-sites list is the bundled Geo data: nothing to download.
    val geoData = RuleSourceManager.usesGeoData(source, region)
    val remote = source.sourceKind != RuleSourceKind.LocalText && !geoData
    val problem = source.status == RuleSourceStatus.FetchFailed || source.status == RuleSourceStatus.ParseFailed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = uiText(source.name),
                color = TextPrimary,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.systemDefault) {
                RuleSourceManager.builtInHint(source.id, region)?.let { hint ->
                    Text(
                        text = uiText(hint.first, hint.second, hint.third),
                        color = TextSecondary,
                        fontSize = TypeScale.Tiny,
                        lineHeight = TypeScale.TinyLine,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Text(
                text = buildList {
                    if (geoData) {
                        add(uiText("直连", "Direct", "Напрямую"))
                        add(uiText("内置 Geo 数据", "Built-in Geo data", "Встроенные Geo-данные"))
                        return@buildList
                    }
                    if (remote) {
                        add(
                            when (source.policy) {
                                RuleTargetPolicy.Proxy -> uiText("走代理", "Proxy", "Через прокси")
                                RuleTargetPolicy.Direct -> uiText("直连", "Direct", "Напрямую")
                                RuleTargetPolicy.Block -> uiText("拦截", "Block", "Блокировать")
                            },
                        )
                    }
                    if (problem && source.convertedRules > 0) {
                        add(uiText("仍用上次的 ${source.convertedRules} 条规则", "Still using the last ${source.convertedRules} rules", "Используются прежние правила: ${source.convertedRules}"))
                    } else if (source.status == RuleSourceStatus.Ready || source.convertedRules > 0) {
                        add(uiText("${source.convertedRules} 条规则", "${source.convertedRules} rules", "Правил: ${source.convertedRules}"))
                    } else if (!source.enabled) {
                        add(uiText("开启后下载", "Downloads when turned on", "Загрузится после включения"))
                    }
                    if (remote) {
                        if (source.status == RuleSourceStatus.Ready || source.convertedRules > 0) add(uiText(source.updatedAt))
                    } else {
                        add(uiText("本地文本"))
                    }
                }.joinToString(" · "),
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if ((updating || problem) && !geoData) {
            StatusPill(
                text = if (updating) "更新中" else source.status.displayName(),
                color = if (updating) Primary else source.status.accent(),
                background = (if (updating) Primary else source.status.accent()).copy(alpha = 0.12f),
                compact = true,
            )
        }
        if (remote) {
            CompactIconAction(
                icon = Icons.Rounded.Refresh,
                tint = if (updating) Primary else TextSecondary,
                onClick = onRefresh,
            )
        }
        TogglePill(checked = source.enabled, onClick = onToggleEnabled)
    }
}

/** Global proxy, Geo region and Geo data folded into one compact card. */
@Composable
internal fun geoRoutingRegionLabel(region: AppGeoRoutingRegion): String {
    return when (region) {
        AppGeoRoutingRegion.China -> uiText("中国（CN）")
        AppGeoRoutingRegion.English -> uiText("英语区域（US）")
        AppGeoRoutingRegion.Russia -> uiText("俄罗斯（RU）")
    }
}

@Composable
internal fun EmptyRulesCard() {
    SurfaceCard {
        Text(
            text = uiText("暂无自定义规则，点右下角 + 添加"),
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
        )
    }
}

@Composable
internal fun AddRuleFab(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = CircleShape,
        color = Primary,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = uiText("添加规则"),
                tint = AccentContentColor,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
