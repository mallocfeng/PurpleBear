package com.mallocgfw.app.core

import android.content.Context
import android.net.VpnService
import com.mallocgfw.app.model.AppSettings
import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.LoadedAppState
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.xray.AppLogManager
import com.mallocgfw.app.xray.TailscaleRuntimeManager
import com.mallocgfw.app.xray.VpnRuntimeStore
import com.mallocgfw.app.xray.VpnServiceController
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The single owner of the app state, shared by the UI, the MCP server, the
 * VPN service and background sync. Everything in this process reads it from
 * here; it is saved to disk shortly after each change.
 *
 * The state is also held in Compose snapshot state, so the UI reads it
 * directly and recomposes on any change, wherever it came from. Changes are
 * pure transforms applied under one lock, then normalized (dangling node
 * links and media routes dropped).
 *
 * While the UI is open it registers a [UiHost] so VPN actions go through the
 * UI's own connect / reconnect flow and its messages show there.
 */
object AppController {

    /** What the open UI offers the controller. All calls run on the main thread. */
    interface UiHost {
        fun showMessage(message: String)
        fun connect(server: ServerNode)
        fun disconnect()
        fun reconnect(server: ServerNode, message: String)
        fun restartTailscale(settings: AppSettings, message: String)
    }

    private lateinit var appContext: Context
    private val lock = Any()
    private val _state = MutableStateFlow<PersistedAppState?>(null)
    private val snapshotState = mutableStateOf<PersistedAppState?>(null)
    private var startupHiddenCount = 0
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var uiHost: UiHost? = null

