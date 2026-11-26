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
                text = uiText("规则分析报告", "Rule analysis", "Анализ правил"),
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
    val node = report.mainNodeName?.let { uiText("主节点：", "Main node: ", "Основной узел: ") + it }
        ?: uiText("未选择主节点", "No main node", "Основной узел не выбран")
    return "$mode · $node"
}

// --- Summary --------------------------------------------------------------

@Composable
private fun SummarySection(report: RuleAnalysisReport) {
    val active = report.active
    val effective = active.count { it.status == RuleEntryStatus.Effective || it.status == RuleEntryStatus.Redundant }
    val partly = active.count { it.status == RuleEntryStatus.Partial || it.status == RuleEntryStatus.Overridden }
    val idle = report.inactive.size
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(effective.toString(), uiText("完全生效", "Fully in effect", "Действуют"), Success, Icons.Rounded.CheckCircle, Modifier.weight(1f))
            StatTile(partly.toString(), uiText("被覆盖", "Overridden", "Перекрыты"), Warning, Icons.Rounded.WarningAmber, Modifier.weight(1f))
            StatTile(idle.toString(), uiText("未生效", "Not in effect", "Не действуют"), TextSecondary, Icons.Rounded.Info, Modifier.weight(1f))
        }
        val totals = active.fold(Triple(0, 0, 0)) { acc, entry ->
            Triple(acc.first + entry.effectiveCount, acc.second + entry.overriddenCount, acc.third + entry.redundantCount)
        }
        val all = totals.first + totals.second + totals.third
        if (all > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = uiText("全部匹配项（域名 / IP / 应用）", "All entries (domains / IPs / apps)", "Все записи (домены / IP / приложения)") + " · " + fmt(all),
                    color = TextSecondary,
                    fontSize = TypeScale.Tiny,
                    lineHeight = TypeScale.TinyLine,
                    fontWeight = FontWeight.SemiBold,
                )
                ProportionBar(
                    parts = listOf(totals.first to Success, totals.second to Warning, totals.third to TextSecondary.copy(alpha = 0.5f)),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LegendDot(Success, uiText("生效", "In effect", "Действуют") + " " + fmt(totals.first))
                    LegendDot(Warning, uiText("被抢先改道", "Taken by a higher rule", "Перехвачены выше") + " " + fmt(totals.second))
                    LegendDot(TextSecondary.copy(alpha = 0.5f), uiText("重复（去向相同）", "Duplicates (same way)", "Дубли (тот же путь)") + " " + fmt(totals.third))
                }
            }
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
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
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

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine)
    }
}

