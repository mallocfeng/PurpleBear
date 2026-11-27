package com.mallocgfw.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mallocgfw.app.mcp.ApprovalBroker
import com.mallocgfw.app.mcp.AuditEntry
import com.mallocgfw.app.mcp.McpAuditLog
import com.mallocgfw.app.mcp.McpConfig
import com.mallocgfw.app.mcp.McpIdleTimeoutOptions
import com.mallocgfw.app.mcp.McpRuntime
import com.mallocgfw.app.mcp.McpScope
import com.mallocgfw.app.mcp.McpSettings
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One-line state for the Settings row. */
@Composable
internal fun mcpSettingsSubtitle(): String {
    val status by McpRuntime.status.collectAsState()
    return when {
        !status.running -> uiText("未开启。让局域网或 Tailnet 里的电脑通过 AI 配置本应用。", "Off. Let an AI on a computer in your LAN or tailnet configure this app.", "Выключено. ИИ на компьютере в вашей сети или tailnet сможет настраивать приложение.")
        status.endpoint != null -> uiText("运行中 · ${status.endpoint}", "Running · ${status.endpoint}", "Работает · ${status.endpoint}")
        else -> uiText("运行中 · ${status.message.orEmpty()}", "Running · ${status.message.orEmpty()}", "Работает · ${status.message.orEmpty()}")
    }
}

