package com.mallocgfw.app.ui

import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.core.StateOps
import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.widget.Toast
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.R
import com.mallocgfw.app.model.AppScreen
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.ImportPreview
import com.mallocgfw.app.model.AppLanguage
import com.mallocgfw.app.model.LocalNodeDraft
import com.mallocgfw.app.model.MainTab
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.RuleRefreshResult
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceKind
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.RuleSourceStatus
import com.mallocgfw.app.model.RuleSourceType
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.ProtectedApp
import com.mallocgfw.app.model.SubscriptionItem
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.model.isSameImportedNode
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.model.mergedSubscriptionGroupNodes
import com.mallocgfw.app.model.normalizedAppVpnMtu
import com.mallocgfw.app.model.RuleTargetPolicy
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.subscriptionMergeKey
import com.mallocgfw.app.model.subscriptionNameKey
import com.mallocgfw.app.ui.theme.Background
import com.mallocgfw.app.ui.theme.MallocGfwTheme
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.update.AppUpdateManager
import com.mallocgfw.app.update.AppUpdateStatus
import com.mallocgfw.app.update.AppUpdateUiState
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
import com.mallocgfw.app.xray.TailscaleRuntimeManager
import com.mallocgfw.app.xray.normalizePrivateTailscaleSubnet
import com.mallocgfw.app.xray.VpnRuntimeStore
import com.mallocgfw.app.xray.VpnServiceController
import com.mallocgfw.app.xray.XrayCoreManager
import com.mallocgfw.app.xray.XrayCoreStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MallocGfwApp(
    launchRequest: MainActivity.LaunchRequest? = null,
    onLaunchRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val quickTileLabel = stringResource(R.string.qs_tile_label)
    val scope = rememberCoroutineScope()
    val screenStateHolder = rememberSaveableStateHolder()
    val initialLoad = remember { AppController.loadForUi(context) }
    val initialState = initialLoad.state
    val xraySnapshot by XrayCoreManager.snapshot.collectAsState()
    val tailscaleSnapshot by TailscaleRuntimeManager.snapshot.collectAsState()
    val vpnSnapshot by VpnRuntimeStore.snapshot.collectAsState()
    var screen by rememberSaveable { mutableStateOf(AppScreen.Launch) }
    var screenHistory by rememberSaveable { mutableStateOf(listOf<AppScreen>()) }
    var previousMainTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    // Where "Add nodes" was opened from (Nodes "+", or Home with no nodes yet).
    var importReturnTab by rememberSaveable { mutableStateOf(MainTab.Servers) }
    var mainTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var connectionStatus by rememberSaveable { mutableStateOf(ConnectionStatus.Disconnected) }
    // The saved state lives in AppController, shared with the MCP server, the
    // VPN service and background sync. These read it (recomposing on any
    // change, wherever it came from) and write through it; it saves itself.
    var proxyMode: ProxyMode by AppStateField({ it.proxyMode }) { s, v -> s.copy(proxyMode = v) }
    var appSettings: com.mallocgfw.app.model.AppSettings by AppStateField({ it.settings }) { s, v -> s.copy(settings = v) }
    var selectedServerId: String by AppStateField(
        { s -> s.selectedServerId.ifBlank { s.servers.firstOrNull { !it.hiddenUnsupported }?.id.orEmpty() } },
    ) { s, v -> s.copy(selectedServerId = v) }
    var selectedRuleSourceId by rememberSaveable {
        mutableStateOf(initialState.ruleSources.firstOrNull()?.id.orEmpty())
    }
    var ruleEditorSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var ruleEditorKind by rememberSaveable { mutableStateOf(RuleSourceKind.RemoteUrl) }
    var scrollRulesToCustomSources by remember { mutableStateOf(false) }
    var preProxyPickerOwnerNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var nodeLinkPickerMode by rememberSaveable { mutableStateOf(NodeLinkPickerMode.PreProxy) }
    var serverFilter by rememberSaveable { mutableStateOf("all") }
    var serverSearch by rememberSaveable { mutableStateOf("") }
    var importInput by rememberSaveable { mutableStateOf("") }
    val servers: List<ServerNode> by AppStateView { it.servers }
    val serverGroups: List<ServerGroup> by AppStateView { it.serverGroups }
    val subscriptions: List<SubscriptionItem> by AppStateView { it.subscriptions }
    val ruleSources: List<RuleSourceItem> by AppStateView { it.ruleSources }
    val recentImports: List<String> by AppStateView { it.recentImports }
    val protectedApps: List<ProtectedApp> by AppStateView { it.protectedApps }
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
    var updateState by remember { mutableStateOf(AppUpdateUiState()) }
    var seenUpdateTag by remember { mutableStateOf(AppUpdateManager.seenUpdateTag(context)) }
    // Red dots on Me, the settings gear and "Check for updates" while a newer
    // release exists that the user hasn't opened the update page for.
    val updateBadge by remember {
        derivedStateOf {
            val info = updateState.info
            info != null &&
                info.tagName != seenUpdateTag &&
                info.isNewerThan(AppUpdateManager.currentVersionCode(context), AppUpdateManager.currentVersionName(context))
        }
    }
    var showUpdatePrompt by rememberSaveable { mutableStateOf(false) }
    var showLatestVersionDialog by rememberSaveable { mutableStateOf(false) }
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
    var selectedPerAppId by rememberSaveable { mutableStateOf("") }
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

    fun currentPersistedState(): PersistedAppState = AppController.current()

    fun applyPersistedState(nextState: PersistedAppState) {
        AppController.edit { nextState }
        warmServerSections()
    }

    fun goToMain(tab: MainTab) {
        if (screen == AppScreen.Subscriptions && tab != MainTab.Import) {
            subscriptionMessage = null
        }
        if (tab == MainTab.Import && mainTab != MainTab.Import) {
            importReturnTab = mainTab
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
            AppScreen.OpenSourceLicenses, AppScreen.Mcp, AppScreen.MediaRouting, AppScreen.MediaRoutingNodePicker, AppScreen.PerAppNodePicker, AppScreen.LogViewer,
            AppScreen.Tailscale -> MainTab.Me
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
            AppScreen.OpenSourceLicenses, AppScreen.Mcp, AppScreen.MediaRouting, AppScreen.MediaRoutingNodePicker, AppScreen.PerAppNodePicker, AppScreen.LogViewer,
            AppScreen.Tailscale -> MainTab.Me
            else -> MainTab.Home
        }
        screenHistory = screenHistory + screen
        screen = target
    }

    fun startVpnService(serverId: String) {
        lastRequestedConnectionServerId = serverId
        val server = servers.firstOrNull { it.id == serverId }
            ?: error("未找到要连接的节点。")
        VpnServiceController.start(context, server)
    }

    fun scheduleBlockingReconnect(
        server: ServerNode,
        message: String,
        disconnectFirst: Boolean,
    ) {
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

    fun reconnectForTailscaleChange(message: String) {
        if (connectionStatus == ConnectionStatus.Connected || VpnServiceController.isRunning()) {
            resolveServerForSettingsReconnect()?.let { reconnectVpnForSettingsChange(it, message) }
        }
    }

    fun connectTailscale(authKey: String) {
        val nextSettings = appSettings.copy(tailscaleEnabled = true)
        appSettings = nextSettings
        scope.launch {
            TailscaleRuntimeManager.restart(context, nextSettings, authKey)
                .onSuccess {
                    runtimeMessage = "Tailscale 已连接，Tailnet 分流已准备就绪。"
                    reconnectForTailscaleChange("Tailscale 已连接，正在重新加载分流…")
                }
                .onFailure { error ->
                    runtimeMessage = error.message ?: "Tailscale 连接失败。"
                }
        }
    }

    fun restartTailscaleForSettings(nextSettings: com.mallocgfw.app.model.AppSettings, message: String) {
        appSettings = nextSettings
        scope.launch {
            TailscaleRuntimeManager.restart(context, nextSettings)
                .onSuccess {
                    runtimeMessage = message
                    reconnectForTailscaleChange("Tailscale 设置已变更，正在重新加载分流…")
                }
                .onFailure { error ->
                    runtimeMessage = error.message ?: "Tailscale 设置未能应用。"
                }
        }
    }

    // Real VPN permission state for the Me row and the permission page.
    // Refreshed on every resume: the user can revoke it in system settings,
    // or another VPN app can take it over.
    var vpnPermissionGranted by remember { mutableStateOf(VpnService.prepare(context) == null) }
    LifecycleResumeEffect(Unit) {
        vpnPermissionGranted = VpnService.prepare(context) == null
        onPauseOrDispose { }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val serverId = pendingVpnServerId
        pendingVpnServerId = null
        vpnPermissionGranted = VpnService.prepare(context) == null
        if (serverId == null) {
            // Asked from the permission page only: no connection to start.
            return@rememberLauncherForActivityResult
        }
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

    fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent == null) {
            vpnPermissionGranted = true
            return
        }
        pendingVpnServerId = null
        vpnPermissionLauncher.launch(prepareIntent)
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
            quickTileLabel,
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
                else -> tr("添加快捷开关失败，系统返回代码 $result。", "Couldn't add the Quick Settings tile (system code $result).", "Не удалось добавить плитку быстрых настроек (код системы $result).")
            }
        }
    }

    fun toggleThemeMode() {
        appSettings = appSettings.copy(lightThemeEnabled = !appSettings.lightThemeEnabled)
    }

    fun updateLanguage(language: AppLanguage) {
        if (appSettings.language == language) return
        val previousRegion = appSettings.geoRoutingRegion
        AppController.edit { StateOps.setLanguage(it, language) }
        val geoRoutingRegionChanged = appSettings.geoRoutingRegion != previousRegion
        settingsMessage = when (language.resolveAppLanguage()) {
            ResolvedLanguage.Chinese -> "界面语言已更新。"
            ResolvedLanguage.English -> "Language updated."
            ResolvedLanguage.Russian -> "Язык интерфейса обновлен."
        }
        val shouldReconnect = geoRoutingRegionChanged && (
            VpnServiceController.isRunning() ||
                connectionStatus == ConnectionStatus.Connected ||
                connectionStatus == ConnectionStatus.Connecting ||
                connectionStatus == ConnectionStatus.Disconnecting
            )
        val reconnectServer = resolveServerForSettingsReconnect()
        if (shouldReconnect && reconnectServer != null) {
            reconnectVpnForSettingsChange(
                server = reconnectServer,
                message = "Geo 分流国家已随界面语言更新，正在重连 VPN 以应用新规则…",
            )
        }
    }

    fun updateGeoRoutingRegion(region: AppGeoRoutingRegion) {
        if (blockingReconnectInFlight) return
        if (appSettings.geoRoutingRegion == region) return
        AppController.edit { StateOps.setGeoRoutingRegion(it, region) }
        val shouldReconnect = VpnServiceController.isRunning() ||
            connectionStatus == ConnectionStatus.Connected ||
            connectionStatus == ConnectionStatus.Connecting ||
            connectionStatus == ConnectionStatus.Disconnecting
        ruleMessage = if (shouldReconnect) "正在重新连接以应用新模式…" else null
        val reconnectServer = resolveServerForSettingsReconnect()
        if (shouldReconnect && reconnectServer != null) {
            reconnectVpnForSettingsChange(
                server = reconnectServer,
                message = "Geo 分流国家已更新，正在重连 VPN 以应用新规则…",
            )
        }
    }

    fun updateStreamingRoutingEnabled(enabled: Boolean) {
        AppController.edit { StateOps.setStreamingRoutingEnabled(it, enabled) }
        pendingStreamingRoutingApply = true
        settingsMessage = if (enabled) {
            "流媒体分流已开启。"
        } else {
            "流媒体分流已关闭。"
        }
    }

    fun updateGlobalProxyEnabled(enabled: Boolean) {
        if (blockingReconnectInFlight) return
        if (appSettings.globalProxyEnabled == enabled) return
        AppController.edit { StateOps.setGlobalProxy(it, enabled) }
        val shouldReconnect = VpnServiceController.isRunning() ||
            connectionStatus == ConnectionStatus.Connected ||
            connectionStatus == ConnectionStatus.Connecting ||
            connectionStatus == ConnectionStatus.Disconnecting
        // The mode card already explains each mode; only report the reconnect.
        ruleMessage = if (shouldReconnect) "正在重新连接以应用新模式…" else null
        val reconnectServer = resolveServerForSettingsReconnect()
        if (shouldReconnect && reconnectServer != null) {
            reconnectVpnForSettingsChange(
                server = reconnectServer,
                message = if (enabled) {
                    "全局代理已开启，正在重启 VPN 服务。完成前请稍候，局域网/私网仍不走代理。"
                } else {
                    "全局代理已关闭，正在重连 VPN 以恢复规则分流…"
                },
            )
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
        val current = protectedApps.firstOrNull { it.id == appId } ?: return
        val nextEnabled = !current.enabled
        AppController.edit { StateOps.setProtectedApps(it, mapOf(appId to nextEnabled)) }
        pendingProtectedAppsApply = true
        settingsMessage = if (nextEnabled) {
            tr("${current.name} 已加入名单。", "${current.name} added to the list.", "${current.name}: добавлено в список.")
        } else {
            tr("${current.name} 已移出名单。", "${current.name} removed from the list.", "${current.name}: удалено из списка.")
        }
    }

    fun updateProtectedAppRoute(appId: String, enabled: Boolean, serverId: String) {
        val current = protectedApps.firstOrNull { it.id == appId } ?: return
        AppController.edit { StateOps.setProtectedAppRoute(it, appId, enabled, serverId) }
        pendingProtectedAppsApply = true
        settingsMessage = when {
            !enabled -> tr("${current.name} 不再代理。", "${current.name} is no longer proxied.", "${current.name}: без прокси.")
            serverId.isBlank() -> tr("${current.name} 使用默认节点。", "${current.name} uses the default node.", "${current.name}: узел по умолчанию.")
            else -> {
                val name = servers.firstOrNull { it.id == serverId }?.name.orEmpty()
                tr("${current.name} 改走 $name。", "${current.name} now uses $name.", "${current.name}: через $name.")
            }
        }
    }

    fun setProtectedAppsEnabled(appIds: List<String>, enabled: Boolean) {
        if (appIds.isEmpty()) return
        AppController.edit { StateOps.setProtectedApps(it, appIds.associateWith { enabled }) }
        pendingProtectedAppsApply = true
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
        AppController.edit { StateOps.setStreamingRoute(it, serviceId, serverId) }
        pendingStreamingRoutingApply = true
        val name = StreamingMediaManager.serviceById(serviceId)?.name
        settingsMessage = if (serverId == StreamingMediaManager.DIRECT) {
            tr("${name ?: "流媒体"} 已设为直连。", "${name ?: "Media"} now goes direct.", "${name ?: "Медиа"}: напрямую.")
        } else {
            tr("${name ?: "流媒体"} 节点已保存。", "${name ?: "Media"} node saved.", "Узел для ${name ?: "медиа"} сохранён.")
        }
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
                ImportParser.buildFilePreviewWithProviders(name, content)
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
        // Import is still a main "tab" internally but is opened from another page
        // (Nodes "+", or Home with no nodes), so go back there; previousMainTab
        // would be Import itself.
        if (screen == AppScreen.Import) {
            goToMain(importReturnTab)
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
                settings = appSettings,
            )
            if (runId == diagnosticRunId) {
                diagnostics = result
            }
        }
    }

    fun applyImport(preview: ImportPreview): ImportApplyResult {
        val connectedServerId = if (connectionStatus == ConnectionStatus.Connected) {
            vpnSnapshot.activeServerId?.takeIf { it.isNotBlank() } ?: appSettings.lastConnectedServerId
        } else {
            null
        }
        val outcome = StateOps.applyImport(currentPersistedState(), preview, connectedServerId)
        applyPersistedState(outcome.state)
        expandedGroups[preview.group.id] = true
        return ImportApplyResult(
            selectedServerChanged = outcome.selectedServerChanged,
            message = outcome.message,
            reconnectServer = outcome.reconnectServer,
        )
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
        if (server.mihomoProxyJson.isNotBlank()) {
            runtimeMessage = tr("${server.protocol} 节点由兼容内核运行，暂不支持在 App 内编辑；如需修改，请删除后重新导入。", "${server.protocol} nodes run on the compatibility core and can't be edited in the app. To change one, delete it and import it again.", "Узлы ${server.protocol} работают на ядре совместимости и не редактируются в приложении. Чтобы изменить узел, удалите его и импортируйте снова.")
            return
        }
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
        if (!NodeLatencyTester.supportsHeartbeatFallback(server)) {
            selectServerForUi(server.id)
            runtimeMessage = NodeLatencyTester.heartbeatFallbackUnsupportedMessage(server)
            return
        }
        preProxyPickerOwnerNodeId = server.id
        nodeLinkPickerMode = NodeLinkPickerMode.Fallback
        selectServerForUi(server.id)
        navigateSecondary(AppScreen.PreProxyNodePicker)
    }

    /** [candidateId] can be linked to [owner]: any runnable node other than the owner. */
    fun isRunnableOtherNode(owner: ServerNode, candidateId: String): Boolean {
        return candidateId.trim().takeIf { it.isNotBlank() }?.let { normalizedId ->
            normalizedId != owner.id &&
                servers.any { candidate ->
                    candidate.id == normalizedId && ManualNodeFactory.isRunnable(candidate)
                }
        } == true
    }

    fun isValidLinkedNodeCandidate(owner: ServerNode, candidateId: String): Boolean {
        return ManualNodeFactory.supportsPreProxy(owner) && isRunnableOtherNode(owner, candidateId)
    }

    fun isValidFallbackNodeCandidate(owner: ServerNode, candidateId: String): Boolean {
        return ManualNodeFactory.isRunnable(owner) &&
            NodeLatencyTester.supportsHeartbeatFallback(owner) &&
            isRunnableOtherNode(owner, candidateId)
    }

    fun updateNodePreProxy(nodeId: String, preProxyNodeId: String) {
        val node = servers.firstOrNull { it.id == nodeId } ?: return
        // The picker only offers valid nodes; anything else clears the link.
        val target = preProxyNodeId.trim().takeIf { isValidLinkedNodeCandidate(node, it) }.orEmpty()
        var outcome: StateOps.LinkOutcome? = null
        AppController.edit { state -> StateOps.setPreProxy(state, nodeId, target).also { outcome = it }.state }
        val result = outcome ?: return
        if (!result.changed) return
        runtimeMessage = result.message
        warmServerSections()
    }

    fun updateNodeFallback(nodeId: String, fallbackNodeId: String) {
        val node = servers.firstOrNull { it.id == nodeId } ?: return
        val target = fallbackNodeId.trim().takeIf { isValidFallbackNodeCandidate(node, it) }.orEmpty()
        var outcome: StateOps.LinkOutcome? = null
        AppController.edit { state -> StateOps.setFallback(state, nodeId, target).also { outcome = it }.state }
        val result = outcome ?: return
        if (result.changed) warmServerSections()
        (result.error ?: result.message)?.let { runtimeMessage = it }
    }

    fun fallbackNodeFor(server: ServerNode?): ServerNode? {
        val source = server ?: return null
        val fallbackId = source.fallbackNodeId.trim().takeIf { it.isNotBlank() } ?: return null
        if (!NodeLatencyTester.supportsHeartbeatFallback(source)) return null
        if (source.id == lastFallbackTargetServerId) return null
        return servers.firstOrNull { candidate ->
            candidate.id == fallbackId &&
                candidate.id != source.id &&
                ManualNodeFactory.isRunnable(candidate)
        }
    }

    fun triggerFallbackReconnect(
        source: ServerNode,
        fallback: ServerNode,
        reason: String,
        disconnectFirst: Boolean,
    ) {
        val message = tr("$reason，正在切换到备用节点 ${fallback.name}…", "$reason. Switching to fallback node ${fallback.name}…", "$reason. Переключение на резервный узел ${fallback.name}…")
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
            val description = "手动编辑的 ${localNodeDraft.protocol.displayName} Local 节点"
            AppController.edit { StateOps.saveLocalNode(it, node, editingLocalNodeId, description) }
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
        val next = StateOps.removeNode(currentPersistedState(), nodeId) ?: return
        applyPersistedState(next)
        if (screen == AppScreen.NodeDetail) {
            goToMain(MainTab.Servers)
        }
    }

    fun removeSubscriptionGroup(groupId: String) {
        val next = StateOps.removeSubscriptionGroup(currentPersistedState(), groupId) ?: return
        applyPersistedState(next)
        expandedGroups.remove(groupId)
        if (screen == AppScreen.Subscriptions || screen == AppScreen.NodeDetail) {
            goToMain(MainTab.Servers)
        }
    }

    fun replaceRuleSource(updated: RuleSourceItem) {
        AppController.edit { StateOps.upsertRuleSource(it, updated) }
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
                ruleMessage = tr("${result.source.name} 已更新，转换 ${result.compiled.convertedRules} 条。", "${result.source.name} updated, ${result.compiled.convertedRules} rules converted.", "${result.source.name}: обновлено, преобразовано правил: ${result.compiled.convertedRules}.")
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

    fun applyUpdateCheckResult(
        info: com.mallocgfw.app.update.AppUpdateInfo,
        promptIfAvailable: Boolean,
        promptIfLatest: Boolean = false,
    ) {
        val currentCode = AppUpdateManager.currentVersionCode(context)
        val currentName = AppUpdateManager.currentVersionName(context)
        if (info.isNewerThan(currentCode, currentName)) {
            updateState = AppUpdateUiState(
                status = AppUpdateStatus.Available,
                info = info,
                message = tr("发现新版本 ${info.versionName}。", "New version ${info.versionName} available.", "Доступна новая версия ${info.versionName}."),
            )
            if (promptIfAvailable) {
                showUpdatePrompt = true
            }
        } else {
            updateState = AppUpdateUiState(
                status = AppUpdateStatus.Latest,
                info = info,
                message = "当前为最新版。",
            )
            if (promptIfLatest) {
                showLatestVersionDialog = true
            }
        }
    }

    fun checkForUpdates(
        promptIfAvailable: Boolean = false,
        promptIfLatest: Boolean = false,
    ) {
        if (updateState.status == AppUpdateStatus.Checking ||
            updateState.status == AppUpdateStatus.Downloading
        ) {
            return
        }
        updateState = updateState.copy(
            status = AppUpdateStatus.Checking,
            message = "正在检查 GitHub Release…",
            downloadedApkPath = null,
            downloadedBytes = 0L,
            totalBytes = 0L,
        )
        scope.launch {
            runCatching {
                AppUpdateManager.checkLatest()
            }.onSuccess { info ->
                applyUpdateCheckResult(info, promptIfAvailable, promptIfLatest)
            }.onFailure { error ->
                updateState = AppUpdateUiState(
                    status = AppUpdateStatus.Failed,
                    info = updateState.info,
                    message = error.message ?: "检查更新失败。",
                )
            }
        }
    }

    fun openUpdateScreen() {
        navigateSecondary(AppScreen.Update)
        if (updateState.status in setOf(
                AppUpdateStatus.Idle,
                AppUpdateStatus.Latest,
                AppUpdateStatus.Failed,
            )
        ) {
            checkForUpdates()
        }
    }

    fun downloadUpdateApk() {
        val info = updateState.info ?: return
        if (updateState.status == AppUpdateStatus.Downloading) return

        updateState = updateState.copy(
            status = AppUpdateStatus.Downloading,
            message = tr("正在从 GitHub 下载 ${info.apkName}…", "Downloading ${info.apkName} from GitHub…", "Загрузка ${info.apkName} с GitHub…"),
            downloadedApkPath = null,
            downloadedBytes = 0L,
            totalBytes = info.apkSizeBytes,
        )
        scope.launch {
            runCatching {
                AppUpdateManager.downloadApk(context, info) { downloadedBytes, totalBytes ->
                    if (updateState.status == AppUpdateStatus.Downloading) {
                        updateState = updateState.copy(
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes.takeIf { it > 0L } ?: info.apkSizeBytes,
                        )
                    }
                }
            }.onSuccess { file ->
                updateState = AppUpdateUiState(
                    status = AppUpdateStatus.Downloaded,
                    info = info,
                    message = "安装包下载完成，点击“安装”继续升级。",
                    downloadedApkPath = file.absolutePath,
                    downloadedBytes = file.length(),
                    totalBytes = file.length(),
                )
            }.onFailure { error ->
                updateState = AppUpdateUiState(
                    status = AppUpdateStatus.Available,
                    info = info,
                    message = error.message ?: "安装包下载失败，请稍后重试。",
                    totalBytes = info.apkSizeBytes,
                )
                AppLogManager.append(context, "UPDATE", "下载更新失败", error)
            }
        }
    }

    fun installDownloadedUpdate() {
        val file = AppUpdateManager.downloadedFile(updateState.downloadedApkPath)
        if (file == null) {
            updateState = updateState.copy(message = "安装包不存在，请重新下载。")
            return
        }
        val message = runCatching {
            AppUpdateManager.installDownloadedApk(context, file)
        }.getOrElse { error ->
            error.message ?: "无法打开系统安装器。"
        }
        if (!message.isNullOrBlank()) {
            updateState = updateState.copy(message = message)
        }
    }

    fun updateDnsSettings(
        dnsMode: AppDnsMode,
        customDnsValue: String,
    ) {
        val normalizedCustomValue = customDnsValue.trim()
        if (AppController.edit { StateOps.setDns(it, dnsMode, normalizedCustomValue) } == null) {
            settingsMessage = "请输入自定义 DNS 地址。"
            return
        }
        settingsMessage = when (dnsMode) {
            AppDnsMode.System -> "DNS 已切换为系统解析。"
            AppDnsMode.Remote -> "DNS 已切换为远端 1.1.1.1。"
            AppDnsMode.Custom -> tr("DNS 已更新为 $normalizedCustomValue。", "DNS set to $normalizedCustomValue.", "DNS изменён на $normalizedCustomValue.")
        }
        // Like MTU: the tunnel picks up DNS only when it starts.
        val activeServer = resolveServerForSettingsReconnect()
        if (connectionStatus == ConnectionStatus.Connected && activeServer != null) {
            reconnectVpnForSettingsChange(
                server = activeServer,
                message = tr("DNS 已更新，正在重连 VPN…", "DNS updated. Reconnecting VPN…", "DNS обновлён. Переподключение VPN…"),
            )
        }
    }

    fun updateVpnMtu(mtu: Int) {
        val normalizedMtu = normalizedAppVpnMtu(mtu)
        if (normalizedMtu == appSettings.vpnMtu) {
            settingsMessage = tr("VPN MTU 已经是 $normalizedMtu。", "VPN MTU is already $normalizedMtu.", "VPN MTU уже равен $normalizedMtu.")
            return
        }
        AppController.edit { StateOps.setMtu(it, normalizedMtu) }
        val activeServer = resolveServerForSettingsReconnect()
        if (connectionStatus == ConnectionStatus.Connected && activeServer != null) {
            settingsMessage = tr("VPN MTU 已设置为 $normalizedMtu，正在重连以生效。", "VPN MTU set to $normalizedMtu. Reconnecting to apply it.", "VPN MTU установлен на $normalizedMtu. Переподключение для применения.")
            reconnectVpnForSettingsChange(
                server = activeServer,
                message = tr("VPN MTU 已设置为 $normalizedMtu，正在重连 VPN…", "VPN MTU set to $normalizedMtu. Reconnecting VPN…", "VPN MTU установлен на $normalizedMtu. Переподключение VPN…"),
            )
        } else {
            settingsMessage = tr("VPN MTU 已设置为 $normalizedMtu，下次连接生效。", "VPN MTU set to $normalizedMtu. Takes effect on the next connection.", "VPN MTU установлен на $normalizedMtu. Вступит в силу при следующем подключении.")
        }
    }

    fun updateProxyQuic(enabled: Boolean) {
        if (enabled == appSettings.proxyQuicEnabled) return
        AppController.edit { StateOps.setProxyQuic(it, enabled) }
        val state = if (enabled) {
            tr("已允许 QUIC 走代理", "QUIC through the proxy is on", "QUIC через прокси включён")
        } else {
            tr("国外 QUIC 已改用 TCP", "Foreign QUIC now falls back to TCP", "Зарубежный QUIC теперь переходит на TCP")
        }
        val activeServer = resolveServerForSettingsReconnect()
        if (connectionStatus == ConnectionStatus.Connected && activeServer != null) {
            settingsMessage = tr("$state，正在重连以生效。", "$state. Reconnecting to apply it.", "$state. Переподключение для применения.")
            reconnectVpnForSettingsChange(
                server = activeServer,
                message = tr("$state，正在重连 VPN…", "$state. Reconnecting VPN…", "$state. Переподключение VPN…"),
            )
        } else {
            settingsMessage = tr("$state，下次连接生效。", "$state. Takes effect on the next connection.", "$state. Вступит в силу при следующем подключении.")
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
                pendingImport = preview
                navigateSecondary(AppScreen.ConfirmImport)
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

    fun addRuleSource(name: String, url: String, type: RuleSourceType, policy: RuleTargetPolicy = RuleTargetPolicy.Proxy) {
        val draft = RuleSourceManager.createDraft(name = name, url = url, requestedType = type, policy = policy)
        replaceRuleSource(draft.copy(status = RuleSourceStatus.Updating))
        goToMain(MainTab.Rules)
        scrollRulesToCustomSources = true
        refreshRuleSource(draft.id)
    }

    fun addTextRuleSource(name: String, text: String, type: RuleSourceType) {
        val draft = RuleSourceManager.createTextDraft(name = name, text = text, requestedType = type)
        replaceRuleSource(draft.copy(status = RuleSourceStatus.Updating))
        goToMain(MainTab.Rules)
        scrollRulesToCustomSources = true
        refreshRuleSource(draft.id)
    }

    fun saveRuleSourceEdits(
        editingSourceId: String?,
        name: String,
        content: String,
        type: RuleSourceType,
        sourceKind: RuleSourceKind,
        policy: RuleTargetPolicy = RuleTargetPolicy.Proxy,
    ) {
        if (editingSourceId.isNullOrBlank() || ruleSources.none { it.id == editingSourceId }) {
            if (sourceKind == RuleSourceKind.LocalText) {
                addTextRuleSource(name, content, type)
            } else {
                addRuleSource(name, content, type, policy)
            }
            return
        }
        val index = ruleSources.indexOfFirst { it.id == editingSourceId }
        val current = ruleSources[index]
        val updated = current.copy(
            name = name.ifBlank { current.name },
            url = if (sourceKind == RuleSourceKind.LocalText) current.url.ifBlank { "local://manual-rule" } else content.trim(),
            content = if (sourceKind == RuleSourceKind.LocalText) content else "",
            type = type,
            sourceKind = sourceKind,
            policy = if (sourceKind == RuleSourceKind.RemoteUrl) policy else current.policy,
            status = RuleSourceStatus.Updating,
            lastError = null,
        )
        replaceRuleSource(updated)
        goToMain(MainTab.Rules)
        scrollRulesToCustomSources = true
        refreshRuleSource(updated.id)
    }

    fun toggleRuleSourceEnabled(sourceId: String) {
        val current = ruleSources.firstOrNull { it.id == sourceId } ?: return
        val enabled = !current.enabled
        AppController.edit { StateOps.setRuleSourceEnabled(it, sourceId, enabled) }
        // A built-in or added source that was never downloaded has no rules yet.
        if (enabled && current.status != RuleSourceStatus.Ready && current.sourceKind == RuleSourceKind.RemoteUrl) {
            refreshRuleSource(sourceId)
        }
        ruleMessage = if (enabled) {
            tr("${current.name} 已启用。重新开关代理后生效。", "${current.name} enabled. Takes effect after turning the proxy off and on.", "${current.name}: включено. Вступит в силу после перезапуска прокси.")
        } else {
            tr("${current.name} 已停用。重新开关代理后生效。", "${current.name} disabled. Takes effect after turning the proxy off and on.", "${current.name}: отключено. Вступит в силу после перезапуска прокси.")
        }
    }

    fun deleteRuleSource(sourceId: String) {
        val source = ruleSources.firstOrNull { it.id == sourceId } ?: return
        RuleSourceManager.deleteSource(context, source)
        AppController.edit { StateOps.removeRuleSource(it, sourceId) }
        if (selectedRuleSourceId == sourceId && !source.systemDefault) {
            selectedRuleSourceId = ruleSources.firstOrNull()?.id.orEmpty()
        }
        ruleMessage = tr("${source.name} 已移除。", "${source.name} removed.", "${source.name}: удалено.")
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
            runtimeMessage = tr("${server.name} 已经是当前线路。", "${server.name} is already the current node.", "${server.name} уже выбран.")
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
            message = tr("正在切换到 ${server.name}，将重新连接 VPN 以应用新线路…", "Switching to ${server.name}; reconnecting VPN to apply the new node…", "Переключение на ${server.name}: VPN переподключится для применения нового узла…"),
        )
    }

    fun testServerLatency(nodeId: String) {
        val index = servers.indexOfFirst { it.id == nodeId }
        if (index < 0) return
        if (latencyTestInFlight[nodeId] == true) {
            val server = servers[index]
            runtimeMessage = tr("节点 ${server.name} 正在检测中，请稍候。", "Node ${server.name} is being tested. Please wait.", "Узел ${server.name} проверяется, подождите.")
            return
        }
        val server = servers[index]
        if (VpnServiceController.isActuallyRunning(context) && NodeLatencyTester.requiresCoreProbe(server)) {
            runtimeMessage = tr("当前已连接 VPN，这类 ${server.protocol} 节点请先断开当前连接后再检测。", "VPN is connected. Disconnect first to test this ${server.protocol} node.", "VPN подключён. Чтобы проверить этот узел ${server.protocol}, сначала отключитесь.")
            AppLogManager.append(
                context,
                "LATENCY",
                "跳过检测：${server.name} 使用 ${server.protocol}/${server.transport}，当前 VPN 已连接",
            )
            return
        }
        latencyTestInFlight[nodeId] = true
        runtimeMessage = tr("正在检测节点 ${server.name}…", "Testing node ${server.name}…", "Проверка узла ${server.name}…")
        AppLogManager.append(context, "LATENCY", "开始检测节点：${server.name} (${server.address}:${server.port})")
        scope.launch {
            var latencyStateChanged = false
            runCatching { NodeLatencyTester.measure(context, server) }
                .onSuccess { latency ->
                    val before = servers.firstOrNull { it.id == nodeId }
                    AppController.edit { StateOps.setNodeLatency(it, nodeId, latency) }
                    if (before != null && before != servers.firstOrNull { it.id == nodeId }) latencyStateChanged = true
                    AppLogManager.append(context, "LATENCY", "节点检测成功：${server.name} -> ${latency} ms")
                    runtimeMessage = tr("节点 ${server.name} 检测完成，延迟 ${latency} ms。", "Node ${server.name} tested: ${latency} ms.", "Узел ${server.name} проверен: ${latency} мс.")
                }
                .onFailure { error ->
                    val before = servers.firstOrNull { it.id == nodeId }
                    AppController.edit { StateOps.setNodeLatency(it, nodeId, null) }
                    if (before != null && before != servers.firstOrNull { it.id == nodeId }) latencyStateChanged = true
                    val latestIndex = servers.indexOfFirst { it.id == nodeId }
                    AppLogManager.append(context, "LATENCY", "节点检测失败：${server.name}", error)
                    runtimeMessage = tr("节点 ${server.name} 检测失败：${error.message ?: "请稍后重试。"}", "Node ${server.name} test failed: ${error.message ?: "Try again later."}", "Не удалось проверить узел ${server.name}: ${error.message ?: "Повторите попытку позже."}")
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

    // Notices at the top of Settings and Rules are confirmations, not states:
    // let them go after a few seconds instead of staying until the next one.
    LaunchedEffect(settingsMessage) {
        if (settingsMessage != null) {
            delay(NOTICE_VISIBLE_MS)
            settingsMessage = null
        }
    }
    LaunchedEffect(ruleMessage) {
        if (ruleMessage != null) {
            delay(NOTICE_VISIBLE_MS)
            ruleMessage = null
        }
    }

    LaunchedEffect(Unit) {
        val sources = ruleSources
        withContext(Dispatchers.IO) { RuleSourceManager.removeOrphanFiles(context, sources) }
    }

    LaunchedEffect(ruleSources.size, defaultRulesBootstrapped) {
        if (defaultRulesBootstrapped) return@LaunchedEffect
        val bootstrapCandidates = ruleSources
            // Optional built-ins start off; they download when turned on.
            .filter { it.systemDefault && it.enabled }
            .filter { it.convertedRules == 0 && it.status != RuleSourceStatus.Ready }
        if (bootstrapCandidates.isNotEmpty()) {
            defaultRulesBootstrapped = true
            bootstrapCandidates.forEach { refreshRuleSource(it.id) }
        }
    }

    // Lets the MCP server and background sync change the state while the UI
    // is open: they start from this in-memory copy and push results back.
    DisposableEffect(Unit) {
        val host = object : AppController.UiHost {
            override fun showMessage(message: String) {
                runtimeMessage = message
            }

            override fun connect(server: ServerNode) {
                selectedServerId = server.id
                connectOrSwitchServer(server)
            }

            override fun disconnect() {
                // Drop reconnects queued by earlier setting changes, or the
                // UI would start the VPN again right after it stops.
                pendingReconnectServerId = null
                pendingReconnectMessage = null
                blockingReconnectInFlight = false
                blockingReconnectMessage = null
                manualDisconnectRequested = true
                lastRequestedConnectionServerId = null
                lastFallbackTargetServerId = null
                appSettings = appSettings.copy(resumeConnectionOnLaunch = false)
                if (VpnServiceController.isRunning() || connectionStatus != ConnectionStatus.Disconnected) {
                    disconnectInFlight = true
                    connectionStatus = ConnectionStatus.Disconnecting
                    runtimeMessage = "正在断开 VPN…"
                    VpnServiceController.disconnect(context)
                }
            }

            override fun reconnect(server: ServerNode, message: String) {
                reconnectVpnForSettingsChange(server, message)
            }

            override fun restartTailscale(settings: com.mallocgfw.app.model.AppSettings, message: String) {
                if (settings.tailscaleEnabled) {
                    restartTailscaleForSettings(settings, message)
                } else {
                    appSettings = settings
                    scope.launch {
                        TailscaleRuntimeManager.stopNow(context)
                        runtimeMessage = message
                        reconnectForTailscaleChange(message)
                    }
                }
            }
        }
        AppController.uiHost = host
        onDispose {
            if (AppController.uiHost === host) AppController.uiHost = null
        }
    }

    // Keep the preloaded node list current when nodes change elsewhere (MCP, sync).
    LaunchedEffect(servers, serverGroups) {
        warmServerSections()
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { GeoDataManager.load(context) }
        geoDataSnapshot = loaded
    }

    LaunchedEffect(appSettings.dailyAutoUpdate) {
        DailySyncScheduler.schedule(context, appSettings.dailyAutoUpdate)
    }

    // Checked on every launch; a newer release shows red dots (no dialog)
    // until the user opens the update page for it.
    LaunchedEffect(Unit) {
        runCatching {
            AppUpdateManager.checkLatest()
        }.onSuccess { info ->
            if (updateState.status != AppUpdateStatus.Checking && updateState.status != AppUpdateStatus.Downloading) {
                applyUpdateCheckResult(info, promptIfAvailable = false)
            }
        }.onFailure { error ->
            AppLogManager.append(context, "UPDATE", "自动检查更新失败", error)
        }
    }

    LaunchedEffect(screen, updateBadge) {
        val tag = updateState.info?.tagName
        if (screen == AppScreen.Update && updateBadge && tag != null) {
            AppUpdateManager.markUpdateSeen(context, tag)
            seenUpdateTag = tag
        }
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
        runtimeMessage = tr("正在恢复上次线路 ${server.name}…", "Restoring the last node ${server.name}…", "Восстановление последнего узла ${server.name}…")
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
        runtimeMessage = tr("连接中断，正在尝试第 $currentAttempt 次自动重连…", "Connection lost. Reconnecting automatically (attempt $currentAttempt)…", "Соединение потеряно. Автоматическое переподключение (попытка $currentAttempt)…")
        delay((1_500L * currentAttempt).coerceAtMost(5_000L))
        if (connectionStatus == ConnectionStatus.Disconnected && !VpnServiceController.isRunning()) {
            requestVpnConnection(server)
        }
    }

    BackHandler(enabled = screen !in setOf(AppScreen.Home, AppScreen.Servers, AppScreen.Rules, AppScreen.Import, AppScreen.Me)) {
        onBack()
    }
    // Import is opened from the "+" on Nodes, so Back returns there instead of leaving the app
    // (onBack handles it, shared with the on-screen back button).
    BackHandler(enabled = screen == AppScreen.Import) {
        onBack()
    }

    // App-level dialogs: give them the app language, not the system one.
    CompositionLocalProvider(LocalAppLanguage provides appSettings.language.resolveAppLanguage()) {
        if (startupHiddenUnsupportedNodeCount > 0 && screen != AppScreen.Launch) {
            AlertDialog(
                onDismissRequest = { startupHiddenUnsupportedNodeCount = 0 },
                title = { Text(uiText("已隐藏不支持节点")) },
                text = {
                    Text(
                        uiText(
                            "启动时已自动隐藏 $startupHiddenUnsupportedNodeCount 个 REALITY + gRPC 节点，这类线路当前版本暂不支持。",
                            "$startupHiddenUnsupportedNodeCount unsupported REALITY + gRPC nodes were hidden at launch.", "При запуске скрыто неподдерживаемых узлов REALITY + gRPC: $startupHiddenUnsupportedNodeCount.",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { startupHiddenUnsupportedNodeCount = 0 }) {
                        Text(uiText("知道了"))
                    }
                },
            )
        }

        if (showUpdatePrompt && updateState.info != null && screen != AppScreen.Launch) {
            val info = updateState.info
            AlertDialog(
                onDismissRequest = { showUpdatePrompt = false },
                title = { Text(uiText("发现新版本")) },
                text = {
                    Text(
                        uiText(
                            "PurpleBear ${info?.versionName.orEmpty()} 已发布，是否查看更新说明？",
                            "PurpleBear ${info?.versionName.orEmpty()} is available. View release notes?", "Вышла версия PurpleBear ${info?.versionName.orEmpty()}. Посмотреть список изменений?",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUpdatePrompt = false
                            navigateSecondary(AppScreen.Update)
                        },
                    ) {
                        Text(uiText("查看"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdatePrompt = false }) {
                        Text(uiText("稍后"))
                    }
                },
            )
        }

        if (showLatestVersionDialog && screen != AppScreen.Launch) {
            AlertDialog(
                onDismissRequest = { showLatestVersionDialog = false },
                title = { Text(uiText("已是最新版")) },
                text = {
                    Text(
                        uiText(
                            "当前安装的 PurpleBear ${AppUpdateManager.currentVersionName(context)} 已是最新版。",
                            "PurpleBear ${AppUpdateManager.currentVersionName(context)} is already up to date.", "Установленная версия PurpleBear ${AppUpdateManager.currentVersionName(context)} последняя.",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLatestVersionDialog = false }) {
                        Text(uiText("知道了"))
                    }
                },
            )
        }
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
    LaunchedEffect(screen, scrollRulesToCustomSources, ruleSources.size) {
        if (screen != AppScreen.Rules || !scrollRulesToCustomSources) return@LaunchedEffect
        val customHeaderIndex = 4 + ruleSources.count { it.systemDefault }
        rulesListState.animateScrollToItem(customHeaderIndex.coerceAtLeast(0))
        scrollRulesToCustomSources = false
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

    val resolvedLanguage = appSettings.language.resolveAppLanguage()
    // Messages built outside Compose (tr) follow the same language.
    AppTextLanguage.apply(appSettings.language)

    CompositionLocalProvider(LocalAppLanguage provides resolvedLanguage) {
    MallocGfwTheme(lightTheme = appSettings.lightThemeEnabled) {
        // Changes an AI client asked for over MCP, waiting for the phone owner's
        // answer. Inside the language provider so the dialog follows the app language.
        McpApprovalDialogHost()
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
                    onOpenScan = ::scanSubscriptionQr,
                    onBack = ::onBack,
                    updateBadge = updateBadge,
                ) { padding ->
                    when (screen) {
                    AppScreen.Permission -> PermissionScreen(
                        padding = padding,
                        granted = vpnPermissionGranted,
                        onGrant = ::requestVpnPermission,
                        onClose = ::onBack,
                    )

                    AppScreen.Home -> HomeScreen(
                        padding = padding,
                        listState = homeListState,
                        connectionStatus = connectionStatus,
                        vpnSnapshot = vpnSnapshot,
                        // The Rules page's global switch overrides routing (see XrayVpnService).
                        proxyMode = if (appSettings.globalProxyEnabled) ProxyMode.Global else proxyMode,
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
                            AppController.edit { StateOps.toggleFavorite(it, id) }
                            warmServerSections()
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
                        geoRoutingRegion = appSettings.geoRoutingRegion,
                        globalProxyEnabled = appSettings.globalProxyEnabled,
                        ruleMessage = ruleMessage,
                        updatingIds = ruleUpdateInFlight.keys,
                        onGlobalProxyChange = ::updateGlobalProxyEnabled,
                        onGeoRoutingRegionChange = ::updateGeoRoutingRegion,
                        onRefreshGeoData = ::refreshGeoData,
                        onSelectSource = { sourceId ->
                            selectedRuleSourceId = sourceId
                            navigateSecondary(AppScreen.RuleSourceDetail)
                        },
                        onAddSource = {
                            ruleEditorSourceId = null
                            ruleEditorKind = RuleSourceKind.RemoteUrl
                            navigateSecondary(AppScreen.AddRuleSource)
                        },
                        onAddTextSource = {
                            ruleEditorSourceId = null
                            ruleEditorKind = RuleSourceKind.LocalText
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
                        // A node run by the compatibility core can be a hop but can't use one.
                        preProxyUnsupported = selectedServer?.let { node ->
                            ManualNodeFactory.isRunnable(node) && !ManualNodeFactory.supportsPreProxy(node)
                        } == true,
                        canSelectFallback = selectedServer?.let(ManualNodeFactory::isRunnable) == true,
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
                        onCreateManual = ::openLocalNodeBuilder,
                    )

                    AppScreen.ConfirmImport -> ConfirmImportScreen(
                        padding = padding,
                        preview = pendingImport,
                        onConfirm = {
                            pendingImport?.let {
                                val applyResult = applyImport(it)
                                applyResult.message?.let { message -> subscriptionMessage = message }
                                // Single nodes land in Local: show them in Nodes, not the
                                // (possibly empty) subscription list.
                                if (it.group.type == ServerGroupType.Local) {
                                    goToMain(MainTab.Servers)
                                } else {
                                    navigateSecondary(AppScreen.Subscriptions)
                                }
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
                                    tr("${subscription.name} 来自文件导入，没有可刷新的订阅 URL，请重新导入文件。", "${subscription.name} was imported from a file and has no URL to refresh. Import the file again.", "${subscription.name} импортирована из файла, URL для обновления нет. Импортируйте файл снова.")
                                } else {
                                    tr("${subscription.name} 使用的是带时效签名的订阅链接，已按静态快照导入，不参与刷新。", "${subscription.name} uses a time-limited signed link and was imported as a static snapshot, so it isn't refreshed.", "${subscription.name} использует ссылку с ограниченной по времени подписью и импортирована как статический снимок, поэтому не обновляется.")
                                }
                                return@SubscriptionsScreen
                            }
                            subscriptionRefreshInFlight[subscriptionId] = true
                            AppController.edit { StateOps.setSubscriptionStatus(it, subscriptionId, "刷新中") }
                            scope.launch {
                                runCatching {
                                    ImportParser.buildPreview(subscription.sourceUrl)
                                }.onSuccess { preview ->
                                    val applyResult = applyImport(preview)
                                    AppController.edit { StateOps.setSubscriptionStatus(it, subscriptionId, "已同步", System.currentTimeMillis()) }
                                    subscriptionMessage = applyResult.message
                                        ?: tr("${preview.group.name} 刷新完成，共同步 ${preview.nodes.count { !it.hiddenUnsupported }} 个节点。", "${preview.group.name} refreshed: ${preview.nodes.count { !it.hiddenUnsupported }} nodes synced.", "${preview.group.name}: обновлено, синхронизировано узлов: ${preview.nodes.count { !it.hiddenUnsupported }}.")
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
                                    AppController.edit { StateOps.setSubscriptionStatus(it, subscriptionId, "刷新失败") }
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
                        onSetApps = ::setProtectedAppsEnabled,
                        onApplyChanges = ::applyProtectedAppsSelection,
                        serverNames = servers.associate { it.id to it.name },
                        onOpenApp = { appId ->
                            selectedPerAppId = appId
                            navigateSecondary(AppScreen.PerAppNodePicker)
                        },
                    )

                    AppScreen.PerAppNodePicker -> PerAppNodePickerScreen(
                        padding = padding,
                        app = protectedApps.firstOrNull { it.id == selectedPerAppId },
                        currentServer = mediaRoutingDefaultServer,
                        groups = serverGroups.filter { it.type == ServerGroupType.Local || it.type == ServerGroupType.Subscription },
                        servers = servers.toList(),
                        onBack = ::onBack,
                        onSelect = { enabled, serverId ->
                            updateProtectedAppRoute(selectedPerAppId, enabled, serverId)
                            onBack()
                        },
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
                        onLanguageChange = ::updateLanguage,
                        onAutoConnectChange = { enabled ->
                            appSettings = appSettings.copy(autoConnectOnLaunch = enabled)
                            settingsMessage = if (enabled) {
                                appText("已开启启动后自动连接。", "Auto connect enabled.", resolvedLanguage)
                            } else {
                                appText("已关闭启动后自动连接。", "Auto connect disabled.", resolvedLanguage)
                            }
                        },
                        onAutoReconnectChange = { enabled ->
                            appSettings = appSettings.copy(autoReconnect = enabled)
                            settingsMessage = if (enabled) {
                                appText("已开启断线自动重连。", "Auto reconnect enabled.", resolvedLanguage)
                            } else {
                                appText("已关闭断线自动重连。", "Auto reconnect disabled.", resolvedLanguage)
                            }
                        },
                        onHeartbeatIntervalChange = { minutes ->
                            AppController.edit { StateOps.setHeartbeat(it, minutes) }
                            settingsMessage = appText("心跳检测间隔已设置为 $minutes 分钟。", "Fallback check interval set to $minutes minutes.", "Интервал проверки резервного узла: $minutes мин.", resolvedLanguage)
                        },
                        onVpnMtuChange = ::updateVpnMtu,
                        onProxyQuicChange = ::updateProxyQuic,
                        onDailyAutoUpdateChange = { enabled ->
                            appSettings = appSettings.copy(dailyAutoUpdate = enabled)
                            settingsMessage = if (enabled) {
                                appText("已开启每日自动更新。仅在非计费网络下执行。", "Daily updates enabled on unmetered networks.", resolvedLanguage)
                            } else {
                                appText("已关闭每日自动更新。", "Daily updates disabled.", resolvedLanguage)
                            }
                        },
                        onUpdateNotificationsChange = ::setUpdateNotificationsEnabled,
                        onOpenUpdates = ::openUpdateScreen,
                        updateBadge = updateBadge,
                        onSyncNow = ::syncAllResources,
                        onDnsChange = ::updateDnsSettings,
                        onLogLevelChange = { level ->
                            appSettings = appSettings.copy(logLevel = level)
                            settingsMessage = appText("日志级别已切换为 ${level.displayName}。", "Log level changed to ${level.displayName}.", "Уровень журналов изменён на ${level.displayName}.", resolvedLanguage)
                        },
                        onAddQuickSettingsTile = ::requestQuickSettingsTile,
                        onViewLogs = ::openLogViewer,
                        onOpenLicenses = { navigateSecondary(AppScreen.OpenSourceLicenses) },
                        onOpenMcp = { navigateSecondary(AppScreen.Mcp) },
                    )

                    AppScreen.Tailscale -> TailscaleScreen(
                        padding = padding,
                        settings = appSettings,
                        snapshot = tailscaleSnapshot,
                        onBack = ::onBack,
                        onEnabledChange = { enabled ->
                            val next = StateOps.setTailscaleEnabled(currentPersistedState(), enabled).settings
                            if (!enabled) {
                                appSettings = next
                                scope.launch {
                                    TailscaleRuntimeManager.stopNow(context)
                                    runtimeMessage = "Tailscale 已关闭。"
                                    reconnectForTailscaleChange("Tailscale 已关闭，正在重新加载分流…")
                                }
                            } else {
                                appSettings = next
                                connectTailscale(next.tailscaleAuthKey)
                            }
                        },
                        onAuthKeyChange = { authKey ->
                            appSettings = appSettings.copy(tailscaleAuthKey = authKey)
                        },
                        onControlUrlChange = { url ->
                            appSettings = appSettings.copy(tailscaleControlUrl = url)
                        },
                        onAlwaysDerpChange = { enabled ->
                            restartTailscaleForSettings(
                                appSettings.copy(tailscaleAlwaysUseDerp = enabled),
                                if (enabled) "已启用始终使用 DERP。" else "已允许 Tailscale 尝试直连 UDP。",
                            )
                        },
                        onConnect = ::connectTailscale,
                        onRefresh = {
                            scope.launch {
                                TailscaleRuntimeManager.refresh(context)
                                    .onFailure { error -> runtimeMessage = error.message ?: "无法刷新 Tailscale 状态。" }
                            }
                        },
                        onSubnetRoutesChange = { routes ->
                            val normalizedRoutes = routes
                                .mapNotNull(::normalizePrivateTailscaleSubnet)
                                .distinct()
                            val advertisedRoutes = tailscaleSnapshot.peers
                                .flatMap { it.routes }
                                .mapNotNull(::normalizePrivateTailscaleSubnet)
                                .toSet()
                            val previousEffectiveRoutes = appSettings.tailscaleSubnetRoutes
                                .mapNotNull(::normalizePrivateTailscaleSubnet)
                                .filter { it in advertisedRoutes }
                                .toSet()
                            val nextEffectiveRoutes = normalizedRoutes
                                .filter { it in advertisedRoutes }
                                .toSet()
                            AppController.edit { StateOps.setTailscaleSubnets(it, normalizedRoutes) }
                            val message = when {
                                normalizedRoutes.isEmpty() -> "已关闭 Tailscale 子网访问。"
                                nextEffectiveRoutes.isEmpty() -> "子网设置已保存，当前 Tailnet 尚未公布对应路由。"
                                else -> tr("已启用子网：${nextEffectiveRoutes.joinToString()}", "Subnets enabled: ${nextEffectiveRoutes.joinToString()}", "Включены подсети: ${nextEffectiveRoutes.joinToString()}")
                            }
                            runtimeMessage = message
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            if (previousEffectiveRoutes != nextEffectiveRoutes) {
                                reconnectForTailscaleChange("Tailscale 子网设置已变更，正在重新加载分流…")
                            }
                        },
                        onExitNodeChange = { nodeId ->
                            val previousNodeId = appSettings.tailscaleExitNodeId
                            val targetNodeName = tailscaleSnapshot.peers
                                .firstOrNull { it.id == nodeId }
                                ?.let { peer -> peer.name.ifBlank { peer.dnsName } }
                            AppController.edit { StateOps.setTailscaleExitNode(it, nodeId) }
                            scope.launch {
                                TailscaleRuntimeManager.setExitNode(context, nodeId)
                                    .onSuccess {
                                        val successMessage = if (nodeId.isBlank()) {
                                            "已取消 Tailscale 出口节点。"
                                        } else {
                                            tr("已切换出口节点：${targetNodeName ?: "所选设备"}", "Exit node: ${targetNodeName ?: "the selected device"}", "Выходной узел: ${targetNodeName ?: "выбранное устройство"}")
                                        }
                                        runtimeMessage = successMessage
                                        Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                                        // Xray only needs a config reload when
                                        // the fallback changes between normal
                                        // proxy and Tailscale. Switching from
                                        // one exit node to another is handled
                                        // entirely inside the live bridge.
                                        if (previousNodeId.isBlank() != nodeId.isBlank()) {
                                            reconnectForTailscaleChange("出口路由模式已变更，正在重新加载分流…")
                                        }
                                    }
                                    .onFailure { error ->
                                        AppController.edit { StateOps.setTailscaleExitNode(it, previousNodeId) }
                                        val failureMessage = error.message ?: "无法切换 Tailscale 出口节点。"
                                        runtimeMessage = failureMessage
                                        Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
                                    }
                            }
                        },
                        onLogout = {
                            scope.launch {
                                TailscaleRuntimeManager.logout(context)
                                    .onSuccess {
                                        appSettings = appSettings.copy(
                                            tailscaleEnabled = false,
                                            tailscaleExitNodeId = "",
                                        )
                                        runtimeMessage = "已从 Tailscale 退出并移除此设备。"
                                        reconnectForTailscaleChange("Tailscale 已退出，正在重新加载分流…")
                                    }
                                    .onFailure { error -> runtimeMessage = error.message ?: "退出 Tailscale 失败。" }
                            }
                        },
                    )

                    AppScreen.OpenSourceLicenses -> OpenSourceLicensesScreen(
                        padding = padding,
                        onBack = ::onBack,
                    )

                    AppScreen.Mcp -> McpScreen(
                        padding = padding,
                        onBack = ::onBack,
                    )

                    AppScreen.Update -> UpdateScreen(
                        padding = padding,
                        state = updateState,
                        currentVersionName = AppUpdateManager.currentVersionName(context),
                        currentVersionCode = AppUpdateManager.currentVersionCode(context),
                        onBack = ::onBack,
                        onCheck = { checkForUpdates(promptIfLatest = true) },
                        onDownload = ::downloadUpdateApk,
                        onInstall = ::installDownloadedUpdate,
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
                        onScanError = { message ->
                            runtimeMessage = message
                        },
                    )

                    AppScreen.Me -> MeScreen(
                        padding = padding,
                        connectionStatus = connectionStatus,
                        proxyMode = proxyMode,
                        server = selectedServer,
                        streamingRoutingEnabled = appSettings.streamingRoutingEnabled,
                        lightThemeEnabled = appSettings.lightThemeEnabled,
                        onToggleTheme = ::toggleThemeMode,
                        onOpenSubscriptions = { navigateSecondary(AppScreen.Subscriptions) },
                        onOpenPerApp = { navigateSecondary(AppScreen.PerApp) },
                        onOpenDiagnostics = { navigateSecondary(AppScreen.Diagnostics) },
                        onOpenSettings = { navigateSecondary(AppScreen.Settings) },
                        onOpenTailscale = { navigateSecondary(AppScreen.Tailscale) },
                        onOpenMediaRouting = { navigateSecondary(AppScreen.MediaRouting) },
                        onOpenPermission = { navigateSecondary(AppScreen.Permission) },
                        vpnPermissionGranted = vpnPermissionGranted,
                        updateBadge = updateBadge,
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
                            ?.takeIf { serverId -> serverId == StreamingMediaManager.DIRECT || servers.any { it.id == serverId } }
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
                            val ownerSupportsCurrentMode = when (nodeLinkPickerMode) {
                                NodeLinkPickerMode.PreProxy -> ownerNode?.let(ManualNodeFactory::supportsPreProxy) == true
                                NodeLinkPickerMode.Fallback -> ownerNode?.let { owner ->
                                    ManualNodeFactory.isRunnable(owner) &&
                                        NodeLatencyTester.supportsHeartbeatFallback(owner)
                                } == true
                            }
                            // Any runnable node can be a hop or a fallback, compatibility-core ones included.
                            candidate.id != ownerNodeId &&
                                ownerSupportsCurrentMode &&
                                ManualNodeFactory.isRunnable(candidate)
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
                            ruleEditorKind = selectedRuleSource?.sourceKind ?: RuleSourceKind.RemoteUrl
                            navigateSecondary(AppScreen.AddRuleSource)
                        },
                        onDelete = { selectedRuleSource?.id?.let(::deleteRuleSource) },
                    )

                    AppScreen.AddRuleSource -> AddRuleSourceScreen(
                        padding = padding,
                        editingSource = ruleSources.firstOrNull { it.id == ruleEditorSourceId },
                        sourceKind = ruleEditorKind,
                        servers = servers,
                        onBack = ::onBack,
                        onSubmit = { name, content, type, sourceKind, policy ->
                            saveRuleSourceEdits(ruleEditorSourceId, name, content, type, sourceKind, policy)
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
                                text = uiText("正在重新连接"),
                                color = TextPrimary,
                                fontSize = TypeScale.SectionTitle,
                                lineHeight = TypeScale.SectionTitleLine,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = blockingReconnectMessage?.let { uiText(it) }
                                    ?: uiText(
                                        "正在重新连接 VPN 并应用新的流媒体分流设置，请稍候…",
                                        "Reconnecting VPN to apply changes. Please wait...",
                                    ),
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
                        // The banner says "Switch": switch right away instead of
                        // sending the user to Home to tap a second Switch.
                        pendingHomeSwitchPromptServerId = null
                        connectOrSwitchServer(promptServer, navigateHomeAfterStart = true)
                    },
                )
                }
            }
        }
    }
    }
}

/** How long a confirmation notice stays at the top of Settings / Rules. */
private const val NOTICE_VISIBLE_MS = 4_000L
