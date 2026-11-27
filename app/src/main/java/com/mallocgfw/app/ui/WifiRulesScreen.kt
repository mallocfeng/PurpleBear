package com.mallocgfw.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import com.mallocgfw.app.wifi.WifiAccess
import com.mallocgfw.app.wifi.WifiRuleRuntime
import com.mallocgfw.app.wifi.WifiRules
import com.mallocgfw.app.wifi.WifiRulesConfig
import com.mallocgfw.app.wifi.WifiZone
import kotlinx.coroutines.delay

/** The "Auto by Wi-Fi" row in Settings. */
@Composable
internal fun wifiRulesSummary(): String {
    val context = LocalContext.current
    val status by WifiRuleRuntime.status.collectAsState()
    val config = remember(status) { WifiRules.load(context) }
    return when {
        !config.enabled -> uiText(
            "连上家里的 Wi-Fi 自动断开代理，离开后自动连上。未开启。",
            "Turns the proxy off on your home Wi-Fi and back on when you leave. Off.",
            "Выключает прокси в домашней сети Wi-Fi и включает вне её. Выключено.",
        )
        config.homeNetworks.isEmpty() -> uiText("已开启，但还没有添加家里的 Wi-Fi。", "On, but no home Wi-Fi added yet.", "Включено, но домашняя сеть Wi-Fi не добавлена.")
        else -> uiText(
            "已开启 · 家里的 Wi-Fi：${config.homeNetworks.joinToString("、")}",
            "On · Home Wi-Fi: ${config.homeNetworks.joinToString(", ")}",
            "Включено · Домашние сети: ${config.homeNetworks.joinToString(", ")}",
        )
    }
}

