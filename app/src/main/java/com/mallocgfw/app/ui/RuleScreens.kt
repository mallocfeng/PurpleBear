package com.mallocgfw.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.RuleSourceDetailSummary
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceType
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceHigh
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
    ruleMessage: String?,
    updatingIds: Set<String>,
    onRefreshGeoData: () -> Unit,
    onSelectSource: (String) -> Unit,
    onAddSource: () -> Unit,
    onToggleEnabled: (String) -> Unit,
    onRefreshSource: (String) -> Unit,
) {
    val defaultSources = ruleSources.filter { it.systemDefault }
    val customSources = ruleSources.filterNot { it.systemDefault }

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
                    subtitle = if (customSources.isEmpty()) {
                        "还没有自定义规则。"
                    } else {
                        "更新后重新连接生效。"
                    },
                )
            }
            if (customSources.isEmpty()) {
                item {
                    EmptyRulesCard(onAddSource = onAddSource)
                }
            } else {
                items(customSources, key = { it.id }) { source ->
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

        AddRuleFab(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 16.dp),
            onClick = onAddSource,
        )
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
                            summary?.metadata?.type?.displayName() ?: "--",
                            fontSize = TypeScale.CardTitle,
                            lineHeight = TypeScale.CardTitleLine,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            summary?.metadata?.url ?: "暂无 URL",
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
                TwoColumnInfoGrid(
                    items = listOf(
                        "最后更新时间" to (summary?.metadata?.updatedAt ?: "--"),
                        "本地规则条数" to "${summary?.metadata?.totalRules ?: 0}",
                        "成功转换数" to "${summary?.metadata?.convertedRules ?: 0}",
                        "跳过数" to "${summary?.metadata?.skippedRules ?: 0}",
                    ),
                )
            }
        }
        item {
            SurfaceCard {
                DetailRow(
                    label = "完整 URL",
                    value = summary?.fullUrl ?: "暂无",
                    trailing = summary?.metadata?.type?.displayName() ?: "--",
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(
                    label = "域名规则",
                    value = "${summary?.domainRuleCount ?: 0} 条",
                    trailing = "IP ${summary?.ipRuleCount ?: 0}",
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(
                    label = "最近错误",
                    value = summary?.metadata?.lastError ?: "最近一次更新没有错误。",
                    trailing = if (summary?.metadata?.systemDefault == true) "系统内置" else "用户添加",
                )
            }
        }
        if (!message.isNullOrBlank()) {
            item { NoteBox(text = message) }
        }
        item {
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
    onBack: () -> Unit,
    onSubmit: (String, String, RuleSourceType) -> Unit,
) {
    var name by remember(editingSource?.id) { mutableStateOf(editingSource?.name.orEmpty()) }
    var url by remember(editingSource?.id) { mutableStateOf(editingSource?.url.orEmpty()) }
    var type by remember(editingSource?.id) { mutableStateOf(editingSource?.type ?: RuleSourceType.Auto) }
    var validationError by remember { mutableStateOf<String?>(null) }

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
                subtitle = "规则来源",
                onBack = onBack,
            )
        }
        item {
            ScreenHeader(
                title = if (editingSource == null) "添加规则 URL" else "修改规则 URL",
                subtitle = "支持 Shadowrocket / Surge 文本规则。",
            )
        }
        item {
            SurfaceCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
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
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = compactInputTextStyle(),
                    minLines = 3,
                    maxLines = 5,
                    label = { CompactInputLabel("规则 URL") },
                    placeholder = { CompactInputPlaceholder("https://raw.githubusercontent.com/...") },
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
                primaryText = if (editingSource == null) "添加并检测" else "保存并重新检测",
                onPrimary = {
                    validationError = when {
                        url.isBlank() -> "请输入规则 URL。"
                        !(url.startsWith("http://") || url.startsWith("https://")) -> "规则 URL 必须以 http:// 或 https:// 开头。"
                        else -> null
                    }
                    if (validationError == null) {
                        onSubmit(name, url, type)
                    }
                },
                secondaryText = "取消",
                onSecondary = onBack,
            )
        }
    }
}

