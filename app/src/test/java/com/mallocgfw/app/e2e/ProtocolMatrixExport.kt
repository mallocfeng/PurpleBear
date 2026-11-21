package com.mallocgfw.app.e2e

import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.NodeCompatibility
import com.mallocgfw.app.model.SsrMihomoConfigFactory
import com.mallocgfw.app.model.TlsPinCache
import com.mallocgfw.app.xray.LocalProxyAllocation
import com.mallocgfw.app.xray.LocalProxyEndpoint
import com.mallocgfw.app.xray.XrayConfigFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Exports client configs for the end-to-end protocol matrix.
 *
 * Runs only when PB_MATRIX_DIR points at a directory containing cases.json
 * (written by the matrix tooling). Every case goes through the same import
 * parser and config builder the app uses; the tooling then runs real traffic
 * through each config with the Xray version the app ships.
 */
class ProtocolMatrixExport {
    @Test
    fun export() {
        val dir = System.getenv("PB_MATRIX_DIR")?.let(::File) ?: return
        val spec = JSONObject(File(dir, "cases.json").readText())
        val pin = spec.getString("pin")
        val out = File(dir, "client").apply { mkdirs() }
        val index = JSONArray()
        val cases = spec.getJSONArray("cases")
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val id = case.getString("id")
            val fileName = when (case.getString("kind")) {
                "clash" -> "clash.yaml"
                "singbox" -> "singbox.json"
                "xray" -> "xray.json"
                "surge" -> "surge.conf"
                "loon" -> "loon.conf"
                "qx" -> "quantumultx.conf"
                else -> "links.txt"
            }
            val preview = runCatching { ImportParser.buildFilePreview(fileName, case.getString("payload")) }
            val ports = case.opt("port").let { p -> if (p is JSONArray) List(p.length()) { p.getInt(it) } else listOf(p as Int) }
            if (preview.isFailure) {
                index.put(
                    JSONObject().put("id", id).put("status", "import-failed")
                        .put("expect", case.optString("expect", "pass"))
                        .put("error", preview.exceptionOrNull()?.message),
                )
                continue
            }
            val nodes = preview.getOrThrow().nodes
            if (nodes.isEmpty()) {
                index.put(
                    JSONObject().put("id", id).put("status", "no-nodes")
                        .put("expect", case.optString("expect", "pass"))
                        .put("summary", preview.getOrThrow().summary),
                )
            }
            nodes.forEachIndexed { n, node ->
                val name = if (nodes.size == 1) id else "$id/${node.name}"
                val entry = JSONObject()
                    .put("id", name)
                    .put("case", id)
                    .put("expect", case.optString("expect", "pass"))
                    .put("note", case.optString("note"))
                    .put("serverPort", ports.getOrElse(n) { ports.first() })
                    .put("protocol", node.protocol)
                    .put("transport", node.transport)
                    .put("security", node.security)
                    .put("hidden", node.hiddenUnsupported)
                    .put("incompatible", NodeCompatibility.coreIncompatibility(node) ?: "")
                // What InsecureTlsPinner does on the device right before connecting.
                ManualNodeFactory.insecureTlsTarget(node)?.let { TlsPinCache.put(it, pin) }
                val socksPort = 41000 + index.length()
                val alloc = LocalProxyAllocation(
                    socks = LocalProxyEndpoint(socksPort, "u$socksPort", "p$socksPort"),
                    http = LocalProxyEndpoint(socksPort + 500, "h$socksPort", "q$socksPort"),
                    ssr = LocalProxyEndpoint(socksPort + 1000, "s$socksPort", "r$socksPort"),
                )
                val config = runCatching {
                    XrayConfigFactory.build(node = node, availableServers = nodes, logLevel = "warning", localProxy = alloc)
                }
                if (config.isFailure) {
                    entry.put("status", "build-failed").put("error", config.exceptionOrNull()?.message)
                } else {
                    val file = File(out, name.replace('/', '_').replace(' ', '_') + ".json")
                    file.writeText(JSONObject(config.getOrThrow()).toString(2))
                    entry.put("status", "built").put("config", file.absolutePath)
                        .put("socksPort", socksPort).put("user", alloc.socks.username).put("pass", alloc.socks.password)
                    NodeCompatibility.mihomoProxy(node)?.let { proxy ->
                        val sidecar = File(out, file.nameWithoutExtension + ".mihomo.yaml")
                        sidecar.writeText(SsrMihomoConfigFactory.buildConfig(proxy, alloc.ssr))
                        entry.put("sidecar", sidecar.absolutePath)
                    }
                }
                index.put(entry)
            }
        }
        File(dir, "index.json").writeText(index.toString(2))
    }
}
