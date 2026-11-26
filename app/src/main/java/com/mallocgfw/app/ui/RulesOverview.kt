package com.mallocgfw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.RuleSourceStatus
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.Surface as SurfaceTone
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.ui.theme.Warning
import com.mallocgfw.app.xray.GeoDataSnapshot

/**
 * The rules tab: the mode switch, then every routing layer as one line, in
 * the order connections are matched (top wins). Lists of rule sources live
 * on their own pages, so this page stays a short overview.
 */
@Composable
internal fun RulesScreen(
    padding: PaddingValues,
    listState: LazyListState,
    ruleSources: List<RuleSourceItem>,
    geoDataSnapshot: GeoDataSnapshot,
    geoDataUpdating: Boolean,
    geoDataUpdatedNotice: Boolean,
    geoRoutingRegion: AppGeoRoutingRegion,
    globalProxyEnabled: Boolean,
    ruleMessage: String?,
    onGlobalProxyChange: (Boolean) -> Unit,
    onGeoRoutingRegionChange: (AppGeoRoutingRegion) -> Unit,
    onRefreshGeoData: () -> Unit,
    onToggleEnabled: (String) -> Unit,
    ruleAnalysisInFlight: Boolean,
    ruleAnalysisShowing: Boolean,
    onAnalyzeRules: () -> Unit,
    perAppEnabled: Boolean,
    streamingRoutingEnabled: Boolean,
    onOpenTopRule: () -> Unit,
    onOpenPerApp: () -> Unit,
    onOpenCustomRules: () -> Unit,
    onOpenMediaRouting: () -> Unit,
    onOpenDefaultRules: () -> Unit,
    fallbackDirect: Boolean,
    onFallbackDirectChange: (Boolean) -> Unit,
) {
    val topRule = ruleSources.firstOrNull(RuleSourceManager::isTopRule)
    val customSources = ruleSources.filterNot { it.systemDefault || RuleSourceManager.isTopRule(it) }
    val defaultSources = ruleSources.filter { it.systemDefault }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = listState,
        contentPadding = mainScreenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "rule-analysis") {
            RuleAnalysisSlider(
                analyzing = ruleAnalysisInFlight,
                engaged = ruleAnalysisInFlight || ruleAnalysisShowing,
                onComplete = onAnalyzeRules,
            )
        }
        item(key = "mode") {
            // On the bare page the default grey track matches the background:
            // use the card colour instead, so it reads like the cards below.
            SegmentedControl(
                options = listOf(
                    false to uiText("规则分流"),
                    true to uiText("全局代理"),
                ),
                selected = globalProxyEnabled,
                onSelect = onGlobalProxyChange,
                trackColor = SurfaceTone,
            )
        }
        if (!ruleMessage.isNullOrBlank()) {
            item { NoteBox(text = ruleMessage) }
        }
        item(key = "order-caption") {
            Text(
                text = uiText("匹配顺序 · 越靠上越优先", "Matching order · top wins", "Порядок проверки · верхние важнее"),
                color = TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
        }
        item(key = "chain") {
            GroupedCard {
                ChainRow(
                    icon = Icons.Rounded.PriorityHigh,
                    iconTint = Warning,
                    title = uiText("最高优先级规则", "Top-priority rules", "Высший приоритет"),
                    value = when {
                        topRule == null -> ChainValue(uiText("未设置", "Not set", "Не задано"))
                        !topRule.enabled -> ChainValue(uiText("已停用", "Off", "Выкл."))
                        topRule.hasProblem() -> ChainValue(uiText("有错误", "Error", "Ошибка"), Error)
                        else -> ChainValue(uiText("${topRule.convertedRules} 条", "${topRule.convertedRules} rules", "Правил: ${topRule.convertedRules}"), Warning)
                    },
                    onClick = onOpenTopRule,
                    trailing = topRule?.let { rule -> { TogglePill(checked = rule.enabled, onClick = { onToggleEnabled(rule.id) }) } },
                )
                ChainDivider()
                ChainRow(
                    icon = Icons.Rounded.Apps,
                    title = uiText("分应用代理", "Per-app proxy", "По приложениям"),
                    // Global mode: every app uses the VPN, but an app's own node still applies.
                    value = if (perAppEnabled && globalProxyEnabled) {
                        ChainValue(uiText("部分生效", "Partly", "Частично"), Warning)
                    } else {
                        onOff(perAppEnabled)
                    },
                    onClick = onOpenPerApp,
                )
                ChainDivider()
                ChainRow(
                    icon = Icons.Rounded.Description,
                    title = uiText("自定义规则", "Custom rules", "Свои правила"),
                    value = sourcesValue(customSources, emptyText = uiText("未添加", "None", "Нет")),
                    onClick = onOpenCustomRules,
                    skipped = globalProxyEnabled,
                )
                ChainDivider()
                ChainRow(
                    icon = Icons.Rounded.LiveTv,
                    title = uiText("流媒体分流", "Media routing", "Медиа"),
                    value = onOff(streamingRoutingEnabled),
                    onClick = onOpenMediaRouting,
                    skipped = globalProxyEnabled,
                )
                ChainDivider()
                ChainRow(
                    icon = Icons.Rounded.Inventory,
                    title = uiText("默认规则", "Default rules", "По умолчанию"),
                    value = sourcesValue(defaultSources, emptyText = uiText("无", "None", "Нет")),
                    onClick = onOpenDefaultRules,
                    skipped = globalProxyEnabled,
                )
                ChainDivider()
                RegionChainRow(
                    region = geoRoutingRegion,
                    skipped = globalProxyEnabled,
                    onRegionChange = onGeoRoutingRegionChange,
                    geoData = geoDataSnapshot,
                    geoDataUpdating = geoDataUpdating,
                    geoDataJustUpdated = geoDataUpdatedNotice,
                    onRefreshGeoData = onRefreshGeoData,
                )
                ChainDivider()
                FallbackChainRow(
                    direct = fallbackDirect,
                    globalProxyEnabled = globalProxyEnabled,
                    onChange = onFallbackDirectChange,
                )
            }
        }
    }
}

