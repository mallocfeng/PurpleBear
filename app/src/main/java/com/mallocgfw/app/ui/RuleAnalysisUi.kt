package com.mallocgfw.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.core.RuleAnalysisReport
import com.mallocgfw.app.model.InactiveReason
import com.mallocgfw.app.model.RouteTarget
import com.mallocgfw.app.model.RuleConflict
import com.mallocgfw.app.model.RuleEntryResult
import com.mallocgfw.app.model.RuleEntryStatus
import com.mallocgfw.app.model.RuleLayer
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.ui.theme.Warning
import java.text.DateFormat
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Slide to analyze
// ---------------------------------------------------------------------------

/**
 * "Slide to unlock" style control at the top of the rules screen: dragging the
 * knob to the right end starts the analysis. The knob then stays at the end
 * while the analysis runs and the report is open ([engaged]), and glides back
 * once that is over. Let go before the end and it eases back.
 */
@Composable
internal fun RuleAnalysisSlider(
    analyzing: Boolean,
    engaged: Boolean,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val trackHeight = 58.dp
    val knobSize = 50.dp
    val inset = 4.dp
    var dragX by remember { mutableFloatStateOf(0f) }
    // Unlocked: the knob waits at the end until the analysis and its report are done.
    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(unlocked, engaged) {
        if (unlocked && !engaged) {
            delay(SLIDER_RETURN_DELAY_MS)
            animate(dragX, 0f, animationSpec = tween(SLIDER_RETURN_MS, easing = FastOutSlowInEasing)) { value, _ -> dragX = value }
            unlocked = false
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(
                Brush.horizontalGradient(
                    listOf(Primary.copy(alpha = if (IsLightTheme) 0.10f else 0.16f), Primary.copy(alpha = if (IsLightTheme) 0.20f else 0.28f)),
                ),
            ),
    ) {
        val maxX = with(density) { (maxWidth - knobSize - inset * 2).toPx() }.coerceAtLeast(1f)
        val progress = (dragX / maxX).coerceIn(0f, 1f)

        // Filled trail behind the knob.
        Box(
            modifier = Modifier
                .padding(inset)
                .width(with(density) { dragX.toDp() } + knobSize)
                .fillMaxHeight()
                .clip(RoundedCornerShape(knobSize / 2))
                .background(Primary.copy(alpha = 0.22f + 0.5f * progress)),
        )

        // Shimmering hint, like the old iPhone lock screen.
        val shimmer = rememberInfiniteTransition(label = "slide-shimmer")
        val sweep by shimmer.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
            label = "sweep",
        )
        val widthPx = with(density) { maxWidth.toPx() }
        val base = TextSecondary
        val highlight = if (IsLightTheme) Primary else Color.White
        Text(
            text = if (analyzing) uiText("正在分析规则…", "Analyzing rules…", "Анализ правил…") else uiText("滑动分析当前规则", "Slide to analyze your rules", "Проведите для анализа правил"),
            style = TextStyle(
                brush = Brush.linearGradient(
                    colors = listOf(base, base, highlight, base, base),
                    start = Offset(widthPx * sweep - widthPx * 0.35f, 0f),
                    end = Offset(widthPx * sweep + widthPx * 0.35f, 0f),
                ),
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = knobSize + inset * 2, end = 12.dp)
                .graphicsLayer { alpha = (1f - progress * 1.6f).coerceIn(0f, 1f) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // The knob.
        Box(
            modifier = Modifier
                .padding(inset)
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .size(knobSize)
                .clip(CircleShape)
                .background(Primary)
                .draggable(
                    enabled = !analyzing && !unlocked,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> dragX = (dragX + delta).coerceIn(0f, maxX) },
                    onDragStopped = {
                        if (dragX >= maxX * 0.88f) {
                            animate(dragX, maxX, animationSpec = tween(160, easing = FastOutSlowInEasing)) { value, _ -> dragX = value }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Same frame as onComplete's "analyzing" flag, so the
                            // return above doesn't start before the analysis does.
                            unlocked = true
                            onComplete()
                        } else {
                            animate(
                                dragX,
                                0f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
                            ) { value, _ -> dragX = value }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A quick analysis would flash a spinner for a few frames: show it
            // only once the analysis has taken a while, and crossfade the icons.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(analyzing) {
                slow = false
                if (analyzing) {
                    delay(SLIDER_SPINNER_DELAY_MS)
                    slow = true
                }
            }
            val face = when {
                analyzing && slow -> KnobFace.Busy
                unlocked && engaged -> KnobFace.Done
                else -> KnobFace.Idle
            }
            Crossfade(targetState = face, animationSpec = tween(260), label = "knob-face") { current ->
                when (current) {
                    KnobFace.Busy -> CircularProgressIndicator(color = AccentContentColor, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                    KnobFace.Done -> Icon(Icons.Rounded.Check, contentDescription = null, tint = AccentContentColor, modifier = Modifier.size(24.dp))
                    KnobFace.Idle -> Icon(Icons.Rounded.Analytics, contentDescription = null, tint = AccentContentColor, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

private enum class KnobFace { Idle, Busy, Done }

// ---------------------------------------------------------------------------
// Report card
// ---------------------------------------------------------------------------

private const val SLIDER_RETURN_DELAY_MS = 180L
private const val SLIDER_RETURN_MS = 750
private const val SLIDER_SPINNER_DELAY_MS = 400L

private val NodeColor: Color get() = if (IsLightTheme) Color(0xFF2F6FED) else Color(0xFF6EA2FF)
private val TailscaleColor: Color get() = if (IsLightTheme) Color(0xFF0F9488) else Color(0xFF3DD6C6)

@Composable
internal fun RuleAnalysisReportOverlay(
    report: RuleAnalysisReport,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(ModalScrimColor)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = ModalSurfaceColor,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .graphicsLayer {
                    val scale = 0.92f + 0.08f * appear.value
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - appear.value) * 60f
                }
                // Swallow taps so only the scrim closes the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ReportHeader(report = report, onDismiss = onDismiss)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SummarySection(report)
                    HighlightsSection(report)
                    ChainSection(report)
                    InactiveSection(report)
                    FooterNote(report)
                }
            }
        }
    }
}

@Composable
private fun ReportHeader(report: RuleAnalysisReport, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Analytics, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = uiText("规则分析", "Rule analysis", "Анализ правил"),
                color = TextPrimary,
                fontSize = TypeScale.SectionTitle,
                lineHeight = TypeScale.SectionTitleLine,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = modeLine(report),
                color = TextSecondary,
                fontSize = TypeScale.Meta,
                lineHeight = TypeScale.MetaLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(ControlSurfaceColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = uiText("关闭", "Close", "Закрыть"), tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun modeLine(report: RuleAnalysisReport): String {
    val mode = when {
        report.globalMode -> uiText("全局代理", "Global proxy", "Глобальный прокси")
        report.perAppMode -> uiText("分应用模式", "Per-app mode", "Режим по приложениям")
        else -> uiText("规则分流", "Rule-based routing", "Маршрутизация по правилам")
    }
    val node = report.mainNodeName ?: uiText("未选节点", "No node", "Узел не выбран")
    return "$mode · $node"
}

// --- Summary --------------------------------------------------------------

@Composable
private fun SummarySection(report: RuleAnalysisReport) {
    val active = report.active
    val effective = active.count { it.status == RuleEntryStatus.Effective || it.status == RuleEntryStatus.Redundant }
    val partly = active.count { it.status == RuleEntryStatus.Partial || it.status == RuleEntryStatus.Overridden }
    val idle = report.inactive.size
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(effective.toString(), uiText("生效", "In effect", "Действуют"), Success, Icons.Rounded.CheckCircle, Modifier.weight(1f))
            StatTile(partly.toString(), uiText("被覆盖", "Overridden", "Перекрыты"), Warning, Icons.Rounded.WarningAmber, Modifier.weight(1f))
            StatTile(idle.toString(), uiText("未生效", "Not in effect", "Не действуют"), TextSecondary, Icons.Rounded.Info, Modifier.weight(1f))
        }
        VerdictBox(report, partly)
    }
}

@Composable
private fun StatTile(value: String, label: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(color.copy(alpha = 0.11f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(value, color = TextPrimary, fontSize = TypeScale.Metric, lineHeight = TypeScale.MetricLine, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProportionBar(parts: List<Pair<Int, Color>>) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(ControlSurfaceTrackColor),
    ) {
        parts.filter { it.first > 0 }.forEach { (count, color) ->
            Box(
                modifier = Modifier
                    .weight(count.toFloat() / total)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

/** One line: the state of things at a glance. */
@Composable
private fun VerdictBox(report: RuleAnalysisReport, overriddenEntries: Int) {
    val brokenLists = report.inactive.count { it.input.inactiveReason == InactiveReason.NotDownloaded }
    val brokenNodes = report.inactive.count { it.input.inactiveReason == InactiveReason.NodeMissing } +
        report.active.count { it.input.missingNodes.isNotEmpty() }
    val (color, icon, text) = when {
        report.globalMode -> Triple(
            Primary,
            Icons.Rounded.Public,
            uiText("全局代理：分流规则已跳过", "Global proxy: routing rules are skipped", "Глобальный прокси: правила маршрутизации пропущены"),
        )
        overriddenEntries > 0 -> Triple(
            Warning,
            Icons.Rounded.WarningAmber,
            uiText(
                "$overriddenEntries 组规则有冲突",
                if (overriddenEntries == 1) "1 rule group conflicts" else "$overriddenEntries rule groups conflict",
                "Конфликтуют групп правил: $overriddenEntries",
            ),
        )
        brokenLists + brokenNodes > 0 -> Triple(
            Error,
            Icons.Rounded.Info,
            uiText("${brokenLists + brokenNodes} 项未能生效", "${brokenLists + brokenNodes} not working", "Не работают: ${brokenLists + brokenNodes}"),
        )
        else -> Triple(
            Success,
            Icons.Rounded.CheckCircle,
            uiText("没有冲突", "No conflicts", "Конфликтов нет"),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextPrimary, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine, fontWeight = FontWeight.SemiBold)
    }
}

// --- Highlights -----------------------------------------------------------

@Composable
private fun HighlightsSection(report: RuleAnalysisReport) {
    val conflicted = report.active.filter { it.lostTo.isNotEmpty() }
    val missingNodes = report.active.filter { it.input.missingNodes.isNotEmpty() }
    if (conflicted.isEmpty() && missingNodes.isEmpty() && !report.perAppMode) return

    ReportSection(title = uiText("需要注意", "Worth a look", "Обратите внимание")) {
        if (report.perAppMode) {
            HighlightRow(
                icon = Icons.Rounded.Apps,
                color = Primary,
                text = uiText(
                    "仅 ${report.vpnAppNames.size} 个应用走 VPN",
                    "Only ${report.vpnAppNames.size} apps use the VPN",
                    "Через VPN только приложений: ${report.vpnAppNames.size}",
                ),
            )
        }
        conflicted.forEach { entry ->
            entry.lostTo.forEach { conflict ->
                HighlightRow(
                    icon = Icons.Rounded.WarningAmber,
                    color = Warning,
                    text = uiText(
                        "「${entryTitle(entry)}」${fmt(conflict.count)} 条被 #${orderOf(report, conflict.winnerId)} 抢先，走",
                        "“${entryTitle(entry)}”: ${fmt(conflict.count)} taken by #${orderOf(report, conflict.winnerId)}, go",
                        "«${entryTitle(entry)}»: ${fmt(conflict.count)} перехватывает #${orderOf(report, conflict.winnerId)}, идут",
                    ),
                    target = conflict.winnerTarget,
                    examples = conflict.examples,
                )
            }
        }
        missingNodes.forEach { entry ->
            HighlightRow(
                icon = Icons.Rounded.Dns,
                color = Error,
                text = uiText(
                    "「${entry.input.name}」节点已删除：${entry.input.missingNodes.joinToString("、")}",
                    "“${entry.input.name}”: node gone: ${entry.input.missingNodes.joinToString(", ")}",
                    "«${entry.input.name}»: узел удалён: ${entry.input.missingNodes.joinToString(", ")}",
                ),
            )
        }
    }
}

@Composable
private fun HighlightRow(
    icon: ImageVector,
    color: Color,
    text: String,
    target: RouteTarget? = null,
    examples: List<String> = emptyList(),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, color = TextPrimary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
                target?.let { TargetChip(it) }
            }
            if (examples.isNotEmpty()) ExampleChips(examples)
        }
    }
}

@Composable
private fun ExampleChips(examples: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        examples.take(3).forEach { example ->
            Text(
                text = example,
                color = TextPrimary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ControlSurfaceColor)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

// --- Chain ----------------------------------------------------------------

private data class ChainStep(
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val description: String,
    val entries: List<RuleEntryResult> = emptyList(),
    val emptyText: String? = null,
    val chips: List<String> = emptyList(),
    val target: RouteTarget? = null,
    val dimmed: Boolean = false,
)

@Composable
private fun ChainSection(report: RuleAnalysisReport) {
    val steps = buildChainSteps(report)
    ReportSection(
        title = uiText("匹配顺序", "Matching order", "Порядок проверки"),
        subtitle = uiText("从上往下，先命中先生效", "Top down; first match wins", "Сверху вниз; решает первое совпадение"),
    ) {
        Column {
            steps.forEachIndexed { index, step ->
                ChainStepRow(number = index + 1, step = step, last = index == steps.lastIndex, report = report)
            }
        }
    }
}

@Composable
private fun buildChainSteps(report: RuleAnalysisReport): List<ChainStep> = buildList {
    val active = report.active
    if (report.perAppMode) {
        add(
            ChainStep(
                icon = Icons.Rounded.Smartphone,
                color = Primary,
                title = uiText("走 VPN 的应用", "Apps on the VPN", "Приложения в VPN"),
                description = "",
                chips = report.vpnAppNames,
            ),
        )
    }
    val systemChips = buildList {
        add("DNS")
        if (report.tailscaleEnabled) {
            add(
                "Tailscale" + if (report.tailscaleSubnetCount > 0) {
                    uiText(" + ${report.tailscaleSubnetCount} 个子网", " + ${report.tailscaleSubnetCount} subnets", " + подсетей: ${report.tailscaleSubnetCount}")
                } else {
                    ""
                },
            )
        }
        if (report.quicBlocked) add(uiText("拦截境外 QUIC", "Foreign QUIC blocked", "Зарубежный QUIC заблокирован"))
    }
    add(
        ChainStep(
            icon = Icons.Rounded.Security,
            color = TextSecondary,
            title = uiText("系统规则", "System rules", "Системные правила"),
            description = "",
            chips = systemChips,
        ),
    )
    if (active.any { it.input.layer == RuleLayer.Top }) add(layerStep(RuleLayer.Top, active, report))
    add(layerStep(RuleLayer.PerApp, active, report))
    add(layerStep(RuleLayer.Custom, active, report))
    add(layerStep(RuleLayer.Streaming, active, report))
    add(layerStep(RuleLayer.Default, active, report))
    add(
        ChainStep(
            icon = Icons.Rounded.Lan,
            color = Success,
            title = uiText("局域网", "Local network", "Локальная сеть"),
            description = "",
            target = RouteTarget.Direct,
        ),
    )
    val regionName = regionText(report)
    add(
        ChainStep(
            icon = Icons.Rounded.Flag,
            color = Success,
            title = if (report.homeSitesDirect) {
                uiText("${regionName}网站和 IP", "$regionName sites and IPs", "Сайты и IP: $regionName")
            } else {
                uiText("${regionName} IP", "$regionName IPs", "IP: $regionName")
            },
            description = if (report.globalMode) skippedText() else "",
            target = RouteTarget.Direct,
            dimmed = report.globalMode,
        ),
    )
    add(
        ChainStep(
            icon = Icons.Rounded.VpnLock,
            color = Primary,
            title = uiText("其余流量", "Everything else", "Всё остальное"),
            description = "",
            target = report.fallback,
        ),
    )
}

@Composable
private fun skippedText(): String = uiText("全局模式跳过", "Skipped in global mode", "Пропуск в глобальном режиме")

@Composable
private fun layerStep(layer: RuleLayer, active: List<RuleEntryResult>, report: RuleAnalysisReport): ChainStep {
    val entries = active.filter { it.input.layer == layer }
    val skippedByGlobal = report.globalMode && layer != RuleLayer.PerApp && layer != RuleLayer.Top
    val none = if (skippedByGlobal) skippedText() else uiText("无", "None", "Нет")
    return when (layer) {
        RuleLayer.Top -> ChainStep(
            icon = Icons.Rounded.PriorityHigh,
            color = Warning,
            title = uiText("最高优先级规则", "Top-priority rules", "Правила высшего приоритета"),
            description = "",
            entries = entries,
        )
        RuleLayer.PerApp -> ChainStep(
            icon = Icons.Rounded.Apps,
            color = Primary,
            title = uiText("分应用节点", "Per-app nodes", "Узлы приложений"),
            description = "",
            entries = entries,
            emptyText = none,
        )
        RuleLayer.Custom -> ChainStep(
            icon = Icons.Rounded.Description,
            color = Primary,
            title = uiText("自定义规则", "Custom rules", "Свои правила"),
            description = "",
            entries = entries,
            emptyText = none,
            dimmed = skippedByGlobal,
        )
        RuleLayer.Streaming -> ChainStep(
            icon = Icons.Rounded.LiveTv,
            color = Primary,
            title = uiText("流媒体分流", "Media routing", "Медиа-маршрутизация"),
            description = "",
            entries = entries,
            emptyText = none,
            dimmed = skippedByGlobal,
        )
        RuleLayer.Default -> ChainStep(
            icon = Icons.Rounded.Inventory,
            color = Primary,
            title = uiText("内置规则", "Built-in rules", "Встроенные правила"),
            description = "",
            entries = entries,
            emptyText = none,
            dimmed = skippedByGlobal,
        )
    }
}

@Composable
private fun ChainStepRow(number: Int, step: ChainStep, last: Boolean, report: RuleAnalysisReport) {
    val railColor = DividerColor
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Line from under this step's dot down to the next step.
                if (!last) {
                    val x = 17.dp.toPx()
                    drawLine(railColor, Offset(x, 34.dp.toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            }
            .graphicsLayer { alpha = if (step.dimmed) 0.5f else 1f },
    ) {
        Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(step.color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(step.icon, contentDescription = null, tint = step.color, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (last) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The title keeps its full width on one line; the chip (which may
            // carry a long node name) gets what is left and shortens itself.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "$number. ${step.title}",
                    color = TextPrimary,
                    fontSize = TypeScale.ListTitle,
                    lineHeight = TypeScale.ListTitleLine,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    step.target?.let { TargetChip(it) }
                    if (step.entries.isEmpty() && step.target == null) {
                        step.emptyText?.let {
                            Text(it, color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine, maxLines = 1)
                        }
                    }
                }
            }
            if (step.description.isNotBlank()) {
                Text(step.description, color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            }
            if (step.chips.isNotEmpty()) AppChips(step.chips)
            step.entries.forEach { EntryCard(it, report) }
        }
    }
}

@Composable
private fun AppChips(names: List<String>) {
    val shown = names.take(12)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { name -> SmallChip(name) }
        if (names.size > shown.size) SmallChip("+${names.size - shown.size}")
    }
}

@Composable
private fun SmallChip(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ControlSurfaceColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun EntryCard(entry: RuleEntryResult, report: RuleAnalysisReport) {
    val statusColor = statusColor(entry.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ControlSurfaceColor)
            .border(1.dp, statusColor.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${entry.order}",
                color = Primary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primary.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entryTitle(entry),
                color = TextPrimary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            StatusPill(entry.status)
        }

        if (entry.input.layer == RuleLayer.PerApp) {
            AppChips(entry.input.apps)
        }

        // Where the entries that decide traffic go.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entry.effectiveTargets.forEach { (target, count) ->
                TargetChip(
                    target = target,
                    suffix = if (entry.input.layer == RuleLayer.PerApp) {
                        uiText("全部流量", "all traffic", "весь трафик")
                    } else {
                        uiText("${fmt(count)} 条", "${fmt(count)}", "${fmt(count)}")
                    },
                )
            }
        }

        if (entry.matcherCount > 0 && entry.input.layer != RuleLayer.PerApp && entry.status != RuleEntryStatus.Effective) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ProportionBar(
                        parts = listOf(
                            entry.effectiveCount to Success,
                            entry.overriddenCount to Warning,
                            entry.redundantCount to TextSecondary.copy(alpha = 0.5f),
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = uiText(
                        "${fmt(entry.effectiveCount)}/${fmt(entry.matcherCount)} 生效",
                        "${fmt(entry.effectiveCount)}/${fmt(entry.matcherCount)} in effect",
                        "${fmt(entry.effectiveCount)}/${fmt(entry.matcherCount)} действуют",
                    ),
                    color = TextSecondary,
                    fontSize = TypeScale.Tiny,
                    lineHeight = TypeScale.TinyLine,
                )
            }
        }
        if (entry.input.missingNodes.isNotEmpty()) {
            Text(
                uiText(
                    "节点已删除：${entry.input.missingNodes.joinToString("、")}",
                    "Node gone: ${entry.input.missingNodes.joinToString(", ")}",
                    "Узел удалён: ${entry.input.missingNodes.joinToString(", ")}",
                ),
                color = Error,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
            )
        }
    }
}

@Composable
private fun StatusPill(status: RuleEntryStatus) {
    val color = statusColor(status)
    val text = when (status) {
        RuleEntryStatus.Effective -> uiText("生效", "In effect", "Действует")
        RuleEntryStatus.Partial -> uiText("部分被覆盖", "Partly overridden", "Частично перекрыто")
        RuleEntryStatus.Overridden -> uiText("完全被覆盖", "Overridden", "Перекрыто")
        RuleEntryStatus.Redundant -> uiText("重复", "Duplicate", "Дубль")
        RuleEntryStatus.Inactive -> uiText("未生效", "Not in effect", "Не действует")
    }
    Text(
        text = text,
        color = color,
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun statusColor(status: RuleEntryStatus): Color = when (status) {
    RuleEntryStatus.Effective -> Success
    RuleEntryStatus.Partial -> Warning
    RuleEntryStatus.Overridden -> Error
    RuleEntryStatus.Redundant -> TextSecondary
    RuleEntryStatus.Inactive -> TextSecondary
}

@Composable
private fun TargetChip(target: RouteTarget, suffix: String? = null) {
    val (icon, color) = targetStyle(target)
    // A long node name shortens with "…"; the count after it always shows.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = targetText(target),
            color = color,
            fontSize = TypeScale.Tiny,
            lineHeight = TypeScale.TinyLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        suffix?.let {
            Text(
                text = " · $it",
                color = color,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun targetStyle(target: RouteTarget): Pair<ImageVector, Color> = when (target.kind) {
    RouteTarget.Kind.Direct -> Icons.Rounded.Home to Success
    RouteTarget.Kind.Proxy -> Icons.Rounded.VpnLock to Primary
    RouteTarget.Kind.Node -> Icons.Rounded.Dns to NodeColor
    RouteTarget.Kind.Block -> Icons.Rounded.Block to Error
    RouteTarget.Kind.Tailscale -> Icons.Rounded.Lan to TailscaleColor
}

@Composable
private fun targetText(target: RouteTarget): String = when (target.kind) {
    RouteTarget.Kind.Direct -> uiText("直连", "Direct", "Напрямую")
    RouteTarget.Kind.Block -> uiText("拦截", "Block", "Блок")
    RouteTarget.Kind.Tailscale -> "Tailscale"
    RouteTarget.Kind.Proxy -> target.nodeName?.let { uiText("代理 · $it", "Proxy · $it", "Прокси · $it") }
        ?: uiText("代理（主节点）", "Proxy (main node)", "Прокси (основной узел)")
    RouteTarget.Kind.Node -> target.nodeName ?: uiText("未知节点", "Unknown node", "Неизвестный узел")
}

// --- Inactive -------------------------------------------------------------

@Composable
private fun InactiveSection(report: RuleAnalysisReport) {
    val inactive = report.inactive
    if (inactive.isEmpty()) return
    ReportSection(
        title = uiText("未生效", "Not in effect", "Не действуют"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ControlSurfaceColor),
        ) {
            inactive.forEachIndexed { index, entry ->
                if (index > 0) GroupDivider(startIndent = 44.dp)
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val reason = entry.input.inactiveReason!!
                    val problem = reason == InactiveReason.NotDownloaded || reason == InactiveReason.NodeMissing
                    Icon(layerIcon(entry.input.layer), contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        entryTitle(entry),
                        color = TextPrimary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inactiveReasonText(reason),
                        color = if (problem) Error else TextSecondary,
                        fontSize = TypeScale.Tiny,
                        lineHeight = TypeScale.TinyLine,
                        fontWeight = if (problem) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun FooterNote(report: RuleAnalysisReport) {
    val time = remember(report.generatedAtMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.generatedAtMs)) }
    Text(
        text = uiText(
            "分析于 $time · 按已保存的设置",
            "Analyzed at $time · from saved settings",
            "Анализ в $time · по сохранённым настройкам",
        ),
        color = TextSecondary,
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
    )
}

// --- Shared bits ----------------------------------------------------------

@Composable
private fun ReportSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = TextPrimary, fontSize = TypeScale.CardTitle, lineHeight = TypeScale.CardTitleLine, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine) }
        content()
    }
}

@Composable
private fun entryTitle(entry: RuleEntryResult): String = when (entry.input.layer) {
    RuleLayer.Top -> uiText("最高优先级规则", "Top-priority rules", "Правила высшего приоритета")
    RuleLayer.PerApp -> entry.input.name.ifBlank { uiText("已删除的节点", "Deleted node", "Удалённый узел") }
    RuleLayer.Default, RuleLayer.Custom -> uiText(entry.input.name)
    RuleLayer.Streaming -> entry.input.name
}

private fun layerIcon(layer: RuleLayer): ImageVector = when (layer) {
    RuleLayer.Top -> Icons.Rounded.PriorityHigh
    RuleLayer.PerApp -> Icons.Rounded.Apps
    RuleLayer.Custom -> Icons.Rounded.Description
    RuleLayer.Streaming -> Icons.Rounded.LiveTv
    RuleLayer.Default -> Icons.Rounded.Inventory
}

@Composable
private fun inactiveReasonText(reason: InactiveReason): String = when (reason) {
    InactiveReason.Disabled -> uiText("已关闭", "Off", "Выкл.")
    InactiveReason.GlobalMode -> uiText("全局模式跳过", "Skipped (global)", "Пропуск (глоб.)")
    InactiveReason.NotDownloaded -> uiText("列表未下载", "Not downloaded", "Не загружен")
    InactiveReason.NodeMissing -> uiText("节点不可用", "Node unavailable", "Узел недоступен")
    InactiveReason.NotPerAppMode -> uiText("需分应用模式", "Needs per-app mode", "Нужен режим приложений")
    InactiveReason.ReplacedByGeoData -> uiText("由直连地区代替", "Covered by region", "Заменено регионом")
    InactiveReason.StreamingOff -> uiText("总开关已关", "Switched off", "Выключено")
}

@Composable
private fun regionText(report: RuleAnalysisReport): String = when (report.region) {
    com.mallocgfw.app.model.AppGeoRoutingRegion.China -> uiText("中国", "China", "Китай")
    com.mallocgfw.app.model.AppGeoRoutingRegion.Russia -> uiText("俄罗斯", "Russia", "Россия")
    com.mallocgfw.app.model.AppGeoRoutingRegion.English -> uiText("美国", "US", "США")
}

private fun orderOf(report: RuleAnalysisReport, id: String): Int = report.entries.firstOrNull { it.input.id == id }?.order ?: 0

private fun fmt(value: Int): String = NumberFormat.getIntegerInstance().format(value)
