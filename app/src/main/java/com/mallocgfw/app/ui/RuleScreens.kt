package com.mallocgfw.app.ui

import androidx.compose.foundation.background
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
    globalProxyEnabled: Boolean,
    ruleMessage: String?,
    updatingIds: Set<String>,
    onGlobalProxyChange: (Boolean) -> Unit,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = mainScreenPadding(bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ScreenHeader(
                    title = "规则管理",
                    subtitle = "管理规则源与 Geo 数据。",
                )
            }
            item {
                SurfaceCard {
                    SettingToggleRow(
                        title = "全局代理",
                        subtitle = if (globalProxyEnabled) {
                            "已开启：正常上网流量走代理，局域网/私网不走代理。"
                        } else {
                            "已关闭：按规则源、Geo CN 和流媒体分流处理。"
                        },
                        checked = globalProxyEnabled,
                        onToggle = { onGlobalProxyChange(!globalProxyEnabled) },
                    )
                    Text(
                        text = uiText("开启后会忽略规则源、流媒体分流和 CN 直连规则；局域网、私有地址仍直连，其余 TCP 与 UDP 流量走当前节点。"),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            if (!ruleMessage.isNullOrBlank()) {
                item { NoteBox(text = ruleMessage) }
            }
            item {
                RuleSourceSectionHeader(
                    title = "Geo 数据资源",
                    subtitle = "更新后重新连接生效。",
                )
            }
            item {
                GeoDataCard(
                    snapshot = geoDataSnapshot,
                    updating = geoDataUpdating,
                    onRefresh = onRefreshGeoData,
                )
            }
            item {
                RuleSourceSectionHeader(
                    title = "系统默认规则源",
                    subtitle = "默认启用。",
                )
            }
            items(defaultSources, key = { it.id }) { source ->
                RuleSourceCard(
                    source = source,
                    selected = selectedRuleSourceId == source.id,
                    updating = updatingIds.contains(source.id),
                    onClick = { onSelectSource(source.id) },
                    onToggleEnabled = { onToggleEnabled(source.id) },
                    onRefresh = { onRefreshSource(source.id) },
                )
            }
            item {
                RuleSourceSectionHeader(
                    title = "自定义规则源",
                    subtitle = if (displayedCustomSources.isEmpty()) {
                        when (selectedCustomKind) {
                            RuleSourceKind.LocalText -> "还没有手动文本规则。"
                            RuleSourceKind.RemoteUrl -> "还没有 URL 规则源。"
                        }
                    } else {
                        "更新后重新连接生效。"
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ModeChip(
                        text = "文本规则",
                        selected = selectedCustomKind == RuleSourceKind.LocalText,
                    ) {
                        selectedCustomKind = RuleSourceKind.LocalText
                    }
                    ModeChip(
                        text = "URL 规则",
                        selected = selectedCustomKind == RuleSourceKind.RemoteUrl,
                    ) {
                        selectedCustomKind = RuleSourceKind.RemoteUrl
                    }
                }
            }
            if (displayedCustomSources.isEmpty()) {
                item { EmptyRulesCard() }
            } else {
                items(displayedCustomSources, key = { it.id }) { source ->
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

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 16.dp),
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
                            add("最后更新时间" to (summary?.metadata?.updatedAt ?: "--"))
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
                    value = uiText("${summary?.domainRuleCount ?: 0} 条", "${summary?.domainRuleCount ?: 0} rules"),
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
                "确认把“${summary.metadata.name}”恢复为系统默认状态？已缓存的规则统计会被清空。"
            } else {
                "确认删除“${summary.metadata.name}”？本地保存的转换规则也会一起移除。"
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
internal fun AddRuleSourceScreen(
    padding: PaddingValues,
    editingSource: RuleSourceItem?,
    sourceKind: RuleSourceKind,
    servers: List<ServerNode>,
    onBack: () -> Unit,
    onSubmit: (String, String, RuleSourceType, RuleSourceKind) -> Unit,
) {
    var name by remember(editingSource?.id) { mutableStateOf(editingSource?.name.orEmpty()) }
    val effectiveKind = editingSource?.sourceKind ?: sourceKind
    var input by remember(editingSource?.id, effectiveKind) {
        mutableStateOf(TextFieldValue(if (effectiveKind == RuleSourceKind.LocalText) editingSource?.content.orEmpty() else editingSource?.url.orEmpty()))
    }
    var type by remember(editingSource?.id) { mutableStateOf(editingSource?.type ?: RuleSourceType.Auto) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var validationErrorLineNumbers by remember { mutableStateOf(emptySet<Int>()) }
    var nodePickerVisible by remember { mutableStateOf(false) }
    val visibleServers = servers.filterNot { it.hiddenUnsupported }
    val missingNodeLabels = remember(input.text, visibleServers) {
        if (effectiveKind == RuleSourceKind.LocalText) {
            RuleSourceManager.missingNodePolicyLabels(input.text, visibleServers)
        } else {
            emptyList()
        }
    }

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
                subtitle = if (effectiveKind == RuleSourceKind.LocalText) "本地文本规则" else "规则来源",
                onBack = onBack,
            )
        }
        item {
            ScreenHeader(
                title = when {
                    effectiveKind == RuleSourceKind.LocalText && editingSource == null -> "添加文本规则"
                    effectiveKind == RuleSourceKind.LocalText -> "编辑文本规则"
                    editingSource == null -> "添加规则 URL"
                    else -> "修改规则 URL"
                },
                subtitle = if (effectiveKind == RuleSourceKind.LocalText) {
                    "手动输入 Shadowrocket / Surge 文本规则。"
                } else {
                    "支持 Shadowrocket / Surge 文本规则。"
                },
            )
        }
        if (effectiveKind == RuleSourceKind.LocalText) {
            item {
                SurfaceCard {
                    Text(
                        text = uiText("编写规则"),
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = uiText("每行一条规则：规则类型,匹配内容,策略。支持 DOMAIN-SUFFIX、DOMAIN、DOMAIN-KEYWORD、URL-REGEX、IP-CIDR、IP-CIDR6、GEOIP。策略支持 DIRECT、PROXY、REJECT，也可以用下方节点菜单插入 NODE 策略。IP 规则可追加 no-resolve。"),
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = uiText("示例：\nDOMAIN-SUFFIX,openai.com,PROXY\nDOMAIN-SUFFIX,apple.com,DIRECT\nGEOIP,CN,DIRECT,no-resolve\nURL-REGEX,^(.+\\.)?example\\.com$,REJECT"),
                        color = TextSecondary,
                    )
                }
            }
        }
        item {
            SurfaceCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    textStyle = compactInputTextStyle(),
                    label = { CompactInputLabel("规则名称") },
                    placeholder = { CompactInputPlaceholder("例如：OpenAI 代理规则") },
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceHigh,
                        unfocusedContainerColor = SurfaceHigh,
                        focusedBorderColor = Primary.copy(alpha = 0.3f),
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        validationError = null
                        validationErrorLineNumbers = emptySet()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = compactInputTextStyle(),
                    minLines = if (effectiveKind == RuleSourceKind.LocalText) 12 else 3,
                    maxLines = if (effectiveKind == RuleSourceKind.LocalText) 24 else 5,
                    visualTransformation = if (effectiveKind == RuleSourceKind.LocalText && validationErrorLineNumbers.isNotEmpty()) {
                        RuleErrorLineVisualTransformation(validationErrorLineNumbers)
                    } else {
                        VisualTransformation.None
                    },
                    label = { CompactInputLabel(if (effectiveKind == RuleSourceKind.LocalText) "规则文本" else "规则 URL") },
                    placeholder = {
                        CompactInputPlaceholder(
                            if (effectiveKind == RuleSourceKind.LocalText) {
                                "DOMAIN-SUFFIX,example.com,PROXY"
                            } else {
                                "https://raw.githubusercontent.com/..."
                            },
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceHigh,
                        unfocusedContainerColor = SurfaceHigh,
                        focusedBorderColor = Primary.copy(alpha = 0.3f),
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (effectiveKind == RuleSourceKind.LocalText) {
                    RuleTextAutocompleteBar(
                        value = input,
                        onValueChange = {
                            input = it
                            validationError = null
                            validationErrorLineNumbers = emptySet()
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CompactPrimaryActionButton(
                        text = "插入节点策略",
                        enabled = visibleServers.isNotEmpty(),
                        onClick = { nodePickerVisible = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = uiText("选择节点会把可识别的 NODE 策略插入到光标位置。订阅刷新后会优先按稳定节点信息重新绑定；找不到节点时默认走 PROXY。"),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (missingNodeLabels.isNotEmpty()) {
                        Text(
                            text = uiText(
                                "以下节点不存在，命中后将默认走代理，建议重新绑定：${missingNodeLabels.joinToString("、")}",
                                "Missing nodes will use PROXY by default. Rebind them: ${missingNodeLabels.joinToString(", ")}",
                            ),
                            color = Error,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(
                        RuleSourceType.Auto,
                        RuleSourceType.Shadowrocket,
                        RuleSourceType.Surge,
                    ).forEach { candidate ->
                        ModeChip(
                            text = candidate.displayName(),
                            selected = type == candidate,
                        ) {
                            type = candidate
                        }
                    }
                }
                if (validationError != null) {
                    Text(
                        text = validationError ?: "",
                        color = Error,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
        item {
            ButtonRow(
                primaryText = "保存",
                onPrimary = {
                    validationErrorLineNumbers = emptySet()
                    validationError = when {
                        input.text.isBlank() -> {
                            if (effectiveKind == RuleSourceKind.LocalText) "请输入规则文本。" else "请输入规则 URL。"
                        }
                        effectiveKind == RuleSourceKind.RemoteUrl &&
                            !(input.text.startsWith("http://") || input.text.startsWith("https://")) -> {
                            "规则 URL 必须以 http:// 或 https:// 开头。"
                        }
                        effectiveKind == RuleSourceKind.LocalText -> {
                            val result = RuleSourceManager.validateTextRules(input.text, type)
                            validationErrorLineNumbers = result.errorLineNumbers
                            result.takeUnless { it.valid }?.message
                        }
                        else -> null
                    }
                    if (validationError == null) {
                        onSubmit(name, input.text, type, effectiveKind)
                    }
                },
                secondaryText = "取消",
                onSecondary = onBack,
            )
        }
    }

    if (nodePickerVisible) {
        NodePolicyPickerDialog(
            servers = visibleServers,
            onDismiss = { nodePickerVisible = false },
            onSelect = { server ->
                val token = RuleSourceManager.nodePolicyToken(server)
                input = input.insertText(token)
                validationError = null
                validationErrorLineNumbers = emptySet()
                nodePickerVisible = false
            },
        )
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