@Composable
internal fun McpScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val status by McpRuntime.status.collectAsState()
    var config by remember { mutableStateOf(McpSettings.load(context)) }
    var tokenVisible by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<McpEdit?>(null) }
    var audit by remember { mutableStateOf(McpAuditLog.read(context)) }
    var showAllAudit by remember { mutableStateOf(false) }

    fun change(update: (McpConfig) -> McpConfig) {
        config = McpSettings.update(context, update)
        McpRuntime.restartIfRunning(context)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                tr3("未授予通知权限：需要确认的操作只能在打开 App 时处理。", "No notification permission: approvals only work while the app is open.", "Нет разрешения на уведомления: подтверждать можно только в открытом приложении."),
                Toast.LENGTH_LONG,
            ).show()
        }
        McpRuntime.start(context)
    }

    fun turnOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            McpRuntime.start(context)
        }
    }

    val host = status.host ?: "<${uiText("手机地址", "phone-address", "адрес-телефона")}>"
    val url = "http://$host:${config.port}/mcp"
    val codexCommand = "export PURPLEBEAR_MCP_TOKEN=${config.token}\ncodex mcp add purplebear --url $url --bearer-token-env-var PURPLEBEAR_MCP_TOKEN"
    val claudeCommand = "claude mcp add --transport http purplebear $url --header \"Authorization: Bearer ${config.token}\""

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AppTopBar(title = uiText("AI 远程控制", "AI remote control", "Удалённое управление ИИ"), onBack = onBack) }
        item {
            ScreenHeader(
                title = uiText("AI 远程控制（MCP）", "AI remote control (MCP)", "Удалённое управление ИИ (MCP)"),
                subtitle = uiText(
                    "电脑上的 Codex、Claude Code 等 AI 客户端可以通过 MCP 查询状态、切换节点、修改配置。修改前会在手机上请你确认。",
                    "AI clients such as Codex or Claude Code on your computer can check status, switch nodes and change settings over MCP. Changes ask you on the phone first.",
                    "ИИ‑клиенты на компьютере (Codex, Claude Code) могут через MCP смотреть статус, переключать узлы и менять настройки. Изменения сначала подтверждаются на телефоне.",
                ),
            )
        }
        item {
            SettingsGroup(title = uiText("服务", "Server", "Сервер")) {
                SettingToggleRow(
                    title = uiText("开启 MCP 服务", "Turn on the MCP server", "Включить сервер MCP"),
                    subtitle = mcpSettingsSubtitle(),
                    checked = status.running,
                    onToggle = { if (status.running) McpRuntime.stop(context) else turnOn() },
                )
                if (status.running) {
                    Spacer(Modifier.height(12.dp))
                    SettingInfoRow(
                        title = uiText("已处理请求", "Requests handled", "Обработано запросов"),
                        subtitle = status.requestCount.toString(),
                    )
                }
            }
        }
        item {
            SettingsGroup(title = uiText("监听范围", "Where it listens", "Где слушает")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(text = uiText("局域网（Wi-Fi）", "LAN (Wi-Fi)", "Локальная сеть (Wi-Fi)"), selected = config.scope == McpScope.Lan) {
                        change { it.copy(scope = McpScope.Lan) }
                    }
                    ModeChip(text = "Tailnet", selected = config.scope == McpScope.Tailnet) {
                        change { it.copy(scope = McpScope.Tailnet) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (config.scope == McpScope.Lan) {
                        uiText(
                            "只在当前 Wi-Fi 地址上监听，移动数据和 VPN 隧道上不开放。局域网是明文 HTTP，同一 Wi-Fi 下能抓包的人可以看到令牌：只在家里等可信网络使用，其他场合请用 Tailnet。",
                            "Listens only on the current Wi-Fi address, never on mobile data or the VPN tunnel. The LAN mode is plain HTTP: anyone who can sniff the same Wi-Fi can see the token. Use it only on trusted networks such as home; use Tailnet elsewhere.",
                            "Слушает только адрес текущей сети Wi-Fi, не мобильные данные и не туннель VPN. В локальной сети используется HTTP без шифрования: перехватив трафик в той же сети Wi-Fi, можно увидеть токен. Используйте только в доверенной сети, например дома; в остальных случаях — Tailnet.",
                        )
                    } else {
                        uiText(
                            "通过 Tailscale 在本机的 Tailnet 地址上监听，流量经 WireGuard 加密，并能按 Tailscale 用户或设备限制访问。需要先开启 Tailscale。",
                            "Listens on this phone's tailnet address through Tailscale: traffic is WireGuard-encrypted and can be limited to Tailscale users or devices. Tailscale must be on.",
                            "Слушает адрес телефона в tailnet через Tailscale: трафик шифруется WireGuard, доступ можно ограничить пользователями или устройствами Tailscale. Tailscale должен быть включён.",
                        )
                    },
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                )
                Spacer(Modifier.height(12.dp))
                SettingActionRow(
                    title = uiText("端口", "Port", "Порт"),
                    subtitle = config.port.toString(),
                    actionText = uiText("修改", "Change", "Изменить"),
                    onAction = { editing = McpEdit.Port },
                )
                Spacer(Modifier.height(12.dp))
                if (config.scope == McpScope.Lan) {
                    SettingActionRow(
                        title = uiText("IP 白名单", "IP allowlist", "Список разрешённых IP"),
                        subtitle = config.ipAllowlist.joinToString().ifBlank { uiText("未设置：任何地址都需凭令牌访问", "None: any address with the token", "Не задан: любой адрес с токеном") },
                        actionText = uiText("修改", "Change", "Изменить"),
                        onAction = { editing = McpEdit.IpAllowlist },
                    )
                } else {
                    SettingActionRow(
                        title = uiText("允许的 Tailscale 用户或设备", "Allowed Tailscale users or devices", "Разрешённые пользователи или устройства Tailscale"),
                        subtitle = config.tailnetAllowlist.joinToString().ifBlank { uiText("未设置：Tailnet 里的任何设备都需凭令牌访问", "None: any tailnet device with the token", "Не задано: любое устройство tailnet с токеном") },
                        actionText = uiText("修改", "Change", "Изменить"),
                        onAction = { editing = McpEdit.TailnetAllowlist },
                    )
                }
            }
        }
        item {
            SettingsGroup(title = uiText("令牌", "Token", "Токен")) {
                SelectionContainer {
                    Text(
                        text = if (tokenVisible) config.token else "•".repeat(12) + config.token.takeLast(4),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TypeScale.Body,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(text = if (tokenVisible) uiText("隐藏", "Hide", "Скрыть") else uiText("显示", "Show", "Показать"), selected = false) { tokenVisible = !tokenVisible }
                    ModeChip(text = uiText("复制", "Copy", "Копировать"), selected = false) { copyToClipboard(context, config.token, sensitive = true) }
                    ModeChip(text = uiText("二维码", "QR code", "QR-код"), selected = false) { showQr = true }
                    ModeChip(text = uiText("重置", "Reset", "Сбросить"), selected = false) { confirmReset = true }
                }
            }
        }
        item {
            SettingsGroup(title = uiText("连接电脑上的 AI", "Connect an AI on your computer", "Подключить ИИ на компьютере")) {
                Text(
                    text = uiText(
                        "在电脑终端运行下面的命令（地址和令牌已填好）。Codex 从环境变量读取令牌。",
                        "Run one of these in a terminal on your computer (address and token filled in). Codex reads the token from an environment variable.",
                        "Выполните одну из команд в терминале компьютера (адрес и токен подставлены). Codex читает токен из переменной окружения.",
                    ),
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                )
                Spacer(Modifier.height(12.dp))
                CommandBlock("Codex", codexCommand) { copyToClipboard(context, codexCommand, sensitive = true) }
                Spacer(Modifier.height(12.dp))
                CommandBlock("Claude Code", claudeCommand) { copyToClipboard(context, claudeCommand, sensitive = true) }
            }
        }
        item {
            SettingsGroup(title = uiText("确认策略", "Approvals", "Подтверждения")) {
                SettingToggleRow(
                    title = uiText("控制操作需要确认", "Ask before control actions", "Спрашивать перед управлением"),
                    subtitle = uiText("连接、断开、切换节点、测速、刷新订阅。", "Connect, disconnect, switch node, test latency, refresh.", "Подключение, отключение, смена узла, проверка, обновление."),
                    checked = config.approveControl,
                    onToggle = { change { it.copy(approveControl = !it.approveControl) } },
                )
                Spacer(Modifier.height(12.dp))
                SettingToggleRow(
                    title = uiText("修改操作需要确认", "Ask before changes", "Спрашивать перед изменениями"),
                    subtitle = uiText("导入节点、改规则、DNS、分流、前置代理和备用节点。", "Import nodes, rules, DNS, routing, pre-proxy and fallback.", "Импорт узлов, правила, DNS, маршрутизация, предварительный прокси и резервный узел."),
                    checked = config.approveModify,
                    onToggle = { change { it.copy(approveModify = !it.approveModify) } },
                )
                Spacer(Modifier.height(12.dp))
                SettingInfoRow(
                    title = uiText("删除和 Tailscale 设置总是需要确认", "Deletions and Tailscale changes always ask", "Удаление и настройки Tailscale всегда требуют подтверждения"),
                    subtitle = uiText("60 秒内没有处理按拒绝处理。", "No answer in 60 seconds counts as deny.", "Без ответа за 60 секунд запрос отклоняется."),
                )
                Spacer(Modifier.height(12.dp))
                SettingToggleRow(
                    title = uiText("向 AI 提供节点地址", "Share node addresses with the AI", "Передавать ИИ адреса узлов"),
                    subtitle = uiText("地址和端口方便 AI 判断节点。密码、UUID、密钥永不提供。", "Address and port help the AI tell nodes apart. Passwords, UUIDs and keys are never shared.", "Адрес и порт помогают ИИ различать узлы. Пароли, UUID и ключи не передаются никогда."),
                    checked = config.exposeAddresses,
                    onToggle = { change { it.copy(exposeAddresses = !it.exposeAddresses) } },
                )
            }
        }
        item {
            SettingsGroup(title = uiText("闲置自动关闭", "Stop when idle", "Остановка при простое")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpIdleTimeoutOptions.forEach { minutes ->
                        ModeChip(
                            text = when (minutes) {
                                0 -> uiText("不自动关闭", "Never", "Никогда")
                                60 -> uiText("1 小时", "1 hour", "1 час")
                                else -> uiText("$minutes 分钟", "$minutes min", "$minutes мин")
                            },
                            selected = config.idleTimeoutMinutes == minutes,
                        ) { change { it.copy(idleTimeoutMinutes = minutes) } }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingActionRow(
                    title = uiText("息屏后保持可连", "Stay reachable with the screen off", "Доступность при выключенном экране"),
                    subtitle = uiText(
                        "部分系统息屏后会限制 Wi-Fi。把 PurpleBear 设为不受电池优化限制更稳定。",
                        "Some phones limit Wi-Fi with the screen off. Exempting PurpleBear from battery optimization helps.",
                        "Некоторые телефоны ограничивают Wi-Fi при выключенном экране. Помогает исключение PurpleBear из оптимизации батареи.",
                    ),
                    actionText = uiText("设置", "Settings", "Настройки"),
                    onAction = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    },
                )
            }
        }
        item {
            SettingsGroup(title = uiText("调用记录", "Call log", "Журнал вызовов")) {
                if (audit.isEmpty()) {
                    Text(uiText("暂无记录。", "No calls yet.", "Вызовов пока нет."), color = TextSecondary, fontSize = TypeScale.Body)
                } else {
                    val shown = if (showAllAudit) audit else audit.take(20)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        shown.forEach { AuditRow(it) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(text = uiText("刷新", "Refresh", "Обновить"), selected = false) { audit = McpAuditLog.read(context) }
                    if (audit.size > 20) {
                        ModeChip(
                            text = if (showAllAudit) uiText("只看最近 20 条", "Latest 20 only", "Только последние 20") else uiText("全部 ${audit.size} 条", "All ${audit.size}", "Все (${audit.size})"),
                            selected = false,
                        ) { showAllAudit = !showAllAudit }
                    }
                    ModeChip(text = uiText("清空", "Clear", "Очистить"), selected = false) {
                        McpAuditLog.clear(context)
                        audit = emptyList()
                    }
                }
            }
        }
    }

    if (showQr) {
        val payload = JSONObject().put("url", url).put("token", config.token).toString()
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text(uiText("配对二维码", "Pairing QR code", "QR-код для подключения"), fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QrCode(payload, Modifier.size(240.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(uiText("包含地址和令牌，不要给别人看。", "Contains the address and token. Don't show it to others.", "Содержит адрес и токен. Не показывайте его другим."), color = TextSecondary, fontSize = TypeScale.Body)
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(uiText("关闭", "Close", "Закрыть"), color = Primary) } },
            containerColor = SurfaceHigh,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(uiText("重置令牌？", "Reset the token?", "Сбросить токен?"), fontWeight = FontWeight.ExtraBold) },
            text = { Text(uiText("已配置的电脑需要换成新令牌才能继续连接。", "Computers set up with the old token will need the new one.", "Компьютерам со старым токеном понадобится новый."), color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    change { it.copy(token = McpSettings.newToken()) }
                    confirmReset = false
                    tokenVisible = true
                }) { Text(uiText("重置", "Reset", "Сбросить"), color = Error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(uiText("取消")) } },
            containerColor = SurfaceHigh,
        )
    }

    editing?.let { edit ->
        val initial = when (edit) {
            McpEdit.Port -> config.port.toString()
            McpEdit.IpAllowlist -> config.ipAllowlist.joinToString(", ")
            McpEdit.TailnetAllowlist -> config.tailnetAllowlist.joinToString(", ")
        }
        McpTextDialog(
            title = when (edit) {
                McpEdit.Port -> uiText("端口", "Port", "Порт")
                McpEdit.IpAllowlist -> uiText("IP 白名单", "IP allowlist", "Список разрешённых IP")
                McpEdit.TailnetAllowlist -> uiText("允许的 Tailscale 用户或设备", "Allowed Tailscale users or devices", "Разрешённые пользователи или устройства Tailscale")
            },
            hint = when (edit) {
                McpEdit.Port -> "1024 – 65535"
                McpEdit.IpAllowlist -> uiText("用逗号分隔，例如 192.168.1.10；留空表示不限制", "Comma-separated, e.g. 192.168.1.10; empty: no limit", "Через запятую, напр. 192.168.1.10; пусто — без ограничений")
                McpEdit.TailnetAllowlist -> uiText("登录名或设备名，用逗号分隔，例如 me@example.com, my-mac", "Login names or device names, comma-separated, e.g. me@example.com, my-mac", "Логины или имена устройств через запятую, напр. me@example.com, my-mac")
            },
            initial = initial,
            onDismiss = { editing = null },
            onSave = { value ->
                val list = value.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                when (edit) {
                    McpEdit.Port -> {
                        val port = value.trim().toIntOrNull()
                        if (port == null || port !in 1024..65535) {
                            Toast.makeText(context, tr3("端口需要在 1024–65535 之间。", "The port must be 1024–65535.", "Порт должен быть от 1024 до 65535."), Toast.LENGTH_SHORT).show()
                            return@McpTextDialog
                        }
                        change { it.copy(port = port) }
                    }
                    McpEdit.IpAllowlist -> change { it.copy(ipAllowlist = list) }
                    McpEdit.TailnetAllowlist -> change { it.copy(tailnetAllowlist = list) }
                }
                editing = null
            },
        )
    }
}

