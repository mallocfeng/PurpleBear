package com.mallocgfw.app.ui

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
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val sectionShape = RoundedCornerShape(18.dp)
    val headerShape = RoundedCornerShape(14.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val containsSelectedServer = section.servers.any { it.id == selectedServerId }
    val headerAccent = if (section.group.type == ServerGroupType.Subscription) Primary else Secondary
    SurfaceCard(
        compact = true,
        shape = sectionShape,
        background = SolidColor(SurfaceLow),
        border = if (IsLightTheme) BorderStroke(0.8.dp, CardOutlineColor) else BorderStroke(0.8.dp, Outline.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceLow, sectionShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(headerShape)
                    .indication(
                        interactionSource = interactionSource,
                        indication = rememberRipple(bounded = true),
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onToggleGroup,
                    )
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(headerAccent.copy(alpha = if (IsLightTheme) 0.70f else 0.82f)),
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(headerAccent.copy(alpha = if (containsSelectedServer) 0.16f else 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.group.name,
                        color = if (containsSelectedServer) headerAccent else TextPrimary,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(if (section.group.type == ServerGroupType.Subscription) "订阅组" else "Local")
                            append(" · ${section.servers.size} 个")
                            append(" · ${section.group.updatedAt}")
                            if (section.hiddenUnsupportedNodeCount > 0) {
                                append(" · 已隐藏 ${section.hiddenUnsupportedNodeCount} 个")
                            }
                        },
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (section.servers.isNotEmpty()) {
                        val groupTesting = section.servers.any { latencyTestingIds.contains(it.id) }
                        CompactIconAction(
                            icon = Icons.Rounded.Sync,
                            tint = if (groupTesting) Primary else TextSecondary,
                            onClick = {
                                section.servers.forEach { server ->
                                    onTestLatency(server.id)
                                }
                            },
                        )
                    }
                    if (section.group.type == ServerGroupType.Local) {
                        CompactIconAction(
                            icon = Icons.Rounded.AddCircle,
                            tint = Primary,
                            onClick = onCreateLocalNode,
                        )
                    }
                    if (section.group.type == ServerGroupType.Subscription) {
                        CompactIconAction(
                            icon = Icons.Rounded.Delete,
                            tint = Error,
                            onClick = { onDeleteGroup(section.group) },
                        )
                    }
                }
            }

            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp)
                        .height(1.dp)
                        .background(Outline.copy(alpha = if (IsLightTheme) 0.16f else 0.20f)),
                )
                if (section.hiddenUnsupportedNodeCount > 0) {
                    Text(
                        text = "已隐藏 ${section.hiddenUnsupportedNodeCount} 个暂不支持节点。",
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                if (section.servers.isEmpty()) {
                    Text(
                        text = if (section.group.type == ServerGroupType.Local) {
                            if (section.hiddenUnsupportedNodeCount > 0) {
                                "Local group 目前没有可显示节点。"
                            } else {
                                "Local group 还没有节点。"
                            }
                        } else {
                            if (section.hiddenUnsupportedNodeCount > 0) {
                                "这个订阅组当前没有可显示节点。"
                            } else {
                                "这个订阅组当前没有匹配到节点。"
                            }
                        },
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        section.servers.forEachIndexed { index, server ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 26.dp, end = 6.dp)
                                        .height(1.dp)
                                        .background(Outline.copy(alpha = if (IsLightTheme) 0.14f else 0.16f)),
                                )
                            }
                            ServerCard(
                                server = server,
                                selected = selectedServerId == server.id,
                                latencyTesting = latencyTestingIds.contains(server.id),
                                onClick = { onSelectServer(server.id) },
                                onOpenDetail = { onOpenServerDetail(server.id) },
                                onToggleFavorite = { onToggleFavorite(server.id) },
                                onTestLatency = { onTestLatency(server.id) },
                                onDelete = if (section.group.type == ServerGroupType.Local) {
                                    { onDeleteServer(server) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