@Composable
private fun VerdictBox(report: RuleAnalysisReport, overriddenEntries: Int) {
    val (color, icon, text) = when {
        report.globalMode -> Triple(
            Primary,
            Icons.Rounded.Public,
            uiText(
                "全局代理已开启：自定义规则、流媒体分流和内置规则都被跳过，除局域网、最高优先级规则和分应用节点外，所有流量都走主节点。",
                "Global proxy is on: custom rules, media routing and built-in rules are skipped. Apart from the local network, top-priority rules and per-app nodes, everything goes through the main node.",
                "Глобальный прокси включён: свои правила, медиа-маршрутизация и встроенные правила пропускаются. Кроме локальной сети, правил высшего приоритета и узлов приложений, весь трафик идёт через основной узел.",
            ),
        )
        overriddenEntries > 0 -> Triple(
            Warning,
            Icons.Rounded.WarningAmber,
            uiText(
                "有 $overriddenEntries 组规则的部分内容被排在前面的规则抢先匹配了，那部分不会按它自己的设置走。详情见下方“需要注意”。",
                "$overriddenEntries rule groups are partly matched first by rules above them; that part doesn't follow their own setting. See “Worth a look” below.",
                "У $overriddenEntries групп правил часть записей перехватывают правила выше; эта часть идёт не по их настройке. Подробнее в разделе «Обратите внимание».",
            ),
        )
        else -> Triple(
            Success,
            Icons.Rounded.CheckCircle,
            uiText(
                "没有冲突：每条开启的规则都按你的设置在起作用。",
                "No conflicts: every rule that's on works the way you set it.",
                "Конфликтов нет: каждое включённое правило работает так, как настроено.",
            ),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextPrimary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
    }
}

// --- Highlights -----------------------------------------------------------

@Composable
private fun HighlightsSection(report: RuleAnalysisReport) {
    val conflicted = report.active.filter { it.lostTo.isNotEmpty() }
    val missingNodes = report.active.filter { it.input.missingNodes.isNotEmpty() }
    val broken = report.inactive.filter {
        it.input.inactiveReason == InactiveReason.NotDownloaded || it.input.inactiveReason == InactiveReason.NodeMissing
    }
    if (conflicted.isEmpty() && missingNodes.isEmpty() && broken.isEmpty() && !report.perAppMode) return

    ReportSection(title = uiText("需要注意", "Worth a look", "Обратите внимание")) {
        if (report.perAppMode) {
            HighlightRow(
                icon = Icons.Rounded.Apps,
                color = Primary,
                text = uiText(
                    "分应用模式：只有选中的 ${report.vpnAppNames.size} 个应用经过 VPN，其他应用直接上网，下面的规则对它们都不起作用。",
                    "Per-app mode: only the ${report.vpnAppNames.size} selected apps use the VPN. Other apps connect directly and no rule below applies to them.",
                    "Режим по приложениям: через VPN идут только ${report.vpnAppNames.size} выбранных приложений. Остальные подключаются напрямую, и правила ниже на них не действуют.",
                ),
            )
        }
        conflicted.forEach { entry ->
            entry.lostTo.forEach { conflict ->
                HighlightRow(
                    icon = Icons.Rounded.WarningAmber,
                    color = Warning,
                    text = uiText(
                        "「${entryTitle(entry)}」有 ${fmt(conflict.count)} 条被 #${orderOf(report, conflict.winnerId)}「${conflict.winnerName}」抢先，实际走 ${targetText(conflict.winnerTarget)}，而不是 ${targetText(conflict.loserTarget)}。",
                        "${fmt(conflict.count)} entries of “${entryTitle(entry)}” are matched first by #${orderOf(report, conflict.winnerId)} “${conflict.winnerName}” and go ${targetText(conflict.winnerTarget)} instead of ${targetText(conflict.loserTarget)}.",
                        "${fmt(conflict.count)} записей «${entryTitle(entry)}» раньше перехватывает #${orderOf(report, conflict.winnerId)} «${conflict.winnerName}»: они идут ${targetText(conflict.winnerTarget)}, а не ${targetText(conflict.loserTarget)}.",
                    ),
                    examples = conflict.examples,
                )
            }
        }
        missingNodes.forEach { entry ->
            HighlightRow(
                icon = Icons.Rounded.Dns,
                color = Error,
                text = uiText(
                    "「${entry.input.name}」里指定的节点 ${entry.input.missingNodes.joinToString("、")} 已不存在，这些行改按规则源的默认策略走。",
                    "Nodes named in “${entry.input.name}” are gone (${entry.input.missingNodes.joinToString(", ")}); those lines use the source's default policy.",
                    "Узлы из «${entry.input.name}» удалены (${entry.input.missingNodes.joinToString(", ")}); эти строки идут по политике источника по умолчанию.",
                ),
            )
        }
        broken.forEach { entry ->
            HighlightRow(
                icon = Icons.Rounded.Info,
                color = Error,
                text = quoted(entryTitle(entry)) + inactiveReasonText(entry.input.inactiveReason!!),
            )
        }
    }
}

@Composable
private fun HighlightRow(icon: ImageVector, color: Color, text: String, examples: List<String> = emptyList()) {
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
            Text(text, color = TextPrimary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            if (examples.isNotEmpty()) ExampleChips(examples)
        }
    }
}