    /** The state as a flow; null until [init]. */
    val state: StateFlow<PersistedAppState?> = _state.asStateFlow()

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        synchronized(lock) {
            if (_state.value != null) return
            appContext = context.applicationContext
            val loaded = AppStateStore.loadWithStartupCleanup(appContext)
            startupHiddenCount = loaded.newlyHiddenUnsupportedNodeCount
            publish(normalize(loaded.state))
        }
        saveScope.launch {
            _state.filterNotNull().drop(1).debounce(SAVE_DELAY_MS).collect { AppStateStore.save(appContext, it) }
        }
    }

    /** The current state, loading it first if needed. Replaces AppStateStore.load in this process. */
    fun load(context: Context): PersistedAppState {
        init(context)
        return current()
    }

    /** For the UI's first frame: the state plus the "newly hidden" count, reported once. */
    fun loadForUi(context: Context): LoadedAppState {
        init(context)
        synchronized(lock) {
            val hidden = startupHiddenCount
            startupHiddenCount = 0
            return LoadedAppState(current(), hidden)
        }
    }

    fun current(): PersistedAppState = checkNotNull(_state.value) { "AppController.init was not called" }

    /** Compose-observable read of the state; reading it in a composable subscribes to changes. */
    fun observed(): PersistedAppState = checkNotNull(snapshotState.value) { "AppController.init was not called" }

    /**
     * Applies [transform] to the current state at once (any thread). Returns the
     * new state, or null when [transform] returned null (nothing to change).
     */
    fun edit(transform: (PersistedAppState) -> PersistedAppState?): PersistedAppState? = synchronized(lock) {
        val base = current()
        val changed = transform(base) ?: return null
        val next = normalize(changed)
        if (next != base) publish(next)
        next
    }

    private val _removalNotice = MutableStateFlow<String?>(null)

    /**
     * Settings cleared because nodes went away (StateOps.removalNotice), until
     * the UI has shown it. Set by deletes, imports, refreshes and background
     * sync alike, so the user hears about it however the node disappeared.
     */
    val removalNotice: StateFlow<String?> = _removalNotice.asStateFlow()

    fun consumeRemovalNotice() {
        _removalNotice.value = null
    }

    /**
     * Removes nodes ([transform] is StateOps.removeNode / removeSubscriptionGroup).
     * See [editNodes].
     */
    fun removeNodes(transform: (PersistedAppState) -> PersistedAppState?): NodeRemoval? =
        editNodes(reconnect = true, transform = transform)

    /**
     * [edit] for a change that can make nodes disappear: a delete, an import or
     * a subscription refresh. When nodes went away:
     * - a selected (not yet connected) node that went away is replaced by the
     *   node the VPN runs on, not an arbitrary one shown as a pending switch;
     * - what it cleared is logged and posted to [removalNotice];
     * - with [reconnect], the running VPN reconnects or disconnects when it used
     *   a removed node (StateOps.removalEffect). Imports pass false: their
     *   ImportOutcome.reconnectServer already covers it, and two reconnects in
     *   a row would race.
     * Returns the new state and the notice, or null when [transform] returned null.
     */
    fun editNodes(reconnect: Boolean, transform: (PersistedAppState) -> PersistedAppState?): NodeRemoval? {
        val connected = connectedServerId()
        var before: PersistedAppState? = null
        val next = edit { state ->
            before = state
            transform(state)?.let { keepConnectedSelected(state, it, connected) }
        } ?: return null
        val base = before ?: return NodeRemoval(next, null)
        return NodeRemoval(next, afterNodeChanges(base, next, connected, reconnect))
    }

    data class NodeRemoval(val state: PersistedAppState, val notice: String?)

    /** What the running tunnel was built with (null: not recorded; see TunnelNodes). */
    fun runningNodeIds(): Set<String>? = connectedServerId()?.let { com.mallocgfw.app.xray.TunnelNodes.current() }

    private fun keepConnectedSelected(before: PersistedAppState, after: PersistedAppState, connected: String?): PersistedAppState {
        val selectedGone = before.selectedServerId.isNotBlank() && after.servers.none { it.id == before.selectedServerId }
        return if (selectedGone && connected != null && after.servers.any { it.id == connected }) {
            after.copy(selectedServerId = connected)
        } else {
            after
        }
    }

    /** Posts the notice and, with [reconnect], fixes the running VPN. Returns the notice. */
    private fun afterNodeChanges(
        base: PersistedAppState,
        next: PersistedAppState,
        connected: String?,
        reconnect: Boolean,
    ): String? {
        if (base.servers.map { it.id }.toSet() == next.servers.map { it.id }.toSet()) return null
        val notice = StateOps.removalNotice(base, next)
        notice?.let {
            AppLogManager.append(appContext, "NODES", it.replace("\n\n", " "))
            _removalNotice.value = it
        }
        if (!reconnect) return notice
        val effect = StateOps.removalEffect(base, next, connected, runningNodeIds())
        if (effect == StateOps.RemovalEffect.None) return notice
        saveScope.launch {
            val host = uiHost
            when (effect) {
                is StateOps.RemovalEffect.Reconnect -> {
                    AppLogManager.append(appContext, "NODES", effect.message)
                    if (host != null) {
                        withContext(Dispatchers.Main) { (uiHost ?: host).reconnect(effect.server, effect.message) }
                    } else {
                        VpnServiceController.start(appContext, effect.server)
                    }
                }
                is StateOps.RemovalEffect.Disconnect -> {
                    AppLogManager.append(appContext, "NODES", effect.message)
                    disconnect()
                    showMessage(effect.message)
                }
                StateOps.RemovalEffect.None -> Unit
            }
        }
        return notice
    }

    /** [edit] that also shows [message] in the open UI. */
    suspend fun update(
        message: String? = null,
        transform: (PersistedAppState) -> PersistedAppState?,
    ): PersistedAppState? {
        val next = edit(transform) ?: return null
        message?.let { showMessage(it) }
        return next
    }

    /** Writes the state to disk now (the debounced save may not have run yet). */
    fun flush() {
        val state = _state.value ?: return
        AppStateStore.save(appContext, state)
    }

    private fun publish(next: PersistedAppState) {
        _state.value = next
        snapshotState.value = next
    }

    private fun normalize(state: PersistedAppState): PersistedAppState {
        val servers = StateOps.normalizeNodeLinks(state.servers)
        val withServers = if (servers == state.servers) state else state.copy(servers = servers)
        return StateOps.normalizeNodeReferences(withServers)
    }

    private suspend fun showMessage(message: String) {
        val host = uiHost ?: return
        withContext(Dispatchers.Main) { (uiHost ?: host).showMessage(message) }
    }

    // ---- VPN ---------------------------------------------------------------

    /** The node the VPN runs on, or null when it isn't connected. */
    fun connectedServerId(): String? {
        val snapshot = VpnRuntimeStore.snapshot.value
        return snapshot.activeServerId?.takeIf { snapshot.status == ConnectionStatus.Connected && it.isNotBlank() }
    }

    fun vpnBusy(): Boolean = VpnServiceController.isRunning() || VpnRuntimeStore.snapshot.value.status != ConnectionStatus.Disconnected

    fun vpnPermissionGranted(): Boolean = VpnService.prepare(appContext) == null

    /** Selects [serverId] and connects to it (or reconnects when already running). */
    suspend fun connect(serverId: String): Result<ServerNode> {
        val server = current().servers.firstOrNull { it.id == serverId }
            ?: return Result.failure(IllegalArgumentException(nodeNotFound(serverId)))
        if (!vpnPermissionGranted()) {
            return Result.failure(
                IllegalStateException(
                    tr(
                        "尚未授予 VPN 权限，请先在手机上打开 PurpleBear 连接一次。",
                        "VPN permission hasn't been granted. Open PurpleBear on the phone and connect once first.",
                        "Разрешение VPN не выдано. Откройте PurpleBear на телефоне и подключитесь один раз.",
                    ),
                ),
            )
        }
        update { it.copy(selectedServerId = server.id) }
        val host = uiHost
        if (host != null) {
            withContext(Dispatchers.Main) { (uiHost ?: host).connect(server) }
        } else {
            AppLogManager.append(appContext, "MCP", "连接节点：${server.name}")
            VpnServiceController.start(appContext, server)
        }
        return Result.success(server)
    }

    suspend fun disconnect() {
        val host = uiHost
        if (host != null) {
            withContext(Dispatchers.Main) { (uiHost ?: host).disconnect() }
        } else {
            update { it.copy(settings = it.settings.copy(resumeConnectionOnLaunch = false)) }
            VpnServiceController.disconnect(appContext)
        }
    }

    /** Reconnects so changed settings take effect; does nothing when the VPN is off. */
    suspend fun reconnectIfRunning(message: String): Boolean {
        if (!vpnBusy()) return false
        val state = current()
        val server = listOf(VpnRuntimeStore.snapshot.value.activeServerId, state.selectedServerId, state.settings.lastConnectedServerId)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .firstNotNullOfOrNull { id -> state.servers.firstOrNull { it.id == id } }
            ?: return false
        val host = uiHost
        if (host != null) {
            withContext(Dispatchers.Main) { (uiHost ?: host).reconnect(server, message) }
        } else {
            AppLogManager.append(appContext, "MCP", "设置已变更，重新连接：${server.name}")
            // ACTION_CONNECT on a running service stops the old core first.
            VpnServiceController.start(appContext, server)
        }
        return true
    }

    /** Applies Tailscale settings already saved in the state. */
    suspend fun restartTailscale(message: String) {
        val settings = current().settings
        val host = uiHost
        if (host != null) {
            withContext(Dispatchers.Main) { (uiHost ?: host).restartTailscale(settings, message) }
            return
        }
        if (settings.tailscaleEnabled) {
            TailscaleRuntimeManager.restart(appContext, settings)
        } else {
            TailscaleRuntimeManager.stopNow(appContext)
        }
        reconnectIfRunning(message)
    }

    private const val SAVE_DELAY_MS = 500L

    private fun nodeNotFound(nodeId: String) =
        tr("找不到节点 $nodeId。", "Node $nodeId not found.", "Узел $nodeId не найден.")
}