@Composable
internal fun WifiRulesScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val status by WifiRuleRuntime.status.collectAsState()
    var config by remember { mutableStateOf(WifiRules.load(context)) }
    // Bumped on resume (back from system settings) and every few seconds: permissions and the Wi-Fi change outside the app.
    var tick by remember { mutableIntStateOf(0) }
    var showManualDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            tick++
        }
    }

    val access = remember(tick, status) { AccessState.read(context) }
    val currentWifi = remember(tick, status) { status.wifiName ?: WifiAccess.currentWifiName(context) }

    fun change(update: (WifiRulesConfig) -> WifiRulesConfig) {
        val before = config
        config = WifiRules.update(context, update)
        // A new list may change where the phone counts as being: look again.
        if (before.homeNetworks != config.homeNetworks || before.enabled != config.enabled) WifiRules.setLastZone(context, null)
        WifiRuleRuntime.sync(context)
    }

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        change { it.copy(enabled = true) }
        if (!WifiAccess.locationGranted(context)) requestLocation(locationLauncher)
    }

    fun turnOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        change { it.copy(enabled = true) }
        if (!WifiAccess.locationGranted(context)) requestLocation(locationLauncher)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(title = uiText("按 Wi-Fi 自动切换", "Auto by Wi-Fi", "Авто по Wi-Fi"), onBack = onBack)
        }
        item {
            Text(
                uiText(
                    "家里有软路由、已经能翻墙时，手机不用再开代理。把家里的 Wi-Fi 加到下面：连上这些 Wi-Fi 时自动断开代理，离开后自动连上上次的节点。",
                    "If a router at home already handles the proxy, the phone doesn't need its own. Add your home Wi-Fi below: on it the proxy turns off by itself, and when you leave it connects to your last node again.",
                    "Если домашний роутер уже работает как прокси, телефону свой не нужен. Добавьте домашнюю сеть Wi-Fi: в ней прокси выключится сам, а вне её снова подключится последний узел.",
                ),
                color = TextSecondary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            SettingsGroup(title = uiText("开关", "Switch", "Переключатель")) {
                SettingToggleRow(
                    title = uiText("按 Wi-Fi 自动连接或断开", "Connect or disconnect by Wi-Fi", "Подключать и отключать по Wi-Fi"),
                    subtitle = statusText(config, status.zone, currentWifi, access),
                    checked = config.enabled,
                    onToggle = { if (config.enabled) change { it.copy(enabled = false) } else turnOn() },
                )
                status.lastEvent?.takeIf { config.enabled }?.let { event ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        uiText("最近：$event", "Last: $event", "Последнее: $event"),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                    )
                }
            }
        }
        item {
            SettingsGroup(title = uiText("家里的 Wi-Fi（连上时断开代理）", "Home Wi-Fi (proxy off here)", "Домашние сети (здесь без прокси)")) {
                if (config.homeNetworks.isEmpty()) {
                    SettingInfoRow(
                        title = uiText("还没有添加", "None yet", "Пока нет"),
                        subtitle = uiText(
                            "连上家里的 Wi-Fi 后点“添加当前 Wi-Fi”，或手动输入 Wi-Fi 名称。",
                            "Join your home Wi-Fi and tap “Add current Wi-Fi”, or type its name.",
                            "Подключитесь к домашней сети и нажмите «Добавить текущую сеть» или введите имя вручную.",
                        ),
                    )
                }
                config.homeNetworks.forEachIndexed { index, name ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    SettingActionRow(
                        title = name,
                        subtitle = if (name == currentWifi) {
                            uiText("当前连接的就是它", "You're on it now", "Вы сейчас в этой сети")
                        } else {
                            uiText("连上时断开代理", "Proxy off on this network", "В этой сети прокси выключен")
                        },
                        actionText = uiText("移除", "Remove", "Удалить"),
                        onAction = { change { config -> config.copy(homeNetworks = config.homeNetworks - name) } },
                    )
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canAddCurrent = currentWifi != null && currentWifi !in config.homeNetworks &&
                        config.homeNetworks.size < WifiRules.MAX_NETWORKS
                    if (canAddCurrent) {
                        ModeChip(
                            text = uiText("添加当前 Wi-Fi「$currentWifi」", "Add current Wi-Fi “$currentWifi”", "Добавить «$currentWifi»"),
                            selected = false,
                        ) {
                            val name = currentWifi ?: return@ModeChip
                            change { it.copy(homeNetworks = (it.homeNetworks + name).distinct()) }
                        }
                    }
                    ModeChip(text = uiText("手动输入", "Type a name", "Ввести имя"), selected = false) { showManualDialog = true }
                }
                if (currentWifi == null && !access.location) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        uiText(
                            "读不到当前 Wi-Fi 名称：Android 只把 Wi-Fi 名称告诉有位置权限的应用。",
                            "Can't read the current Wi-Fi name: Android shows Wi-Fi names only to apps with location access.",
                            "Не видно имя текущей сети: Android сообщает имена Wi-Fi только приложениям с доступом к геолокации.",
                        ),
                        color = TextSecondary,
                        fontSize = TypeScale.Meta,
                        lineHeight = TypeScale.MetaLine,
                    )
                }
            }
        }
        item {
            SettingsGroup(title = uiText("需要的权限", "What it needs", "Что нужно")) {
                SettingActionRow(
                    title = uiText("位置权限", "Location access", "Доступ к геолокации"),
                    subtitle = if (access.location) {
                        uiText("已允许。只用来读取 Wi-Fi 名称，不会记录位置。", "Allowed. Used only to read the Wi-Fi name; no location is recorded.", "Разрешено. Только чтобы видеть имя Wi-Fi; местоположение не записывается.")
                    } else {
                        uiText("未允许。Android 要求有位置权限才能读取 Wi-Fi 名称。", "Not allowed. Android needs it before it tells an app the Wi-Fi name.", "Не разрешено. Без него Android не сообщает имя Wi-Fi.")
                    },
                    actionText = if (access.location) uiText("已允许", "Allowed", "Есть") else uiText("允许", "Allow", "Разрешить"),
                    actionEnabled = !access.location,
                    onAction = { requestLocation(locationLauncher) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Spacer(Modifier.height(12.dp))
                    SettingActionRow(
                        title = uiText("后台读取（始终允许）", "In the background (Allow all the time)", "В фоне («Разрешить всегда»)"),
                        subtitle = if (access.background) {
                            uiText("已允许，不打开应用也能切换。", "Allowed: it switches with the app closed.", "Разрешено: переключение работает и при закрытом приложении.")
                        } else {
                            uiText("在系统页面里选“始终允许”，否则只有打开应用时才会切换。", "Pick “Allow all the time” on the system page, or it only switches while the app is open.", "Выберите «Разрешить всегда», иначе переключение работает только при открытом приложении.")
                        },
                        actionText = if (access.background) uiText("已允许", "Allowed", "Есть") else uiText("设置", "Set", "Настроить"),
                        actionEnabled = access.location && !access.background,
                        onAction = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingActionRow(
                    title = uiText("系统定位", "Location", "Геолокация"),
                    subtitle = if (access.locationOn) {
                        uiText("已打开。", "On.", "Включена.")
                    } else {
                        uiText("已关闭。定位关闭时 Android 不提供 Wi-Fi 名称。", "Off. With location off Android hides Wi-Fi names.", "Выключена. Без неё Android скрывает имена Wi-Fi.")
                    },
                    actionText = if (access.locationOn) uiText("已打开", "On", "Вкл.") else uiText("打开", "Turn on", "Включить"),
                    actionEnabled = !access.locationOn,
                    onAction = { openSettings(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                )
                Spacer(Modifier.height(12.dp))
                SettingActionRow(
                    title = uiText("电池优化（不受限制）", "Battery (Unrestricted)", "Батарея (без ограничений)"),
                    subtitle = if (access.battery) {
                        uiText("已不受限制，后台切换更可靠。", "Unrestricted: switching in the background is reliable.", "Без ограничений: переключение в фоне надёжнее.")
                    } else {
                        uiText("建议设为不受限制，否则系统可能在后台拦下自动连接。", "Recommended: otherwise Android may stop it from connecting in the background.", "Рекомендуется: иначе Android может не дать подключиться в фоне.")
                    },
                    actionText = if (access.battery) uiText("已设置", "Done", "Готово") else uiText("设置", "Set", "Настроить"),
                    actionEnabled = !access.battery,
                    onAction = { requestUnrestrictedBattery(context) },
                )
            }
        }
        item {
            NoteBox(
                text = uiText(
                    "只在进出家里的 Wi-Fi 时切换一次：在家手动打开代理、或在外面手动断开，都会保持到下次进出。锁定模式下不会自动断开。",
                    "It switches only when you arrive or leave: turning the proxy on at home or off outside by hand stays that way until the next move. Lockdown mode keeps the proxy on.",
                    "Переключение происходит только при входе в домашнюю сеть или выходе из неё: ручное включение дома или выключение вне дома сохраняется до следующего перехода. В режиме блокировки прокси не отключается.",
                ),
            )
        }
    }

    if (showManualDialog) {
        ManualWifiDialog(
            existing = config.homeNetworks,
            onDismiss = { showManualDialog = false },
            onAdd = { name ->
                showManualDialog = false
                change { it.copy(homeNetworks = (it.homeNetworks + name).distinct().take(WifiRules.MAX_NETWORKS)) }
            },
        )
    }
}

private data class AccessState(
    val location: Boolean,
    val background: Boolean,
    val locationOn: Boolean,
    val battery: Boolean,
) {
    companion object {
        fun read(context: Context) = AccessState(
            location = WifiAccess.locationGranted(context),
            background = WifiAccess.backgroundLocationGranted(context),
            locationOn = WifiAccess.locationEnabled(context),
            battery = WifiAccess.batteryUnrestricted(context),
        )
    }
}

@Composable
private fun statusText(config: WifiRulesConfig, zone: WifiZone?, currentWifi: String?, access: AccessState): String = when {
    !config.enabled -> uiText("未开启", "Off", "Выключено")
    config.homeNetworks.isEmpty() -> uiText("已开启，请先添加家里的 Wi-Fi", "On: add your home Wi-Fi first", "Включено: добавьте домашнюю сеть")
    !access.location || !access.locationOn -> uiText("已开启，但读不到 Wi-Fi 名称：请允许位置权限并打开定位", "On, but the Wi-Fi name can't be read: allow location and turn it on", "Включено, но имя Wi-Fi не видно: разрешите геолокацию и включите её")
    zone == WifiZone.Home -> uiText("已开启 · 在家里的「$currentWifi」，不用代理", "On · On home Wi-Fi “$currentWifi”: no proxy", "Включено · Домашняя сеть «$currentWifi»: без прокси")
    zone == WifiZone.Away -> uiText("已开启 · 不在家里的 Wi-Fi，使用代理", "On · Away from home Wi-Fi: proxy on", "Включено · Вне домашней сети: прокси включён")
    zone == WifiZone.Offline -> uiText("已开启 · 没有网络", "On · No network", "Включено · Нет сети")
    zone == WifiZone.Unknown -> uiText("已开启 · 读不到 Wi-Fi 名称", "On · Can't read the Wi-Fi name", "Включено · Не видно имя Wi-Fi")
    else -> uiText("已开启 · 正在检查网络…", "On · Checking the network…", "Включено · Проверка сети…")
}

private fun requestLocation(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
}

@SuppressLint("BatteryLife")
private fun requestUnrestrictedBattery(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    if (!openSettings(context, direct)) openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

/**
 * From the activity, in its own task: with NEW_TASK the system page joins the
 * Settings app's task, and closing it would show whatever Settings had open
 * instead of coming back here.
 */
private fun openSettings(context: Context, intent: Intent): Boolean = runCatching {
    val activity = context.activity()
    if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/** The UI's context can be a wrapper (the app's language), not the activity itself. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun ManualWifiDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val cleaned = WifiRules.cleanSsid(name)
    ChoiceDialogFrame(
        icon = Icons.Rounded.Wifi,
        title = uiText("添加家里的 Wi-Fi", "Add home Wi-Fi", "Добавить домашнюю сеть"),
        subtitle = uiText(
            "输入 Wi-Fi 名称，要和手机上显示的完全一样（区分大小写）。",
            "Type the Wi-Fi name exactly as the phone shows it (case matters).",
            "Введите имя сети точно как на телефоне (с учётом регистра).",
        ),
        note = null,
        canSave = cleaned != null && cleaned !in existing,
        onDismiss = onDismiss,
        onSave = { cleaned?.let(onAdd) },
        saveText = uiText("添加", "Add", "Добавить"),
    ) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = compactInputTextStyle(),
                label = { CompactInputLabel(uiText("Wi-Fi 名称", "Wi-Fi name", "Имя сети")) },
                isError = cleaned != null && cleaned in existing,
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
            if (cleaned != null && cleaned in existing) {
                Spacer(Modifier.height(6.dp))
                Text(uiText("已经在列表里了。", "Already in the list.", "Уже в списке."), color = Error, fontSize = TypeScale.Meta)
            }
        }
    }
}
