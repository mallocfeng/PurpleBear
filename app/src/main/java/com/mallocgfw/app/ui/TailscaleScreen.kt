package com.mallocgfw.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.model.AppSettings
import com.mallocgfw.app.model.DEFAULT_TAILSCALE_CONTROL_URL
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.xray.TailscaleRuntimeSnapshot
import com.mallocgfw.app.xray.TailscaleRuntimeStatus
import com.mallocgfw.app.xray.normalizePrivateTailscaleSubnet

@Composable
internal fun TailscaleScreen(
    padding: PaddingValues,
    settings: AppSettings,
    snapshot: TailscaleRuntimeSnapshot,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAuthKeyChange: (String) -> Unit,
    onControlUrlChange: (String) -> Unit,
    onAlwaysDerpChange: (Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubnetRoutesChange: (List<String>) -> Unit,
    onExitNodeChange: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val authKey = settings.tailscaleAuthKey
    // Not keyed on the saved value: every keystroke is saved, and a blank value
    // is stored as the official URL, so keying would refill the field with it
    // as soon as the user clears it to type their own server.
    var controlUrl by rememberSaveable {
        mutableStateOf(settings.tailscaleControlUrl.ifBlank { DEFAULT_TAILSCALE_CONTROL_URL })
    }
    var revealAuthKey by remember { mutableStateOf(false) }
    var subnetInput by remember { mutableStateOf("") }
    var subnetInputError by remember { mutableStateOf<String?>(null) }
    val selectedSubnetRoutes = settings.tailscaleSubnetRoutes
        .mapNotNull(::normalizePrivateTailscaleSubnet)
        .distinct()
    val advertisedSubnetOwners = snapshot.peers
        .flatMap { peer ->
            peer.routes.mapNotNull { route ->
                normalizePrivateTailscaleSubnet(route)?.let { normalized ->
                    normalized to peer.name.ifBlank { peer.dnsName }
                }
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, owners) -> owners.distinct() }
    val unavailableSelectedRoutes = selectedSubnetRoutes.filterNot(advertisedSubnetOwners::containsKey)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = "Tailscale", subtitle = "Tailnet 分流", onBack = onBack)
        }
        item {
            SettingsGroup(title = "全局模块") {
                SettingToggleRow(
                    title = "启用 Tailscale",
                    subtitle = "只接管 Tailnet 流量；设置变更会重载模块。",
                    checked = settings.tailscaleEnabled,
                    onToggle = { onEnabledChange(!settings.tailscaleEnabled) },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when (snapshot.status) {
                        TailscaleRuntimeStatus.Connected -> uiText("已连接", "Connected") + snapshot.ipv4?.let { " · $it" }.orEmpty()
                        TailscaleRuntimeStatus.Connecting -> uiText("正在连接…")
                        TailscaleRuntimeStatus.Failed -> uiText(snapshot.message ?: "连接失败")
                        TailscaleRuntimeStatus.Stopped -> uiText("未连接")
                    },
                    color = if (snapshot.status == TailscaleRuntimeStatus.Connected) Success else TextSecondary,
                    fontWeight = FontWeight.Bold,
                )
                snapshot.tailnet?.let { tailnet ->
                    Text("Tailnet · $tailnet", color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            SettingsGroup(title = "认证与控制面") {
                OutlinedTextField(
                    value = authKey,
                    onValueChange = onAuthKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText("认证密钥")) },
                    placeholder = { Text("tskey-auth-…") },
                    singleLine = true,
                    visualTransformation = if (revealAuthKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(
                            text = uiText(if (revealAuthKey) "隐藏" else "显示"),
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable { revealAuthKey = !revealAuthKey },
                            color = TextSecondary,
                        )
                    },
                )
                Text(
                    uiText("保存在本机应用私有设置中；显示密钥时请注意周围环境。"),
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = controlUrl,
                    onValueChange = { value ->
                        controlUrl = value
                        onControlUrlChange(value)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText("控制服务器 URL")) },
                    placeholder = { Text(uiText("留空使用官方 Tailscale")) },
                    singleLine = true,
                )
                if (settings.tailscaleEnabled) {
                    Spacer(Modifier.height(12.dp))
                    ButtonRow(
                        primaryText = "应用设置并重新连接",
                        onPrimary = {
                            onControlUrlChange(controlUrl.trim())
                            onConnect(authKey)
                        },
                        secondaryText = "刷新",
                        onSecondary = onRefresh,
                    )
                } else {
                    Text(
                        uiText("填写认证信息后，打开上方“启用 Tailscale”开关即可连接。"),
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "连接行为") {
                SettingToggleRow(
                    title = "始终使用 DERP",
                    subtitle = "禁用直连 UDP，所有 Tailnet 通信通过 DERP 中继。",
                    checked = settings.tailscaleAlwaysUseDerp,
                    onToggle = { onAlwaysDerpChange(!settings.tailscaleAlwaysUseDerp) },
                )
                Spacer(Modifier.height(12.dp))
                SettingInfoRow(
                    title = "MagicDNS",
                    subtitle = uiText("Tailnet 设备名称（如 nas.xxx.ts.net）在 VPN 内可直接访问；其他域名的 DNS 保持原有设置。") +
                        snapshot.magicDnsSuffix?.let { suffix -> "\n" + uiText("当前 Tailnet 后缀：$suffix", "Tailnet suffix: $suffix") }.orEmpty(),
                )
            }
        }
        item {
            SettingsGroup(title = "子网访问") {
                Text(
                    uiText("仅启用你选择的私有子网。未设置时不会接管任何局域网地址。"),
                    color = TextSecondary,
                )
                if (advertisedSubnetOwners.isEmpty() && selectedSubnetRoutes.isEmpty()) {
                    Text(
                        uiText("当前没有检测到已批准的 Tailscale 子网路由。"),
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                advertisedSubnetOwners.toSortedMap().forEach { (route, owners) ->
                    Spacer(Modifier.height(10.dp))
                    SettingToggleRow(
                        title = route,
                        subtitle = uiText("由 ${owners.joinToString()} 公布", "Advertised by ${owners.joinToString()}"),
                        checked = route in selectedSubnetRoutes,
                        onToggle = {
                            onSubnetRoutesChange(
                                if (route in selectedSubnetRoutes) {
                                    selectedSubnetRoutes - route
                                } else {
                                    selectedSubnetRoutes + route
                                },
                            )
                        },
                    )
                }
                unavailableSelectedRoutes.forEach { route ->
                    Spacer(Modifier.height(10.dp))
                    SettingToggleRow(
                        title = route,
                        subtitle = "当前未由 Tailnet 公布，不会激活",
                        checked = true,
                        onToggle = { onSubnetRoutesChange(selectedSubnetRoutes - route) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = subnetInput,
                    onValueChange = {
                        subnetInput = it
                        subnetInputError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText("手动添加私有子网 CIDR")) },
                    placeholder = { Text(uiText("例如 192.168.2.0/24")) },
                    singleLine = true,
                    isError = subnetInputError != null,
                )
                subnetInputError?.let { message ->
                    Text(
                        uiText(message),
                        color = Error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedActionButton(
                    text = "添加子网",
                    onClick = {
                        val normalized = normalizePrivateTailscaleSubnet(subnetInput)
                        when {
                            normalized == null -> subnetInputError = "请输入有效的私有 IPv4/IPv6 CIDR。"
                            normalized in selectedSubnetRoutes -> subnetInputError = "该子网已经添加。"
                            else -> {
                                onSubnetRoutesChange(selectedSubnetRoutes + normalized)
                                subnetInput = ""
                                subnetInputError = null
                            }
                        }
                    },
                )
            }
        }
        item {
            SettingsGroup(title = "出口节点") {
                Text(
                    uiText("选择后，当前 VPN 的默认流量将优先通过该 Tailscale 出口节点。留空则仅处理 Tailnet 流量。"),
                    color = TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ModeChip("不使用", settings.tailscaleExitNodeId.isBlank()) { onExitNodeChange("") }
                    snapshot.peers.filter { it.canExit }.forEach { peer ->
                        ModeChip(
                            peer.name.ifBlank { peer.dnsName },
                            settings.tailscaleExitNodeId == peer.id,
                        ) { onExitNodeChange(peer.id) }
                    }
                }
            }
        }
        item {
            if (snapshot.peers.isEmpty()) {
                SettingsGroup(title = "设备") {
                    Text(uiText("连接后会自动显示 Tailnet 中的设备。"), color = TextSecondary)
                }
            } else {
                // The device cards follow as their own items; no empty card under the label.
                SectionLabel("设备")
            }
        }
        items(snapshot.peers, key = { it.id.ifBlank { it.dnsName } }) { peer ->
            val noMagicDnsName = uiText("无 MagicDNS 名称")
            val onlineLabel = uiText("在线")
            val offlineLabel = uiText("离线")
            val activeLabel = uiText("活跃")
            val exitNodeLabel = uiText("出口节点")
            SurfaceCard {
                Text(peer.name.ifBlank { peer.dnsName }, fontWeight = FontWeight.Bold, fontSize = TypeScale.ListTitle)
                Text(
                    buildString {
                        append(peer.dnsName.ifBlank { noMagicDnsName })
                        append(" · ")
                        append(if (peer.online) onlineLabel else offlineLabel)
                        if (peer.active) append(" · $activeLabel")
                        peer.relay?.takeIf { it.isNotBlank() }?.let { append(" · DERP $it") }
                        if (peer.canExit) append(" · $exitNodeLabel")
                    },
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val tailscaleAddresses = listOfNotNull(peer.ipv4, peer.ipv6)
                Text(
                    text = if (tailscaleAddresses.isEmpty()) {
                        uiText("Tailscale IP：暂无地址")
                    } else {
                        uiText("Tailscale IP：", "Tailscale IP: ") + tailscaleAddresses.joinToString(" · ")
                    },
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (peer.routes.isNotEmpty()) {
                    Text(uiText("子网路由：", "Subnet routes: ") + peer.routes.joinToString(), color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            OutlinedActionButton(text = "退出并移除此设备", onClick = onLogout)
        }
    }
}