private enum class McpEdit { Port, IpAllowlist, TailnetAllowlist }

/** Shows the oldest pending MCP approval as a dialog while the app is open. */
@Composable
internal fun McpApprovalDialogHost() {
    val pending by ApprovalBroker.pending.collectAsState()
    val request = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                if (request.dangerous) {
                    uiText("AI 请求高风险操作", "AI asks for a risky change", "ИИ просит рискованное действие")
                } else {
                    uiText("AI 请求操作 PurpleBear", "AI asks to change PurpleBear", "ИИ просит изменить PurpleBear")
                },
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.summary, color = TextPrimary, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                Text(uiText("来自：${request.caller}", "From: ${request.caller}", "Откуда: ${request.caller}"), color = TextSecondary, fontSize = TypeScale.Body)
                Text(uiText("60 秒内不处理将自动拒绝。", "Denied automatically after 60 seconds.", "Через 60 секунд запрос будет отклонён."), color = TextSecondary, fontSize = TypeScale.Body)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { ApprovalBroker.answer(request.id, ApprovalBroker.Answer.Allow) }) {
                    Text(uiText("允许", "Allow", "Разрешить"), color = Primary, fontWeight = FontWeight.Bold)
                }
                if (!request.dangerous) {
                    TextButton(onClick = { ApprovalBroker.answer(request.id, ApprovalBroker.Answer.AllowSimilar) }) {
                        Text(uiText("10 分钟内同类都允许", "Allow similar for 10 min", "Разрешать такие 10 мин"), color = Primary)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { ApprovalBroker.answer(request.id, ApprovalBroker.Answer.Deny) }) {
                Text(uiText("拒绝", "Deny", "Отклонить"), color = Error, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceHigh,
    )
}

@Composable
private fun CommandBlock(label: String, command: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = TypeScale.Body, modifier = Modifier.weight(1f))
            TextButton(onClick = onCopy) { Text(uiText("复制", "Copy", "Копировать"), color = Primary) }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceLow)
                .padding(10.dp),
        ) {
            // The token stays masked on screen; Copy puts the real command on the clipboard.
            Text(
                text = command.replace(Regex("""(PURPLEBEAR_MCP_TOKEN=|Bearer )[A-Za-z0-9_-]{8,}"""), "$1••••••"),
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = TypeScale.Meta,
            )
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    val time = remember(entry.timeMs) { SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(entry.timeMs)) }
    Column {
        Text(
            "${entry.tool} · ${if (entry.result == "ok") uiText("成功", "OK", "Успешно") else uiText("失败", "Failed", "Ошибка")}" +
                (entry.approvedBy?.let { " · " + uiText("已在手机确认", "approved on phone", "подтверждено на телефоне") } ?: ""),
            color = if (entry.result == "ok") TextPrimary else Error,
            fontWeight = FontWeight.Bold,
            fontSize = TypeScale.Body,
        )
        Text("$time · ${entry.caller}", color = TextSecondary, fontSize = TypeScale.Meta)
        Text(entry.summary, color = TextSecondary, fontSize = TypeScale.Meta)
        if (entry.result != "ok") Text(entry.result, color = TextSecondary, fontSize = TypeScale.Meta)
    }
}

@Composable
private fun QrCode(content: String, modifier: Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier = modifier.background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

@Composable
private fun McpTextDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = false,
                )
                Text(hint, color = TextSecondary, fontSize = TypeScale.Meta)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(uiText("保存"), color = Primary, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("取消")) } },
        containerColor = SurfaceHigh,
    )
}

internal fun copyToClipboard(context: Context, text: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("PurpleBear MCP", text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, com.mallocgfw.app.model.tr("已复制", "Copied", "Скопировано"), Toast.LENGTH_SHORT).show()
    }
}

private fun tr3(zh: String, en: String, ru: String) = com.mallocgfw.app.model.tr(zh, en, ru)

