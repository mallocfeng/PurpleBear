package com.mallocgfw.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** One choice in an [OptionSheet]. */
internal data class SheetOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val icon: ImageVector? = null,
    val iconTint: Color = Primary,
    /** Shown instead of [icon], e.g. a flag. */
    val emoji: String? = null,
)

/**
 * A bottom sheet for picking one of a few options: a title, then each option
 * as a large card. With [selected] the current one is ticked; without it the
 * options act like actions (chevron instead of a tick).
 *
 * Picking slides the sheet away first, then reports the choice, so a
 * reconnect dialog that follows doesn't appear under a closing sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> OptionSheet(
    title: String,
    options: List<SheetOption<T>>,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    selected: T? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ModalSurfaceColor,
        scrimColor = ModalScrimColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(TextSecondary.copy(alpha = 0.35f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = TypeScale.SectionTitle,
                    lineHeight = TypeScale.SectionTitleLine,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                    )
                }
            }
            options.forEach { option ->
                OptionCard(
                    option = option,
                    selected = selected != null && option.value == selected,
                    actionStyle = selected == null,
                    onClick = {
                        if (option.value == selected) close() else close { onSelect(option.value) }
                    },
                )
            }
            footer?.invoke(this)
        }
    }
}

@Composable
private fun <T> OptionCard(
    option: SheetOption<T>,
    selected: Boolean,
    actionStyle: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val background by animateColorAsState(
        if (selected) Primary.copy(alpha = 0.12f) else ControlSurfaceColor,
        label = "option-background",
    )
    val borderColor by animateColorAsState(
        if (selected) Primary.copy(alpha = 0.7f) else Color.Transparent,
        label = "option-border",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.5.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            option.emoji != null -> Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TextSecondary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(option.emoji, fontSize = TypeScale.SectionTitle)
            }
            option.icon != null -> Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(option.iconTint.copy(alpha = if (IsLightTheme) 0.12f else 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(option.icon, contentDescription = null, tint = option.iconTint, modifier = Modifier.size(22.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = option.title,
                color = TextPrimary,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.SemiBold,
            )
            if (option.description != null) {
                Text(
                    text = option.description,
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                )
            }
        }
        if (actionStyle) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp),
            )
        } else {
            RadioMark(selected)
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    if (selected) {
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).background(Primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = ModalSurfaceColor, modifier = Modifier.size(15.dp))
        }
    } else {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(1.5.dp, TextSecondary.copy(alpha = 0.45f), CircleShape),
        )
    }
}

