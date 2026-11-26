package com.mallocgfw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary

private const val PROJECT_URL = "https://github.com/mallocfeng/PurpleBear"
private const val ISSUES_URL = "$PROJECT_URL/issues"

/** Settings → About: who we are, which version this is, and where the source lives. */
@Composable
internal fun AboutScreen(
    padding: PaddingValues,
    appVersion: String,
    coreVersion: String?,
    systemVersion: String,
    updateBadge: Boolean,
    onBack: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenFailed: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    fun open(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure { onOpenFailed() }
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
            AppTopBar(title = uiText("关于", "About", "О приложении"), onBack = onBack)
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BrandLogo(size = 84.dp)
                Text(
                    text = APP_BRAND_NAME,
                    color = TextPrimary,
                    fontSize = TypeScale.SectionTitle,
                    lineHeight = TypeScale.SectionTitleLine,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = uiText("版本 $appVersion", "Version $appVersion", "Версия $appVersion"),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                )
                Text(
                    text = uiText(
                        "开源的 Android 代理客户端",
                        "An open-source proxy client for Android",
                        "Прокси-клиент для Android с открытым кодом",
                    ),
                    color = TextSecondary,
                    fontSize = TypeScale.Meta,
                    lineHeight = TypeScale.MetaLine,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            GroupedCard {
                ListRow(
                    title = uiText("检查更新", "Check for updates", "Проверить обновления"),
                    value = if (updateBadge) uiText("有新版本", "New version", "Есть новая версия") else null,
                    icon = Icons.Rounded.SystemUpdate,
                    onClick = onOpenUpdates,
                    badge = updateBadge,
                )
                GroupDivider(startIndent = 58.dp)
                ListRow(
                    title = uiText("项目主页", "Project page", "Страница проекта"),
                    value = "GitHub",
                    icon = Icons.Rounded.Code,
                    onClick = { open(PROJECT_URL) },
                )
                GroupDivider(startIndent = 58.dp)
                ListRow(
                    title = uiText("问题反馈", "Report a problem", "Сообщить о проблеме"),
                    value = "Issues",
                    icon = Icons.Rounded.BugReport,
                    onClick = { open(ISSUES_URL) },
                )
                GroupDivider(startIndent = 58.dp)
                ListRow(
                    title = uiText("开源许可", "Open-source licenses", "Лицензии"),
                    icon = Icons.Rounded.Description,
                    onClick = onOpenLicenses,
                )
            }
        }
        item {
            Text(
                text = buildString {
                    append((coreVersion ?: "Xray").trim())
                    append(" · ")
                    append(systemVersion)
                    append('\n')
                    append(uiText("以 MIT 许可证开源", "Open source under the MIT License", "Открытый код по лицензии MIT"))
                },
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}