private data class ChainValue(val text: String, val color: Color? = null)

@Composable
private fun onOff(enabled: Boolean): ChainValue =
    if (enabled) ChainValue(uiText("已启用", "On", "Вкл."), Success) else ChainValue(uiText("关闭", "Off", "Выкл."))

/** "3/5 开启", or a warning when a list failed to download. */
@Composable
private fun sourcesValue(sources: List<RuleSourceItem>, emptyText: String): ChainValue {
    if (sources.isEmpty()) return ChainValue(emptyText)
    if (sources.any { it.enabled && it.hasProblem() }) return ChainValue(uiText("有错误", "Error", "Ошибка"), Error)
    val on = sources.count { it.enabled }
    return ChainValue(
        uiText("$on/${sources.size} 开启", "$on/${sources.size} on", "Вкл.: $on/${sources.size}"),
        if (on > 0) Success else null,
    )
}

private fun RuleSourceItem.hasProblem(): Boolean =
    status == RuleSourceStatus.FetchFailed || status == RuleSourceStatus.ParseFailed

@Composable
private fun ChainDivider() = GroupDivider(startIndent = 58.dp)

/**
 * One routing layer: icon, name and state on a single line. Same metrics as
 * [ListRow] (the Me tab), so lists across the app line up.
 */
@Composable
private fun ChainRow(
    icon: ImageVector,
    title: String,
    value: ChainValue,
    onClick: (() -> Unit)?,
    iconTint: Color = Primary,
    skipped: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .alpha(if (skipped) 0.4f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowIcon(icon = icon, tint = iconTint)
            Text(
                text = title,
                color = TextPrimary,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (skipped) uiText("跳过", "Skipped", "Пропуск") else value.text,
                color = if (skipped) TextSecondary else value.color ?: TextSecondary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                maxLines = 1,
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp),
            )
        } else {
            Spacer(Modifier.width(13.dp))
        }
    }
}

/**
 * The direct region: matched after the rule lists; tap to pick another in a
 * sheet. The Geo data it is built from lives at the bottom of that sheet.
 */
@Composable
private fun RegionChainRow(
    region: AppGeoRoutingRegion,
    skipped: Boolean,
    onRegionChange: (AppGeoRoutingRegion) -> Unit,
    geoData: GeoDataSnapshot,
    geoDataUpdating: Boolean,
    geoDataJustUpdated: Boolean,
    onRefreshGeoData: () -> Unit,
) {
    var sheet by remember { mutableStateOf(false) }
    ChainRow(
        icon = Icons.Rounded.Place,
        iconTint = Success,
        title = uiText("直连地区", "Direct region", "Регион напрямую"),
        value = ChainValue(geoRoutingRegionLabel(region)),
        onClick = { sheet = true },
        skipped = skipped,
    )
    if (sheet) {
        OptionSheet(
            title = uiText("直连地区", "Direct region", "Регион напрямую"),
            subtitle = uiText(
                "这个地区的网站和 IP 不走代理，直接联网。",
                "Sites and IPs in this region skip the proxy and connect directly.",
                "Сайты и IP этого региона идут напрямую, без прокси.",
            ),
            options = AppGeoRoutingRegion.entries.map { option ->
                SheetOption(
                    value = option,
                    title = geoRoutingRegionLabel(option),
                    description = regionDescription(option),
                    emoji = regionFlag(option),
                )
            },
            selected = region,
            onDismiss = { sheet = false },
            onSelect = onRegionChange,
            footer = {
                GeoDataCard(
                    snapshot = geoData,
                    updating = geoDataUpdating,
                    justUpdated = geoDataJustUpdated,
                    onRefresh = onRefreshGeoData,
                )
            },
        )
    }
}

