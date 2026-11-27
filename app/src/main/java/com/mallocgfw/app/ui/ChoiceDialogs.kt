package com.mallocgfw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import com.mallocgfw.app.model.normalizedAppVpnMtu
import com.mallocgfw.app.model.AppVpnMtuOptions
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.MonitorHeart
import com.mallocgfw.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.rounded.ToggleOn
import com.mallocgfw.app.model.AppLogLevel
import com.mallocgfw.app.model.AppLanguage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.DnsInput
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.IsLightTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary

/** Frame shared by the settings choice dialogs: icon header, content, note, Cancel / Save. */
@Composable
internal fun ChoiceDialogFrame(
    icon: ImageVector,
    title: String,
    subtitle: String,
    note: String?,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveText: String? = null,
    /** Null: a single full-width primary button. */
    dismissText: String? = "",
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = SurfaceHigh,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Primary.copy(alpha = if (IsLightTheme) 0.12f else 0.20f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
                    }
                    Column {
                        Text(title, color = TextPrimary, fontSize = TypeScale.CardTitle, lineHeight = TypeScale.CardTitleLine, fontWeight = FontWeight.ExtraBold)
                        Text(subtitle, color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                content()
                if (note != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(note, color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine, modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (dismissText != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceLow)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(dismissText.ifEmpty { uiText("取消") }, color = TextPrimary, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        CompactPrimaryActionButton(text = saveText ?: uiText("保存"), enabled = canSave, onClick = onSave, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** DNS settings: three option cards; "manual" opens an address box with common servers. */
@Composable
internal fun DnsSettingsDialog(
    initialMode: AppDnsMode,
    initialCustomDns: String,
    region: AppGeoRoutingRegion,
    onDismiss: () -> Unit,
    onConfirm: (AppDnsMode, String) -> Unit,
) {
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var custom by remember(initialCustomDns) { mutableStateOf(initialCustomDns) }
    val customValid = DnsInput.isValid(custom)
    val canSave = mode != AppDnsMode.Custom || customValid

    ChoiceDialogFrame(
        icon = Icons.Rounded.Dns,
        title = uiText("DNS 设置", "DNS", "DNS"),
        subtitle = uiText("把网址解析成 IP 地址。不确定就选“系统 DNS”。", "Turns site names into IP addresses. Pick System DNS if unsure.", "Переводит адреса сайтов в IP. Если не уверены, выберите системный DNS."),
        note = uiText("连接中保存会自动重连 VPN 生效。", "Saving while connected reconnects the VPN to apply it.", "При сохранении во время подключения VPN переподключится."),
        canSave = canSave,
        onDismiss = onDismiss,
        onSave = { onConfirm(mode, if (mode == AppDnsMode.Custom) DnsInput.normalize(custom) else custom) },
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceOptionCard(
                        icon = Icons.Rounded.Smartphone,
                        title = uiText("系统 DNS", "System DNS", "Системный DNS"),
                        badge = uiText("推荐", "Recommended", "Рекомендуется"),
                        subtitle = uiText("跟随手机当前 Wi-Fi 或移动网络的 DNS。", "Uses the DNS of the current Wi-Fi or mobile network.", "DNS текущей сети Wi-Fi или мобильной сети."),
                        selected = mode == AppDnsMode.System,
                        onClick = { mode = AppDnsMode.System },
                    )
                    ChoiceOptionCard(
                        icon = Icons.Rounded.Public,
                        title = "Cloudflare 1.1.1.1",
                        subtitle = uiText("经代理查询，避开本地网络的 DNS 污染。", "Queried through the proxy, away from local DNS tampering.", "Запросы через прокси, без подмены DNS в локальной сети."),
                        selected = mode == AppDnsMode.Remote,
                        onClick = { mode = AppDnsMode.Remote },
                    )
                    ChoiceOptionCard(
                        icon = Icons.Rounded.Edit,
                        title = uiText("手动输入", "Enter manually", "Вручную"),
                        subtitle = if (mode == AppDnsMode.Custom || custom.isBlank()) {
                            uiText("填写 DNS 服务器 IP，或加密 DNS（DoH / DoT / DoQ）地址。", "A DNS server IP, or an encrypted DNS (DoH / DoT / DoQ) address.", "IP DNS-сервера или адрес зашифрованного DNS (DoH / DoT / DoQ).")
                        } else {
                            uiText("上次填写：$custom", "Last used: $custom", "Ранее: $custom")
                        },
                        selected = mode == AppDnsMode.Custom,
                        onClick = { mode = AppDnsMode.Custom },
                    )
                }

                if (mode == AppDnsMode.Custom) {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = custom,
                        // A Chinese input method types 。 and ： for . and :; show and keep ASCII.
                        onValueChange = { custom = DnsInput.toHalfWidth(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = compactInputTextStyle(),
                        label = { CompactInputLabel(uiText("DNS 服务器地址", "DNS server address", "Адрес DNS-сервера")) },
                        placeholder = { CompactInputPlaceholder("8.8.8.8 / https://… / tls://… / quic://…") },
                        isError = custom.isNotBlank() && !customValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceLow,
                            unfocusedContainerColor = SurfaceLow,
                            errorContainerColor = SurfaceLow,
                            focusedBorderColor = Primary.copy(alpha = 0.4f),
                            unfocusedBorderColor = Color.Transparent,
                            errorBorderColor = Error.copy(alpha = 0.6f),
                            cursorColor = Primary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                    )
                    val encrypted = DnsInput.encryptedOf(custom)
                    val unreachableDoq = customValid && DnsInput.isUnreachableDoq(custom, region)
                    Text(
                        text = when {
                            custom.isNotBlank() && DnsInput.isRefusedByAndroid(custom) -> uiText(
                                "本机回环（127.x、::1）和未指定地址（0.0.0.0、::）不能用作 VPN 的 DNS，Android 会拒绝。请填写真实的 DNS 服务器地址。",
                                "Loopback (127.x, ::1) and unspecified (0.0.0.0, ::) addresses can't be the VPN's DNS: Android refuses them. Enter a real DNS server.",
                                "Адреса loopback (127.x, ::1) и неуказанные (0.0.0.0, ::) нельзя использовать как DNS для VPN: Android их отклоняет. Укажите настоящий DNS-сервер.",
                            )
                            custom.isNotBlank() && !customValid -> uiText(
                                "请填 IP 地址（端口固定 53），或加密 DNS 地址：https://…（DoH）、tls://…（DoT）、quic://…（DoQ）。",
                                "Enter an IP (port 53), or an encrypted DNS address: https://… (DoH), tls://… (DoT), quic://… (DoQ).",
                                "Введите IP (порт 53) или адрес зашифрованного DNS: https://… (DoH), tls://… (DoT), quic://… (DoQ).",
                            )
                            unreachableDoq -> uiText(
                                "国外的 DoQ 服务器在国内通常连不上：DoQ 只能直连，不能经代理。请改用同一服务的 DoH（https://…）或 DoT（tls://…），它们会自动经代理连接。",
                                "Foreign DoQ servers usually can't be reached from China: DoQ only connects directly, never through the proxy. Use the same service's DoH (https://…) or DoT (tls://…) instead; they go through the proxy automatically.",
                                "Зарубежные DoQ-серверы обычно недоступны из Китая: DoQ подключается только напрямую, без прокси. Используйте DoH (https://…) или DoT (tls://…) того же сервиса — они автоматически идут через прокси.",
                            )
                            encrypted?.kind == DnsInput.EncryptedDns.Kind.DoQ -> uiText(
                                "加密 DNS（DoQ）：查询加密发送，DoQ 始终直连服务器，不经代理。" + ADDRESS_ONLY_DNS_NOTE_ZH,
                                "Encrypted DNS (DoQ): queries are encrypted; DoQ always connects directly, not through the proxy." + ADDRESS_ONLY_DNS_NOTE_EN,
                                "Зашифрованный DNS (DoQ): запросы шифруются; DoQ всегда подключается напрямую, без прокси." + ADDRESS_ONLY_DNS_NOTE_RU,
                            )
                            encrypted?.kind == DnsInput.EncryptedDns.Kind.DoH -> uiText(
                                "加密 DNS（DoH）：查询加密发送，按分流规则直连或经代理连接服务器（国内区域下，Google、Cloudflare 等国外服务器固定经代理）。" + ADDRESS_ONLY_DNS_NOTE_ZH,
                                "Encrypted DNS (DoH): queries are encrypted and reach the server direct or through the proxy, following your rules (in the China region, foreign servers such as Google and Cloudflare always go through the proxy)." + ADDRESS_ONLY_DNS_NOTE_EN,
                                "Зашифрованный DNS (DoH): запросы шифруются и идут к серверу напрямую или через прокси по правилам (в регионе «Китай» зарубежные серверы, например Google и Cloudflare, всегда идут через прокси)." + ADDRESS_ONLY_DNS_NOTE_RU,
                            )
                            encrypted != null -> uiText(
                                "加密 DNS（${encrypted.label}）：查询加密发送，按分流规则直连或经代理连接服务器（国内区域下，Google、Cloudflare 等国外服务器固定经代理），支持所有记录类型。",
                                "Encrypted DNS (${encrypted.label}): queries are encrypted and reach the server direct or through the proxy, following your rules (in the China region, foreign servers such as Google and Cloudflare always go through the proxy). All record types are supported.",
                                "Зашифрованный DNS (${encrypted.label}): запросы шифруются и идут к серверу напрямую или через прокси по правилам (в регионе «Китай» зарубежные серверы, например Google и Cloudflare, всегда идут через прокси). Поддерживаются все типы записей.",
                            )
                            else -> uiText("也可以直接点下面的常用服务器：", "Or pick a common server:", "Или выберите популярный сервер:")
                        },
                        color = if ((custom.isNotBlank() && !customValid) || unreachableDoq) Error else TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DnsPresetRow(DnsInput.presets, custom) { custom = it }
                    Text(
                        text = uiText("加密 DNS", "Encrypted DNS", "Зашифрованный DNS"),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp, start = 4.dp),
                    )
                    DnsPresetRow(DnsInput.encryptedPresets, custom) { custom = it }
                }

    }
}

// DoH / DoQ go through Xray's DNS module, which only resolves addresses
// (see XrayConfigFactory.encryptedDnsForwardsAllQueryTypes).
private const val ADDRESS_ONLY_DNS_NOTE_ZH =
    "仅解析 IPv4 / IPv6 地址（A / AAAA）；HTTPS、SVCB、TXT、SRV 等其他记录会得到空结果，依赖它们的应用可能无法正常工作。需要完整支持请改用 DoT（tls://…）。"
private const val ADDRESS_ONLY_DNS_NOTE_EN =
    " Only IPv4 / IPv6 addresses (A / AAAA) are resolved; HTTPS, SVCB, TXT, SRV and other records get an empty answer, so apps that rely on them may not work. Use DoT (tls://…) for full support."
private const val ADDRESS_ONLY_DNS_NOTE_RU =
    " Разрешаются только адреса IPv4 / IPv6 (A / AAAA); на HTTPS, SVCB, TXT, SRV и другие записи приходит пустой ответ, поэтому зависящие от них приложения могут не работать. Для полной поддержки используйте DoT (tls://…)."

/** A scrollable row of DNS servers; tapping one fills the address box. */
@Composable
private fun DnsPresetRow(presets: List<DnsInput.Preset>, current: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val selected = current.trim() == preset.address
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Primary.copy(alpha = 0.18f) else SurfaceLow)
                    .border(1.dp, if (selected) Primary.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onPick(preset.address) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(uiText(preset.name), color = if (selected) Primary else TextPrimary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine, fontWeight = FontWeight.SemiBold)
                Text(preset.address.removePrefix("https://").removeSuffix("/dns-query"), color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine)
            }
        }
    }
}

/** Log level: four explained cards. */
@Composable
internal fun LanguageDialog(
    selected: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (AppLanguage) -> Unit,
) {
    var pending by remember(selected) { mutableStateOf(selected) }
    ChoiceDialogFrame(
        icon = Icons.Rounded.Translate,
        title = uiText("界面语言", "Display language", "Язык интерфейса"),
        subtitle = uiText("选择 App 显示的语言。", "Choose the language the app is shown in.", "Выберите язык приложения."),
        note = null,
        canSave = true,
        onDismiss = onDismiss,
        onSave = { onConfirm(pending) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppLanguage.entries.forEach { language ->
                ChoiceOptionCard(
                    icon = if (language == AppLanguage.System) Icons.Rounded.Smartphone else Icons.Rounded.Translate,
                    title = languageNativeName(language),
                    subtitle = when (language) {
                        AppLanguage.System -> uiText("跟随手机的系统语言。", "Follows the phone's language.", "Как в системе телефона.")
                        AppLanguage.Chinese -> uiText("简体中文", "Chinese", "Китайский")
                        AppLanguage.English -> uiText("英语", "English", "Английский")
                        AppLanguage.Russian -> uiText("俄语", "Russian", "Русский")
                    },
                    selected = pending == language,
                    onClick = { pending = language },
                )
            }
        }
    }
}

/** A language's name in that language, so anyone can find their own. */
@Composable
internal fun languageNativeName(language: AppLanguage): String = when (language) {
    AppLanguage.System -> uiText("跟随系统", "System", "Системный")
    AppLanguage.Chinese -> "中文"
    AppLanguage.English -> "English"
    AppLanguage.Russian -> "Русский"
}

@Composable
internal fun LogLevelDialog(
    selected: AppLogLevel,
    onDismiss: () -> Unit,
    onConfirm: (AppLogLevel) -> Unit,
) {
    var pending by remember(selected) { mutableStateOf(selected) }
    ChoiceDialogFrame(
        icon = Icons.Rounded.Description,
        title = uiText("日志级别", "Log level", "Уровень журналов"),
        subtitle = uiText("决定内核日志记多详细。平时用“警告”就够了。", "How much detail the core logs. Warning is enough for everyday use.", "Насколько подробно ядро ведёт журнал. Для обычного использования хватит «Предупреждений»."),
        note = uiText(
            "下次连接时生效。日志只保存在本机，可在“查看”里浏览。访问日志（连接过的网站）只在“信息”和“调试”级别记录，调回“警告”或“错误”后会在下次连接时清除。",
            "Applies from the next connection. Logs stay on this phone; open them with View. The access log (sites connected to) is only kept at Info and Debug, and is cleared on the next connection after switching back to Warning or Error.",
            "Применится при следующем подключении. Журналы хранятся только на телефоне, откройте их через «Просмотр». Журнал доступа (посещённые сайты) ведётся только на уровнях «Информация» и «Отладка» и очищается при следующем подключении после возврата к «Предупреждениям» или «Ошибкам».",
        ),
        canSave = true,
        onDismiss = onDismiss,
        onSave = { onConfirm(pending) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppLogLevel.entries.forEach { level ->
                val (icon, title, hint) = when (level) {
                    AppLogLevel.Error -> Triple(
                        Icons.Rounded.ErrorOutline,
                        uiText("错误", "Error", "Ошибки"),
                        uiText("只记录出错信息，日志最少。", "Only errors; the smallest log.", "Только ошибки, самый короткий журнал."),
                    )
                    AppLogLevel.Warning -> Triple(
                        Icons.Rounded.WarningAmber,
                        uiText("警告", "Warning", "Предупреждения"),
                        uiText("错误加上值得注意的情况，日常使用推荐。", "Errors plus things worth noticing; best for everyday use.", "Ошибки и важные события; для повседневного использования."),
                    )
                    AppLogLevel.Info -> Triple(
                        Icons.Rounded.Info,
                        uiText("信息", "Info", "Информация"),
                        uiText("记录更多运行过程，排查连不上、分流不对时用。", "More about what the core does; for connection or routing problems.", "Больше подробностей о работе ядра; для поиска проблем с подключением или маршрутизацией."),
                    )
                    AppLogLevel.Debug -> Triple(
                        Icons.Rounded.BugReport,
                        uiText("调试", "Debug", "Отладка"),
                        uiText("记录全部细节，日志增长很快，查完问题记得调回来。", "Every detail; the log grows fast, so switch back when done.", "Все подробности; журнал растёт быстро, верните уровень после проверки."),
                    )
                }
                ChoiceOptionCard(
                    icon = icon,
                    title = "$title · ${level.wireValue}",
                    badge = if (level == AppLogLevel.Warning) uiText("推荐", "Recommended", "Рекомендуется") else null,
                    subtitle = hint,
                    selected = pending == level,
                    onClick = { pending = level },
                )
            }
        }
    }
}

/**
 * Explains the Quick Settings tile before Android's own confirm (which apps
 * can't restyle). On Android 12 and older, where apps can't ask, it shows the
 * manual steps instead.
 */
/** How to turn on Android's Always-on VPN for this app (the system has no API to do it). */
@Composable
internal fun AlwaysOnVpnDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ChoiceDialogFrame(
        icon = Icons.Rounded.ToggleOn,
        title = uiText("开机自动连接", "Connect on boot", "Подключение при загрузке"),
        subtitle = uiText(
            "由 Android 的“始终开启的 VPN”完成：开机、应用更新后，系统会自动启动 PurpleBear 并连接上次的节点。",
            "Done by Android's Always-on VPN: after the phone starts or the app updates, the system starts PurpleBear and connects to the last node.",
            "Это делает «Постоянная VPN» Android: после загрузки телефона или обновления приложения система запускает PurpleBear и подключает последний узел.",
        ),
        note = uiText(
            "同一页的“屏蔽未使用 VPN 的连接”请按需打开：打开后不能在 App 里手动断开；分应用模式下，没选中的应用会完全断网。",
            "Turn on \"Block connections without VPN\" on the same page only if you want it: with it on, the VPN can't be turned off from the app, and in per-app mode the apps you didn't select lose all network access.",
            "«Блокировать соединения без VPN» на той же странице включайте только при необходимости: тогда VPN нельзя выключить из приложения, а в режиме по приложениям невыбранные приложения остаются без сети.",
        ),
        canSave = true,
        onDismiss = onDismiss,
        onSave = onOpenSettings,
        saveText = uiText("打开系统 VPN 设置", "Open system VPN settings", "Открыть настройки VPN"),
    ) {
        Text(
            text = uiText(
                "1. 在系统 VPN 列表里点 PurpleBear 右边的齿轮。\n2. 打开“始终开启的 VPN”。\n\n需要先在 PurpleBear 里成功连接过一次。",
                "1. In the system VPN list, tap the gear next to PurpleBear.\n2. Turn on \"Always-on VPN\".\n\nConnect once in PurpleBear first.",
                "1. В списке VPN нажмите шестерёнку рядом с PurpleBear.\n2. Включите «Постоянная VPN».\n\nСначала один раз подключитесь в PurpleBear.",
            ),
            color = TextPrimary,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
        )
    }
}

@Composable
internal fun QuickTileDialog(
    canRequest: Boolean,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    ChoiceDialogFrame(
        icon = Icons.Rounded.ToggleOn,
        title = uiText("添加快捷开关", "Add a Quick Settings tile", "Плитка быстрых настроек"),
        subtitle = uiText("下拉通知栏就能一键连接或断开，不用打开 App。", "Connect or disconnect from the notification shade without opening the app.", "Подключайтесь и отключайтесь из шторки, не открывая приложение."),
        note = null,
        canSave = true,
        onDismiss = onDismiss,
        onSave = if (canRequest) onContinue else onDismiss,
        saveText = if (canRequest) uiText("继续", "Continue", "Продолжить") else uiText("知道了", "Got it", "Понятно"),
        dismissText = if (canRequest) "" else null,
    ) {
        // A mock of the tile as it appears in the shade.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceLow)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Primary)
                    .padding(start = 16.dp, end = 26.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_vpn_tile), contentDescription = null, tint = AccentContentColor, modifier = Modifier.size(26.dp))
                Column {
                    Text(stringResource(R.string.qs_tile_label), color = AccentContentColor, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine, fontWeight = FontWeight.Bold)
                    Text(uiText("点按连接 / 断开", "Tap to connect / disconnect", "Нажмите, чтобы подключиться"), color = AccentContentColor.copy(alpha = 0.8f), fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine)
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        val steps = if (canRequest) {
            listOf(
                uiText("点“继续”后，系统会弹出确认框。", "Tap Continue and Android shows a confirmation.", "Нажмите «Продолжить» — Android покажет подтверждение."),
                uiText("在系统确认框里选“添加”，开关就会出现在下拉菜单里。", "Choose Add tile there; the tile appears in the shade.", "Выберите «Добавить» — плитка появится в шторке."),
            )
        } else {
            listOf(
                uiText("从屏幕顶部向下滑两次，展开快捷设置。", "Swipe down twice from the top of the screen to open Quick Settings.", "Дважды проведите вниз от верха экрана, чтобы открыть быстрые настройки."),
                uiText("点“编辑”（铅笔图标）。", "Tap Edit (the pencil icon).", "Нажмите «Изменить» (значок карандаша)."),
                uiText("找到“PurpleBear VPN”，按住拖到上方。", "Find \"PurpleBear VPN\" and drag it up.", "Найдите «PurpleBear VPN» и перетащите наверх."),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", color = Primary, fontSize = TypeScale.Tiny, fontWeight = FontWeight.Bold)
                    }
                    Text(step, color = TextPrimary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Fallback check interval: three explained cards. */
@Composable
internal fun HeartbeatIntervalDialog(
    selectedMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var pending by remember(selectedMinutes) {
        mutableStateOf(selectedMinutes.takeIf { it in HeartbeatIntervalOptionsMinutes } ?: 5)
    }
    ChoiceDialogFrame(
        icon = Icons.Rounded.MonitorHeart,
        title = uiText("备用节点心跳", "Fallback check", "Проверка резервного узла"),
        subtitle = uiText(
            "连接后定时检测当前节点，连续 3 次失败就自动换到它的备用节点。",
            "While connected, the node is checked regularly; after 3 failures in a row the app switches to its fallback.",
            "Во время подключения узел регулярно проверяется; после 3 неудач подряд приложение переключается на резервный.",
        ),
        note = uiText(
            "只对设置了备用节点的线路生效（在节点详情里设置）。下一次检测起生效，不用重连。",
            "Only for nodes with a fallback (set in node details). Applies from the next check; no reconnect needed.",
            "Только для узлов с резервным (задаётся в деталях узла). Применится со следующей проверки, переподключение не нужно.",
        ),
        canSave = true,
        onDismiss = onDismiss,
        onSave = { onConfirm(pending) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeartbeatIntervalOptionsMinutes.forEach { minutes ->
                val (icon, hint) = when (minutes) {
                    2 -> Icons.Rounded.Bolt to uiText(
                        "发现断线最快，最多约 6 分钟切换；检测更频繁，稍费电。",
                        "Spots outages fastest (switches within about 6 min); checks more often, uses a bit more battery.",
                        "Быстрее всего замечает сбои (переключение примерно за 6 мин); чаще проверяет, чуть больше расход батареи.",
                    )
                    5 -> Icons.Rounded.Schedule to uiText(
                        "兼顾速度和耗电，最多约 15 分钟切换。",
                        "Balances speed and battery; switches within about 15 min.",
                        "Баланс скорости и батареи; переключение примерно за 15 мин.",
                    )
                    else -> Icons.Rounded.BatterySaver to uiText(
                        "最省电，但最多约 30 分钟才会切换。",
                        "Easiest on the battery, but switching can take about 30 min.",
                        "Экономнее всего, но переключение может занять около 30 мин.",
                    )
                }
                ChoiceOptionCard(
                    icon = icon,
                    title = uiText("每 $minutes 分钟", "Every $minutes min", "Каждые $minutes мин"),
                    badge = if (minutes == 5) uiText("推荐", "Recommended", "Рекомендуется") else null,
                    subtitle = hint,
                    selected = pending == minutes,
                    onClick = { pending = minutes },
                )
            }
        }
    }
}

/** VPN MTU: four explained cards. */
@Composable
internal fun VpnMtuDialog(
    selectedMtu: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var pending by remember(selectedMtu) { mutableStateOf(normalizedAppVpnMtu(selectedMtu)) }
    ChoiceDialogFrame(
        icon = Icons.Rounded.SettingsEthernet,
        title = "VPN MTU",
        subtitle = uiText(
            "每个数据包的最大大小。不清楚就选 1400；网页加载到一半卡住、下载停住时可以调低。",
            "The largest packet size. Keep 1400 if unsure; lower it if pages stall half-loaded or downloads stop.",
            "Максимальный размер пакета. Если не уверены, оставьте 1400; уменьшите, если страницы зависают на полпути или загрузки останавливаются.",
        ),
        note = uiText("连接中保存会自动重连 VPN 生效。", "Saving while connected reconnects the VPN to apply it.", "При сохранении во время подключения VPN переподключится."),
        canSave = true,
        onDismiss = onDismiss,
        onSave = { onConfirm(pending) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppVpnMtuOptions.forEach { mtu ->
                val (icon, title, hint) = when (mtu) {
                    1400 -> Triple(
                        Icons.Rounded.Smartphone,
                        uiText("1400 · 最稳", "1400 · Steadiest", "1400 · Надёжнее всего"),
                        uiText("移动网络、PPPoE 宽带、Hysteria2 / QUIC 节点，或不确定时用。", "Mobile data, PPPoE broadband, Hysteria2 / QUIC nodes, or when unsure.", "Мобильная сеть, PPPoE, узлы Hysteria2 / QUIC или если не уверены."),
                    )
                    1420 -> Triple(
                        Icons.Rounded.Wifi,
                        uiText("1420 · 稳妥", "1420 · Safe", "1420 · Безопасно"),
                        uiText("普通宽带，偶尔出现网页加载一半或下载停住时用。", "Regular broadband that sometimes leaves pages half-loaded or downloads stuck.", "Обычный интернет, когда иногда зависают страницы или загрузки."),
                    )
                    1460 -> Triple(
                        Icons.Rounded.Speed,
                        uiText("1460 · 偏性能", "1460 · Faster", "1460 · Быстрее"),
                        uiText("网络稳定、没有卡顿时，可以稍微提升吞吐。", "Stable networks with no stalls; slightly better throughput.", "Стабильная сеть без зависаний; немного выше скорость."),
                    )
                    else -> Triple(
                        Icons.Rounded.Lan,
                        uiText("1500 · 最大", "1500 · Maximum", "1500 · Максимум"),
                        uiText("只建议在直连宽带、局域网，或确认线路支持 1500 时用。", "Only for direct broadband, LAN, or links known to carry 1500.", "Только для прямого подключения, локальной сети или линий с поддержкой 1500."),
                    )
                }
                ChoiceOptionCard(
                    icon = icon,
                    title = title,
                    badge = if (mtu == 1400) uiText("推荐", "Recommended", "Рекомендуется") else null,
                    subtitle = hint,
                    selected = pending == mtu,
                    onClick = { pending = mtu },
                )
            }
        }
    }
}

@Composable
internal fun ChoiceOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Primary.copy(alpha = if (IsLightTheme) 0.10f else 0.16f) else SurfaceLow)
            .border(1.5.dp, if (selected) Primary.copy(alpha = 0.7f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) Primary.copy(alpha = 0.22f) else Primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = TextPrimary, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine, fontWeight = FontWeight.Bold)
                if (badge != null) {
                    Text(
                        text = badge,
                        color = Primary,
                        fontSize = TypeScale.Tiny,
                        lineHeight = TypeScale.TinyLine,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Primary.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(subtitle, color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) Primary else Color.Transparent)
                .border(1.5.dp, if (selected) Primary else TextSecondary.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = AccentContentColor, modifier = Modifier.size(15.dp))
        }
    }
}