@Composable
private fun ExampleChips(examples: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(uiText("例：", "e.g.", "напр.:"), color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine)
        examples.forEach { example ->
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
        subtitle = uiText(
            "每个连接从上往下逐条比对，命中第一条就按它走，后面的不再看。",
            "Each connection is checked from the top down; the first rule that matches decides, the rest are not looked at.",
            "Каждое соединение проверяется сверху вниз: решает первое совпавшее правило, остальные не смотрятся.",
        ),
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
                title = uiText("VPN 入口", "VPN entry", "Вход в VPN"),
                description = uiText(
                    "只有这些应用进入 VPN，其余应用直接联网：",
                    "Only these apps enter the VPN; the rest connect directly:",
                    "В VPN входят только эти приложения, остальные подключаются напрямую:",
                ),
                chips = report.vpnAppNames,
            ),
        )
    }
    val systemLines = buildList {
        add(uiText("DNS 查询走专用通道，保证解析可用", "DNS queries take their own path so lookups keep working", "DNS-запросы идут своим путём, чтобы разрешение имён работало"))
        if (report.tailscaleEnabled) {
            add(
                uiText("Tailscale：*.ts.net、100.64.0.0/10", "Tailscale: *.ts.net, 100.64.0.0/10", "Tailscale: *.ts.net, 100.64.0.0/10") +
                    if (report.tailscaleSubnetCount > 0) uiText(" 及 ${report.tailscaleSubnetCount} 个子网", " and ${report.tailscaleSubnetCount} subnets", " и ${report.tailscaleSubnetCount} подсетей") else "",
            )
        }
        if (report.quicBlocked) {
            add(
                uiText(
                    "拦截境外 QUIC（UDP 443），应用会自动改用 TCP",
                    "Foreign QUIC (UDP 443) is blocked; apps fall back to TCP",
                    "Зарубежный QUIC (UDP 443) блокируется; приложения переходят на TCP",
                ),
            )
        }
    }
    add(
        ChainStep(
            icon = Icons.Rounded.Security,
            color = TextSecondary,
            title = uiText("系统保留规则", "System rules", "Системные правила"),
            description = systemLines.joinToString("\n") { "• $it" },
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
            title = uiText("局域网直连", "Local network direct", "Локальная сеть напрямую"),
            description = uiText("路由器、NAS、内网地址（geoip:private）", "Router, NAS and private addresses (geoip:private)", "Роутер, NAS и частные адреса (geoip:private)"),
            target = RouteTarget.Direct,
        ),
    )
    val regionName = regionText(report)
    add(
        ChainStep(
            icon = Icons.Rounded.Flag,
            color = Success,
            title = uiText("本地区直连", "Home region direct", "Свой регион напрямую"),
            description = when {
                report.globalMode -> uiText("全局模式下跳过", "Skipped in global mode", "Пропускается в глобальном режиме")
                report.homeSitesDirect -> uiText("${regionName}网站（geosite）和${regionName} IP（geoip）", "$regionName sites (geosite) and $regionName IPs (geoip)", "Сайты ($regionName, geosite) и IP ($regionName, geoip)")
                else -> uiText("${regionName} IP（geoip）", "$regionName IPs (geoip)", "IP ($regionName, geoip)")
            },
            target = RouteTarget.Direct,
            dimmed = report.globalMode,
        ),
    )
    add(
        ChainStep(
            icon = Icons.Rounded.VpnLock,
            color = Primary,
            title = uiText("其余所有流量", "Everything else", "Весь остальной трафик"),
            description = uiText("上面都没命中的连接", "Connections nothing above matched", "Соединения, которым ничего выше не подошло"),
            target = report.fallback,
        ),
    )
}

