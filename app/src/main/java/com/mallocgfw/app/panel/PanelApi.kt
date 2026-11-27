package com.mallocgfw.app.panel

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import com.mallocgfw.app.BuildConfig
import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.mcp.HttpRequest
import com.mallocgfw.app.mcp.HttpResponse
import com.mallocgfw.app.mcp.McpAuth
import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.TextLanguage
import com.mallocgfw.app.stats.ConnectionMonitor
import com.mallocgfw.app.stats.ConnectionView
import com.mallocgfw.app.stats.LabeledConnection
import com.mallocgfw.app.stats.TrafficLabels
import com.mallocgfw.app.stats.TrafficStatsStore
import com.mallocgfw.app.stats.TrafficSummary
import com.mallocgfw.app.xray.VpnRuntimeStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The remote panel's HTTP endpoints. Read-only:
 * - `GET /`: the page (no data in it);
 * - `GET /api/snapshot`: connections and traffic, with `Authorization: Bearer <token>`;
 * - `GET /api/icons?apps=a,b`: app icons as data URLs, with the token too.
 */
class PanelApi(
    private val context: Context,
    private val auth: McpAuth,
    private val onAuthorized: () -> Unit = {},
) {
    suspend fun handle(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return HttpResponse(405, headers = mapOf("Allow" to "GET"))
        return when (request.path.substringBefore('?')) {
            "/", "/index.html" -> page()
            "/api/snapshot" -> {
                refuse(request)?.let { return it }
                onAuthorized()
                ConnectionMonitor.touch()
                HttpResponse.json(200, snapshot())
            }
            "/api/icons" -> {
                refuse(request)?.let { return it }
                HttpResponse.json(200, icons(query(request.path)["apps"].orEmpty()), mapOf("Cache-Control" to "private, max-age=3600"))
            }
            else -> HttpResponse(404)
        }
    }

    /** The answer for a request without the right token; null when it has it. */
    private fun refuse(request: HttpRequest): HttpResponse? =
        when (val outcome = auth.check(request.remoteAddress, request.header("authorization"))) {
            McpAuth.Outcome.Ok -> null
            is McpAuth.Outcome.Locked -> HttpResponse.json(
                429,
                JSONObject().put("error", "locked"),
                mapOf("Retry-After" to outcome.retryAfterSec.toString()),
            )
            else -> HttpResponse.json(401, JSONObject().put("error", "unauthorized"))
        }

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            val (key, value) = it.split('=', limit = 2)
            key to java.net.URLDecoder.decode(value, "UTF-8")
        }

    private fun icons(apps: String): JSONObject = JSONObject().apply {
        apps.split(',')
            .map(String::trim)
            .filter { PACKAGE.matches(it) && TrafficLabels.isPackage(it) }
            .distinct()
            .take(MAX_ICONS)
            .forEach { name -> icon(name)?.let { put(name, it) } }
    }

    private fun icon(packageName: String): String? {
        iconCache[packageName]?.let { return it.ifEmpty { null } }
        val url = runCatching {
            val bitmap = context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
        if (iconCache.size > MAX_CACHED_ICONS) iconCache.clear()
        iconCache[packageName] = url.orEmpty()
        return url
    }

    private fun page(): HttpResponse {
        val html = context.assets.open("panel/index.html").bufferedReader().use { it.readText() }
        return HttpResponse(
            200,
            html,
            mapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Cache-Control" to "no-cache",
                // Everything is inline; the page talks only to this server.
                "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "no-referrer",
            ),
        )
    }

    private fun snapshot(): JSONObject {
        val runtime = VpnRuntimeStore.snapshot.value
        val node = runtime.activeServerId?.let { id -> AppController.current().servers.firstOrNull { it.id == id }?.name }
        val history = TrafficStatsStore.history(context)
        val connected = runtime.status == ConnectionStatus.Connected
        val today = TrafficStatsStore.today()
        return PanelJson.snapshot(
            view = ConnectionMonitor.view.value,
            connected = connected,
            node = node,
            summaries = listOf(
                "today" to history.summarize(today, 1),
                "week" to history.summarize(today, 7),
                "month" to history.summarize(today, 30),
            ),
            language = when (AppTextLanguage.current) {
                TextLanguage.Chinese -> "zh"
                TextLanguage.English -> "en"
                TextLanguage.Russian -> "ru"
            },
            version = BuildConfig.VERSION_NAME,
            appLabel = { TrafficLabels.appLabel(context, it) },
            device = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
                Build.MODEL
            } else {
                "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"
            },
            connectedAtMs = runtime.connectedAtMs.takeIf { connected },
        )
    }

    private companion object {
        val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        const val MAX_ICONS = 40
        const val MAX_CACHED_ICONS = 400
        const val ICON_PX = 64
        val iconCache = ConcurrentHashMap<String, String>()
    }
}