@Composable
private fun regionDescription(region: AppGeoRoutingRegion): String = when (region) {
    AppGeoRoutingRegion.China -> uiText("中国大陆的网站和 IP 直连", "Sites and IPs in mainland China go direct", "Сайты и IP Китая идут напрямую")
    AppGeoRoutingRegion.English -> uiText("美国的 IP 直连", "IPs in the United States go direct", "IP США идут напрямую")
    AppGeoRoutingRegion.Russia -> uiText("俄罗斯的网站和 IP 直连", "Sites and IPs in Russia go direct", "Сайты и IP России идут напрямую")
}

private fun regionFlag(region: AppGeoRoutingRegion): String = when (region) {
    AppGeoRoutingRegion.China -> "\uD83C\uDDE8\uD83C\uDDF3"
    AppGeoRoutingRegion.English -> "\uD83C\uDDFA\uD83C\uDDF8"
    AppGeoRoutingRegion.Russia -> "\uD83C\uDDF7\uD83C\uDDFA"
}

/**
 * Where traffic no rule matched goes: proxy or direct, picked in a sheet.
 * Global mode sends everything through the proxy, so there is nothing to pick.
 */
@Composable
private fun FallbackChainRow(
    direct: Boolean,
    globalProxyEnabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var sheet by remember { mutableStateOf(false) }
    val proxyText = uiText("走代理", "Proxy", "Прокси")
    val directText = uiText("直连", "Direct", "Напрямую")
    val showDirect = direct && !globalProxyEnabled
    ChainRow(
        icon = if (showDirect) Icons.Rounded.Home else Icons.Rounded.VpnLock,
        iconTint = if (showDirect) Success else Primary,
        title = uiText("其余流量", "Everything else", "Остальное"),
        value = if (showDirect) ChainValue(directText, Success) else ChainValue(proxyText, Primary),
        onClick = if (globalProxyEnabled) null else ({ sheet = true }),
    )
    if (sheet) {
        OptionSheet(
            title = uiText("其余流量", "Everything else", "Остальное"),
            subtitle = uiText(
                "上面的规则都没命中的连接，最后走这里。",
                "Connections that no rule above matched end up here.",
                "Сюда попадают соединения, не совпавшие ни с одним правилом выше.",
            ),
            options = listOf(
                SheetOption(
                    value = false,
                    title = proxyText,
                    description = uiText("通过当前节点访问", "Go through the current node", "Через текущий узел"),
                    icon = Icons.Rounded.VpnLock,
                ),
                SheetOption(
                    value = true,
                    title = directText,
                    description = uiText("不经过代理，直接联网", "Connect directly, without the proxy", "Напрямую, без прокси"),
                    icon = Icons.Rounded.Home,
                    iconTint = Success,
                ),
            ),
            selected = direct,
            onDismiss = { sheet = false },
            onSelect = onChange,
        )
    }
}

