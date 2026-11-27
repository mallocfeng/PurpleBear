package com.mallocgfw.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app with a launcher icon: what can be picked to skip the VPN. */
internal data class LaunchableApp(val packageName: String, val label: String)

/**
 * The apps on the phone that show in the launcher (visible through the
 * MAIN/LAUNCHER query in the manifest), without PurpleBear itself, by name.
 */
internal fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { LaunchableApp(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }
}

/**
 * The bypass list: every launcher app with a switch; switched on, the app
 * skips the VPN. Picked apps first, then by name; [search] matches name or package.
 */
internal fun LazyListScope.bypassAppItems(
    apps: List<LaunchableApp>?,
    bypassed: Set<String>,
    search: String,
    onToggle: (String) -> Unit,
) {
    if (apps == null) {
        item(key = "bypass_loading") { NoteBox(text = uiText("正在读取已安装的应用…", "Reading installed apps…", "Чтение установленных приложений…")) }
        return
    }
    val query = search.trim().lowercase()
    val shown = apps
        .filter { query.isEmpty() || query in it.label.lowercase() || query in it.packageName.lowercase() }
        .sortedBy { if (it.packageName in bypassed) 0 else 1 }
    item(key = "bypass_header") {
        val count = bypassed.count { id -> apps.any { it.packageName == id } }
        Text(
            text = uiText(
                "已选 $count 个应用不走 VPN",
                if (count == 1) "1 app skips the VPN" else "$count apps skip the VPN",
                "Без VPN: $count",
            ),
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    items(shown, key = { "bypass_${it.packageName}" }) { app ->
        val on = app.packageName in bypassed
        SurfaceCard(modifier = Modifier.clickable { onToggle(app.packageName) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PackageIcon(app.packageName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        color = TextPrimary,
                        fontSize = TypeScale.ListTitle,
                        lineHeight = TypeScale.ListTitleLine,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (on) uiText("不走 VPN", "Skips the VPN", "Без VPN") else uiText("走 VPN", "Uses the VPN", "Через VPN"),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                    )
                }
                TogglePill(checked = on, onClick = { onToggle(app.packageName) })
            }
        }
    }
    if (shown.isEmpty()) {
        item(key = "bypass_empty") { NoteBox(text = "没有匹配到应用。") }
    }
}

/** An installed app's icon on a rounded tile (empty while loading or when there is none). */
@Composable
internal fun PackageIcon(packageName: String?, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = packageName?.let { name ->
            withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(name).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .background(ControlSurfaceColor),
        contentAlignment = Alignment.Center,
    ) {
        icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size * 0.9f)) }
    }
}