/** The JSON the panel page reads. */
object PanelJson {
    const val MAX_CLOSED = 100

    fun snapshot(
        view: ConnectionView,
        connected: Boolean,
        node: String?,
        summaries: List<Pair<String, TrafficSummary>>,
        language: String,
        version: String,
        appLabel: (String) -> String,
        nowMs: Long = System.currentTimeMillis(),
        device: String = "",
        connectedAtMs: Long? = null,
    ): JSONObject = JSONObject().apply {
        put("lang", language)
        put("version", version)
        put("device", device)
        put("now", nowMs)
        put(
            "vpn",
            JSONObject()
                .put("connected", connected)
                .put("tracking", view.running)
                .putOpt("node", node)
                .putOpt("since", connectedAtMs),
        )
        put("speed", JSONObject().put("up", view.upSpeed).put("down", view.downSpeed))
        // [time, up, down] triples, so a page opened just now can draw the last minutes.
        put(
            "speedHistory",
            JSONArray().apply {
                view.speedHistory.forEach { put(JSONArray().put(it.timeMs).put(it.up).put(it.down)) }
            },
        )
        put("active", JSONArray().apply { view.active.forEach { put(connection(it)) } })
        put("closed", JSONArray().apply { view.closed.take(MAX_CLOSED).forEach { put(connection(it)) } })
        put(
            "traffic",
            JSONObject().apply {
                summaries.forEach { (name, summary) -> put(name, summary(summary, appLabel)) }
            },
        )
    }

    private fun connection(item: LabeledConnection) = JSONObject().apply {
        val c = item.connection
        put("id", c.id)
        put("host", c.host)
        put("port", c.port)
        putOpt("ip", c.ip)
        put("net", c.network)
        put("app", item.appLabel)
        put("appKey", item.appKey)
        put("rule", item.ruleLabel)
        put("node", item.nodeLabel)
        put("route", TrafficLabels.routeKind(item.nodeKey))
        putOpt("ruleKind", item.ruleKind)
        putOpt("uid", c.uid.takeIf { it >= 0 })
        put("up", c.up)
        put("down", c.down)
        put("start", c.startMs)
        c.endMs?.let { put("end", it) }
    }

    private fun summary(summary: TrafficSummary, appLabel: (String) -> String) = JSONObject().apply {
        put("up", summary.total.up)
        put("down", summary.total.down)
        put(
            "apps",
            JSONArray().apply {
                summary.apps.forEach { (key, count) ->
                    put(
                        JSONObject()
                            .put("key", key)
                            .put("name", appLabel(key))
                            .put("up", count.up)
                            .put("down", count.down)
                            // Which nodes the app used: proxy or direct at a glance.
                            .put(
                                "via",
                                JSONArray().apply {
                                    summary.appNodes[key].orEmpty().forEach { (node, c) ->
                                        put(
                                            JSONObject()
                                                .put("name", TrafficLabels.nodeLabel(node))
                                                .put("route", TrafficLabels.routeKind(node))
                                                .put("up", c.up)
                                                .put("down", c.down),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
        put(
            "nodes",
            JSONArray().apply {
                summary.nodes.forEach { (key, count) ->
                    put(
                        JSONObject()
                            .put("name", TrafficLabels.nodeLabel(key))
                            .put("route", TrafficLabels.routeKind(key))
                            .put("up", count.up)
                            .put("down", count.down),
                    )
                }
            },
        )
        put(
            "daily",
            JSONArray().apply {
                summary.daily.forEach { (day, count) ->
                    put(JSONObject().put("day", day).put("up", count.up).put("down", count.down))
                }
            },
        )
    }
}