/** Geo data size, update state and button, at the bottom of the region sheet. */
@Composable
private fun GeoDataCard(snapshot: GeoDataSnapshot, updating: Boolean, justUpdated: Boolean, onRefresh: () -> Unit) {
    val error = snapshot.lastError?.takeIf { it.isNotBlank() }
    val sizes = "geoip ${formatBytes(snapshot.geoipBytes)} · geosite ${formatBytes(snapshot.geositeBytes)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ControlSurfaceColor.copy(alpha = 0.55f))
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(icon = Icons.Rounded.Storage, tint = TextSecondary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = uiText("Geo 数据", "Geo data", "Geo-данные"),
                color = TextPrimary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    // A failed update keeps the data already on the phone.
                    error != null -> uiText(
                        "更新失败，继续使用现有数据",
                        "Update failed; the current data stays in use",
                        "Не удалось обновить; используются текущие данные",
                    ) + " · " + sizes
                    justUpdated -> uiText("已更新", "Updated", "Обновлено") + " · " + sizes
                    else -> sizes
                },
                color = when {
                    error != null -> Error
                    justUpdated -> Success
                    else -> TextSecondary
                },
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Primary.copy(alpha = if (updating) 0.08f else 0.16f))
                .clickable(enabled = !updating, onClick = onRefresh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (updating) {
                CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
            Text(
                text = if (updating) uiText("更新中…") else uiText("更新"),
                color = Primary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rule list pages
// ---------------------------------------------------------------------------

/** The user's own rule lists (text and URL), with an add button. */
@Composable
internal fun CustomRulesScreen(
    padding: PaddingValues,
    sources: List<RuleSourceItem>,
    selectedRuleSourceId: String,
    updatingIds: Set<String>,
    globalProxyEnabled: Boolean,
    onBack: () -> Unit,
    onSelectSource: (String) -> Unit,
    onToggleEnabled: (String) -> Unit,
    onRefreshSource: (String) -> Unit,
    onAddTextSource: () -> Unit,
    onAddSource: () -> Unit,
) {
    var addMenu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberRetainedLazyListState(),
            contentPadding = screenPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { AppTopBar(title = "自定义规则", subtitle = uiText("按列表顺序匹配", "Matched in list order", "Проверяются по порядку"), onBack = onBack) }
            if (globalProxyEnabled) {
                item { SkippedInGlobalNote() }
            }
            item {
                if (sources.isEmpty()) {
                    EmptyRulesCard()
                } else {
                    GroupedCard {
                        sources.forEachIndexed { index, source ->
                            if (index > 0) GroupDivider()
                            RuleSourceCard(
                                source = source,
                                selected = selectedRuleSourceId == source.id,
                                updating = updatingIds.contains(source.id),
                                onClick = { onSelectSource(source.id) },
                                onToggleEnabled = { onToggleEnabled(source.id) },
                                onRefresh = { onRefreshSource(source.id) },
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 12.dp + LocalBottomBarInset.current),
        ) {
            AddRuleFab(onClick = { addMenu = true })
        }
    }
    if (addMenu) {
        OptionSheet(
            title = uiText("添加自定义规则", "Add custom rules", "Добавить правила"),
            options = listOf(
                SheetOption(
                    value = false,
                    title = uiText("手动文本规则"),
                    description = uiText("直接输入或粘贴规则", "Type or paste rules yourself", "Ввести или вставить правила"),
                    icon = Icons.Rounded.EditNote,
                ),
                SheetOption(
                    value = true,
                    title = uiText("添加规则 URL"),
                    description = uiText("订阅远程规则列表，可以随时更新", "Subscribe to a remote rule list you can update", "Подписка на удалённый список правил"),
                    icon = Icons.Rounded.Link,
                ),
            ),
            onDismiss = { addMenu = false },
            onSelect = { url -> if (url) onAddSource() else onAddTextSource() },
        )
    }
}

/** The built-in rule lists, each with its switch. */
@Composable
internal fun DefaultRulesScreen(
    padding: PaddingValues,
    sources: List<RuleSourceItem>,
    selectedRuleSourceId: String,
    updatingIds: Set<String>,
    region: AppGeoRoutingRegion,
    globalProxyEnabled: Boolean,
    onBack: () -> Unit,
    onSelectSource: (String) -> Unit,
    onToggleEnabled: (String) -> Unit,
    onRefreshSource: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { AppTopBar(title = "默认规则", subtitle = uiText("内置规则列表", "Built-in rule lists", "Встроенные списки"), onBack = onBack) }
        if (globalProxyEnabled) {
            item { SkippedInGlobalNote() }
        }
        item {
            GroupedCard {
                sources.forEachIndexed { index, source ->
                    if (index > 0) GroupDivider()
                    RuleSourceCard(
                        source = source,
                        selected = selectedRuleSourceId == source.id,
                        updating = updatingIds.contains(source.id),
                        onClick = { onSelectSource(source.id) },
                        onToggleEnabled = { onToggleEnabled(source.id) },
                        onRefresh = { onRefreshSource(source.id) },
                        region = region,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkippedInGlobalNote() {
    NoteBox(
        text = uiText(
            "全局代理模式下，这些规则暂不生效。",
            "These rules are paused in global proxy mode.",
            "В режиме глобального прокси эти правила не действуют.",
        ),
    )
}
