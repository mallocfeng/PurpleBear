package com.mallocgfw.app.ui

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Outline
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary

@Composable
internal fun GroupSectionCard(
    section: ServerSection,
    expanded: Boolean,
    selectedServerId: String,
    onToggleGroup: () -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenServerDetail: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    latencyTestingIds: Set<String>,
    onTestLatency: (String) -> Unit,
    onDeleteServer: (ServerNode) -> Unit,
    onDeleteGroup: (ServerGroup) -> Unit,
    onCreateLocalNode: () -> Unit,
) {
    val containsSelectedServer = section.servers.any { it.id == selectedServerId }
    val groupTesting = section.servers.any { latencyTestingIds.contains(it.id) }
    val isLocal = section.group.type == ServerGroupType.Local
    val fastestLatency = section.servers.mapNotNull { it.latencyMs.takeIf { ms -> ms > 0 } }.minOrNull()
    var menuOpen by remember { mutableStateOf(false) }
    // Pressing the header tints the card's whole coloured area (header and the
    // frame round the node panel) instead of a ripple that stops in a straight
    // line where the panel starts.
    val headerInteraction = remember { MutableInteractionSource() }
    val headerPressed by headerInteraction.collectIsPressedAsState()
    val cardColor by animateColorAsState(
        targetValue = if (headerPressed) GroupCardPressedColor else GroupCardColor,
        animationSpec = tween(durationMillis = if (headerPressed) 90 else 260),
        label = "group-card-press",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(cardColor),
    ) {
        // Group header: a rounded-square type icon, bold name and a full-width
        // divider set it apart from the round-avatar node rows it contains.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = headerInteraction, indication = null, onClick = onToggleGroup)
                .padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isLocal) Icons.Rounded.PhoneAndroid else Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = AccentContentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = uiText(section.group.name),
                        color = TextPrimary,
                        fontSize = TypeScale.ListTitle,
                        lineHeight = TypeScale.ListTitleLine,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (containsSelectedServer) {
                        Text(
                            text = uiText("使用中", "In use"),
                            color = AccentContentColor,
                            fontSize = TypeScale.Tiny,
                            lineHeight = TypeScale.TinyLine,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Primary)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(uiText("${section.servers.size} 个节点", "${section.servers.size} nodes", "Узлов: ${section.servers.size}"))
                        if (section.group.type == ServerGroupType.Subscription) {
                            append(" · ${uiText(section.group.updatedAt)}")
                        }
                        fastestLatency?.let { append(" · ${uiText("最快", "Fastest")} $it ms") }
                        if (section.hiddenUnsupportedNodeCount > 0) {
                            append(" · ${uiText("隐藏 ${section.hiddenUnsupportedNodeCount} 个", "${section.hiddenUnsupportedNodeCount} hidden", "скрыто: ${section.hiddenUnsupportedNodeCount}")}")
                        }
                    },
                    color = TextSecondary,
                    fontSize = TypeScale.Tiny,
                    lineHeight = TypeScale.TinyLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (section.servers.isNotEmpty()) {
                CompactIconAction(
                    icon = Icons.Rounded.Speed,
                    tint = if (groupTesting) Primary else TextSecondary,
                    onClick = {
                        section.servers.forEach { server -> onTestLatency(server.id) }
                    },
                    // A collapsed group hides its results, so testing it would
                    // only spend traffic: the icon dims and ignores taps until
                    // the group is expanded.
                    enabled = expanded,
                )
            }
            // Group actions live in a menu, so "delete group" is never a stray
            // tap away from the expand arrow.
            Box {
                CompactIconAction(icon = Icons.Rounded.MoreHoriz, tint = TextSecondary, onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (isLocal) {
                        DropdownMenuItem(
                            text = { Text(uiText("新建节点"), color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, tint = Primary) },
                            onClick = {
                                menuOpen = false
                                onCreateLocalNode()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(uiText("删除订阅组"), color = Error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Error) },
                            onClick = {
                                menuOpen = false
                                onDeleteGroup(section.group)
                            },
                        )
                    }
                }
            }
            // The chevron turns over with the panel instead of swapping icons.
            val chevronTurn by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                label = "group-chevron",
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(24.dp)
                    .rotate(chevronTurn),
            )
        }

        // The node panel unfolds with a soft spring (a little settle at the end)
        // while fading and growing in from the top; folding away is quicker and
        // without the bounce.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 280f,
                    visibilityThreshold = IntSize.VisibilityThreshold,
                ),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(durationMillis = 320, delayMillis = 40, easing = LinearOutSlowInEasing)) +
                scaleIn(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 280f),
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(0.5f, 0f),
                ),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = IntSize.VisibilityThreshold,
                ),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(durationMillis = 160, easing = FastOutLinearInEasing)),
        ) {
            // The nodes sit in an inset panel inside the group card.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(GroupPanelColor),
            ) {
                if (section.servers.isEmpty()) {
                    Text(
                        text = uiText(
                            if (isLocal) "还没有节点，点 + 新建或导入" else "没有匹配的节点",
                        ),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    section.servers.forEachIndexed { index, server ->
                        if (index > 0) GroupDivider(startIndent = 62.dp)
                        ServerCard(
                            server = server,
                            selected = selectedServerId == server.id,
                            latencyTesting = latencyTestingIds.contains(server.id),
                            onClick = { onSelectServer(server.id) },
                            onOpenDetail = { onOpenServerDetail(server.id) },
                            onToggleFavorite = { onToggleFavorite(server.id) },
                            onTestLatency = { onTestLatency(server.id) },
                            onDelete = null,
                        )
                    }
                }
            }
        }
    }
}
