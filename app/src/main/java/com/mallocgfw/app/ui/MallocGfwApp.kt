package com.mallocgfw.app.ui

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.R
import com.mallocgfw.app.model.AppScreen
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.ImportPreview
import com.mallocgfw.app.model.LocalNodeDraft
import com.mallocgfw.app.model.MainTab
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.RuleRefreshResult
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.RuleSourceStatus
import com.mallocgfw.app.model.RuleSourceType
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.mergedSubscriptionGroupNodes
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.StreamingRouteSelection
import com.mallocgfw.app.model.subscriptionMergeKey
import com.mallocgfw.app.model.subscriptionNameKey
import com.mallocgfw.app.ui.theme.Background
import com.mallocgfw.app.ui.theme.MallocGfwTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.sync.BackgroundSyncManager
import com.mallocgfw.app.sync.DailySyncScheduler
import com.mallocgfw.app.sync.SyncNotifications
import com.mallocgfw.app.quicksettings.VpnQuickSettingsTileService
import com.mallocgfw.app.xray.AppLogManager
import com.mallocgfw.app.xray.DiagnosticsManager
import com.mallocgfw.app.xray.GeoDataManager
import com.mallocgfw.app.xray.GeoDataSnapshot
import com.mallocgfw.app.xray.GeoDataStatus
import com.mallocgfw.app.xray.NodeLatencyTester
import com.mallocgfw.app.xray.VpnRuntimeStore
import com.mallocgfw.app.xray.VpnServiceController
import com.mallocgfw.app.xray.XrayCoreManager
import com.mallocgfw.app.xray.XrayCoreStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MallocGfwApp(
    launchRequest: MainActivity.LaunchRequest? = null,
    onLaunchRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenStateHolder = rememberSaveableStateHolder()
    val initialLoad = remember { AppStateStore.loadWithStartupCleanup(context) }
    val initialState = initialLoad.state
    val xraySnapshot by XrayCoreManager.snapshot.collectAsState()
    val vpnSnapshot by VpnRuntimeStore.snapshot.collectAsState()
    var screen by rememberSaveable { mutableStateOf(AppScreen.Launch) }
    var screenHistory by rememberSaveable { mutableStateOf(listOf<AppScreen>()) }
    var previousMainTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var mainTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var connectionStatus by rememberSaveable { mutableStateOf(ConnectionStatus.Disconnected) }
    var proxyMode by rememberSaveable { mutableStateOf(initialState.proxyMode) }
    var appSettings by remember { mutableStateOf(initialState.settings) }
    var selectedServerId by rememberSaveable {
        mutableStateOf(
            initialState.selectedServerId.ifBlank {
                initialState.servers.firstOrNull { !it.hiddenUnsupported }?.id.orEmpty()
            },
        )
    }
    var selectedRuleSourceId by rememberSaveable {
        mutableStateOf(initialState.ruleSources.firstOrNull()?.id.orEmpty())
    }
    var ruleEditorSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var preProxyPickerOwnerNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var nodeLinkPickerMode by rememberSaveable { mutableStateOf(NodeLinkPickerMode.PreProxy) }
    var serverFilter by rememberSaveable { mutableStateOf("all") }
    var serverSearch by rememberSaveable { mutableStateOf("") }
    var importInput by rememberSaveable { mutableStateOf("") }
    val servers = remember { initialState.servers.toMutableStateList() }
    val serverGroups = remember { initialState.serverGroups.toMutableStateList() }
    val subscriptions = remember { initialState.subscriptions.toMutableStateList() }
    val ruleSources = remember { initialState.ruleSources.toMutableStateList() }
    val recentImports = remember { initialState.recentImports.toMutableStateList() }
    val protectedApps = remember { initialState.protectedApps.toMutableStateList() }
    val initialExpandedGroupIds = remember(initialState) {
        buildSet {
            add(ImportParser.LOCAL_GROUP_ID)
            initialState.servers.firstOrNull { it.id == initialState.selectedServerId }?.groupId?.let(::add)
        }
    }
    val expandedGroups = remember {
        mutableStateMapOf<String, Boolean>().apply {
            initialState.serverGroups.forEach { group ->
                put(group.id, group.id in initialExpandedGroupIds)
            }
        }
    }
    var diagnostics by remember { mutableStateOf(buildDiagnostics()) }
    var diagnosticRunId by remember { mutableIntStateOf(0) }
    var pendingImport by remember { mutableStateOf<ImportPreview?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importInFlight by remember { mutableStateOf(false) }
    var startupHiddenUnsupportedNodeCount by rememberSaveable {
        mutableIntStateOf(initialLoad.newlyHiddenUnsupportedNodeCount)
    }
    var runtimeMessage by remember { mutableStateOf<String?>(null) }
    var ruleMessage by remember { mutableStateOf<String?>(null) }
    var subscriptionMessage by remember { mutableStateOf<String?>(null) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var pendingVpnServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReconnectServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReconnectMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var blockingReconnectInFlight by rememberSaveable { mutableStateOf(false) }
    var blockingReconnectMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingProtectedAppsApply by rememberSaveable { mutableStateOf(false) }
    var pendingStreamingRoutingApply by rememberSaveable { mutableStateOf(false) }
    var disconnectInFlight by rememberSaveable { mutableStateOf(false) }
    var lastAutoLatencyProbeServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingHomeSwitchPromptServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var homeSwitchFocusNonce by rememberSaveable { mutableIntStateOf(0) }
    var lastRequestedConnectionServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFallbackTargetServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var defaultRulesBootstrapped by rememberSaveable { mutableStateOf(false) }
    var autoConnectAttemptedServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoReconnectAttempts by rememberSaveable { mutableIntStateOf(0) }
    var manualDisconnectRequested by rememberSaveable { mutableStateOf(false) }
    var perAppSearch by rememberSaveable { mutableStateOf("") }
    var selectedStreamingServiceId by rememberSaveable { mutableStateOf("") }
    var localNodeDraft by remember { mutableStateOf(LocalNodeDraft()) }
    var editingLocalNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var localNodeCompatibilityWarning by rememberSaveable { mutableStateOf<String?>(null) }
    var localNodeBuilderMessage by remember { mutableStateOf<String?>(null) }
    var geoDataSnapshot by remember {
        mutableStateOf(
            GeoDataSnapshot(
                status = GeoDataStatus.Idle,
                updatedAt = "加载中…",
                sourceLabel = "加载中…",
                geoipBytes = 0L,
                geositeBytes = 0L,
            ),
        )
    }
    var geoDataUpdating by remember { mutableStateOf(false) }
    var syncInFlight by remember { mutableStateOf(false) }
    var logViewerText by remember { mutableStateOf("") }
    val homeListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val serversListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val rulesListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val latencyTestInFlight = remember { mutableStateMapOf<String, Boolean>() }
    val ruleUpdateInFlight = remember { mutableStateMapOf<String, Boolean>() }
    val subscriptionRefreshInFlight = remember { mutableStateMapOf<String, Boolean>() }
    var preloadedDefaultServerSections by remember { mutableStateOf<List<ServerSection>>(emptyList()) }
    var serverPreloadRevision by remember { mutableIntStateOf(0) }

    fun warmServerSections() {
        val revision = ++serverPreloadRevision
        val groupsSnapshot = serverGroups.toList()
        val serversSnapshot = servers.toList()
        scope.launch {
            val warmed = withContext(Dispatchers.Default) {
                groupedServers(
                    groups = groupsSnapshot,
                    servers = serversSnapshot,
                    filter = "all",
                    search = "",
                )
            }
            if (serverPreloadRevision == revision) {
                preloadedDefaultServerSections = warmed
            }
        }
    }

    fun currentPersistedState(): PersistedAppState {
        return PersistedAppState(
            serverGroups = serverGroups.toList(),
            servers = servers.toList(),
            subscriptions = subscriptions.toList(),
            ruleSources = ruleSources.toList(),
            recentImports = recentImports.toList(),
            protectedApps = protectedApps.toList(),
            selectedServerId = selectedServerId,
            proxyMode = proxyMode,
            settings = appSettings,
        )
    }

    fun normalizeStreamingSelectionsInMemory() {
        val validServerIds = servers.map { it.id }.toSet()
        val normalizedSelections = appSettings.streamingSelections
            .mapNotNull { selection ->
                when {
                    selection.serverId.isBlank() -> null
                    selection.serverId in validServerIds -> selection
                    else -> null
                }
            }
            .distinctBy { it.serviceId }
        if (normalizedSelections != appSettings.streamingSelections) {
            appSettings = appSettings.copy(streamingSelections = normalizedSelections)
        }
    }

    fun normalizePreProxySelectionsInMemory() {
        val visibleServerMap = servers.filterNot { it.hiddenUnsupported }.associateBy { it.id }
        servers.indices.forEach { index ->
            val server = servers[index]
            val normalizedPreProxyId = server.preProxyNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    ManualNodeFactory.supportsPreProxy(server) &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::supportsPreProxy) == true
            }.orEmpty()
            val normalizedFallbackId = server.fallbackNodeId.trim().takeIf { candidateId ->
                candidateId.isNotBlank() &&
                    candidateId != server.id &&
                    ManualNodeFactory.supportsPreProxy(server) &&
                    visibleServerMap[candidateId]?.let(ManualNodeFactory::supportsPreProxy) == true
            }.orEmpty()
            if (normalizedPreProxyId != server.preProxyNodeId || normalizedFallbackId != server.fallbackNodeId) {
                servers[index] = server.copy(
                    preProxyNodeId = normalizedPreProxyId,
                    fallbackNodeId = normalizedFallbackId,
                )
            }
        }
    }

    fun applyPersistedState(nextState: PersistedAppState) {
        val visibleServers = nextState.servers.filterNot { it.hiddenUnsupported }
        serverGroups.clear()
        serverGroups.addAll(nextState.serverGroups)
        servers.clear()
        servers.addAll(nextState.servers)
        subscriptions.clear()
        subscriptions.addAll(nextState.subscriptions)
        ruleSources.clear()
        ruleSources.addAll(nextState.ruleSources)
        recentImports.clear()
        recentImports.addAll(nextState.recentImports)
        protectedApps.clear()
        protectedApps.addAll(nextState.protectedApps)
        normalizePreProxySelectionsInMemory()
        selectedServerId = nextState.selectedServerId.ifBlank { visibleServers.firstOrNull()?.id.orEmpty() }
        appSettings = nextState.settings
        normalizeStreamingSelectionsInMemory()
        warmServerSections()
    }

    fun goToMain(tab: MainTab) {
        if (screen == AppScreen.Subscriptions && tab != MainTab.Import) {
            subscriptionMessage = null
        }
        mainTab = tab
        previousMainTab = tab
        screenHistory = emptyList()
        screen = when (tab) {
            MainTab.Home -> AppScreen.Home
            MainTab.Servers -> AppScreen.Servers
            MainTab.Rules -> AppScreen.Rules
            MainTab.Import -> AppScreen.Import
            MainTab.Me -> AppScreen.Me
        }
    }

    fun syncMainTabWithScreen(target: AppScreen) {
        mainTab = when (target) {
            AppScreen.Home, AppScreen.QrScanner -> MainTab.Home
            AppScreen.Servers, AppScreen.NodeDetail, AppScreen.LocalNodeBuilder, AppScreen.PreProxyNodePicker -> MainTab.Servers
            AppScreen.Rules, AppScreen.RuleSourceDetail, AppScreen.AddRuleSource -> MainTab.Rules
            AppScreen.Import, AppScreen.ConfirmImport, AppScreen.Subscriptions -> MainTab.Import
            AppScreen.Me, AppScreen.PerApp, AppScreen.Diagnostics, AppScreen.Settings, AppScreen.Permission,
            AppScreen.MediaRouting, AppScreen.MediaRoutingNodePicker, AppScreen.LogViewer -> MainTab.Me
            else -> mainTab
        }
    }

    fun selectServerForUi(serverId: String) {
        selectedServerId = serverId
        val activeServerId = vpnSnapshot.activeServerId
        pendingHomeSwitchPromptServerId = when {
            screen != AppScreen.Servers -> null
            connectionStatus != ConnectionStatus.Connected -> null
            activeServerId.isNullOrBlank() -> null
            serverId == activeServerId -> null
            else -> serverId
        }
    }

    fun navigateSecondary(target: AppScreen) {
        if (screen == AppScreen.Subscriptions && target != AppScreen.Subscriptions) {
            subscriptionMessage = null
        }
        if (screen == target) return
        previousMainTab = when (screen) {
            AppScreen.Home, AppScreen.QrScanner -> MainTab.Home
            AppScreen.Servers, AppScreen.NodeDetail, AppScreen.LocalNodeBuilder -> MainTab.Servers
            AppScreen.PreProxyNodePicker -> MainTab.Servers
            AppScreen.Rules, AppScreen.RuleSourceDetail, AppScreen.AddRuleSource -> MainTab.Rules
            AppScreen.Import, AppScreen.ConfirmImport, AppScreen.Subscriptions -> MainTab.Import
            AppScreen.Me, AppScreen.PerApp, AppScreen.Diagnostics, AppScreen.Settings, AppScreen.Permission,
            AppScreen.MediaRouting, AppScreen.MediaRoutingNodePicker, AppScreen.LogViewer -> MainTab.Me
            else -> MainTab.Home
        }
        screenHistory = screenHistory + screen
        screen = target
    }

    fun startVpnService(serverId: String) {
        lastRequestedConnectionServerId = serverId
        AppStateStore.save(context, currentPersistedState())
        val server = servers.firstOrNull { it.id == serverId }
            ?: error("未找到要连接的节点。")
        VpnServiceController.start(context, server)
    }

    fun scheduleBlockingReconnect(
        server: ServerNode,
        message: String,
        disconnectFirst: Boolean,
    ) {
        AppStateStore.save(context, currentPersistedState())
        pendingReconnectServerId = server.id
        pendingReconnectMessage = message
        blockingReconnectInFlight = true
        blockingReconnectMessage = message
        runtimeMessage = message
        if (disconnectFirst) {
            disconnectInFlight = true
            connectionStatus = ConnectionStatus.Disconnecting
            VpnServiceController.disconnect(context)
        } else {
            disconnectInFlight = false
            connectionStatus = ConnectionStatus.Disconnected
        }
    }

    fun reconnectVpnForSettingsChange(server: ServerNode, message: String) {
        scheduleBlockingReconnect(
            server = server,
            message = message,
            disconnectFirst = true,
        )
    }

    fun resolveServerForSettingsReconnect(): ServerNode? {
        val candidateIds = listOf(
            vpnSnapshot.activeServerId,
            selectedServerId,
            appSettings.lastConnectedServerId,
        ).filterNotNull().filter { it.isNotBlank() }
        return candidateIds.firstNotNullOfOrNull { candidateId ->
            servers.firstOrNull { it.id == candidateId }
        } ?: servers.firstOrNull()
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val serverId = pendingVpnServerId
        pendingVpnServerId = null
        if (result.resultCode == Activity.RESULT_OK && !serverId.isNullOrBlank()) {
            disconnectInFlight = false
            connectionStatus = ConnectionStatus.Connecting
            runtimeMessage = "VPN 权限已授予，正在启动连接…"
            startVpnService(serverId)
        } else {
            disconnectInFlight = false
            connectionStatus = ConnectionStatus.Disconnected
            runtimeMessage = "未授予 VPN 权限。"
            appSettings = appSettings.copy(resumeConnectionOnLaunch = false)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        appSettings = appSettings.copy(showUpdateNotifications = granted)
        settingsMessage = if (granted) {
            "更新通知已开启。"
        } else {
            "系统未授予通知权限，无法显示更新通知。"
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            navigateSecondary(AppScreen.QrScanner)
        } else {
            runtimeMessage = "未授予相机权限，无法扫码导入。"
        }
    }

    fun requestVpnConnection(server: ServerNode?, navigateHomeAfterStart: Boolean = false) {
        if (server == null) {
            runtimeMessage = "请先导入并选择一个节点。"
            goToMain(MainTab.Import)
            return
        }
        if (navigateHomeAfterStart) {
            goToMain(MainTab.Home)
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingVpnServerId = server.id
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            pendingVpnServerId = null
            disconnectInFlight = false
            connectionStatus = ConnectionStatus.Connecting
            runtimeMessage = "正在申请系统 VPN 并启动连接…"
            startVpnService(server.id)
        }
    }

    fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            settingsMessage = "当前系统不支持应用内直接添加快捷开关，请到系统下拉菜单的编辑页手动添加。"
            return
        }
        val activity = context as? Activity
        if (activity == null || activity.isFinishing) {
            settingsMessage = "当前无法拉起系统快捷开关面板，请稍后重试。"
            return
        }
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
        if (statusBarManager == null) {
            settingsMessage = "当前系统没有提供快捷开关服务，请在系统编辑页手动添加。"
            return
        }
        statusBarManager.requestAddTileService(
            ComponentName(context, VpnQuickSettingsTileService::class.java),
            context.getString(R.string.qs_tile_label),
            Icon.createWithResource(context, R.drawable.ic_vpn_tile),
            ContextCompat.getMainExecutor(context),
        ) { result ->
            settingsMessage = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "VPN 快捷开关已添加到下拉菜单。"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "VPN 快捷开关已经添加过了。"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "你取消了添加快捷开关。"
                StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND -> "请保持应用在前台后再试一次。"
                StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT -> "快捷开关组件无效，请重新安装这版应用后再试。"
                StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE -> "系统拒绝了当前快捷开关请求。"
                StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER -> "当前系统用户不支持添加这个快捷开关。"
                StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE -> "当前系统没有状态栏快捷开关服务。"
                StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> "系统已经在处理另一个快捷开关请求，请稍后再试。"
                else -> "添加快捷开关失败，系统返回代码 $result。"
            }
        }
    }

    fun toggleThemeMode() {
        appSettings = appSettings.copy(lightThemeEnabled = !appSettings.lightThemeEnabled)
    }

    fun updateStreamingRoutingEnabled(enabled: Boolean) {
        appSettings = appSettings.copy(streamingRoutingEnabled = enabled)
        pendingStreamingRoutingApply = true
        settingsMessage = if (enabled) {
            "流媒体分流已开启。"
        } else {
            "流媒体分流已关闭。"
        }
    }

    fun updateProxyMode(nextMode: ProxyMode) {
        if (proxyMode == nextMode) return
        proxyMode = nextMode
        val perAppEnabled = nextMode == ProxyMode.PerApp
        val shouldReconnect = VpnServiceController.isRunning() ||
            connectionStatus == ConnectionStatus.Connected ||
            connectionStatus == ConnectionStatus.Connecting ||
            connectionStatus == ConnectionStatus.Disconnecting
        if (!perAppEnabled) {
            pendingProtectedAppsApply = false
        }
        settingsMessage = if (perAppEnabled) {
            if (shouldReconnect) {
                "已开启分应用代理，正在重连 VPN 以只代理指定应用。"
            } else {
                "分应用代理已开启。"
            }
        } else {
            if (shouldReconnect) {
                "已关闭分应用代理，正在重连 VPN 以恢复默认模式。"
            } else {
                "已关闭分应用代理，已切回默认模式。"
            }
        }
        val reconnectServer = resolveServerForSettingsReconnect()
        if (shouldReconnect && reconnectServer != null) {
            reconnectVpnForSettingsChange(
                server = reconnectServer,
                message = if (perAppEnabled) {
                    "分应用代理已开启，正在重连 VPN 以应用新模式…"
                } else {
                    "分应用代理已关闭，正在重连 VPN 以恢复默认模式…"
                },
            )
        }
    }

    fun updateProtectedAppEnabled(appId: String) {
        val index = protectedApps.indexOfFirst { it.id == appId }
        if (index < 0) return
        val current = protectedApps[index]
        val nextEnabled = !current.enabled
        protectedApps[index] = current.copy(enabled = nextEnabled)
        pendingProtectedAppsApply = true
        settingsMessage = if (nextEnabled) {
            "${current.name} 已加入名单。"
        } else {
            "${current.name} 已移出名单。"
        }
    }

    fun applyProtectedAppsSelection() {
        if (proxyMode != ProxyMode.PerApp) {
            settingsMessage = "请先启用分应用代理，再应用当前应用名单。"
            return
        }
        if (!pendingProtectedAppsApply) {
            settingsMessage = "当前分应用代理名单已经是最新状态。"
            return
        }
        val activeServer = servers.firstOrNull { it.id == (vpnSnapshot.activeServerId ?: selectedServerId) }
        if (connectionStatus == ConnectionStatus.Connected && activeServer != null) {
            reconnectVpnForSettingsChange(
                server = activeServer,
                message = "正在重连 VPN 以应用新的分应用代理名单…",
            )
        } else {
            settingsMessage = "应用名单已保存。"
        }
    }

    fun updateStreamingServiceSelection(serviceId: String, serverId: String) {
        val nextSelections = appSettings.streamingSelections
            .filterNot { it.serviceId == serviceId }
            .toMutableList()
        if (serverId.isNotBlank()) {
            nextSelections += StreamingRouteSelection(
                serviceId = serviceId,
                serverId = serverId,
            )
        }
        appSettings = appSettings.copy(streamingSelections = nextSelections)
        pendingStreamingRoutingApply = true
        settingsMessage = "${StreamingMediaManager.serviceById(serviceId)?.name ?: "流媒体"} 节点已保存。"
    }

    fun applyStreamingRoutingChanges() {
        if (!pendingStreamingRoutingApply) {
            settingsMessage = "当前流媒体分流设置已经是最新状态。"
            return
        }
        val activeServer = servers.firstOrNull { it.id == (vpnSnapshot.activeServerId ?: selectedServerId) }
        if (connectionStatus == ConnectionStatus.Connected && activeServer != null) {
            reconnectVpnForSettingsChange(
                server = activeServer,
                message = if (appSettings.streamingRoutingEnabled) {
                    "正在重连 VPN 以应用新的流媒体分流设置…"
                } else {
                    "正在重连 VPN 以移除流媒体分流设置…"
                },
            )
        } else {
            settingsMessage = "流媒体分流设置已保存。"
        }
    }

    fun setUpdateNotificationsEnabled(enabled: Boolean) {
        if (!enabled) {
            appSettings = appSettings.copy(showUpdateNotifications = false)
            settingsMessage = "更新通知已关闭。"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        appSettings = appSettings.copy(showUpdateNotifications = true)
        settingsMessage = "更新通知已开启。"
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importInFlight = true
            importError = null
            pendingImport = null
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            runCatching {
                val name = context.resolveDisplayName(uri) ?: "附件导入"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件内容。")
                ImportParser.buildFilePreview(name, content)
            }.onSuccess {
                pendingImport = it
                navigateSecondary(AppScreen.ConfirmImport)
            }.onFailure {
                importError = it.message ?: "文件导入失败。"
            }
            importInFlight = false
        }
    }

    fun onBack() {
        if (screen == AppScreen.Launch) return
        if (screen == AppScreen.Onboarding) {
            screen = AppScreen.Launch
            return
        }
        if (screen == AppScreen.Permission) {
            screen = AppScreen.Onboarding
            return
        }
        if (screenHistory.isNotEmpty()) {
            val previousScreen = screenHistory.last()
            screenHistory = screenHistory.dropLast(1)
            syncMainTabWithScreen(previousScreen)
            screen = previousScreen
            return
        }
        goToMain(previousMainTab)
    }

    fun rerunDiagnostics() {
        diagnosticRunId += 1
        diagnostics = DiagnosticsManager.pendingSteps()
        val runId = diagnosticRunId
        val diagnosticServer = servers.firstOrNull { it.id == (vpnSnapshot.activeServerId ?: selectedServerId) }
            ?: servers.firstOrNull { it.id == selectedServerId }
            ?: servers.firstOrNull()
        val ruleSourcesSnapshot = ruleSources.toList()
        scope.launch {
            val result = DiagnosticsManager.run(
                context = context,
                connectionStatus = connectionStatus,
                vpnSnapshot = vpnSnapshot,
                xraySnapshot = xraySnapshot,
                server = diagnosticServer,
                ruleSources = ruleSourcesSnapshot,
            )
            if (runId == diagnosticRunId) {
                diagnostics = result
            }
        }
    }

    fun applyImport(preview: ImportPreview): ImportApplyResult {
        val selectedBefore = servers.firstOrNull { it.id == selectedServerId }
        val existingGroupIndex = serverGroups.indexOfFirst { it.id == preview.group.id }
        val targetGroupId = preview.group.id
        val selectedWasInTargetGroup = selectedBefore?.groupId == targetGroupId
        val selectedWasConnected = selectedWasInTargetGroup &&
            selectedBefore != null &&
            connectionStatus == ConnectionStatus.Connected &&
            (vpnSnapshot.activeServerId == selectedBefore.id ||
                (vpnSnapshot.activeServerId.isNullOrBlank() && appSettings.lastConnectedServerId == selectedBefore.id))
        val targetGroup = preview.group.copy(id = targetGroupId)
        if (existingGroupIndex >= 0) {
            serverGroups[existingGroupIndex] = targetGroup
        } else {
            serverGroups.add(0, targetGroup)
        }
        expandedGroups[targetGroupId] = true

        val normalizedPreviewNodes = preview.nodes.map { node ->
            if (node.groupId == targetGroupId) node else node.copy(groupId = targetGroupId)
        }
        if (preview.group.type == ServerGroupType.Subscription) {
            val otherServers = servers.filterNot { it.groupId == targetGroupId }
            servers.clear()
            servers.addAll(otherServers)
            servers.addAll(normalizedPreviewNodes)
        } else {
            normalizedPreviewNodes.forEach { normalizedNode ->
                val existingNodeIndex = servers.indexOfFirst {
                    it.groupId == normalizedNode.groupId && (
                        it.rawUri == normalizedNode.rawUri ||
                            it.subscriptionMergeKey() == normalizedNode.subscriptionMergeKey()
                        )
                }
                if (existingNodeIndex >= 0) {
                    val existingNode = servers[existingNodeIndex]
                    servers[existingNodeIndex] = normalizedNode.copy(
                        id = existingNode.id,
                        favorite = existingNode.favorite,
                        latencyMs = existingNode.latencyMs,
                        stable = existingNode.stable,
                        preProxyNodeId = existingNode.preProxyNodeId,
                        fallbackNodeId = existingNode.fallbackNodeId,
                    )
                } else {
                    servers.add(normalizedNode)
                }
            }
        }
        val normalizedGroupServers = servers.toList().mergedSubscriptionGroupNodes(targetGroupId)
        servers.clear()
        servers.addAll(normalizedGroupServers)
        normalizePreProxySelectionsInMemory()
        val mergedNodeCount = servers.count { it.groupId == targetGroupId && !it.hiddenUnsupported }
        val selectedAfter = if (selectedWasInTargetGroup && selectedBefore != null) {
            servers.firstOrNull {
                it.groupId == targetGroupId &&
                    !it.hiddenUnsupported &&
                    it.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
            } ?: servers.firstOrNull {
                it.groupId == targetGroupId &&
                    it.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
            } ?: servers.firstOrNull { it.groupId == targetGroupId && !it.hiddenUnsupported }
                ?: servers.firstOrNull { it.groupId == targetGroupId }
        } else {
            null
        }
        selectedAfter?.let { nextSelection ->
            selectedServerId = nextSelection.id
            expandedGroups[targetGroupId] = true
        }

        preview.subscription?.let { subscription ->
            val normalizedSubscription = subscription.copy(
                id = targetGroupId,
                name = targetGroup.name,
                nodes = mergedNodeCount,
                autoUpdate = targetGroup.sourceUrl != null && subscription.autoUpdate,
                sourceUrl = targetGroup.sourceUrl,
                nextSync = if (targetGroup.sourceUrl != null && subscription.autoUpdate) "手动" else "快照",
                status = subscription.status,
            )
            val subscriptionIndex = subscriptions.indexOfFirst { it.id == targetGroupId }
            if (subscriptionIndex >= 0) {
                subscriptions[subscriptionIndex] = normalizedSubscription
            } else {
                subscriptions.add(0, normalizedSubscription)
            }
        }

        val recentTitle = when (preview.group.type) {
            ServerGroupType.Subscription -> "${preview.group.name} · 订阅 · 刚刚"
            ServerGroupType.Local -> "${preview.nodes.firstOrNull()?.name ?: "Local"} · 单节点 · 刚刚"
        }
        recentImports.remove(recentTitle)
        recentImports.add(0, recentTitle)
        while (recentImports.size > 5) recentImports.removeAt(recentImports.lastIndex)

        normalizeStreamingSelectionsInMemory()
        warmServerSections()
        val selectedNameStillExists = selectedAfter != null &&
            selectedBefore != null &&
            selectedAfter.subscriptionNameKey() == selectedBefore.subscriptionNameKey()
        return if (selectedAfter != null && selectedBefore != null && !selectedNameStillExists) {
            ImportApplyResult(
                selectedServerChanged = true,
                message = "原选中节点 ${selectedBefore.name} 已不存在，已切换到 ${selectedAfter.name}。",
                reconnectServer = if (selectedWasConnected) selectedAfter else null,
            )
        } else if (selectedAfter != null && selectedWasConnected) {
            ImportApplyResult(
                reconnectServer = selectedAfter,
            )
        } else {
            ImportApplyResult()
        }
    }

    fun openLocalNodeBuilder() {
        editingLocalNodeId = null
        localNodeCompatibilityWarning = null
        localNodeDraft = LocalNodeDraft()
        localNodeBuilderMessage = null
        navigateSecondary(AppScreen.LocalNodeBuilder)
    }

    fun openLocalNodeEditor(nodeId: String) {
        val server = servers.firstOrNull { it.id == nodeId } ?: return
        runCatching {
            ManualNodeFactory.prefillFromServerNode(server)
        }.onSuccess { draft ->
            selectedServerId = nodeId
            editingLocalNodeId = nodeId
            localNodeCompatibilityWarning = if (ManualNodeFactory.requiresCompatibilityEditWarning(server)) {
                "旧版 Local 节点，建议谨慎编辑。"
            } else {
                null
            }
            localNodeDraft = draft
            localNodeBuilderMessage = null
            navigateSecondary(AppScreen.LocalNodeBuilder)
        }.onFailure { error ->
            runtimeMessage = error.message ?: "当前 Local 节点暂时无法编辑。"
        }
    }

    fun openNodeDetailForServer(nodeId: String?) {
        val server = servers.firstOrNull { it.id == nodeId } ?: return
        selectServerForUi(server.id)
        navigateSecondary(AppScreen.NodeDetail)
    }

    fun openPreProxyPicker(nodeId: String) {
        val server = servers.firstOrNull { it.id == nodeId } ?: return
        preProxyPickerOwnerNodeId = server.id
        nodeLinkPickerMode = NodeLinkPickerMode.PreProxy
        selectServerForUi(server.id)
        navigateSecondary(AppScreen.PreProxyNodePicker)
    }

    fun openFallbackNodePicker(nodeId: String) {
        val server = servers.firstOrNull { it.id == nodeId } ?: return
        preProxyPickerOwnerNodeId = server.id
        nodeLinkPickerMode = NodeLinkPickerMode.Fallback
        selectServerForUi(server.id)
        navigateSecondary(AppScreen.PreProxyNodePicker)
    }

    fun isValidLinkedNodeCandidate(owner: ServerNode, candidateId: String): Boolean {
        return candidateId.trim().takeIf { it.isNotBlank() }?.let { normalizedId ->
            normalizedId != owner.id &&
                ManualNodeFactory.supportsPreProxy(owner) &&
                servers.any { candidate ->
                    candidate.id == normalizedId &&
                        !candidate.hiddenUnsupported &&
                        ManualNodeFactory.supportsPreProxy(candidate)
                }
        } == true
    }

    fun updateNodePreProxy(nodeId: String, preProxyNodeId: String) {
        val nodeIndex = servers.indexOfFirst { it.id == nodeId }
        if (nodeIndex < 0) return
        val node = servers[nodeIndex]
        val normalizedTargetId = preProxyNodeId.trim().takeIf { candidateId ->
            isValidLinkedNodeCandidate(node, candidateId)
        }.orEmpty()
        if (normalizedTargetId == node.preProxyNodeId) return
        servers[nodeIndex] = node.copy(preProxyNodeId = normalizedTargetId)
        runtimeMessage = if (normalizedTargetId.isBlank()) {
            "已清除 ${node.name} 的前置代理。重新连接后生效。"
        } else {
            val targetName = servers.firstOrNull { it.id == normalizedTargetId }?.name ?: "所选节点"
            "已将 ${targetName} 设为 ${node.name} 的前置代理。重新连接后生效。"
        }
        warmServerSections()
    }

    fun updateNodeFallback(nodeId: String, fallbackNodeId: String) {
        val nodeIndex = servers.indexOfFirst { it.id == nodeId }
        if (nodeIndex < 0) return
        val node = servers[nodeIndex]
        val normalizedTargetId = fallbackNodeId.trim().takeIf { candidateId ->
            isValidLinkedNodeCandidate(node, candidateId)
        }.orEmpty()
        if (normalizedTargetId == node.fallbackNodeId) return
        servers[nodeIndex] = node.copy(fallbackNodeId = normalizedTargetId)
        runtimeMessage = if (normalizedTargetId.isBlank()) {
            "已清除 ${node.name} 的备用节点。"
        } else {
            val targetName = servers.firstOrNull { it.id == normalizedTargetId }?.name ?: "所选节点"
            "已将 ${targetName} 设为 ${node.name} 的备用节点。当前节点检测失败时会自动切换。"
        }
        warmServerSections()
    }

    fun fallbackNodeFor(server: ServerNode?): ServerNode? {
        val source = server ?: return null
        val fallbackId = source.fallbackNodeId.trim().takeIf { it.isNotBlank() } ?: return null
        if (source.id == lastFallbackTargetServerId) return null
        return servers.firstOrNull { candidate ->
            candidate.id == fallbackId &&
                candidate.id != source.id &&
                !candidate.hiddenUnsupported &&
                ManualNodeFactory.supportsPreProxy(candidate)
        }
    }

    fun triggerFallbackReconnect(
        source: ServerNode,
        fallback: ServerNode,
        reason: String,
        disconnectFirst: Boolean,
    ) {
        val message = "$reason，正在切换到备用节点 ${fallback.name}…"
        AppLogManager.append(
            context,
            "FALLBACK",
            "节点 ${source.name} 触发备用切换：${fallback.name}，原因：$reason",
        )
        lastFallbackTargetServerId = fallback.id
        selectedServerId = fallback.id
        scheduleBlockingReconnect(
            server = fallback,
            message = message,
            disconnectFirst = disconnectFirst,
        )
    }

    fun saveLocalNodeDraft() {
        runCatching {
            ManualNodeFactory.buildServerNode(localNodeDraft)
        }.onSuccess { node ->
            localNodeBuilderMessage = null
            val editingId = editingLocalNodeId
            if (editingId != null) {
                val existingIndex = servers.indexOfFirst { it.id == editingId }
                if (existingIndex >= 0) {
                    val existingNode = servers[existingIndex]
                    servers[existingIndex] = node.copy(
                        id = existingNode.id,
                        groupId = existingNode.groupId,
                        subscription = existingNode.subscription,
                        latencyMs = existingNode.latencyMs,
                        stable = existingNode.stable,
                        favorite = existingNode.favorite,
                        rawUri = existingNode.rawUri,
                        hiddenUnsupported = existingNode.hiddenUnsupported,
                        preProxyNodeId = existingNode.preProxyNodeId,
                        fallbackNodeId = existingNode.fallbackNodeId,
                        description = "手动编辑的 ${localNodeDraft.protocol.displayName} Local 节点",
                    )
                    selectedServerId = existingNode.id
                } else {
                    servers.add(0, node)
                    selectedServerId = node.id
                }
            } else {
                servers.add(0, node)
                selectedServerId = node.id
            }
            normalizePreProxySelectionsInMemory()
            editingLocalNodeId = null
            localNodeCompatibilityWarning = null
            expandedGroups[ImportParser.LOCAL_GROUP_ID] = true
            warmServerSections()
            goToMain(MainTab.Servers)
        }.onFailure { error ->
            localNodeBuilderMessage = error.message ?: if (editingLocalNodeId != null) {
                "保存节点失败，请检查必填项。"
            } else {
                "新建节点失败，请检查必填项。"
            }
        }
    }

    fun removeServerNode(nodeId: String) {
        val node = servers.firstOrNull { it.id == nodeId } ?: return
        servers.removeAll { it.id == nodeId }
        normalizePreProxySelectionsInMemory()
        recentImports.removeAll { it.contains(node.name) }
        if (selectedServerId == nodeId) {
            selectedServerId = servers.firstOrNull()?.id.orEmpty()
        }
        if (screen == AppScreen.NodeDetail) {
            goToMain(MainTab.Servers)
        }
        normalizeStreamingSelectionsInMemory()
        warmServerSections()
    }

    fun removeSubscriptionGroup(groupId: String) {
        val group = serverGroups.firstOrNull { it.id == groupId } ?: return
        if (group.type != ServerGroupType.Subscription) return
        val removedNodeIds = servers.filter { it.groupId == groupId }.map { it.id }.toSet()
        serverGroups.removeAll { it.id == groupId }
        subscriptions.removeAll { it.id == groupId }
        servers.removeAll { it.groupId == groupId }
        normalizePreProxySelectionsInMemory()
        expandedGroups.remove(groupId)
        recentImports.removeAll { it.contains(group.name) }
        if (selectedServerId in removedNodeIds) {
            selectedServerId = servers.firstOrNull()?.id.orEmpty()
        }
        if (screen == AppScreen.Subscriptions || screen == AppScreen.NodeDetail) {
            goToMain(MainTab.Servers)
        }
        normalizeStreamingSelectionsInMemory()
        warmServerSections()
    }

    fun replaceRuleSource(updated: RuleSourceItem) {
        val index = ruleSources.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            ruleSources[index] = updated
        } else {
            ruleSources.add(updated)
        }
        selectedRuleSourceId = updated.id
    }

    fun refreshRuleSource(sourceId: String, openDetailAfter: Boolean = false) {
        val source = ruleSources.firstOrNull { it.id == sourceId } ?: return
        if (ruleUpdateInFlight[sourceId] == true) return
        ruleUpdateInFlight[sourceId] = true
        replaceRuleSource(source.copy(status = RuleSourceStatus.Updating))
        if (openDetailAfter) {
            navigateSecondary(AppScreen.RuleSourceDetail)
        }
        scope.launch {
            runCatching {
                RuleSourceManager.refreshSource(context, source)
            }.onSuccess { result: RuleRefreshResult ->
                replaceRuleSource(result.source)
                ruleMessage = "${result.source.name} 已更新，转换 ${result.compiled.convertedRules} 条。"
            }.onFailure { error ->
                replaceRuleSource(RuleSourceManager.buildFailureState(source, error))
                ruleMessage = error.message ?: "规则更新失败。"
            }
            ruleUpdateInFlight.remove(sourceId)
        }
    }

    fun refreshGeoData() {
        if (geoDataUpdating) return
        geoDataUpdating = true
        geoDataSnapshot = geoDataSnapshot.copy(status = GeoDataStatus.Updating, lastError = null)
        scope.launch {
            runCatching {
                GeoDataManager.refresh(context)
            }.onSuccess { snapshot ->
                geoDataSnapshot = snapshot
                ruleMessage = "Geo 数据已更新。"
            }.onFailure { error ->
                geoDataSnapshot = geoDataSnapshot.copy(
                    status = GeoDataStatus.Failed,
                    lastError = error.message ?: "Geo 数据更新失败。",
                )
                ruleMessage = error.message ?: "Geo 数据更新失败。"
            }
            geoDataUpdating = false
        }
    }

    fun syncAllResources() {
        if (syncInFlight) return
        syncInFlight = true
        settingsMessage = "已开始后台同步订阅、规则和 Geo 资源。"
        val connectedServerBeforeSync = if (connectionStatus == ConnectionStatus.Connected) {
            val activeId = vpnSnapshot.activeServerId?.takeIf { it.isNotBlank() } ?: selectedServerId
            servers.firstOrNull { it.id == activeId }
        } else {
            null
        }
        scope.launch {
            runCatching {
                BackgroundSyncManager.syncAll(context, currentPersistedState())
            }.onSuccess { report ->
                val nextState = report.state.copy(settings = appSettings)
                val reconnectServer = connectedServerBeforeSync?.let { previous ->
                    val previousStillExists = nextState.servers.any { it.id == previous.id }
                    if (previousStillExists && nextState.selectedServerId == previous.id) {
                        null
                    } else {
                        nextState.servers.firstOrNull {
                            it.id == nextState.selectedServerId &&
                                it.groupId == previous.groupId
                        }
                    }
                }
                applyPersistedState(nextState)
                geoDataSnapshot = report.geoDataSnapshot
                settingsMessage = report.summary
                ruleMessage = report.summary
                subscriptionMessage = report.summary
                if (appSettings.showUpdateNotifications) {
                    SyncNotifications.showSyncCompleted(context, report.summary)
                }
                reconnectServer?.let { server ->
                    reconnectVpnForSettingsChange(
                        server = server,
                        message = "订阅已同步，正在重新连接当前线路…",
                    )
                }
            }.onFailure { error ->
                settingsMessage = error.message ?: "后台同步失败。"
                AppLogManager.append(context, "SYNC", "手动同步失败", error)
            }
            syncInFlight = false
        }
    }

    fun updateDnsSettings(
        dnsMode: AppDnsMode,
        customDnsValue: String,
    ) {
        val normalizedCustomValue = customDnsValue.trim()
        if (dnsMode == AppDnsMode.Custom && normalizedCustomValue.isBlank()) {
            settingsMessage = "请输入自定义 DNS 地址。"
            return
        }
        appSettings = appSettings.copy(
            dnsMode = dnsMode,
            customDnsValue = normalizedCustomValue,
        )
        settingsMessage = when (dnsMode) {
            AppDnsMode.System -> "DNS 已切换为系统解析。"
            AppDnsMode.Remote -> "DNS 已切换为远端 1.1.1.1。"
            AppDnsMode.Custom -> "DNS 已更新为 $normalizedCustomValue。"
        }
    }

    fun openLogViewer() {
        logViewerText = AppLogManager.readRecentLogs(context)
        navigateSecondary(AppScreen.LogViewer)
    }

    fun importScannedCode(rawValue: String) {
        val scanned = rawValue.trim()
        importInput = ""
        if (scanned.isBlank()) {
            runtimeMessage = "二维码里没有可导入的链接。"
            return
        }
        scope.launch {
            runCatching {
                ImportParser.buildPreview(scanned)
            }.onSuccess { preview ->
                applyImport(preview)
                if (preview.group.type == ServerGroupType.Subscription) {
                    subscriptionMessage = "${preview.group.name} 已通过二维码导入，共同步 ${preview.nodes.count { !it.hiddenUnsupported }} 个节点。"
                    navigateSecondary(AppScreen.Subscriptions)
                } else {
                    selectedServerId = preview.nodes.firstOrNull()?.id.orEmpty()
                    runtimeMessage = "${preview.nodes.firstOrNull()?.name ?: "节点"} 已通过二维码导入。"
                    goToMain(MainTab.Servers)
                }
            }.onFailure { error ->
                runtimeMessage = error.message ?: "二维码内容无法识别为订阅或节点链接。"
                AppLogManager.append(context, "SCAN", "二维码导入失败", error)
            }
        }
    }

    fun scanSubscriptionQr() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            navigateSecondary(AppScreen.QrScanner)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun addRuleSource(name: String, url: String, type: RuleSourceType) {
        val draft = RuleSourceManager.createDraft(name = name, url = url, requestedType = type)
        replaceRuleSource(draft.copy(status = RuleSourceStatus.Updating))
        refreshRuleSource(draft.id, openDetailAfter = true)
    }

    fun saveRuleSourceEdits(
        editingSourceId: String?,
        name: String,
        url: String,
        type: RuleSourceType,
    ) {
        if (editingSourceId.isNullOrBlank()) {
            addRuleSource(name, url, type)
            return
        }
        val index = ruleSources.indexOfFirst { it.id == editingSourceId }
        if (index < 0) {
            addRuleSource(name, url, type)
            return
        }
        val current = ruleSources[index]
        val updated = current.copy(
            name = name.ifBlank { current.name },
            url = url.trim(),
            type = type,
            status = RuleSourceStatus.Updating,
            lastError = null,
        )
        replaceRuleSource(updated)
        refreshRuleSource(updated.id, openDetailAfter = true)
    }

    fun toggleRuleSourceEnabled(sourceId: String) {
        val index = ruleSources.indexOfFirst { it.id == sourceId }
        if (index < 0) return
        val current = ruleSources[index]
        val enabled = !current.enabled
        ruleSources[index] = current.copy(
            enabled = enabled,
            status = if (enabled) {
                if (current.convertedRules > 0) RuleSourceStatus.Ready else RuleSourceStatus.Idle
            } else {
                RuleSourceStatus.Disabled
            },
        )
        ruleMessage = if (enabled) {
            "${current.name} 已启用。重新开关代理后生效。"
        } else {
            "${current.name} 已停用。重新开关代理后生效。"
        }
    }

    fun deleteRuleSource(sourceId: String) {
        val source = ruleSources.firstOrNull { it.id == sourceId } ?: return
        RuleSourceManager.deleteSource(context, source)
        if (source.systemDefault) {
            replaceRuleSource(
                source.copy(
                    enabled = true,
                    updatedAt = "未更新",
                    status = RuleSourceStatus.Idle,
                    totalRules = 0,
                    convertedRules = 0,
                    skippedRules = 0,
                    lastError = null,
                ),
            )
        } else {
            ruleSources.removeAll { it.id == sourceId }
            if (selectedRuleSourceId == sourceId) {
                selectedRuleSourceId = ruleSources.firstOrNull()?.id.orEmpty()
            }
        }
        ruleMessage = "${source.name} 已移除。"
    }

    fun toggleCore(server: ServerNode?, navigateHomeAfterStart: Boolean = false) {
        when (connectionStatus) {
            ConnectionStatus.Disconnected -> {
                manualDisconnectRequested = false
                requestVpnConnection(server, navigateHomeAfterStart)
            }

            ConnectionStatus.Connected -> {
                scope.launch {
                    manualDisconnectRequested = true
                    lastRequestedConnectionServerId = null
                    lastFallbackTargetServerId = null
                    appSettings = appSettings.copy(resumeConnectionOnLaunch = false)
                    disconnectInFlight = true
                    connectionStatus = ConnectionStatus.Disconnecting
                    runtimeMessage = "正在断开 VPN…"
                    VpnServiceController.disconnect(context)
                }
            }

            ConnectionStatus.Connecting,
            ConnectionStatus.Disconnecting -> Unit
        }
    }

    fun connectOrSwitchServer(server: ServerNode?, navigateHomeAfterStart: Boolean = false) {
        if (server == null) {
            runtimeMessage = "请先导入并选择一个节点。"
            goToMain(MainTab.Import)
            return
        }
        if (connectionStatus != ConnectionStatus.Connected || !VpnServiceController.isRunning()) {
            manualDisconnectRequested = false
            requestVpnConnection(server, navigateHomeAfterStart)
            return
        }
        val activeServerId = vpnSnapshot.activeServerId
        if (activeServerId == server.id) {
            runtimeMessage = "${server.name} 已经是当前线路。"
            if (navigateHomeAfterStart) {
                goToMain(MainTab.Home)
            }
            return
        }
        selectedServerId = server.id
        if (navigateHomeAfterStart) {
            goToMain(MainTab.Home)
        }
        reconnectVpnForSettingsChange(
            server = server,
            message = "正在切换到 ${server.name}，将重新连接 VPN 以应用新线路…",
        )
    }

    fun testServerLatency(nodeId: String) {
        val index = servers.indexOfFirst { it.id == nodeId }
        if (index < 0) return
        if (latencyTestInFlight[nodeId] == true) {
            val server = servers[index]
            runtimeMessage = "节点 ${server.name} 正在检测中，请稍候。"
            return
        }
        val server = servers[index]
        if (VpnServiceController.isActuallyRunning(context) && NodeLatencyTester.requiresCoreProbe(server)) {
            runtimeMessage = "当前已连接 VPN，这类 ${server.protocol} 节点请先断开当前连接后再检测。"
            AppLogManager.append(
                context,
                "LATENCY",
                "跳过检测：${server.name} 使用 ${server.protocol}/${server.transport}，当前 VPN 已连接",
            )
            return
        }
        latencyTestInFlight[nodeId] = true
        runtimeMessage = "正在检测节点 ${server.name}…"
        AppLogManager.append(context, "LATENCY", "开始检测节点：${server.name} (${server.address}:${server.port})")
        scope.launch {
            var latencyStateChanged = false
            runCatching { NodeLatencyTester.measure(context, server) }
                .onSuccess { latency ->
                    val latestIndex = servers.indexOfFirst { it.id == nodeId }
                    if (latestIndex >= 0) {
                        val current = servers[latestIndex]
                        val updated = current.copy(
                            latencyMs = latency,
                            stable = true,
                        )
                        if (updated != current) {
                            servers[latestIndex] = updated
                            latencyStateChanged = true
                        }
                    }
                    AppLogManager.append(context, "LATENCY", "节点检测成功：${server.name} -> ${latency} ms")
                    runtimeMessage = "节点 ${server.name} 检测完成，延迟 ${latency} ms。"
                }
                .onFailure { error ->
                    val latestIndex = servers.indexOfFirst { it.id == nodeId }
                    if (latestIndex >= 0) {
                        val current = servers[latestIndex]
                        val updated = current.copy(
                            latencyMs = 0,
                            stable = false,
                        )
                        if (updated != current) {
                            servers[latestIndex] = updated
                            latencyStateChanged = true
                        }
                    }
                    AppLogManager.append(context, "LATENCY", "节点检测失败：${server.name}", error)
                    runtimeMessage = "节点 ${server.name} 检测失败：${error.message ?: "请稍后重试。"}"
                    val latestServer = latestIndex.takeIf { it >= 0 }?.let { servers[it] }
                    val fallbackServer = latestServer
                        ?.takeIf {
                            connectionStatus == ConnectionStatus.Connected &&
                                vpnSnapshot.activeServerId == it.id &&
                                !blockingReconnectInFlight &&
                                pendingReconnectServerId.isNullOrBlank()
                        }
                        ?.let(::fallbackNodeFor)
                    if (latestServer != null && fallbackServer != null) {
                        AppLogManager.append(context, "FALLBACK", "当前节点检测失败，准备切换备用节点", error)
                        triggerFallbackReconnect(
                            source = latestServer,
                            fallback = fallbackServer,
                            reason = "当前节点检测不通",
                            disconnectFirst = true,
                        )
                    }
                }
            latencyTestInFlight.remove(nodeId)
            if (latencyStateChanged) {
                warmServerSections()
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(1200)
        if (screen != AppScreen.Launch) return@LaunchedEffect
        if (appSettings.onboardingCompleted) {
            goToMain(MainTab.Home)
        } else {
            screen = AppScreen.Onboarding
        }
    }

    LaunchedEffect(Unit) {
        VpnRuntimeStore.initialize(context)
        runCatching { XrayCoreManager.initialize(context) }
            .onSuccess {
                runtimeMessage = XrayCoreManager.snapshot.value.message
            }
            .onFailure {
                runtimeMessage = it.message ?: "无法加载 Xray 内核。"
            }
    }

    LaunchedEffect(Unit) {
        warmServerSections()
    }

    LaunchedEffect(launchRequest?.nonce) {
        val request = launchRequest ?: return@LaunchedEffect
        when (request.action) {
            MainActivity.LaunchAction.TileConnect -> {
                val requestedServer = request.serverId?.let { serverId ->
                    servers.firstOrNull { it.id == serverId }
                }
                val fallbackServer = servers.firstOrNull { it.id == appSettings.lastConnectedServerId }
                    ?: servers.firstOrNull { it.id == selectedServerId }
                    ?: servers.firstOrNull()
                manualDisconnectRequested = false
                requestVpnConnection(requestedServer ?: fallbackServer, navigateHomeAfterStart = true)
            }
            MainActivity.LaunchAction.TilePreferences -> {
                appSettings = appSettings.copy(onboardingCompleted = true)
                navigateSecondary(AppScreen.Settings)
            }
        }
        onLaunchRequestConsumed()
    }

    LaunchedEffect(ruleSources.size, defaultRulesBootstrapped) {
        if (defaultRulesBootstrapped) return@LaunchedEffect
        val bootstrapCandidates = ruleSources
            .filter { it.systemDefault }
            .filter { it.convertedRules == 0 && it.status != RuleSourceStatus.Ready }
        if (bootstrapCandidates.isNotEmpty()) {
            defaultRulesBootstrapped = true
            bootstrapCandidates.forEach { refreshRuleSource(it.id) }
        }
    }

    LaunchedEffect(Unit) {
        @OptIn(FlowPreview::class)
        snapshotFlow { currentPersistedState() }
            .distinctUntilChanged()
            .debounce(500L)
            .collectLatest { state ->
                withContext(Dispatchers.IO) {
                    AppStateStore.save(context, state)
                }
            }
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { GeoDataManager.load(context) }
        geoDataSnapshot = loaded
    }

    LaunchedEffect(appSettings.dailyAutoUpdate) {
        DailySyncScheduler.schedule(context, appSettings.dailyAutoUpdate)
    }

    LaunchedEffect(screen) {
        if (screen == AppScreen.Diagnostics) {
            rerunDiagnostics()
        }
    }

    val autoConnectTargetServer by remember(appSettings.lastConnectedServerId) {
        derivedStateOf {
            servers.firstOrNull { it.id == appSettings.lastConnectedServerId }
        }
    }
    LaunchedEffect(
        screen,
        appSettings.autoConnectOnLaunch,
        appSettings.resumeConnectionOnLaunch,
        appSettings.lastConnectedServerId,
        autoConnectTargetServer?.id,
        connectionStatus,
    ) {
        val mainScreens = setOf(
            AppScreen.Home,
            AppScreen.Servers,
            AppScreen.Rules,
            AppScreen.Import,
            AppScreen.Me,
        )
        if (screen !in mainScreens) return@LaunchedEffect
        if (!appSettings.autoConnectOnLaunch || !appSettings.resumeConnectionOnLaunch) {
            autoConnectAttemptedServerId = null
            return@LaunchedEffect
        }
        if (connectionStatus != ConnectionStatus.Disconnected || VpnServiceController.isRunning()) return@LaunchedEffect
        val server = autoConnectTargetServer ?: return@LaunchedEffect
        if (autoConnectAttemptedServerId == server.id) return@LaunchedEffect
        autoConnectAttemptedServerId = server.id
        runtimeMessage = "正在恢复上次线路 ${server.name}…"
        requestVpnConnection(server)
    }

    var lastAppliedActiveServerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vpnSnapshot.status, vpnSnapshot.message, vpnSnapshot.activeServerId) {
        connectionStatus = vpnSnapshot.status
        if (vpnSnapshot.status != ConnectionStatus.Connecting &&
            vpnSnapshot.status != ConnectionStatus.Disconnecting
        ) {
            disconnectInFlight = false
        }
        if (blockingReconnectInFlight) {
            when (vpnSnapshot.status) {
                ConnectionStatus.Connected -> {
                    blockingReconnectInFlight = false
                    blockingReconnectMessage = null
                }
                ConnectionStatus.Disconnected -> {
                    val reconnectPending = !pendingReconnectServerId.isNullOrBlank() || disconnectInFlight
                    if (!reconnectPending && !VpnServiceController.isRunning()) {
                        blockingReconnectInFlight = false
                        blockingReconnectMessage = null
                    }
                }
                else -> Unit
            }
        }
        vpnSnapshot.activeServerId?.takeIf { it.isNotBlank() }?.let { activeId ->
            if (activeId != lastAppliedActiveServerId || selectedServerId.isBlank()) {
                selectedServerId = activeId
                lastAppliedActiveServerId = activeId
            }
        } ?: run {
            lastAppliedActiveServerId = null
        }
        if (!vpnSnapshot.message.isNullOrBlank()) {
            runtimeMessage = vpnSnapshot.message
        }
        if (vpnSnapshot.status == ConnectionStatus.Connected && !vpnSnapshot.activeServerId.isNullOrBlank()) {
            appSettings = appSettings.copy(
                lastConnectedServerId = vpnSnapshot.activeServerId.orEmpty(),
                resumeConnectionOnLaunch = true,
            )
            lastRequestedConnectionServerId = null
            lastFallbackTargetServerId = null
            if (proxyMode == ProxyMode.PerApp && pendingProtectedAppsApply) {
                pendingProtectedAppsApply = false
            }
            if (pendingStreamingRoutingApply) {
                pendingStreamingRoutingApply = false
            }
            autoReconnectAttempts = 0
            manualDisconnectRequested = false
        }
        if (manualDisconnectRequested && vpnSnapshot.status == ConnectionStatus.Disconnected) {
            manualDisconnectRequested = false
            autoReconnectAttempts = 0
        }
    }

    LaunchedEffect(vpnSnapshot.status, pendingReconnectServerId) {
        val reconnectServerId = pendingReconnectServerId ?: return@LaunchedEffect
        if (vpnSnapshot.status != ConnectionStatus.Disconnected) return@LaunchedEffect
        var attempts = 0
        while (VpnServiceController.isRunning() && attempts < 40) {
            delay(100)
            attempts += 1
        }
        if (VpnServiceController.isRunning()) return@LaunchedEffect
        val server = servers.firstOrNull { it.id == reconnectServerId }
        pendingReconnectServerId = null
        val message = pendingReconnectMessage
        pendingReconnectMessage = null
        if (server == null) {
            runtimeMessage = "原线路已不存在，无法自动重连。"
            return@LaunchedEffect
        }
        runtimeMessage = message ?: "设置已更新，正在重新连接当前线路…"
        requestVpnConnection(server)
    }

    LaunchedEffect(xraySnapshot.status, xraySnapshot.message, vpnSnapshot.status) {
        when (xraySnapshot.status) {
            XrayCoreStatus.Failed -> {
                val failedServer = (
                    vpnSnapshot.activeServerId?.takeIf { it.isNotBlank() }
                        ?: lastRequestedConnectionServerId
                    )?.let { failedId -> servers.firstOrNull { it.id == failedId } }
                val fallbackServer = if (
                    failedServer != null &&
                    !blockingReconnectInFlight &&
                    pendingReconnectServerId.isNullOrBlank()
                ) {
                    fallbackNodeFor(failedServer)
                } else {
                    null
                }
                disconnectInFlight = false
                blockingReconnectInFlight = false
                blockingReconnectMessage = null
                if (vpnSnapshot.status == ConnectionStatus.Disconnected) {
                    connectionStatus = ConnectionStatus.Disconnected
                }
                if (failedServer != null && fallbackServer != null) {
                    triggerFallbackReconnect(
                        source = failedServer,
                        fallback = fallbackServer,
                        reason = "当前节点连接失败",
                        disconnectFirst = false,
                    )
                }
            }
            XrayCoreStatus.Ready -> if (connectionStatus == ConnectionStatus.Connecting &&
                vpnSnapshot.status == ConnectionStatus.Disconnected
            ) {
                disconnectInFlight = false
                if (blockingReconnectInFlight && pendingReconnectServerId.isNullOrBlank()) {
                    blockingReconnectInFlight = false
                    blockingReconnectMessage = null
                }
                connectionStatus = ConnectionStatus.Disconnected
            }
            else -> Unit
        }
        xraySnapshot.message?.let { runtimeMessage = it }
    }

    var previousVpnStatus by remember { mutableStateOf(vpnSnapshot.status) }
    LaunchedEffect(vpnSnapshot.status, vpnSnapshot.activeServerId, xraySnapshot.status, appSettings.autoReconnect) {
        val lastStatus = previousVpnStatus
        previousVpnStatus = vpnSnapshot.status
        val shouldReconnect = vpnSnapshot.status == ConnectionStatus.Disconnected &&
            lastStatus in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting) &&
            xraySnapshot.status != XrayCoreStatus.Failed &&
            !manualDisconnectRequested &&
            pendingReconnectServerId == null &&
            appSettings.autoReconnect &&
            autoReconnectAttempts < 3
        if (!shouldReconnect) return@LaunchedEffect
        val serverId = (vpnSnapshot.activeServerId ?: appSettings.lastConnectedServerId).orEmpty()
        val server = servers.firstOrNull { it.id == serverId } ?: return@LaunchedEffect
        autoReconnectAttempts += 1
        val currentAttempt = autoReconnectAttempts
        runtimeMessage = "连接中断，正在尝试第 $currentAttempt 次自动重连…"
        delay((1_500L * currentAttempt).coerceAtMost(5_000L))
        if (connectionStatus == ConnectionStatus.Disconnected && !VpnServiceController.isRunning()) {
            requestVpnConnection(server)
        }
    }

    BackHandler(enabled = screen !in setOf(AppScreen.Home, AppScreen.Servers, AppScreen.Rules, AppScreen.Import, AppScreen.Me)) {
        onBack()
    }

    if (startupHiddenUnsupportedNodeCount > 0 && screen != AppScreen.Launch) {
        AlertDialog(
            onDismissRequest = { startupHiddenUnsupportedNodeCount = 0 },
            title = { Text("已隐藏不支持节点") },
            text = {
                Text("启动时已自动隐藏 $startupHiddenUnsupportedNodeCount 个 REALITY + gRPC 节点，这类线路当前版本暂不支持。")
            },
            confirmButton = {
                TextButton(onClick = { startupHiddenUnsupportedNodeCount = 0 }) {
                    Text("知道了")
                }
            },
        )
    }

    val selectedServer = servers.firstOrNull { it.id == selectedServerId } ?: servers.firstOrNull()
    val activeServer = servers.firstOrNull { it.id == vpnSnapshot.activeServerId }
    val homeDisplayedServer = when (connectionStatus) {
        ConnectionStatus.Connected,
        ConnectionStatus.Connecting,
        ConnectionStatus.Disconnecting -> activeServer ?: selectedServer
        ConnectionStatus.Disconnected -> selectedServer
    }
    val homePendingServer = if (
        connectionStatus in setOf(
            ConnectionStatus.Connected,
            ConnectionStatus.Connecting,
            ConnectionStatus.Disconnecting,
        ) && selectedServer?.id != null &&
        selectedServer.id != homeDisplayedServer?.id
    ) {
        selectedServer
    } else {
        null
    }

    LaunchedEffect(screen, homeDisplayedServer?.id, homeDisplayedServer?.latencyMs) {
        if (screen != AppScreen.Home) return@LaunchedEffect
        val server = homeDisplayedServer ?: return@LaunchedEffect
        if (server.latencyMs > 0) return@LaunchedEffect
        if (latencyTestInFlight[server.id] == true) return@LaunchedEffect
        if (lastAutoLatencyProbeServerId == server.id) return@LaunchedEffect
        lastAutoLatencyProbeServerId = server.id
        testServerLatency(server.id)
    }

    LaunchedEffect(screen, pendingHomeSwitchPromptServerId) {
        val promptServerId = pendingHomeSwitchPromptServerId ?: return@LaunchedEffect
        if (screen != AppScreen.Servers) {
            pendingHomeSwitchPromptServerId = null
            return@LaunchedEffect
        }
        delay(5_000)
        if (pendingHomeSwitchPromptServerId == promptServerId) {
            pendingHomeSwitchPromptServerId = null
        }
    }
    val selectedServerGroup = selectedServer?.let { server ->
        serverGroups.firstOrNull { it.id == server.groupId }
    }
    val selectedStreamingService = StreamingMediaManager.serviceById(selectedStreamingServiceId)
    val mediaRoutingDefaultServer = activeServer ?: selectedServer
    val selectedRuleSource = ruleSources.firstOrNull { it.id == selectedRuleSourceId } ?: ruleSources.firstOrNull()
    val ruleDetailSummary by remember(selectedRuleSource, ruleSources.size) {
        derivedStateOf {
            selectedRuleSource?.let { RuleSourceManager.loadDetailSummary(context, it) }
        }
    }
    val needsServerSections = screen == AppScreen.Servers || screen == AppScreen.NodeDetail
    val serverSections by remember(
        needsServerSections,
        serverFilter,
        serverSearch,
        preloadedDefaultServerSections,
    ) {
        derivedStateOf {
            if (!needsServerSections) {
                emptyList()
            } else if (serverFilter == "all" && serverSearch.isBlank() && preloadedDefaultServerSections.isNotEmpty()) {
                preloadedDefaultServerSections
            } else {
                groupedServers(serverGroups, servers, serverFilter, serverSearch)
            }
        }
    }

    BackHandler(enabled = blockingReconnectInFlight) {}

    MallocGfwTheme(lightTheme = appSettings.lightThemeEnabled) {
        Surface(color = Background, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                screenStateHolder.SaveableStateProvider(screen.name) {
                when (screen) {
                AppScreen.Launch -> LaunchScreen(onSkip = { goToMain(MainTab.Home) })
                AppScreen.Onboarding -> OnboardingScreen(
                    onNext = {
                        appSettings = appSettings.copy(onboardingCompleted = true)
                        goToMain(MainTab.Home)
                    },
                    onSkip = {
                        appSettings = appSettings.copy(onboardingCompleted = true)
                        goToMain(MainTab.Home)
                    },
                )

                else -> AppScaffold(
                    screen = screen,
                    currentTab = mainTab,
                    lightThemeEnabled = appSettings.lightThemeEnabled,
                    onSelectTab = ::goToMain,
                    onToggleTheme = ::toggleThemeMode,
                    onOpenSettings = { navigateSecondary(AppScreen.Settings) },
                    onOpenScan = ::scanSubscriptionQr,
                ) { padding ->
                    when (screen) {
                    AppScreen.Permission -> PermissionScreen(
                        padding = padding,
                        onClose = { goToMain(MainTab.Home) },
                    )

                    AppScreen.Home -> HomeScreen(
                        padding = padding,
                        listState = homeListState,
                        connectionStatus = connectionStatus,
                        vpnSnapshot = vpnSnapshot,
                        proxyMode = proxyMode,
                        currentServer = homeDisplayedServer,
                        pendingServer = homePendingServer,
                        focusPendingSwitchNonce = homeSwitchFocusNonce,
                        coreVersion = xraySnapshot.version,
                        latencyTesting = homeDisplayedServer?.id?.let { latencyTestInFlight[it] == true } == true,
                        onToggleConnection = { toggleCore(selectedServer) },
                        onRetestLatency = {
                            homeDisplayedServer?.id?.let(::testServerLatency)
                            if (connectionStatus == ConnectionStatus.Connected) {
                                VpnServiceController.refreshSpeedMeasurement(context)
                            }
                        },
                        onOpenServers = {
                            val pending = homePendingServer
                            if (pending != null) {
                                connectOrSwitchServer(pending, navigateHomeAfterStart = true)
                            } else {
                                goToMain(MainTab.Servers)
                            }
                        },
                        onOpenNodeDetail = { openNodeDetailForServer(selectedServer?.id) },
                        onOpenImport = { goToMain(MainTab.Import) },
                        onOpenDiagnostics = { navigateSecondary(AppScreen.Diagnostics) },
                        onOpenSubscriptions = { navigateSecondary(AppScreen.Subscriptions) },
                        onOpenPerApp = { navigateSecondary(AppScreen.PerApp) },
                        onOpenMediaRouting = { navigateSecondary(AppScreen.MediaRouting) },
                    )

                    AppScreen.Servers -> ServersScreen(
                        padding = padding,
                        listState = serversListState,
                        sections = serverSections,
                        expandedGroups = expandedGroups,
                        selectedServerId = selectedServerId,
                        filter = serverFilter,
                        search = serverSearch,
                        onFilterChange = { serverFilter = it },
                        onSearchChange = { serverSearch = it },
                        onSelectServer = ::selectServerForUi,
                        onOpenServerDetail = ::openNodeDetailForServer,
                        onToggleGroup = { groupId ->
                            expandedGroups[groupId] = !(expandedGroups[groupId] ?: true)
                        },
                        onToggleFavorite = { id ->
                            val index = servers.indexOfFirst { it.id == id }
                            if (index >= 0) {
                                servers[index] = servers[index].copy(favorite = !servers[index].favorite)
                                warmServerSections()
                            }
                        },
                        latencyTestingIds = latencyTestInFlight.keys,
                        onTestLatency = ::testServerLatency,
                        onDeleteServer = ::removeServerNode,
                        onDeleteSubscriptionGroup = ::removeSubscriptionGroup,
                        onCreateLocalNode = ::openLocalNodeBuilder,
                    )

                    AppScreen.Rules -> RulesScreen(
                        padding = padding,
                        listState = rulesListState,
                        ruleSources = ruleSources,
                        selectedRuleSourceId = selectedRuleSourceId,
                        geoDataSnapshot = geoDataSnapshot,
                        geoDataUpdating = geoDataUpdating,
                        ruleMessage = ruleMessage,
                        updatingIds = ruleUpdateInFlight.keys,
                        onRefreshGeoData = ::refreshGeoData,
                        onSelectSource = { sourceId ->
                            selectedRuleSourceId = sourceId
                            navigateSecondary(AppScreen.RuleSourceDetail)
                        },
                        onAddSource = {
                            ruleEditorSourceId = null
                            navigateSecondary(AppScreen.AddRuleSource)
                        },
                        onToggleEnabled = ::toggleRuleSourceEnabled,
                        onRefreshSource = ::refreshRuleSource,
                    )

                    AppScreen.NodeDetail -> NodeDetailScreen(
                        padding = padding,
                        server = selectedServer,
                        group = selectedServerGroup,
                        preProxyNode = servers.firstOrNull { it.id == selectedServer?.preProxyNodeId },
                        fallbackNode = servers.firstOrNull { it.id == selectedServer?.fallbackNodeId },
                        canSelectPreProxy = selectedServer?.let(ManualNodeFactory::supportsPreProxy) == true,
                        canSelectFallback = selectedServer?.let(ManualNodeFactory::supportsPreProxy) == true,
                        connectionStatus = connectionStatus,
                        activeServerId = vpnSnapshot.activeServerId,
                        runtimeMessage = runtimeMessage,
                        onBack = ::onBack,
                        onConnect = { connectOrSwitchServer(selectedServer, navigateHomeAfterStart = true) },
                        onTestLatency = { selectedServer?.id?.let(::testServerLatency) },
                        latencyTesting = selectedServer?.id?.let { latencyTestInFlight[it] == true } == true,
                        onOpenPreProxyPicker = { selectedServer?.id?.let(::openPreProxyPicker) },
                        onOpenFallbackPicker = { selectedServer?.id?.let(::openFallbackNodePicker) },
                        onEditLocalNode = { selectedServer?.id?.let(::openLocalNodeEditor) },
                        onDeleteNode = ::removeServerNode,
                        onDeleteGroup = ::removeSubscriptionGroup,
                    )

                    AppScreen.Import -> ImportScreen(
                        padding = padding,
                        input = importInput,
                        recentImports = recentImports,
                        importInFlight = importInFlight,
                        errorMessage = importError,
                        onInputChange = { importInput = it },
                        onOpenFile = { fileLauncher.launch(arrayOf("*/*")) },
                        onOpenConfirm = {
                            scope.launch {
                                val submittedInput = importInput
                                importInFlight = true
                                importError = null
                                pendingImport = null
                                runCatching { ImportParser.buildPreview(submittedInput) }
                                    .onSuccess {
                                        pendingImport = it
                                        navigateSecondary(AppScreen.ConfirmImport)
                                    }
                                    .onFailure { importError = it.message ?: "导入失败，请检查链接格式。" }
                                importInput = ""
                                importInFlight = false
                            }
                        },
                    )

                    AppScreen.ConfirmImport -> ConfirmImportScreen(
                        padding = padding,
                        preview = pendingImport,
                        onConfirm = {
                            pendingImport?.let {
                                val applyResult = applyImport(it)
                                applyResult.message?.let { message -> subscriptionMessage = message }
                                navigateSecondary(AppScreen.Subscriptions)
                                applyResult.reconnectServer?.let { reconnectServer ->
                                    reconnectVpnForSettingsChange(
                                        server = reconnectServer,
                                        message = "订阅已更新，正在重新连接当前线路…",
                                    )
                                }
                            }
                        },
                        onImportOnly = { goToMain(MainTab.Import) },
                    )

                    AppScreen.Subscriptions -> SubscriptionsScreen(
                        padding = padding,
                        subscriptions = subscriptions,
                        message = subscriptionMessage,
                        refreshingIds = subscriptionRefreshInFlight.keys,
                        onRefresh = { subscriptionId ->
                            val index = subscriptions.indexOfFirst { it.id == subscriptionId }
                            if (index < 0 || subscriptionRefreshInFlight[subscriptionId] == true) return@SubscriptionsScreen
                            val subscription = subscriptions[index]
                            if (!subscription.autoUpdate || subscription.sourceUrl.isNullOrBlank()) {
                                subscriptionMessage = if (subscription.sourceUrl.isNullOrBlank()) {
                                    "${subscription.name} 来自文件导入，没有可刷新的订阅 URL，请重新导入文件。"
                                } else {
                                    "${subscription.name} 使用的是带时效签名的订阅链接，已按静态快照导入，不参与刷新。"
                                }
                                return@SubscriptionsScreen
                            }
                            subscriptionRefreshInFlight[subscriptionId] = true
                            subscriptions[index] = subscription.copy(status = "刷新中")
                            scope.launch {
                                runCatching {
                                    ImportParser.buildPreview(subscription.sourceUrl)
                                }.onSuccess { preview ->
                                    val applyResult = applyImport(preview)
                                    val refreshedIndex = subscriptions.indexOfFirst { it.id == subscriptionId }
                                    if (refreshedIndex >= 0) {
                                        subscriptions[refreshedIndex] = subscriptions[refreshedIndex].copy(
                                            status = "已同步",
                                            updatedAt = "刚刚",
                                            updatedAtMs = System.currentTimeMillis(),
                                        )
                                    }
                                    subscriptionMessage = applyResult.message
                                        ?: "${preview.group.name} 刷新完成，共同步 ${preview.nodes.count { !it.hiddenUnsupported }} 个节点。"
                                    if (applyResult.selectedServerChanged) {
                                        goToMain(MainTab.Servers)
                                    }
                                    applyResult.reconnectServer?.let { reconnectServer ->
                                        reconnectVpnForSettingsChange(
                                            server = reconnectServer,
                                            message = "订阅已刷新，正在重新连接当前线路…",
                                        )
                                    }
                                }.onFailure { error ->
                                    val failedIndex = subscriptions.indexOfFirst { it.id == subscriptionId }
                                    if (failedIndex >= 0) {
                                        subscriptions[failedIndex] = subscriptions[failedIndex].copy(status = "刷新失败")
                                    }
                                    subscriptionMessage = error.message ?: "订阅刷新失败。"
                                }
                                subscriptionRefreshInFlight.remove(subscriptionId)
                            }
                        },
                        onBack = ::onBack,
                        onOpenSettings = { navigateSecondary(AppScreen.Settings) },
                        onDelete = ::removeSubscriptionGroup,
                    )

                    AppScreen.PerApp -> PerAppScreen(
                        padding = padding,
                        proxyMode = proxyMode,
                        search = perAppSearch,
                        apps = protectedApps,
                        hasPendingChanges = pendingProtectedAppsApply,
                        applyEnabled = proxyMode == ProxyMode.PerApp &&
                            pendingProtectedAppsApply &&
                            connectionStatus == ConnectionStatus.Connected,
                        onBack = ::onBack,
                        onModeChange = ::updateProxyMode,
                        onSearchChange = { perAppSearch = it },
                        onToggleApp = ::updateProtectedAppEnabled,
                        onApplyChanges = ::applyProtectedAppsSelection,
                    )

                    AppScreen.Diagnostics -> DiagnosticsScreen(
                        padding = padding,
                        steps = diagnostics,
                        connectionStatus = connectionStatus,
                        onBack = ::onBack,
                        onRerun = ::rerunDiagnostics,
                        onChangeServer = { goToMain(MainTab.Servers) },
                        onCopySummary = { navigateSecondary(AppScreen.Settings) },
                    )

                    AppScreen.Settings -> SettingsScreen(
                        padding = padding,
                        settings = appSettings,
                        coreVersion = xraySnapshot.version,
                        coreAbi = xraySnapshot.abi,
                        coreStatus = xraySnapshot.status,
                        systemVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                        appVersion = APP_DISPLAY_VERSION,
                        syncInFlight = syncInFlight,
                        message = settingsMessage,
                        onBack = ::onBack,
                        onAutoConnectChange = { enabled ->
                            appSettings = appSettings.copy(autoConnectOnLaunch = enabled)
                            settingsMessage = if (enabled) "已开启启动后自动连接。" else "已关闭启动后自动连接。"
                        },
                        onAutoReconnectChange = { enabled ->
                            appSettings = appSettings.copy(autoReconnect = enabled)
                            settingsMessage = if (enabled) "已开启断线自动重连。" else "已关闭断线自动重连。"
                        },
                        onHeartbeatIntervalChange = { minutes ->
                            appSettings = appSettings.copy(heartbeatIntervalMinutes = minutes)
                            settingsMessage = "心跳检测间隔已设置为 $minutes 分钟。"
                        },
                        onDailyAutoUpdateChange = { enabled ->
                            appSettings = appSettings.copy(dailyAutoUpdate = enabled)
                            settingsMessage = if (enabled) "已开启每日自动更新。仅在非计费网络下执行。" else "已关闭每日自动更新。"
                        },
                        onUpdateNotificationsChange = ::setUpdateNotificationsEnabled,
                        onSyncNow = ::syncAllResources,
                        onDnsChange = ::updateDnsSettings,
                        onLogLevelChange = { level ->
                            appSettings = appSettings.copy(logLevel = level)
                            settingsMessage = "日志级别已切换为 ${level.displayName}。"
                        },
                        onAddQuickSettingsTile = ::requestQuickSettingsTile,
                        onViewLogs = ::openLogViewer,
                    )

                    AppScreen.LogViewer -> LogViewerScreen(
                        padding = padding,
                        logs = logViewerText,
                        onBack = ::onBack,
                        onRefresh = ::openLogViewer,
                    )

                    AppScreen.LocalNodeBuilder -> LocalNodeBuilderScreen(
                        padding = padding,
                        draft = localNodeDraft,
                        compatibilityWarning = localNodeCompatibilityWarning,
                        message = localNodeBuilderMessage,
                        editing = editingLocalNodeId != null,
                        onBack = ::onBack,
                        onDraftChange = {
                            localNodeDraft = it
                            localNodeBuilderMessage = null
                        },
                        onSave = ::saveLocalNodeDraft,
                    )

                    AppScreen.QrScanner -> QrScannerScreen(
                        padding = padding,
                        onBack = ::onBack,
                        onDetected = { rawValue ->
                            importScannedCode(rawValue)
                        },
                    )

                    AppScreen.Me -> MeScreen(
                        padding = padding,
                        connectionStatus = connectionStatus,
                        proxyMode = proxyMode,
                        server = selectedServer,
                        streamingRoutingEnabled = appSettings.streamingRoutingEnabled,
                        onOpenSubscriptions = { navigateSecondary(AppScreen.Subscriptions) },
                        onOpenPerApp = { navigateSecondary(AppScreen.PerApp) },
                        onOpenDiagnostics = { navigateSecondary(AppScreen.Diagnostics) },
                        onOpenSettings = { navigateSecondary(AppScreen.Settings) },
                        onOpenMediaRouting = { navigateSecondary(AppScreen.MediaRouting) },
                        onOpenPermission = { navigateSecondary(AppScreen.Permission) },
                    )

                    AppScreen.MediaRouting -> MediaRoutingScreen(
                        padding = padding,
                        enabled = appSettings.streamingRoutingEnabled,
                        connectionStatus = connectionStatus,
                        hasPendingChanges = pendingStreamingRoutingApply,
                        services = StreamingMediaManager.services,
                        selections = appSettings.streamingSelections,
                        currentServer = mediaRoutingDefaultServer,
                        servers = servers.toList(),
                        onBack = ::onBack,
                        onEnabledChange = ::updateStreamingRoutingEnabled,
                        onApplyChanges = ::applyStreamingRoutingChanges,
                        onOpenService = { serviceId ->
                            selectedStreamingServiceId = serviceId
                            navigateSecondary(AppScreen.MediaRoutingNodePicker)
                        },
                    )

                    AppScreen.MediaRoutingNodePicker -> MediaRoutingNodePickerScreen(
                        padding = padding,
                        service = selectedStreamingService,
                        currentServer = mediaRoutingDefaultServer,
                        groups = serverGroups.filter { it.type == ServerGroupType.Local || it.type == ServerGroupType.Subscription },
                        servers = servers.toList(),
                        selectedServerId = appSettings.streamingSelections
                            .firstOrNull { it.serviceId == selectedStreamingService?.id }
                            ?.serverId
                            ?.takeIf { serverId -> servers.any { it.id == serverId } }
                            .orEmpty(),
                        onBack = ::onBack,
                        onSelect = { serverId ->
                            selectedStreamingService?.id?.let { serviceId ->
                                updateStreamingServiceSelection(serviceId, serverId)
                            }
                            onBack()
                        },
                    )

                    AppScreen.PreProxyNodePicker -> PreProxyNodePickerScreen(
                        padding = padding,
                        mode = nodeLinkPickerMode,
                        server = servers.firstOrNull { it.id == preProxyPickerOwnerNodeId }
                            ?: selectedServer,
                        group = selectedServerGroup,
                        candidates = servers.filter { candidate ->
                            val ownerNodeId = preProxyPickerOwnerNodeId ?: selectedServerId
                            val ownerNode = servers.firstOrNull { it.id == ownerNodeId } ?: selectedServer
                            candidate.id != ownerNodeId &&
                                !candidate.hiddenUnsupported &&
                                ownerNode?.let(ManualNodeFactory::supportsPreProxy) == true &&
                                ManualNodeFactory.supportsPreProxy(candidate)
                        },
                        selectedServerId = (
                            servers.firstOrNull { it.id == preProxyPickerOwnerNodeId }
                                ?: selectedServer
                            )?.let { owner ->
                                when (nodeLinkPickerMode) {
                                    NodeLinkPickerMode.PreProxy -> owner.preProxyNodeId
                                    NodeLinkPickerMode.Fallback -> owner.fallbackNodeId
                                }
                            }.orEmpty(),
                        onBack = ::onBack,
                        onSelect = { targetNodeId ->
                            when (nodeLinkPickerMode) {
                                NodeLinkPickerMode.PreProxy -> updateNodePreProxy(preProxyPickerOwnerNodeId ?: selectedServerId, targetNodeId)
                                NodeLinkPickerMode.Fallback -> updateNodeFallback(preProxyPickerOwnerNodeId ?: selectedServerId, targetNodeId)
                            }
                            onBack()
                        },
                    )

                    AppScreen.RuleSourceDetail -> RuleSourceDetailScreen(
                        padding = padding,
                        summary = ruleDetailSummary,
                        updating = selectedRuleSource?.id?.let { ruleUpdateInFlight[it] == true } == true,
                        message = ruleMessage,
                        onBack = ::onBack,
                        onRefresh = { selectedRuleSource?.id?.let(::refreshRuleSource) },
                        onEdit = {
                            ruleEditorSourceId = selectedRuleSource?.id
                            navigateSecondary(AppScreen.AddRuleSource)
                        },
                        onDelete = { selectedRuleSource?.id?.let(::deleteRuleSource) },
                    )

                    AppScreen.AddRuleSource -> AddRuleSourceScreen(
                        padding = padding,
                        editingSource = ruleSources.firstOrNull { it.id == ruleEditorSourceId },
                        onBack = ::onBack,
                        onSubmit = { name, url, type ->
                            saveRuleSourceEdits(ruleEditorSourceId, name, url, type)
                        },
                    )

                    else -> Unit
                    }
                }
                }
            }

                if (blockingReconnectInFlight) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ModalScrimColor)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = ModalSurfaceColor,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 28.dp, vertical = 24.dp)
                                .widthIn(min = 220.dp, max = 280.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CircularProgressIndicator(
                                color = Primary,
                                strokeWidth = 3.dp,
                            )
                            Text(
                                text = "正在重新连接",
                                color = TextPrimary,
                                fontSize = TypeScale.SectionTitle,
                                lineHeight = TypeScale.SectionTitleLine,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = blockingReconnectMessage ?: "正在重新连接 VPN 并应用新的流媒体分流设置，请稍候…",
                                color = TextSecondary,
                                fontSize = TypeScale.Body,
                                lineHeight = TypeScale.BodyLine,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            val promptServer = servers.firstOrNull { it.id == pendingHomeSwitchPromptServerId }
            if (screen == AppScreen.Servers && promptServer != null) {
                PendingSwitchBanner(
                    serverName = promptServer.name,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 96.dp),
                    onClick = {
                        homeSwitchFocusNonce += 1
                        goToMain(MainTab.Home)
                    },
                )
                }
            }
        }
    }
}
