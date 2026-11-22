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
) {
    val remote = source.sourceKind != RuleSourceKind.LocalText
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
                RuleSourceManager.builtInHint(source.id)?.let { hint ->
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
                    if (remote) {
                        add(
                            when (source.policy) {
                                RuleTargetPolicy.Proxy -> uiText("走代理", "Proxy", "Через прокси")
                                RuleTargetPolicy.Direct -> uiText("直连", "Direct", "Напрямую")
                                RuleTargetPolicy.Block -> uiText("拦截", "Block", "Блокировать")
                            },
                        )
                    }
                    if (source.status == RuleSourceStatus.Ready || source.convertedRules > 0) {
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
        if (updating || problem) {
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
internal fun RoutingOverviewCard(
    globalProxyEnabled: Boolean,
    onGlobalProxyChange: (Boolean) -> Unit,
    geoDataSnapshot: GeoDataSnapshot,
    geoDataUpdating: Boolean,
    geoRoutingRegion: AppGeoRoutingRegion,
    onGeoRoutingRegionChange: (AppGeoRoutingRegion) -> Unit,
    onRefreshGeoData: () -> Unit,
) {
    var regionMenu by remember { mutableStateOf(false) }
    GroupedCard {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = uiText("代理模式"),
                color = TextPrimary,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.SemiBold,
            )
            SegmentedControl(
                options = listOf(
                    false to uiText("规则分流"),
                    true to uiText("全局代理"),
                ),
                selected = globalProxyEnabled,
                onSelect = onGlobalProxyChange,
            )
            Text(
                text = if (globalProxyEnabled) {
                    uiText("除局域网外，所有流量都走代理。下方规则暂不生效。")
                } else {
                    geoRoutingModeDescription(geoRoutingRegion)
                },
                color = TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
            )
        }
        if (!globalProxyEnabled) {
            GroupDivider()
            Box {
                ListRow(
                    title = "直连地区",
                    value = geoRoutingRegionLabel(geoRoutingRegion),
                    icon = Icons.Rounded.Place,
                    onClick = { regionMenu = true },
                )
                DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                    AppGeoRoutingRegion.entries.forEach { region ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(geoRoutingRegionLabel(region), color = TextPrimary, fontWeight = FontWeight.Medium)
                                    Text(
                                        geoRoutingRegionSubtitle(region),
                                        color = TextSecondary,
                                        fontSize = TypeScale.Tiny,
                                        lineHeight = TypeScale.TinyLine,
                                    )
                                }
                            },
                            onClick = {
                                regionMenu = false
                                onGeoRoutingRegionChange(region)
                            },
                        )
                    }
                }
            }
        }
        GroupDivider(startIndent = 58.dp)
        ListRow(
            title = "Geo 数据",
            subtitle = geoDataSnapshot.lastError?.takeIf { it.isNotBlank() }
                ?: "geoip ${formatBytes(geoDataSnapshot.geoipBytes)} · geosite ${formatBytes(geoDataSnapshot.geositeBytes)}",
            icon = Icons.Rounded.Storage,
            trailing = {
                TextAction(
                    text = if (geoDataUpdating) "更新中…" else "更新",
                    enabled = !geoDataUpdating,
                    onClick = onRefreshGeoData,
                )
            },
        )
    }
}

@Composable
private fun geoRoutingModeDescription(region: AppGeoRoutingRegion): String {
    return when (region) {
        AppGeoRoutingRegion.China -> uiText("国内网站和直连规则不走代理，其余走代理。")
        AppGeoRoutingRegion.English -> uiText("美国网站和直连规则不走代理，其余走代理。")
        AppGeoRoutingRegion.Russia -> uiText("俄罗斯网站和直连规则不走代理，其余走代理。")
    }
}

@Composable
private fun GeoRoutingRegionDropdown(
    selectedRegion: AppGeoRoutingRegion,
    onRegionChange: (AppGeoRoutingRegion) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = uiText("Geo 分流国家"),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
        )
        Text(
            text = geoRoutingRegionSubtitle(selectedRegion),
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
        )
        Box {
            Box(modifier = Modifier.clickable { expanded = true }) {
                OutlinedActionChip(geoRoutingRegionLabel(selectedRegion))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                AppGeoRoutingRegion.entries.forEach { region ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = geoRoutingRegionLabel(region),
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = geoRoutingRegionSubtitle(region),
                                    color = TextSecondary,
                                    fontSize = TypeScale.Meta,
                                    lineHeight = TypeScale.MetaLine,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onRegionChange(region)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun geoRoutingRegionLabel(region: AppGeoRoutingRegion): String {
    return when (region) {
        AppGeoRoutingRegion.China -> uiText("中国（CN）")
        AppGeoRoutingRegion.English -> uiText("英语区域（US）")
        AppGeoRoutingRegion.Russia -> uiText("俄罗斯（RU）")
    }
}

@Composable
private fun geoRoutingRegionSubtitle(region: AppGeoRoutingRegion): String {
    return when (region) {
        AppGeoRoutingRegion.China -> uiText("直连 geosite:cn 与 geoip:cn。")
        AppGeoRoutingRegion.English -> uiText("直连 geoip:us；EN 不是 Geo 国家码。")
        AppGeoRoutingRegion.Russia -> uiText("直连 geosite:category-ru 与 geoip:ru。")
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
