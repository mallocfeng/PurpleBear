package com.mallocgfw.app.ui

import androidx.compose.foundation.background
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.model.RuleUrlPresets
import com.mallocgfw.app.model.RuleTextHelper
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.model.RuleTargetPolicy
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.RuleSourceKind
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.RuleSourceDetailSummary
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceType
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.GeoDataSnapshot

@Composable
internal fun RulesScreen(
    padding: PaddingValues,
    listState: LazyListState,
    ruleSources: List<RuleSourceItem>,
    selectedRuleSourceId: String,
    geoDataSnapshot: GeoDataSnapshot,
    geoDataUpdating: Boolean,
    geoRoutingRegion: AppGeoRoutingRegion,
    globalProxyEnabled: Boolean,
    ruleMessage: String?,
    updatingIds: Set<String>,
    onGlobalProxyChange: (Boolean) -> Unit,
    onGeoRoutingRegionChange: (AppGeoRoutingRegion) -> Unit,
    onRefreshGeoData: () -> Unit,
    onSelectSource: (String) -> Unit,
    onAddSource: () -> Unit,
    onAddTextSource: () -> Unit,
    onToggleEnabled: (String) -> Unit,
    onRefreshSource: (String) -> Unit,
) {
    val defaultSources = ruleSources.filter { it.systemDefault }
    val customSources = ruleSources.filterNot { it.systemDefault }
    var selectedCustomKind by remember { mutableStateOf(RuleSourceKind.LocalText) }
    var showAddRuleMenu by remember { mutableStateOf(false) }
    val displayedCustomSources = customSources.filter { it.sourceKind == selectedCustomKind }
    // Global mode skips every rule; dim them so the page shows they are idle.
    val rulesAlpha = if (globalProxyEnabled) 0.45f else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = mainScreenPadding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "routing-card") {
                RoutingOverviewCard(
                    globalProxyEnabled = globalProxyEnabled,
                    onGlobalProxyChange = onGlobalProxyChange,
                    geoDataSnapshot = geoDataSnapshot,
                    geoDataUpdating = geoDataUpdating,
                    geoRoutingRegion = geoRoutingRegion,
                    onGeoRoutingRegionChange = onGeoRoutingRegionChange,
                    onRefreshGeoData = onRefreshGeoData,
                )
            }
            if (!ruleMessage.isNullOrBlank()) {
                item { NoteBox(text = ruleMessage) }
            }
            item {
                SectionLabel(
                    text = "默认规则",
                    trailing = if (globalProxyEnabled) {
                        { InactiveRulesHint() }
                    } else {
                        null
                    },
                )
            }
            item(key = "default-rules") {
                GroupedCard(modifier = Modifier.alpha(rulesAlpha)) {
                    defaultSources.forEachIndexed { index, source ->
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
            item {
                SectionLabel(
                    text = "自定义规则",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceChip(
                                text = uiText("文本"),
                                selected = selectedCustomKind == RuleSourceKind.LocalText,
                                onClick = { selectedCustomKind = RuleSourceKind.LocalText },
                            )
                            ChoiceChip(
                                text = "URL",
                                selected = selectedCustomKind == RuleSourceKind.RemoteUrl,
                                onClick = { selectedCustomKind = RuleSourceKind.RemoteUrl },
                            )
                        }
                    },
                )
            }
            item(key = "custom-rules") {
                if (displayedCustomSources.isEmpty()) {
                    EmptyRulesCard()
                } else {
                    GroupedCard(modifier = Modifier.alpha(rulesAlpha)) {
                        displayedCustomSources.forEachIndexed { index, source ->
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
            AddRuleFab(
                onClick = { showAddRuleMenu = true },
            )
            DropdownMenu(
                expanded = showAddRuleMenu,
                onDismissRequest = { showAddRuleMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(uiText("手动文本规则")) },
                    onClick = {
                        showAddRuleMenu = false
                        onAddTextSource()
                    },
                )
                DropdownMenuItem(
                    text = { Text(uiText("添加规则 URL")) },
                    onClick = {
                        showAddRuleMenu = false
                        onAddSource()
                    },
                )
            }
        }
    }
}

@Composable
internal fun RuleSourceDetailScreen(
    padding: PaddingValues,
    summary: RuleSourceDetailSummary?,
    updating: Boolean,
    message: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "规则详情", subtitle = "规则来源", onBack = onBack)
        }
        item {
            ScreenHeader(
                title = summary?.metadata?.name ?: "未选择规则源",
                subtitle = "查看来源和转换结果。",
            )
        }
        item {
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Eyebrow("规则类型")
                        Text(
                            uiText(summary?.metadata?.type?.displayName() ?: "--"),
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            uiText(if (summary?.metadata?.sourceKind == RuleSourceKind.LocalText) {
                                "本地文本规则"
                            } else {
                                summary?.metadata?.url ?: "暂无 URL"
                            }),
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 8.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusPill(
                        text = if (updating) "更新中" else summary?.metadata?.status?.displayName() ?: "未选择",
                        color = if (updating) Primary else summary?.metadata?.status?.accent() ?: TextSecondary,
                        background = if (updating) Primary.copy(alpha = 0.14f) else (summary?.metadata?.status?.accent()
                            ?: TextSecondary).copy(alpha = 0.14f),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                val isLocalText = summary?.metadata?.sourceKind == RuleSourceKind.LocalText
                TwoColumnInfoGrid(
                    items = buildList {
                        if (!isLocalText) {
                            add("最后更新时间" to (summary?.metadata?.updatedAt?.let { uiText(it) } ?: "--"))
                        }
                        addAll(listOf(
                        "本地规则条数" to "${summary?.metadata?.totalRules ?: 0}",
                        "成功转换数" to "${summary?.metadata?.convertedRules ?: 0}",
                        "跳过数" to "${summary?.metadata?.skippedRules ?: 0}",
                        ))
                    },
                )
            }
        }
        item {
            SurfaceCard {
                DetailRow(
                    label = if (summary?.metadata?.sourceKind == RuleSourceKind.LocalText) "规则内容" else "完整 URL",
                    value = if (summary?.metadata?.sourceKind == RuleSourceKind.LocalText) {
                        summary.metadata.content.lineSequence().firstOrNull { it.isNotBlank() } ?: uiText("暂无")
                    } else {
                        summary?.fullUrl ?: uiText("暂无")
                    },
                    trailing = summary?.metadata?.type?.displayName() ?: "--",
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(
                    label = "域名规则",
                    value = uiText("${summary?.domainRuleCount ?: 0} 条", "${summary?.domainRuleCount ?: 0} rules", "Правил: ${summary?.domainRuleCount ?: 0}"),
                    trailing = "IP ${summary?.ipRuleCount ?: 0}",
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(
                    label = "最近错误",
                    value = summary?.metadata?.lastError ?: uiText("最近一次更新没有错误。"),
                    trailing = if (summary?.metadata?.systemDefault == true) "系统内置" else "用户添加",
                )
            }
        }
        if (!message.isNullOrBlank()) {
            item { NoteBox(text = message) }
        }
        item {
            if (summary?.metadata?.sourceKind == RuleSourceKind.LocalText) {
                ButtonRow(
                    primaryText = "编辑文本规则",
                    onPrimary = onEdit,
                    secondaryText = "删除规则源",
                    onSecondary = { showDeleteDialog = true },
                )
            } else {
                ButtonRow(
                    primaryText = if (updating) "正在更新…" else "立即更新",
                    onPrimary = onRefresh,
                    secondaryText = "编辑名称或 URL",
                    onSecondary = onEdit,
                    tertiaryText = if (summary?.metadata?.systemDefault == true) "恢复默认" else "删除规则源",
                    onTertiary = { showDeleteDialog = true },
                )
            }
        }
    }

    if (showDeleteDialog && summary != null) {
        ConfirmDeleteDialog(
            title = if (summary.metadata.systemDefault) "恢复默认规则" else "删除规则源",
            message = if (summary.metadata.systemDefault) {
                uiText("确认把“${summary.metadata.name}”恢复为系统默认状态？已缓存的规则统计会被清空。", "Reset \"${summary.metadata.name}\" to its default state? Cached rule stats will be cleared.", "Сбросить «${summary.metadata.name}» к состоянию по умолчанию? Кэшированная статистика правил будет очищена.")
            } else {
                uiText("确认删除“${summary.metadata.name}”？本地保存的转换规则也会一起移除。", "Delete \"${summary.metadata.name}\"? Its locally saved converted rules will also be removed.", "Удалить «${summary.metadata.name}»? Локально сохранённые преобразованные правила тоже будут удалены.")
            },
            confirmText = if (summary.metadata.systemDefault) "恢复默认" else "删除",
            onConfirm = {
                onDelete()
                showDeleteDialog = false
                onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun ruleFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedContainerColor = SurfaceHigh,
    unfocusedContainerColor = SurfaceHigh,
    focusedBorderColor = Primary.copy(alpha = 0.3f),
    unfocusedBorderColor = Color.Transparent,
    cursorColor = Primary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
)

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine, fontWeight = FontWeight.Bold)
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun RuleTextHelper.Match.label(): String = when (this) {
    RuleTextHelper.Match.Suffix -> uiText("整个网站", "Whole site", "Весь сайт")
    RuleTextHelper.Match.Exact -> uiText("仅此域名", "This domain only", "Только этот домен")
    RuleTextHelper.Match.Keyword -> uiText("包含关键词", "Contains keyword", "Содержит слово")
    RuleTextHelper.Match.IpRange -> uiText("IP / IP 段", "IP / range", "IP / диапазон")
}

@Composable
private fun RuleTextHelper.Action.label(): String = when (this) {
    RuleTextHelper.Action.Proxy -> uiText("走代理", "Proxy", "Через прокси")
    RuleTextHelper.Action.Direct -> uiText("直连", "Direct", "Напрямую")
    RuleTextHelper.Action.Reject -> uiText("拦截", "Block", "Блокировать")
}

@Composable
private fun RuleTargetPolicy.label(): String = when (this) {
    RuleTargetPolicy.Proxy -> uiText("走代理", "Proxy", "Через прокси")
    RuleTargetPolicy.Direct -> uiText("直连", "Direct", "Напрямую")
    RuleTargetPolicy.Block -> uiText("拦截", "Block", "Блокировать")
}

@Composable
private fun RuleTextHelper.LineView.describe(): String = when (kind) {
    RuleTextHelper.Kind.Suffix -> uiText("$target 及其所有子域名", "$target and all its subdomains", "$target и все поддомены")
    RuleTextHelper.Kind.Exact -> uiText("仅 $target", "Only $target", "Только $target")
    RuleTextHelper.Kind.Keyword -> uiText("域名包含“$target”", "Domains containing \"$target\"", "Домены со словом «$target»")
    RuleTextHelper.Kind.IpRange -> uiText("IP 段 $target", "IP range $target", "Диапазон IP $target")
    RuleTextHelper.Kind.GeoIp -> if (target.equals("CN", ignoreCase = true)) {
        uiText("中国的 IP", "IPs in China", "IP Китая")
    } else {
        uiText("$target 的 IP", "IPs in $target", "IP региона $target")
    }
    RuleTextHelper.Kind.Regex -> uiText("符合正则 $target", "Matching pattern $target", "По шаблону $target")
    RuleTextHelper.Kind.Other -> raw
}

private val templateLabels = mapOf(
    "ai" to Triple("AI 服务走代理", "AI services via proxy", "ИИ-сервисы через прокси"),
    "google" to Triple("Google 与 YouTube 走代理", "Google & YouTube via proxy", "Google и YouTube через прокси"),
    "telegram" to Triple("Telegram 走代理", "Telegram via proxy", "Telegram через прокси"),
    "github" to Triple("GitHub 走代理", "GitHub via proxy", "GitHub через прокси"),
    "apple" to Triple("苹果服务直连", "Apple services direct", "Сервисы Apple напрямую"),
    "cn" to Triple("中国 IP 直连", "China IPs direct", "IP Китая напрямую"),
)

/** Chip whose unselected state is still visible on a card. */
@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) AccentContentColor else TextPrimary,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Primary else SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** "+ something" chip that adds a set of rules. */
@Composable
private fun AddChip(text: String, onClick: () -> Unit) {
    Text(
        text = "+ $text",
        color = Primary,
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private enum class NodePickerPurpose { InsertIntoText, QuickAdd }

private sealed interface LinkCheck {
    data object Idle : LinkCheck
    data object Checking : LinkCheck
    data class Ok(val rules: Int, val skipped: Int) : LinkCheck
    data class Failed(val message: String) : LinkCheck
}

@Composable
internal fun AddRuleSourceScreen(
    padding: PaddingValues,
    editingSource: RuleSourceItem?,
    sourceKind: RuleSourceKind,
    servers: List<ServerNode>,
    onBack: () -> Unit,
    onSubmit: (String, String, RuleSourceType, RuleSourceKind, RuleTargetPolicy) -> Unit,
) {
    val effectiveKind = editingSource?.sourceKind ?: sourceKind
    val isText = effectiveKind == RuleSourceKind.LocalText
    var name by remember(editingSource?.id) { mutableStateOf(editingSource?.name.orEmpty()) }
    var input by remember(editingSource?.id, effectiveKind) {
        mutableStateOf(TextFieldValue(if (isText) editingSource?.content.orEmpty() else editingSource?.url.orEmpty()))
    }
    var type by remember(editingSource?.id) { mutableStateOf(editingSource?.type ?: RuleSourceType.Auto) }
    var policy by remember(editingSource?.id) { mutableStateOf(editingSource?.policy ?: RuleTargetPolicy.Proxy) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var nodePicker by remember { mutableStateOf<NodePickerPurpose?>(null) }
    val visibleServers = servers.filterNot { it.hiddenUnsupported }
    val language = LocalAppLanguage.current

    fun updateInput(value: TextFieldValue) {
        input = value
        saveError = null
    }

    // Text rules: live check and a readable list.
    val validation = remember(input.text, type, isText) {
        if (isText && input.text.isNotBlank()) RuleSourceManager.validateTextRules(input.text, type) else null
    }
    val errorLines = validation?.errorLineNumbers.orEmpty()
    val lineViews = remember(input.text, isText) { if (isText) RuleTextHelper.describe(input.text) else emptyList() }
    val missingNodeLabels = remember(input.text, visibleServers) {
        if (isText) RuleSourceManager.missingNodePolicyLabels(input.text, visibleServers) else emptyList()
    }
    var advancedOpen by remember(editingSource?.id) { mutableStateOf(false) }
    var templateMessage by remember { mutableStateOf<String?>(null) }

    // Quick add.
    var quickTarget by remember { mutableStateOf("") }
    var quickMatch by remember { mutableStateOf(RuleTextHelper.Match.Suffix) }
    var quickAction by remember { mutableStateOf<RuleTextHelper.Action?>(RuleTextHelper.Action.Proxy) }
    var quickNode by remember { mutableStateOf<ServerNode?>(null) }
    val quickLine = RuleTextHelper.buildLine(quickTarget, quickMatch, quickAction, quickNode?.let(RuleSourceManager::nodePolicyToken))

    // URL rules.
    val scope = rememberCoroutineScope()
    var linkCheck by remember { mutableStateOf<LinkCheck>(LinkCheck.Idle) }
    val normalizedUrl = RuleUrlPresets.normalizeUrl(input.text)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(
                title = if (editingSource == null) "新增规则源" else "编辑规则源",
                subtitle = if (isText) "本地文本规则" else "规则来源",
                onBack = onBack,
            )
        }
        item {
            ScreenHeader(
                title = when {
                    isText && editingSource == null -> uiText("添加文本规则")
                    isText -> uiText("编辑文本规则")
                    editingSource == null -> uiText("添加规则 URL")
                    else -> uiText("修改规则 URL")
                },
                subtitle = if (isText) {
                    uiText(
                        "告诉 App 哪些网站走代理、哪些直连或拦截。用“快速添加”或模板就行，不需要懂规则格式。",
                        "Tell the app which sites use the proxy, go direct or are blocked. Use Quick add or a template; no rule syntax needed.",
                        "Укажите, какие сайты идут через прокси, напрямую или блокируются. Используйте быстрое добавление или шаблон — знать синтаксис не нужно.",
                    )
                } else {
                    uiText(
                        "订阅一份别人维护的规则列表，会自动定期更新。可以直接选下面的常用规则源。",
                        "Subscribe to a rule list someone maintains; it updates automatically. Pick a common source below to start.",
                        "Подпишитесь на поддерживаемый список правил — он обновляется сам. Можно выбрать готовый источник ниже.",
                    )
                },
            )
        }
        item {
            SurfaceCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    textStyle = compactInputTextStyle(),
                    label = { CompactInputLabel("规则名称") },
                    placeholder = {
                        CompactInputPlaceholder(uiText("可不填，会自动命名", "Optional; named automatically", "Необязательно, назовём сами"))
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ruleFieldColors(),
                    singleLine = true,
                )
                if (!isText) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            updateInput(it)
                            linkCheck = LinkCheck.Idle
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = compactInputTextStyle(),
                        minLines = 2,
                        maxLines = 4,
                        label = { CompactInputLabel("规则 URL") },
                        placeholder = { CompactInputPlaceholder("https://raw.githubusercontent.com/...") },
                        shape = RoundedCornerShape(14.dp),
                        colors = ruleFieldColors(),
                    )
                    if (normalizedUrl != input.text.trim() && normalizedUrl.isNotBlank()) {
                        SectionHint(uiText("GitHub 网页链接会自动换成原始文件链接：$normalizedUrl", "GitHub page links are switched to the raw file: $normalizedUrl", "Ссылка на страницу GitHub заменится прямой ссылкой: $normalizedUrl"))
                    }
                }
            }
        }

        if (isText) {
            // Quick add: one rule from a link, domain or IP.
            item {
                SurfaceCard {
                    SectionTitle(uiText("快速添加一条规则", "Quick add a rule", "Быстро добавить правило"))
                    SectionHint(uiText("粘贴网址、域名或 IP，选好怎么处理，点“添加”。", "Paste a link, domain or IP, choose what to do, tap Add.", "Вставьте ссылку, домен или IP, выберите действие и нажмите «Добавить»."))
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = quickTarget,
                        onValueChange = {
                            quickTarget = it
                            quickMatch = RuleTextHelper.detectMatch(it, quickMatch)
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        textStyle = compactInputTextStyle(),
                        label = { CompactInputLabel(uiText("网址、域名或 IP", "Link, domain or IP", "Ссылка, домен или IP")) },
                        placeholder = { CompactInputPlaceholder("chatgpt.com") },
                        shape = RoundedCornerShape(14.dp),
                        colors = ruleFieldColors(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Eyebrow(uiText("匹配范围", "Match", "Охват"))
                    Spacer(modifier = Modifier.height(6.dp))
                    ChipRow {
                        RuleTextHelper.Match.entries.forEach { match ->
                            OptionChip(text = match.label(), selected = quickMatch == match, onClick = { quickMatch = match })
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Eyebrow(uiText("命中后", "Then", "Затем"))
                    Spacer(modifier = Modifier.height(6.dp))
                    ChipRow {
                        RuleTextHelper.Action.entries.forEach { action ->
                            OptionChip(
                                text = action.label(),
                                selected = quickNode == null && quickAction == action,
                                onClick = {
                                    quickAction = action
                                    quickNode = null
                                },
                            )
                        }
                        if (visibleServers.isNotEmpty()) {
                            OptionChip(
                                text = quickNode?.let { uiText("节点：${it.name}", "Node: ${it.name}", "Узел: ${it.name}") }
                                    ?: uiText("指定节点…", "Pick a node…", "Выбрать узел…"),
                                selected = quickNode != null,
                                onClick = { nodePicker = NodePickerPurpose.QuickAdd },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = when {
                            quickTarget.isBlank() -> uiText("例如：chatgpt.com、https://claude.ai/new、1.2.3.4", "e.g. chatgpt.com, https://claude.ai/new, 1.2.3.4", "например: chatgpt.com, https://claude.ai/new, 1.2.3.4")
                            quickLine == null -> uiText("这个地址不太对，请检查（整个网站和仅此域名需要带“.”的域名，IP 段需要 IP）。", "That doesn't look right. Whole-site and single-domain rules need a domain with a dot; IP rules need an IP.", "Похоже, адрес неверный: для сайта или домена нужен домен с точкой, для IP — IP-адрес.")
                            else -> uiText("将添加：$quickLine", "Will add: $quickLine", "Будет добавлено: $quickLine")
                        },
                        color = if (quickTarget.isNotBlank() && quickLine == null) Error else TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CompactPrimaryActionButton(
                        text = uiText("添加", "Add", "Добавить"),
                        enabled = quickLine != null,
                        onClick = {
                            val line = quickLine ?: return@CompactPrimaryActionButton
                            val (next, added) = RuleTextHelper.appendLines(input.text, listOf(line))
                            updateInput(TextFieldValue(next, TextRange(next.length)))
                            templateMessage = if (added == 0) tr("这条规则已经在列表里了。", "That rule is already in the list.", "Это правило уже есть в списке.") else null
                            quickTarget = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Templates.
            item {
                SurfaceCard {
                    SectionTitle(uiText("常用模板", "Templates", "Шаблоны"))
                    SectionHint(uiText("点一下就加入一组常用规则，已有的不会重复添加。", "One tap adds a set of common rules; nothing is added twice.", "Одно нажатие добавляет набор правил; повторы пропускаются."))
                    Spacer(modifier = Modifier.height(10.dp))
                    ChipRow {
                        RuleTextHelper.templates.forEach { template ->
                            val label = templateLabels[template.id]
                            AddChip(
                                text = label?.let { uiText(it.first, it.second, it.third) } ?: template.id,
                                onClick = {
                                    val (next, added) = RuleTextHelper.appendLines(input.text, template.lines)
                                    updateInput(TextFieldValue(next, TextRange(next.length)))
                                    templateMessage = if (added == 0) {
                                        tr("这些规则已经都在了。", "These rules are already there.", "Эти правила уже есть.")
                                    } else {
                                        tr("已添加 $added 条规则。", "Added $added rules.", "Добавлено правил: $added.")
                                    }
                                },
                            )
                        }
                    }
                    templateMessage?.let {
                        Text(it, color = Primary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            // Readable list.
            item {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(uiText("规则列表 · ${lineViews.size} 条", "Rules · ${lineViews.size}", "Правила · ${lineViews.size}"))
                    }
                    if (errorLines.isNotEmpty()) {
                        SectionHint(uiText("有 ${errorLines.size} 条无法识别（红色），修改或删除后才能保存。", "${errorLines.size} can't be recognized (red). Fix or delete them to save.", "Не распознано: ${errorLines.size} (красные). Исправьте или удалите их, чтобы сохранить."))
                    } else {
                        SectionHint(uiText("从上往下匹配，先命中的规则生效。", "Checked top to bottom; the first match wins.", "Проверяются сверху вниз, срабатывает первое совпадение."))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    if (lineViews.isEmpty()) {
                        Text(
                            uiText("还没有规则。用上面的“快速添加”或模板开始。", "No rules yet. Start with Quick add or a template above.", "Правил пока нет. Начните с быстрого добавления или шаблона."),
                            color = TextSecondary,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lineViews.forEach { view ->
                            RuleLineRow(
                                view = view,
                                broken = view.lineNumber in errorLines,
                                onDelete = {
                                    val next = RuleTextHelper.removeLine(input.text, view.lineNumber)
                                    updateInput(TextFieldValue(next, TextRange(next.length)))
                                },
                            )
                        }
                    }
                }
            }
            // Raw text, for people who know the format.
            item {
                SurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SectionTitle(uiText("高级：直接编辑文本", "Advanced: edit the text", "Дополнительно: редактировать текст"))
                            SectionHint(uiText("Shadowrocket / Surge 格式，每行一条。", "Shadowrocket / Surge format, one rule per line.", "Формат Shadowrocket / Surge, по одному правилу в строке."))
                        }
                        TextAction(text = if (advancedOpen) uiText("收起", "Hide", "Скрыть") else uiText("展开", "Show", "Показать"), onClick = { advancedOpen = !advancedOpen })
                    }
                    if (advancedOpen) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = uiText("每行一条规则：规则类型,匹配内容,策略。支持 DOMAIN-SUFFIX、DOMAIN、DOMAIN-KEYWORD、URL-REGEX、IP-CIDR、IP-CIDR6、GEOIP。策略支持 DIRECT、PROXY、REJECT，也可以用下方节点菜单插入 NODE 策略。IP 规则可追加 no-resolve。"),
                            color = TextSecondary,
                            fontSize = TypeScale.Meta,
                            lineHeight = TypeScale.MetaLine,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = ::updateInput,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = compactInputTextStyle(),
                            minLines = 8,
                            maxLines = 24,
                            visualTransformation = if (errorLines.isNotEmpty()) RuleErrorLineVisualTransformation(errorLines) else VisualTransformation.None,
                            label = { CompactInputLabel("规则文本") },
                            placeholder = { CompactInputPlaceholder("DOMAIN-SUFFIX,example.com,PROXY") },
                            shape = RoundedCornerShape(14.dp),
                            colors = ruleFieldColors(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        RuleTextAutocompleteBar(value = input, onValueChange = ::updateInput)
                        Spacer(modifier = Modifier.height(12.dp))
                        CompactPrimaryActionButton(
                            text = "插入节点策略",
                            enabled = visibleServers.isNotEmpty(),
                            onClick = { nodePicker = NodePickerPurpose.InsertIntoText },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SectionHint(uiText("选择节点会把可识别的 NODE 策略插入到光标位置。订阅刷新后会优先按稳定节点信息重新绑定；找不到节点时默认走 PROXY。"))
                        Spacer(modifier = Modifier.height(12.dp))
                        Eyebrow(uiText("格式（一般选自动）", "Format (Auto is fine)", "Формат (обычно «Авто»)"))
                        Spacer(modifier = Modifier.height(6.dp))
                        ChipRow {
                            listOf(RuleSourceType.Auto, RuleSourceType.Shadowrocket, RuleSourceType.Surge).forEach { candidate ->
                                OptionChip(text = uiText(candidate.displayName()), selected = type == candidate, onClick = { type = candidate })
                            }
                        }
                    }
                    if (missingNodeLabels.isNotEmpty()) {
                        Text(
                            text = uiText(
                                "以下节点不存在，命中后将默认走代理，建议重新绑定：${missingNodeLabels.joinToString("、")}",
                                "Missing nodes will use PROXY by default. Rebind them: ${missingNodeLabels.joinToString(", ")}", "Отсутствующие узлы по умолчанию идут через PROXY. Привяжите заново: ${missingNodeLabels.joinToString(", ")}",
                            ),
                            color = Error,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        } else {
            // URL: common sources.
            item {
                SurfaceCard {
                    SectionTitle(uiText("常用规则源", "Common sources", "Популярные источники"))
                    SectionHint(uiText("点一下自动填好名称、链接和处理方式（来自 blackmatrix7 规则库）。", "One tap fills in the name, link and action (from the blackmatrix7 rule collection).", "Одно нажатие заполнит название, ссылку и действие (из коллекции blackmatrix7)."))
                    Spacer(modifier = Modifier.height(10.dp))
                    ChipRow {
                        RuleUrlPresets.all.forEach { preset ->
                            OptionChip(
                                text = uiText(preset.name),
                                selected = normalizedUrl == preset.url,
                                onClick = {
                                    updateInput(TextFieldValue(preset.url, TextRange(preset.url.length)))
                                    policy = preset.policy
                                    if (name.isBlank() || RuleUrlPresets.all.any { it.name == name || appText(it.name, language) == name }) {
                                        name = appText(preset.name, language)
                                    }
                                    linkCheck = LinkCheck.Idle
                                },
                            )
                        }
                    }
                }
            }
            // URL: action for lines without one.
            item {
                SurfaceCard {
                    SectionTitle(uiText("命中后怎么处理", "When a rule matches", "При совпадении"))
                    SectionHint(uiText("大多数规则列表只写网站、不写处理方式，就按这里处理；写了的以列表为准。", "Most lists only name sites; those use this. Lines that say what to do keep their own action.", "Большинство списков содержат только сайты — к ним применяется это действие. Строки со своим действием сохраняют его."))
                    Spacer(modifier = Modifier.height(10.dp))
                    ChipRow {
                        listOf(RuleTargetPolicy.Proxy, RuleTargetPolicy.Direct, RuleTargetPolicy.Block).forEach { candidate ->
                            OptionChip(text = candidate.label(), selected = policy == candidate, onClick = { policy = candidate })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Eyebrow(uiText("格式（一般选自动）", "Format (Auto is fine)", "Формат (обычно «Авто»)"))
                    Spacer(modifier = Modifier.height(6.dp))
                    ChipRow {
                        listOf(RuleSourceType.Auto, RuleSourceType.Shadowrocket, RuleSourceType.Surge).forEach { candidate ->
                            OptionChip(text = uiText(candidate.displayName()), selected = type == candidate, onClick = { type = candidate })
                        }
                    }
                }
            }
            // URL: check before saving.
            item {
                SurfaceCard {
                    SectionTitle(uiText("检测链接", "Check the link", "Проверить ссылку"))
                    SectionHint(uiText("先下载试试，看能识别多少条规则。", "Download it once to see how many rules are recognized.", "Скачать один раз и посмотреть, сколько правил распознано."))
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedActionButton(
                        text = if (linkCheck == LinkCheck.Checking) uiText("检测中…", "Checking…", "Проверка…") else uiText("检测链接", "Check link", "Проверить"),
                        enabled = linkCheck != LinkCheck.Checking && (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")),
                        onClick = {
                            linkCheck = LinkCheck.Checking
                            val url = normalizedUrl
                            scope.launch {
                                linkCheck = runCatching { RuleSourceManager.previewUrl(url, type, policy) }.fold(
                                    onSuccess = { LinkCheck.Ok(it.convertedRules, it.skippedRules) },
                                    onFailure = { LinkCheck.Failed(it.message ?: "") },
                                )
                            }
                        },
                    )
                    when (val check = linkCheck) {
                        is LinkCheck.Ok -> Text(
                            text = if (check.skipped == 0) {
                                uiText("可用：识别到 ${check.rules} 条规则。", "Works: ${check.rules} rules recognized.", "Работает: распознано правил: ${check.rules}.")
                            } else {
                                uiText("可用：识别到 ${check.rules} 条，另有 ${check.skipped} 条本 App 不支持会被跳过。", "Works: ${check.rules} recognized; ${check.skipped} unsupported will be skipped.", "Работает: распознано ${check.rules}, неподдерживаемых будет пропущено: ${check.skipped}.")
                            },
                            color = Success,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        is LinkCheck.Failed -> Text(
                            text = uiText("无法使用：${check.message}", "Can't use it: ${check.message}", "Не удалось: ${check.message}"),
                            color = Error,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        else -> Unit
                    }
                }
            }
        }

        item {
            saveError?.let {
                Text(text = it, color = Error, modifier = Modifier.padding(bottom = 10.dp))
            }
            val emptyName = uiText("我的规则", "My rules", "Мои правила")
            val missingText = uiText("还没有规则，请先添加。", "No rules yet. Add some first.", "Правил пока нет. Сначала добавьте их.")
            val missingUrl = uiText("请输入规则 URL。")
            val badUrl = uiText("规则 URL 必须以 http:// 或 https:// 开头。")
            ButtonRow(
                primaryText = "保存",
                onPrimary = {
                    if (isText) {
                        saveError = when {
                            input.text.isBlank() -> missingText
                            validation != null && !validation.valid -> validation.message
                            else -> null
                        }
                        if (saveError == null) {
                            val finalName = name.trim().ifBlank { RuleTextHelper.suggestName(input.text) ?: emptyName }
                            onSubmit(finalName, input.text, type, effectiveKind, policy)
                        }
                    } else {
                        val url = normalizedUrl
                        saveError = when {
                            url.isBlank() -> missingUrl
                            !(url.startsWith("http://") || url.startsWith("https://")) -> badUrl
                            else -> null
                        }
                        if (saveError == null) {
                            val fileName = url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.')
                            val finalName = name.trim().ifBlank { fileName.ifBlank { emptyName } }
                            onSubmit(finalName, url, type, effectiveKind, policy)
                        }
                    }
                },
                secondaryText = "取消",
                onSecondary = onBack,
            )
        }
    }

    nodePicker?.let { purpose ->
        NodePolicyPickerDialog(
            servers = visibleServers,
            onDismiss = { nodePicker = null },
            onSelect = { server ->
                when (purpose) {
                    NodePickerPurpose.InsertIntoText -> updateInput(input.insertText(RuleSourceManager.nodePolicyToken(server)))
                    NodePickerPurpose.QuickAdd -> quickNode = server
                }
                nodePicker = null
            },
        )
    }
}

@Composable
private fun RuleLineRow(
    view: RuleTextHelper.LineView,
    broken: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (broken) Error.copy(alpha = 0.10f) else SurfaceHigh)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (broken) uiText("第 ${view.lineNumber} 行无法识别", "Line ${view.lineNumber} isn't recognized", "Строка ${view.lineNumber} не распознана") else view.describe(),
                color = if (broken) Error else TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = view.raw,
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!broken) {
            val (label, color) = when {
                view.nodeLabel != null -> uiText("节点 ${view.nodeLabel}", "Node ${view.nodeLabel}", "Узел ${view.nodeLabel}") to Primary
                view.action == RuleTextHelper.Action.Direct -> view.action.label() to Success
                view.action == RuleTextHelper.Action.Reject -> view.action.label() to Error
                else -> RuleTextHelper.Action.Proxy.label() to Primary
            }
            Text(
                text = label,
                color = color,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(max = 120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        CompactIconAction(icon = Icons.Rounded.Close, tint = TextSecondary, onClick = onDelete)
    }
}

private fun TextFieldValue.insertText(text: String): TextFieldValue {
    val start = selection.start.coerceIn(0, this.text.length)
    val end = selection.end.coerceIn(0, this.text.length)
    val rangeStart = minOf(start, end)
    val rangeEnd = maxOf(start, end)
    val nextText = this.text.replaceRange(rangeStart, rangeEnd, text)
    val nextCursor = rangeStart + text.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}

private class RuleErrorLineVisualTransformation(
    private val errorLineNumbers: Set<Int>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        var lineNumber = 1
        var lineStart = 0
        val rawText = text.text

        while (lineStart <= rawText.length) {
            val lineEnd = rawText.indexOf('\n', lineStart).let { if (it < 0) rawText.length else it }
            if (errorLineNumbers.contains(lineNumber)) {
                builder.addStyle(
                    style = SpanStyle(color = Error),
                    start = lineStart,
                    end = lineEnd,
                )
            }
            if (lineEnd == rawText.length) break
            lineStart = lineEnd + 1
            lineNumber += 1
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
private fun NodePolicyPickerDialog(
    servers: List<ServerNode>,
    onDismiss: () -> Unit,
    onSelect: (ServerNode) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredServers = remember(query, servers) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            servers
        } else {
            servers.filter { server ->
                server.policyNodeSearchText().contains(keyword, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(uiText("选择策略节点"), fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    textStyle = compactInputTextStyle(),
                    label = { CompactInputLabel("搜索节点") },
                    placeholder = { CompactInputPlaceholder("输入节点名、订阅、协议") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceLow,
                        unfocusedContainerColor = SurfaceLow,
                        focusedBorderColor = Primary.copy(alpha = 0.3f),
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                Text(
                    text = uiText("点击节点后会插入可长期识别的 NODE 策略。"),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                )
                if (filteredServers.isEmpty()) {
                    Text(
                        text = uiText("没有匹配的节点。"),
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredServers, key = { it.id }) { server ->
                            NodePolicyPickerRow(
                                server = server,
                                onClick = { onSelect(server) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("关闭"), color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
private fun NodePolicyPickerRow(
    server: ServerNode,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = server.name,
            color = TextPrimary,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = uiText(server.policyNodeSubtitle()),
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ServerNode.policyNodeSubtitle(): String {
    return listOf(
        subscription.ifBlank { "本地节点" },
        protocol,
        transport,
        listOf(address, port).filter { it.isNotBlank() }.joinToString(":"),
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun ServerNode.policyNodeSearchText(): String {
    return listOf(
        name,
        subscription,
        protocol,
        transport,
        region,
        address,
        port,
    ).joinToString(" ")
}

@Composable
private fun RuleTextAutocompleteBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val suggestions = remember(value.text, value.selection) { value.ruleTextCompletions() }
    if (suggestions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = uiText("自动补全"),
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { suggestion ->
                ModeChip(
                    text = suggestion.label,
                    selected = false,
                ) {
                    onValueChange(value.replaceCurrentRuleToken(suggestion.insertion, suggestion.cursorOffset))
                }
            }
        }
    }
}

private data class RuleCompletion(
    val label: String,
    val insertion: String,
    val cursorOffset: Int? = null,
)

private fun TextFieldValue.ruleTextCompletions(): List<RuleCompletion> {
    val context = currentRuleTokenContext()
    val token = context.token.uppercase()
    val typeSuggestions = listOf(
        "DOMAIN-SUFFIX",
        "DOMAIN",
        "DOMAIN-KEYWORD",
        "URL-REGEX",
        "IP-CIDR",
        "IP-CIDR6",
        "GEOIP",
    )
    val policySuggestions = listOf("DIRECT", "PROXY", "REJECT", "no-resolve")
    return when (context.partIndex) {
        0 -> typeSuggestions
            .filter { token.isBlank() || it.startsWith(token) }
            .map { RuleCompletion(label = it, insertion = "$it,") }

        1 -> when (context.ruleType) {
            "DOMAIN-SUFFIX", "DOMAIN" -> listOf(
                RuleCompletion("example.com", "example.com,"),
                RuleCompletion("google.com", "google.com,"),
                RuleCompletion("openai.com", "openai.com,"),
            )
            "DOMAIN-KEYWORD" -> listOf(
                RuleCompletion("openai", "openai,"),
                RuleCompletion("google", "google,"),
                RuleCompletion("youtube", "youtube,"),
            )
            "URL-REGEX" -> listOf(
                RuleCompletion("^(.+\\.)?example\\.com$", "^(.+\\.)?example\\.com$,"),
                RuleCompletion(".*\\.googlevideo\\.com$", ".*\\.googlevideo\\.com$,"),
            )
            "IP-CIDR" -> listOf(
                RuleCompletion("8.8.8.8/32", "8.8.8.8/32,"),
                RuleCompletion("1.1.1.1/32", "1.1.1.1/32,"),
                RuleCompletion("192.168.0.0/16", "192.168.0.0/16,"),
            )
            "IP-CIDR6" -> listOf(
                RuleCompletion("2001:4860:4860::8888/128", "2001:4860:4860::8888/128,"),
            )
            "GEOIP" -> listOf(
                RuleCompletion("CN", "CN,"),
                RuleCompletion("US", "US,"),
                RuleCompletion("JP", "JP,"),
            )
            else -> emptyList()
        }.filter { token.isBlank() || it.insertion.uppercase().startsWith(token) }

        else -> policySuggestions
            .filter { token.isBlank() || it.uppercase().startsWith(token) }
            .map { RuleCompletion(label = it, insertion = it) }
    }
}

private data class RuleTokenContext(
    val start: Int,
    val end: Int,
    val token: String,
    val partIndex: Int,
    val ruleType: String,
)

private fun TextFieldValue.currentRuleTokenContext(): RuleTokenContext {
    val cursor = selection.start.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val relativeCursor = cursor - lineStart
    val tokenStartInLine = line.lastIndexOf(',', (relativeCursor - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val tokenEndInLine = line.indexOf(',', relativeCursor).let { if (it < 0) line.length else it }
    val partIndex = line.take(tokenStartInLine).count { it == ',' }
    val ruleType = line.substringBefore(",").trim().uppercase()
    return RuleTokenContext(
        start = lineStart + tokenStartInLine,
        end = lineStart + tokenEndInLine,
        token = text.substring(lineStart + tokenStartInLine, lineStart + tokenEndInLine).trim(),
        partIndex = partIndex,
        ruleType = ruleType,
    )
}

private fun TextFieldValue.replaceCurrentRuleToken(
    insertion: String,
    cursorOffset: Int? = null,
): TextFieldValue {
    val context = currentRuleTokenContext()
    val prefixWhitespace = text.substring(context.start, context.end).takeWhile { it.isWhitespace() }
    val replacement = prefixWhitespace + insertion
    val nextText = text.replaceRange(context.start, context.end, replacement)
    val nextCursor = context.start + (cursorOffset ?: replacement.length)
    return copy(text = nextText, selection = TextRange(nextCursor.coerceIn(0, nextText.length)))
}

@Composable
private fun InactiveRulesHint() {
    Text(
        text = uiText("全局模式下不生效"),
        color = TextSecondary,
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
    )
}