@Composable
private fun layerStep(layer: RuleLayer, active: List<RuleEntryResult>, report: RuleAnalysisReport): ChainStep {
    val entries = active.filter { it.input.layer == layer }
    val skippedByGlobal = report.globalMode && layer != RuleLayer.PerApp && layer != RuleLayer.Top
    return when (layer) {
        RuleLayer.Top -> ChainStep(
            icon = Icons.Rounded.PriorityHigh,
            color = Warning,
            title = uiText("最高优先级规则", "Top-priority rules", "Правила высшего приоритета"),
            description = uiText("排在所有规则之前，全局模式下也生效", "Before every other rule, global mode included", "Раньше всех правил, и в глобальном режиме тоже"),
            entries = entries,
        )
        RuleLayer.PerApp -> ChainStep(
            icon = Icons.Rounded.Apps,
            color = Primary,
            title = uiText("分应用节点", "Per-app nodes", "Узлы приложений"),
            description = uiText("指定了节点的应用：整个应用的流量都走该节点", "Apps with their own node: all of the app's traffic uses it", "Приложения со своим узлом: весь их трафик идёт через него"),
            entries = entries,
            emptyText = uiText("没有生效的应用节点", "No per-app node in effect", "Нет действующих узлов приложений"),
        )
        RuleLayer.Custom -> ChainStep(
            icon = Icons.Rounded.Description,
            color = Primary,
            title = uiText("自定义规则", "Custom rules", "Свои правила"),
            description = uiText("你添加的文本 / URL 规则，按列表顺序", "Your text / URL rules, in list order", "Ваши текстовые / URL-правила по порядку списка"),
            entries = entries,
            emptyText = if (skippedByGlobal) uiText("全局模式下跳过", "Skipped in global mode", "Пропускается в глобальном режиме") else uiText("没有生效的自定义规则", "No custom rule in effect", "Нет действующих своих правил"),
            dimmed = skippedByGlobal,
        )
        RuleLayer.Streaming -> ChainStep(
            icon = Icons.Rounded.LiveTv,
            color = Primary,
            title = uiText("流媒体分流", "Media routing", "Медиа-маршрутизация"),
            description = uiText("选了节点或直连的流媒体服务", "Media services with a node or direct chosen", "Сервисы с выбранным узлом или напрямую"),
            entries = entries,
            emptyText = if (skippedByGlobal) uiText("全局模式下跳过", "Skipped in global mode", "Пропускается в глобальном режиме") else uiText("没有生效的流媒体分流", "No media route in effect", "Нет действующих медиа-маршрутов"),
            dimmed = skippedByGlobal,
        )
        RuleLayer.Default -> ChainStep(
            icon = Icons.Rounded.Inventory,
            color = Primary,
            title = uiText("内置规则", "Built-in rules", "Встроенные правила"),
            description = uiText("默认规则列表", "The default rule lists", "Списки правил по умолчанию"),
            entries = entries,
            emptyText = if (skippedByGlobal) uiText("全局模式下跳过", "Skipped in global mode", "Пропускается в глобальном режиме") else uiText("没有生效的内置规则", "No built-in rule in effect", "Нет действующих встроенных правил"),
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
                .padding(bottom = if (last) 0.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "$number. ${step.title}",
                    color = TextPrimary,
                    fontSize = TypeScale.ListTitle,
                    lineHeight = TypeScale.ListTitleLine,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                step.target?.let { TargetChip(it) }
            }
            if (step.description.isNotBlank()) {
                Text(step.description, color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            }
            if (step.chips.isNotEmpty()) AppChips(step.chips)
            if (step.entries.isEmpty()) {
                step.emptyText?.let {
                    Text(it, color = TextSecondary.copy(alpha = 0.8f), fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine)
                }
            } else {
                step.entries.forEach { EntryCard(it, report) }
            }
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

        if (entry.matcherCount > 0 && entry.input.layer != RuleLayer.PerApp) {
            ProportionBar(
                parts = listOf(
                    entry.effectiveCount to Success,
                    entry.overriddenCount to Warning,
                    entry.redundantCount to TextSecondary.copy(alpha = 0.5f),
                ),
            )
            Text(
                text = buildString {
                    append(uiText("共 ${fmt(entry.matcherCount)} 条 · 生效 ${fmt(entry.effectiveCount)}", "${fmt(entry.matcherCount)} entries · ${fmt(entry.effectiveCount)} in effect", "${fmt(entry.matcherCount)} записей · действуют ${fmt(entry.effectiveCount)}"))
                    if (entry.overriddenCount > 0) append(uiText(" · 被抢先 ${fmt(entry.overriddenCount)}", " · ${fmt(entry.overriddenCount)} taken", " · перехвачено ${fmt(entry.overriddenCount)}"))
                    if (entry.redundantCount > 0) append(uiText(" · 重复 ${fmt(entry.redundantCount)}", " · ${fmt(entry.redundantCount)} duplicates", " · дублей ${fmt(entry.redundantCount)}"))
                },
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
            )
        }

        entry.lostTo.forEach { conflict -> ConflictLine(conflict, report) }
        if (entry.redundantCount > 0 && entry.lostTo.isEmpty() && entry.status == RuleEntryStatus.Redundant) {
            Text(
                uiText(
                    "这些内容已被前面的规则以同样的去向覆盖，删掉它也不影响结果。",
                    "Rules above already send all of this the same way; removing it changes nothing.",
                    "Правила выше уже направляют всё это так же; удаление ничего не изменит.",
                ),
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
            )
        }
        if (entry.input.missingNodes.isNotEmpty()) {
            Text(
                uiText(
                    "⚠ 节点 ${entry.input.missingNodes.joinToString("、")} 不存在，对应行改走默认策略",
                    "⚠ Node ${entry.input.missingNodes.joinToString(", ")} is gone; those lines use the default policy",
                    "⚠ Узел ${entry.input.missingNodes.joinToString(", ")} удалён; эти строки идут по политике по умолчанию",
                ),
                color = Error,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
            )
        }
        if (entry.unanalyzedCount > 0) {
            Text(
                uiText(
                    "${fmt(entry.unanalyzedCount)} 条正则规则无法判断是否被覆盖，按生效计算",
                    "${fmt(entry.unanalyzedCount)} regex entries can't be checked for overlap; counted as in effect",
                    "${fmt(entry.unanalyzedCount)} regex-записей нельзя проверить на перекрытие; считаются действующими",
                ),
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
            )
        }
    }
}

@Composable
private fun ConflictLine(conflict: RuleConflict, report: RuleAnalysisReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Warning.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Warning, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = uiText(
                    "${fmt(conflict.count)} 条被 #${orderOf(report, conflict.winnerId)}「${conflict.winnerName}」抢先 →",
                    "${fmt(conflict.count)} taken by #${orderOf(report, conflict.winnerId)} “${conflict.winnerName}” →",
                    "${fmt(conflict.count)} перехватывает #${orderOf(report, conflict.winnerId)} «${conflict.winnerName}» →",
                ),
                color = TextPrimary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            TargetChip(conflict.winnerTarget)
        }
        if (conflict.examples.isNotEmpty()) ExampleChips(conflict.examples)
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = targetText(target) + (suffix?.let { " · $it" } ?: ""),
            color = color,
            fontSize = TypeScale.Tiny,
            lineHeight = TypeScale.TinyLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
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
        title = uiText("未生效的规则", "Rules not in effect", "Недействующие правила"),
        subtitle = uiText("这些规则现在不参与匹配：", "These rules take no part in matching right now:", "Эти правила сейчас не участвуют в проверке:"),
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(layerIcon(entry.input.layer), contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entryTitle(entry),
                            color = TextPrimary,
                            fontSize = TypeScale.Meta,
                            lineHeight = TypeScale.MetaLine,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            layerName(entry.input.layer) + " · " + inactiveReasonText(entry.input.inactiveReason!!),
                            color = TextSecondary,
                            fontSize = TypeScale.Tiny,
                            lineHeight = TypeScale.TinyLine,
                        )
                    }
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
            "分析时间 $time。按当前保存的设置和手机上已下载的规则列表计算；改过设置后需重新连接 VPN 才会生效。域名只和域名比、IP 只和 IP 比，无法预知某个域名会解析到哪个 IP。",
            "Analyzed at $time, from the saved settings and the rule lists on this phone. Changes apply after the VPN reconnects. Domains are compared with domains and IPs with IPs; which IP a domain resolves to can't be known in advance.",
            "Анализ в $time по сохранённым настройкам и спискам правил на телефоне. Изменения вступают в силу после переподключения VPN. Домены сравниваются с доменами, IP — с IP; в какой IP разрешится домен, заранее неизвестно.",
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

@Composable
private fun layerName(layer: RuleLayer): String = when (layer) {
    RuleLayer.Top -> uiText("最高优先级规则", "Top-priority rules", "Правила высшего приоритета")
    RuleLayer.PerApp -> uiText("分应用节点", "Per-app node", "Узел приложения")
    RuleLayer.Custom -> uiText("自定义规则", "Custom rule", "Своё правило")
    RuleLayer.Streaming -> uiText("流媒体分流", "Media routing", "Медиа-маршрут")
    RuleLayer.Default -> uiText("内置规则", "Built-in rule", "Встроенное правило")
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
    InactiveReason.Disabled -> uiText("已关闭", "switched off", "выключено")
    InactiveReason.GlobalMode -> uiText("全局模式下跳过", "skipped in global mode", "пропускается в глобальном режиме")
    InactiveReason.NotDownloaded -> uiText("规则列表还没下载成功，暂不生效", "its list isn't downloaded yet, so it has no effect", "список ещё не загружен, поэтому не действует")
    InactiveReason.NodeMissing -> uiText("选择的节点不存在或无法运行，暂不生效", "its node is gone or can't run, so it has no effect", "узел удалён или не может работать, поэтому не действует")
    InactiveReason.NotPerAppMode -> uiText("只在分应用模式下生效", "only applies in per-app mode", "действует только в режиме по приложениям")
    InactiveReason.ReplacedByGeoData -> uiText("当前地区改用内置 Geo 数据（见“本地区直连”）", "this region uses the bundled Geo data instead (see “Home region direct”)", "для этого региона используются встроенные Geo-данные (см. «Свой регион напрямую»)")
    InactiveReason.StreamingOff -> uiText("流媒体分流总开关已关闭", "media routing is switched off", "медиа-маршрутизация выключена")
}

@Composable
private fun regionText(report: RuleAnalysisReport): String = when (report.region) {
    com.mallocgfw.app.model.AppGeoRoutingRegion.China -> uiText("中国", "China", "Китай")
    com.mallocgfw.app.model.AppGeoRoutingRegion.Russia -> uiText("俄罗斯", "Russia", "Россия")
    com.mallocgfw.app.model.AppGeoRoutingRegion.English -> uiText("美国", "US", "США")
}

/** A name in the UI language's quotes, followed by the space that language needs. */
@Composable
private fun quoted(name: String): String = uiText("「$name」", "“$name” ", "«$name» ")

private fun orderOf(report: RuleAnalysisReport, id: String): Int = report.entries.firstOrNull { it.input.id == id }?.order ?: 0

private fun fmt(value: Int): String = NumberFormat.getIntegerInstance().format(value)
