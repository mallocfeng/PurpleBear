package com.mallocgfw.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.LocalNodeDraft
import com.mallocgfw.app.model.LocalNodeProtocol
import com.mallocgfw.app.model.LocalNodeSecurity
import com.mallocgfw.app.model.LocalNodeShadowsocksMethod
import com.mallocgfw.app.model.LocalNodeTransport
import com.mallocgfw.app.model.LocalNodeWireGuardDomainStrategy
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceHigh
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary

@Composable
internal fun LocalNodeBuilderScreen(
    padding: PaddingValues,
    draft: LocalNodeDraft,
    compatibilityWarning: String?,
    message: String?,
    editing: Boolean,
    onBack: () -> Unit,
    onDraftChange: (LocalNodeDraft) -> Unit,
    onSave: () -> Unit,
) {
    val protocol = draft.protocol
    var advancedExpanded by rememberSaveable(protocol) { mutableStateOf(false) }
    var prefillDialogVisible by rememberSaveable { mutableStateOf(false) }
    var inlineMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val transportChoices = ManualNodeFactory.transportOptions(protocol).map {
        BuilderDropdownOption(it, it.displayName)
    }
    val securityChoices = ManualNodeFactory.securityOptions(protocol).map {
        BuilderDropdownOption(it, it.displayName)
    }
    val shadowsocksChoices = LocalNodeShadowsocksMethod.entries.map {
        BuilderDropdownOption(it, it.displayName)
    }
    val wireGuardStrategyChoices = LocalNodeWireGuardDomainStrategy.entries.map {
        BuilderDropdownOption(it, it.displayName)
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
            AppTopBar(title = if (editing) "编辑节点" else "新建节点", subtitle = "Local", onBack = onBack)
        }
        item {
            ScreenHeader(
                title = if (editing) "编辑 Local 节点" else "手动新建客户端节点",
                subtitle = if (editing) {
                    "编辑 Local 节点参数。"
                } else {
                    "填写必要参数生成 Local 节点。"
                },
            )
        }
        compatibilityWarning?.takeIf { it.isNotBlank() }?.let { warning ->
            item { NoteBox(text = warning) }
        }
        (inlineMessage ?: message)?.takeIf { it.isNotBlank() }?.let { text ->
            item { NoteBox(text = text) }
        }
        item {
            SurfaceCard {
                Eyebrow("基础信息")
                Spacer(modifier = Modifier.height(12.dp))
                BuilderTextField(
                    label = "节点名称",
                    value = draft.nodeName,
                    onValueChange = { onDraftChange(draft.copy(nodeName = it)) },
                    placeholder = ManualNodeFactory.exampleName(protocol),
                    helper = "可留空。留空时会按协议和地址自动命名。",
                )
                Spacer(modifier = Modifier.height(12.dp))
                BuilderDropdownField(
                    label = "节点协议",
                    selected = protocol,
                    options = ManualNodeFactory.protocolOptions.map {
                        BuilderDropdownOption(it, it.displayName, it.subtitle)
                    },
                    onSelected = { next ->
                        inlineMessage = null
                        onDraftChange(ManualNodeFactory.applyProtocolDefaults(draft, next))
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BuilderSmallActionButton(
                        text = "套用示例",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            inlineMessage = "已套用 ${protocol.displayName} 示例模板。"
                            onDraftChange(ManualNodeFactory.exampleDraft(protocol))
                        },
                    )
                    BuilderSmallActionButton(
                        text = "分享链接预填",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            inlineMessage = null
                            prefillDialogVisible = true
                        },
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                BuilderTextField(
                    label = "服务端地址",
                    value = draft.address,
                    onValueChange = { onDraftChange(draft.copy(address = it)) },
                    placeholder = ManualNodeFactory.exampleAddress(protocol),
                )
                Spacer(modifier = Modifier.height(12.dp))
                BuilderTextField(
                    label = "端口",
                    value = draft.port,
                    onValueChange = { onDraftChange(draft.copy(port = it)) },
                    placeholder = ManualNodeFactory.examplePort(protocol),
                )
                Spacer(modifier = Modifier.height(12.dp))
                NoteBox(
                    text = when (protocol) {
                        LocalNodeProtocol.HTTP -> "HTTP 代理不加密，只适合你明确知道链路安全的场景。"
                        LocalNodeProtocol.SOCKS -> "SOCKS5 不加密，适合上游代理。"
                        LocalNodeProtocol.WIREGUARD -> "按 Xray WireGuard 出站结构写入。"
                        LocalNodeProtocol.HYSTERIA -> "按 Hysteria2 出站结构写入。"
                        else -> protocol.subtitle
                    },
                )
            }
        }
        item {
            SurfaceCard {
                Eyebrow("鉴权与密钥")
                Spacer(modifier = Modifier.height(12.dp))
                when (protocol) {
                    LocalNodeProtocol.VLESS -> {
                        BuilderTextField(
                            label = "UUID",
                            value = draft.userId,
                            onValueChange = { onDraftChange(draft.copy(userId = it)) },
                            placeholder = ManualNodeFactory.exampleUuid(protocol),
                        )
                        if (advancedExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            BuilderTextField(
                                label = "encryption",
                                value = draft.vlessEncryption,
                                onValueChange = { onDraftChange(draft.copy(vlessEncryption = it)) },
                                placeholder = "默认 none",
                                helper = "可留空。VLESS 客户端一般用 none。",
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            BuilderTextField(
                                label = "flow",
                                value = draft.vlessFlow,
                                onValueChange = { onDraftChange(draft.copy(vlessFlow = it)) },
                                placeholder = "例如 xtls-rprx-vision",
                                helper = "可留空。只有服务端明确要求时再填。",
                            )
                        }
                    }

                    LocalNodeProtocol.VMESS -> {
                        BuilderTextField(
                            label = "UUID",
                            value = draft.userId,
                            onValueChange = { onDraftChange(draft.copy(userId = it)) },
                            placeholder = ManualNodeFactory.exampleUuid(protocol),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BuilderDropdownField(
                            label = "VMess 加密方式",
                            selected = draft.vmessSecurity,
                            options = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero").map {
                                BuilderDropdownOption(it, it)
                            },
                            onSelected = { onDraftChange(draft.copy(vmessSecurity = it)) },
                        )
                    }

                    LocalNodeProtocol.TROJAN -> {
                        BuilderTextField(
                            label = "密码",
                            value = draft.password,
                            onValueChange = { onDraftChange(draft.copy(password = it)) },
                            placeholder = "例如 my-strong-password",
                        )
                    }

                    LocalNodeProtocol.SHADOWSOCKS -> {
                        BuilderDropdownField(
                            label = "加密方法",
                            selected = draft.shadowsocksMethod,
                            options = shadowsocksChoices,
                            onSelected = { onDraftChange(draft.copy(shadowsocksMethod = it)) },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BuilderTextField(
                            label = "密码 / 密钥",
                            value = draft.password,
                            onValueChange = { onDraftChange(draft.copy(password = it)) },
                            placeholder = "例如 super-secret-key",
                        )
                        if (advancedExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            BooleanSettingRow(
                                title = "UDP over TCP",
                                subtitle = "旧机场或特殊链路需要时再打开。默认关闭。",
                                checked = draft.shadowsocksUot,
                                onCheckedChange = { onDraftChange(draft.copy(shadowsocksUot = it)) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            BuilderTextField(
                                label = "UoT 版本",
                                value = draft.shadowsocksUotVersion,
                                onValueChange = { onDraftChange(draft.copy(shadowsocksUotVersion = it)) },
                                placeholder = "默认 2",
                                helper = "可留空。通常保持 2。",
                            )
                        }
                    }

                    LocalNodeProtocol.SOCKS,
                    LocalNodeProtocol.HTTP,
                    -> {
                        BuilderTextField(
                            label = "用户名",
                            value = draft.username,
                            onValueChange = { onDraftChange(draft.copy(username = it)) },
                            placeholder = "可留空",
                            helper = "可留空。上游代理不要求鉴权时不用填。",
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BuilderTextField(
                            label = "密码",
                            value = draft.password,
                            onValueChange = { onDraftChange(draft.copy(password = it)) },
                            placeholder = "可留空",
                            helper = "可留空。和用户名一起使用。",
                        )
                        if (protocol == LocalNodeProtocol.HTTP && advancedExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            BuilderTextField(
                                label = "自定义 HTTP 头",
                                value = draft.httpHeaders,
                                onValueChange = { onDraftChange(draft.copy(httpHeaders = it)) },
                                placeholder = "Header: Value",
                                helper = "可留空。每行一个，格式为 Header: Value。",
                                singleLine = false,
                                minLines = 4,
                            )
                        }
                    }

                    LocalNodeProtocol.WIREGUARD -> {
                        BuilderTextField(
                            label = "客户端私钥",
                            value = draft.wireGuardSecretKey,
                            onValueChange = { onDraftChange(draft.copy(wireGuardSecretKey = it)) },
                            placeholder = "32 字节 WireGuard 私钥",
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BuilderTextField(
                            label = "对端公钥",
                            value = draft.wireGuardPublicKey,
                            onValueChange = { onDraftChange(draft.copy(wireGuardPublicKey = it)) },
                            placeholder = "32 字节 WireGuard 公钥",
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BuilderTextField(
                            label = "预共享密钥",
                            value = draft.wireGuardPreSharedKey,
                            onValueChange = { onDraftChange(draft.copy(wireGuardPreSharedKey = it)) },
                            placeholder = "preSharedKey",
                            helper = "可留空。只有服务端要求时再填。",
                        )
                    }

                    LocalNodeProtocol.HYSTERIA -> {
                        BuilderTextField(
                            label = "认证口令",
                            value = draft.hysteriaAuth,
                            onValueChange = { onDraftChange(draft.copy(hysteriaAuth = it)) },
                            placeholder = "例如 hy2-password",
                        )
                        if (advancedExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            BuilderTextField(
                                label = "UDP 空闲超时（秒）",
                                value = draft.hysteriaUdpIdleTimeout,
                                onValueChange = { onDraftChange(draft.copy(hysteriaUdpIdleTimeout = it)) },
                                placeholder = "默认 60",
                                helper = "可留空。默认按 Xray 的 60 秒。",
                            )
                        }
                    }
                }
            }
        }
        item {
            SurfaceCard {
                Eyebrow("传输与安全")
                Spacer(modifier = Modifier.height(12.dp))
                if (advancedExpanded) {
                    NoteBox(
                        text = when (protocol) {
                            LocalNodeProtocol.VLESS -> "按服务端要求填写传输与安全。"
                            LocalNodeProtocol.VMESS -> "通常保持 auto。"
                            LocalNodeProtocol.TROJAN -> "通常配合 TLS 使用。"
                            LocalNodeProtocol.SHADOWSOCKS -> "按服务端提供的 method 填写。"
                            else -> "映射到 Xray streamSettings。"
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (transportChoices.isNotEmpty()) {
                    BuilderDropdownField(
                        label = "传输方式",
                        selected = draft.transport,
                        options = transportChoices,
                        onSelected = { onDraftChange(draft.copy(transport = it)) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (securityChoices.size > 1) {
                    BuilderDropdownField(
                        label = "安全层",
                        selected = draft.security,
                        options = securityChoices,
                        onSelected = { onDraftChange(draft.copy(security = it)) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    SettingInfoRow(
                        title = "安全层",
                        subtitle = securityChoices.firstOrNull()?.label ?: "固定值",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (transportChoices.isNotEmpty()) {
                    when (draft.transport) {
                        LocalNodeTransport.WS,
                        LocalNodeTransport.HTTP2,
                        LocalNodeTransport.HTTP_UPGRADE,
                        LocalNodeTransport.XHTTP,
                        -> {
                            BuilderTextField(
                                label = "Host",
                                value = draft.host,
                                onValueChange = { onDraftChange(draft.copy(host = it)) },
                                placeholder = "可留空",
                                helper = "可留空。WS / HTTP 类传输常用。",
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            BuilderTextField(
                                label = "Path",
                                value = draft.path,
                                onValueChange = { onDraftChange(draft.copy(path = it)) },
                                placeholder = "/ 或其他路径",
                                helper = "可留空。HTTP 类传输常用。",
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        LocalNodeTransport.GRPC -> {
                            BuilderTextField(
                                label = "serviceName",
                                value = draft.serviceName,
                                onValueChange = { onDraftChange(draft.copy(serviceName = it)) },
                                placeholder = "gRPC serviceName",
                                helper = "可留空。只有服务端配置了 gRPC 时才需要填。",
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        LocalNodeTransport.TCP -> Unit
                    }
                }
                if (draft.security == LocalNodeSecurity.TLS || protocol == LocalNodeProtocol.HYSTERIA) {
                    BuilderTextField(
                        label = "Server Name / SNI",
                        value = draft.serverName,
                        onValueChange = { onDraftChange(draft.copy(serverName = it)) },
                        placeholder = ManualNodeFactory.exampleServerName(protocol),
                        helper = if (protocol == LocalNodeProtocol.HYSTERIA) "建议填写，不要留空。" else "可留空。多数 TLS 节点建议填写。",
                    )
                    if (advancedExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        BuilderTextField(
                            label = "指纹 fingerprint",
                            value = draft.fingerprint,
                            onValueChange = { onDraftChange(draft.copy(fingerprint = it)) },
                            placeholder = "例如 chrome / safari",
                            helper = "可留空。uTLS / REALITY 常见。",
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BuilderTextField(
                            label = "ALPN",
                            value = draft.alpn,
                            onValueChange = { onDraftChange(draft.copy(alpn = it)) },
                            placeholder = "例如 h2,http/1.1",
                            helper = "可留空。多个值用逗号分隔。",
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BooleanSettingRow(
                            title = "跳过证书校验",
                            subtitle = "不推荐。只有你确认上游证书异常但仍可信时再打开。",
                            checked = draft.allowInsecure,
                            onCheckedChange = { onDraftChange(draft.copy(allowInsecure = it)) },
                        )
                    }
                }
                if (draft.security == LocalNodeSecurity.REALITY && advancedExpanded) {
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "REALITY 公钥",
                        value = draft.realityPublicKey,
                        onValueChange = { onDraftChange(draft.copy(realityPublicKey = it)) },
                        placeholder = "REALITY publicKey",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "shortId",
                        value = draft.realityShortId,
                        onValueChange = { onDraftChange(draft.copy(realityShortId = it)) },
                        placeholder = "可留空",
                        helper = "可留空。服务端要求时再填。",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "spiderX",
                        value = draft.realitySpiderX,
                        onValueChange = { onDraftChange(draft.copy(realitySpiderX = it)) },
                        placeholder = "可留空",
                        helper = "可留空。部分 REALITY 配置会要求。",
                    )
                }
            }
        }
        item {
            BuilderSmallActionButton(
                text = if (advancedExpanded) "收起高级选项" else "展开高级选项",
                onClick = { advancedExpanded = !advancedExpanded },
            )
        }
        if (protocol == LocalNodeProtocol.WIREGUARD && advancedExpanded) {
            item {
                SurfaceCard {
                    Eyebrow("WireGuard 进阶")
                    Spacer(modifier = Modifier.height(12.dp))
                    NoteBox(text = "地址和 Allowed IPs 需要带 CIDR。")
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "本地地址列表",
                        value = draft.wireGuardLocalAddresses,
                        onValueChange = { onDraftChange(draft.copy(wireGuardLocalAddresses = it)) },
                        placeholder = "10.0.0.2/32, fd59:.../128",
                        helper = "可留空。多个值用逗号或换行分隔。",
                        singleLine = false,
                        minLines = 3,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "Allowed IPs",
                        value = draft.wireGuardAllowedIps,
                        onValueChange = { onDraftChange(draft.copy(wireGuardAllowedIps = it)) },
                        placeholder = "0.0.0.0/0, ::0/0",
                        helper = "可留空。默认会按 Xray 的全量路由处理。",
                        singleLine = false,
                        minLines = 3,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "reserved",
                        value = draft.wireGuardReserved,
                        onValueChange = { onDraftChange(draft.copy(wireGuardReserved = it)) },
                        placeholder = "例如 1,2,3",
                        helper = "可留空。Cloudflare WARP 等部分线路会用到。",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "MTU",
                        value = draft.wireGuardMtu,
                        onValueChange = { onDraftChange(draft.copy(wireGuardMtu = it)) },
                        placeholder = "默认 1420",
                        helper = "可留空。一般保持默认。",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "workers",
                        value = draft.wireGuardWorkers,
                        onValueChange = { onDraftChange(draft.copy(wireGuardWorkers = it)) },
                        placeholder = "可留空",
                        helper = "可留空。默认按 CPU 核心数。",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderTextField(
                        label = "keepAlive",
                        value = draft.wireGuardKeepAlive,
                        onValueChange = { onDraftChange(draft.copy(wireGuardKeepAlive = it)) },
                        placeholder = "可留空",
                        helper = "可留空。单位秒。",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BuilderDropdownField(
                        label = "域名解析策略",
                        selected = draft.wireGuardDomainStrategy,
                        options = wireGuardStrategyChoices,
                        onSelected = { onDraftChange(draft.copy(wireGuardDomainStrategy = it)) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BooleanSettingRow(
                        title = "强制 noKernelTun",
                        subtitle = "默认关闭。只有你明确知道当前设备不适合 kernel tun 时再打开。",
                        checked = draft.wireGuardNoKernelTun,
                        onCheckedChange = { onDraftChange(draft.copy(wireGuardNoKernelTun = it)) },
                    )
                }
            }
        }
        item {
            PrimaryActionButton(
                text = if (editing) "保存修改" else "保存到 Local",
                onClick = onSave,
            )
        }
    }

    if (prefillDialogVisible) {
        PrefillLinkDialog(
            onDismiss = { prefillDialogVisible = false },
            onConfirm = { raw ->
                runCatching {
                    ManualNodeFactory.prefillFromShareLink(raw)
                }.onSuccess { nextDraft ->
                    inlineMessage = "已从分享链接预填 ${nextDraft.protocol.displayName} 表单。"
                    prefillDialogVisible = false
                    onDraftChange(nextDraft)
                }.onFailure { error ->
                    inlineMessage = error.message ?: "分享链接预填失败。"
                }
            },
        )
    }
}

internal data class BuilderDropdownOption<T>(
    val value: T,
    val label: String,
    val subtitle: String? = null,
)

@Composable
internal fun compactInputTextStyle(): TextStyle = TextStyle(
    color = TextPrimary,
    fontSize = TypeScale.Meta,
    lineHeight = TypeScale.MetaLine,
    fontWeight = FontWeight.Medium,
)

@Composable
internal fun CompactInputLabel(text: String) {
    Text(
        text = uiText(text),
        fontSize = TypeScale.Tiny,
        lineHeight = TypeScale.TinyLine,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun CompactInputPlaceholder(text: String) {
    Text(
        text = uiText(text),
        fontSize = TypeScale.Meta,
        lineHeight = TypeScale.MetaLine,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> BuilderDropdownField(
    label: String,
    selected: T,
    options: List<BuilderDropdownOption<T>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.value == selected }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedOption?.label.orEmpty(),
            onValueChange = {},
            readOnly = true,
            textStyle = compactInputTextStyle(),
            label = { CompactInputLabel(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .menuAnchor(),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceHigh,
                unfocusedContainerColor = SurfaceHigh,
                focusedBorderColor = Primary.copy(alpha = 0.32f),
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Primary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label, color = TextPrimary, fontWeight = FontWeight.Bold)
                                option.subtitle?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = TextSecondary, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelected(option.value)
                        },
                    )
                }
            }
        }
    }
    selectedOption?.subtitle?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
internal fun BuilderTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    helper: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (singleLine) Modifier.heightIn(min = 52.dp) else Modifier),
        textStyle = compactInputTextStyle(),
        label = { CompactInputLabel(label) },
        placeholder = { CompactInputPlaceholder(placeholder) },
        supportingText = helper?.let {
            {
                Text(
                    it,
                    color = TextSecondary,
                    fontSize = TypeScale.Tiny,
                    lineHeight = TypeScale.TinyLine,
                )
            }
        },
        shape = RoundedCornerShape(14.dp),
        singleLine = singleLine,
        minLines = minLines,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SurfaceHigh,
            unfocusedContainerColor = SurfaceHigh,
            focusedBorderColor = Primary.copy(alpha = 0.32f),
            unfocusedBorderColor = Color.Transparent,
            cursorColor = Primary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedPlaceholderColor = TextSecondary,
            unfocusedPlaceholderColor = TextSecondary,
            focusedLabelColor = TextSecondary,
            unfocusedLabelColor = TextSecondary,
            focusedSupportingTextColor = TextSecondary,
            unfocusedSupportingTextColor = TextSecondary,
        ),
    )
}

@Composable
internal fun BuilderSmallActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        border = BorderStroke(1.dp, Primary.copy(alpha = 0.2f)),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun BooleanSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = TypeScale.ListTitle,
                lineHeight = TypeScale.ListTitleLine,
                fontWeight = FontWeight.Bold,
            )
            Text(subtitle, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        TogglePill(
            checked = checked,
            onClick = { onCheckedChange(!checked) },
        )
    }
}

@Composable
internal fun PrefillLinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText("从分享链接预填"), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "粘贴分享链接后自动填表。",
                    color = TextSecondary,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = compactInputTextStyle(),
                    label = { CompactInputLabel("分享链接") },
                    placeholder = { CompactInputPlaceholder("粘贴 vless:// / vmess:// / trojan:// ...") },
                    shape = RoundedCornerShape(14.dp),
                    minLines = 4,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceHigh,
                        unfocusedContainerColor = SurfaceHigh,
                        focusedBorderColor = Primary.copy(alpha = 0.32f),
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(uiText("预填"), color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("取消"))
            }
        },
        containerColor = SurfaceHigh,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}
