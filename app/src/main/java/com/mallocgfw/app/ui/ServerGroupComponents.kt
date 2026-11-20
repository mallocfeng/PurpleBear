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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ripple
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
    val containsSelectedServer = section.servers.any { it.id == selectedServerId }
    val groupTesting = section.servers.any { latencyTestingIds.contains(it.id) }
    GroupedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleGroup)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = uiText(section.group.name),
                        color = TextPrimary,
                        fontSize = TypeScale.ListTitle,
                        lineHeight = TypeScale.ListTitleLine,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (containsSelectedServer) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Primary),
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(uiText("${section.servers.size} 个节点", "${section.servers.size} nodes"))
                        if (section.group.type == ServerGroupType.Subscription) {
                            append(" · ${uiText(section.group.updatedAt)}")
                        }
                        if (section.hiddenUnsupportedNodeCount > 0) {
                            append(" · ${uiText("隐藏 ${section.hiddenUnsupportedNodeCount} 个", "${section.hiddenUnsupportedNodeCount} hidden")}")
                        }
                    },
                    color = TextSecondary,
                    fontSize = TypeScale.Tiny,
                    lineHeight = TypeScale.TinyLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (section.servers.isNotEmpty()) {
                CompactIconAction(
                    icon = Icons.Rounded.Speed,
                    tint = if (groupTesting) Primary else TextSecondary,
                    onClick = {
                        section.servers.forEach { server -> onTestLatency(server.id) }
                    },
                )
            }
            if (section.group.type == ServerGroupType.Local) {
                CompactIconAction(icon = Icons.Rounded.Add, tint = Primary, onClick = onCreateLocalNode)
            } else {
                CompactIconAction(
                    icon = Icons.Rounded.DeleteOutline,
                    tint = TextSecondary,
                    onClick = { onDeleteGroup(section.group) },
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }

        if (expanded) {
            if (section.servers.isEmpty()) {
                GroupDivider()
                Text(
                    text = uiText(
                        if (section.group.type == ServerGroupType.Local) "还没有节点，点 + 新建或导入" else "没有匹配的节点",
                    ),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            } else {
                section.servers.forEach { server ->
                    GroupDivider(startIndent = 64.dp)
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
