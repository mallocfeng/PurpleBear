package com.mallocgfw.app.mcp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/*
 * MCP over Streamable HTTP, JSON responses only (no SSE), stateless.
 *
 * Two protocol generations are served from the same endpoint:
 *  - 2026-07-28: no handshake; every request carries MCP-Protocol-Version,
 *    Mcp-Method (and Mcp-Name for tools/call) headers and the version in
 *    params._meta; clients discover the server with server/discover.
 *  - 2025-03-26 … 2025-11-25: initialize / notifications/initialized
 *    handshake. We never issue an Mcp-Session-Id, which those versions allow.
 *
 * Nothing here touches Android, so it is unit-tested on the JVM.
 */

const val MODERN_PROTOCOL_VERSION = "2026-07-28"
val LEGACY_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
val SUPPORTED_PROTOCOL_VERSIONS = listOf(MODERN_PROTOCOL_VERSION) + LEGACY_PROTOCOL_VERSIONS

private const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
private const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

const val MAX_TOOL_CALL_MS = 90_000L

class HttpRequest(
    val method: String,
    val path: String,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val body: String,
    val remoteAddress: String,
    /** Set only for connections forwarded by the Tailscale bridge. */
    val tailnetPeer: TailnetPeer? = null,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

data class TailnetPeer(val user: String, val device: String)

class HttpResponse(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(status: Int, value: JSONObject, extra: Map<String, String> = emptyMap()) =
            HttpResponse(status, value.toString(), mapOf("Content-Type" to "application/json") + extra)
    }
}

enum class ToolLevel { Read, Control, Modify, Dangerous }

/** A tool failure the model should see (bad arguments, node not found …). */
class ToolError(message: String) : Exception(message)

class ToolResult(
    val structured: Any,
    val text: String,
    val isError: Boolean = false,
)

/** A validated call, ready to run once approved. */
class PreparedCall(
    /** One line for the approval prompt and the audit log; no secrets. */
    val summary: String,
    val execute: suspend () -> ToolResult,
)

class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JSONObject,
    val level: ToolLevel,
    /** Validates the arguments; throws [ToolError] for bad input. Must not change anything. */
    val prepare: suspend (JSONObject) -> PreparedCall,
)

data class Caller(val address: String, val tailnetPeer: TailnetPeer?) {
    fun label(): String = tailnetPeer?.let { "${it.user} (${it.device}, $address)" } ?: address
}

enum class ApprovalDecision { Approved, Denied, TimedOut }

interface Approver {
    suspend fun approve(caller: Caller, tool: McpTool, summary: String): ApprovalDecision
}

data class AuditEntry(
    val timeMs: Long,
    val caller: String,
    val tool: String,
    val summary: String,
    val result: String,
    val approvedBy: String?,
)

data class McpPolicy(
    val approveControl: Boolean = false,
    val approveModify: Boolean = true,
    /** Empty: any address. */
    val ipAllowlist: List<String> = emptyList(),
    /** Tailnet logins or device names allowed in; empty: any peer of the tailnet. */
    val tailnetAllowlist: List<String> = emptyList(),
) {
    fun needsApproval(level: ToolLevel): Boolean = when (level) {
        ToolLevel.Read -> false
        ToolLevel.Control -> approveControl
        ToolLevel.Modify -> approveModify
        ToolLevel.Dangerous -> true
    }
}

/** Bearer-token check with a per-address lockout after repeated failures. */
class McpAuth(
    private val token: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 5,
    private val lockMs: Long = 60_000,
) {
    private val failures = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()

    sealed interface Outcome {
        data object Ok : Outcome
        data object Missing : Outcome
        data object Wrong : Outcome
        data class Locked(val retryAfterSec: Long) : Outcome
    }

    @Synchronized
    fun check(address: String, authorization: String?): Outcome {
        val now = clock()
        lockedUntil[address]?.let { until ->
            if (now < until) return Outcome.Locked(((until - now) / 1000).coerceAtLeast(1))
            lockedUntil.remove(address)
            failures.remove(address)
        }
        val presented = authorization?.trim()
            ?.takeIf { it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }
            ?.substring(7)?.trim()
        val expected = token()
        if (presented != null && expected.isNotEmpty() && constantTimeEquals(presented, expected)) {
            failures.remove(address)
            return Outcome.Ok
        }
        val count = (failures[address] ?: 0) + 1
        failures[address] = count
        if (count >= maxFailures) {
            lockedUntil[address] = now + lockMs
            failures.remove(address)
        }
        return if (presented == null) Outcome.Missing else Outcome.Wrong
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

class McpProtocol(
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String,
    private val auth: McpAuth,
    private val policy: () -> McpPolicy,
    private val tools: () -> List<McpTool>,
    private val approver: Approver,
    private val audit: (AuditEntry) -> Unit,
    private val onAuthorizedRequest: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val toolTimeoutMs: Long = MAX_TOOL_CALL_MS,
) {
    suspend fun handle(request: HttpRequest): HttpResponse {
        if (request.path.substringBefore('?') != "/mcp") return HttpResponse(404)
        if (!originAllowed(request.header("origin"))) {
            return HttpResponse.json(403, errorBody(null, -32600, "Origin not allowed"))
        }
        val currentPolicy = policy()
        if (currentPolicy.ipAllowlist.isNotEmpty() && request.tailnetPeer == null &&
            request.remoteAddress !in currentPolicy.ipAllowlist
        ) {
            return HttpResponse.json(403, errorBody(null, -32600, "Address not allowed"))
        }
        request.tailnetPeer?.let { peer ->
            if (currentPolicy.tailnetAllowlist.isNotEmpty() &&
                currentPolicy.tailnetAllowlist.none { it.equals(peer.user, true) || it.equals(peer.device, true) }
            ) {
                return HttpResponse.json(403, errorBody(null, -32600, "Tailnet peer not allowed"))
            }
        }
        when (val outcome = auth.check(request.remoteAddress, request.header("authorization"))) {
            McpAuth.Outcome.Ok -> Unit
            is McpAuth.Outcome.Locked -> return HttpResponse.json(
                429,
                errorBody(null, -32600, "Too many failed attempts"),
                mapOf("Retry-After" to outcome.retryAfterSec.toString()),
            )
            else -> return HttpResponse.json(
                401,
                errorBody(null, -32600, "Missing or invalid bearer token"),
                mapOf("WWW-Authenticate" to "Bearer"),
            )
        }
        if (request.method != "POST") {
            return HttpResponse(405, headers = mapOf("Allow" to "POST"))
        }
        onAuthorizedRequest()
        val parsed = runCatching { org.json.JSONTokener(request.body).nextValue() }.getOrNull()
        if (parsed is JSONArray) {
            return HttpResponse.json(400, errorBody(null, -32600, "Batch requests are not supported"))
        }
        val message = parsed as? JSONObject
            ?: return HttpResponse.json(400, errorBody(null, -32700, "Parse error"))
        if (message.optString("jsonrpc") != "2.0") {
            return HttpResponse.json(400, errorBody(message.opt("id"), -32600, "Invalid JSON-RPC request"))
        }
        val method = message.optString("method")
        if (method.isEmpty()) {
            // A response from the client (we never send requests to it).
            return HttpResponse(202)
        }
        if (!message.has("id")) return HttpResponse(202) // notification
        val id = message.get("id")
        val params = message.optJSONObject("params") ?: JSONObject()

        if (method == "initialize") return initialize(id, params)

        val headerVersion = request.header("mcp-protocol-version")?.trim()
        val metaVersion = params.optJSONObject("_meta")?.optString(META_PROTOCOL_VERSION)?.takeIf { it.isNotBlank() }
        val modern = headerVersion == MODERN_PROTOCOL_VERSION || metaVersion == MODERN_PROTOCOL_VERSION
        if (modern) {
            if (headerVersion == null || (metaVersion != null && metaVersion != headerVersion)) {
                return headerMismatch(id, "MCP-Protocol-Version header must match _meta protocol version")
            }
            if (request.header("mcp-method") != method) {
                return headerMismatch(id, "Mcp-Method header must match the request method")
            }
            if (method == "tools/call" && request.header("mcp-name") != params.optString("name")) {
                return headerMismatch(id, "Mcp-Name header must match the tool name")
            }
        } else if (headerVersion != null && headerVersion !in LEGACY_PROTOCOL_VERSIONS) {
            return unsupportedVersion(id, headerVersion)
        } else if (metaVersion != null && metaVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
            return unsupportedVersion(id, metaVersion)
        }

        val result = when (method) {
            "server/discover" -> discover()
            "ping" -> JSONObject()
            "tools/list" -> toolsList(modern)
            "tools/call" -> return callTool(id, params, request, modern)
            else -> return HttpResponse.json(if (modern) 404 else 200, errorBody(id, -32601, "Method not found: $method"))
        }
        if (modern) result.put("resultType", "complete")
        return HttpResponse.json(200, resultBody(id, result))
    }

    private fun originAllowed(origin: String?): Boolean {
        if (origin == null) return true
        // Browser pages on the computer itself (e.g. MCP Inspector) may call us;
        // any other origin is a page trying to reach a LAN service (DNS rebinding).
        val host = runCatching { java.net.URI(origin).host }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }

    private fun initialize(id: Any, params: JSONObject): HttpResponse {
        val requested = params.optString("protocolVersion")
        val version = if (requested in LEGACY_PROTOCOL_VERSIONS) requested else LEGACY_PROTOCOL_VERSIONS.first()
        val result = JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", capabilities())
            .put("serverInfo", serverInfo())
            .put("instructions", instructions)
        return HttpResponse.json(200, resultBody(id, result))
    }

    private fun discover(): JSONObject = JSONObject()
        .put("supportedVersions", JSONArray(SUPPORTED_PROTOCOL_VERSIONS))
        .put("capabilities", capabilities())
        .put("_meta", JSONObject().put(META_SERVER_INFO, serverInfo()))
        .put("instructions", instructions)
        .put("ttlMs", 300_000)
        .put("cacheScope", "private")

    private fun capabilities() = JSONObject().put("tools", JSONObject().put("listChanged", false))

    private fun serverInfo() = JSONObject()
        .put("name", serverName)
        .put("title", "PurpleBear")
        .put("version", serverVersion)

    private fun toolsList(modern: Boolean): JSONObject {
        val list = JSONArray()
        tools().sortedBy { it.name }.forEach { tool ->
            list.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("title", tool.title)
                    .put("description", tool.description)
                    .put("inputSchema", tool.inputSchema)
                    .put(
                        "annotations",
                        JSONObject()
                            .put("title", tool.title)
                            .put("readOnlyHint", tool.level == ToolLevel.Read)
                            .put("destructiveHint", tool.level == ToolLevel.Dangerous)
                            .put("idempotentHint", tool.level == ToolLevel.Read)
                            .put("openWorldHint", false),
                    ),
            )
        }
        val result = JSONObject().put("tools", list)
        if (modern) result.put("ttlMs", 300_000).put("cacheScope", "private")
        return result
    }

    private suspend fun callTool(id: Any, params: JSONObject, request: HttpRequest, modern: Boolean): HttpResponse {
        val name = params.optString("name")
        val tool = tools().firstOrNull { it.name == name }
            ?: return HttpResponse.json(if (modern) 404 else 200, errorBody(id, -32602, "Unknown tool: $name"))
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val caller = Caller(request.remoteAddress, request.tailnetPeer)
        var summary = name
        var approvedBy: String? = null
        val result: ToolResult = try {
            withTimeout(toolTimeoutMs) {
                val prepared = tool.prepare(args)
                summary = prepared.summary
                if (policy().needsApproval(tool.level)) {
                    when (approver.approve(caller, tool, prepared.summary)) {
                        ApprovalDecision.Approved -> approvedBy = "phone"
                        ApprovalDecision.Denied -> throw ToolError(DENIED)
                        ApprovalDecision.TimedOut -> throw ToolError(DENIED)
                    }
                }
                prepared.execute()
            }
        } catch (error: ToolError) {
            ToolResult(JSONObject().put("error", error.message), error.message.orEmpty(), isError = true)
        } catch (_: TimeoutCancellationException) {
            val text = "Timed out after ${toolTimeoutMs / 1000} s."
            ToolResult(JSONObject().put("error", text), text, isError = true)
        } catch (error: Exception) {
            val text = error.message ?: error.javaClass.simpleName
            ToolResult(JSONObject().put("error", text), text, isError = true)
        }
        audit(
            AuditEntry(
                timeMs = clock(),
                caller = caller.label(),
                tool = name,
                summary = summary,
                result = if (result.isError) "error: ${result.text.take(160)}" else "ok",
                approvedBy = approvedBy,
            ),
        )
        val body = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.text)))
            .put("structuredContent", result.structured)
            .put("isError", result.isError)
        if (modern) body.put("resultType", "complete")
        return HttpResponse.json(200, resultBody(id, body))
    }

    private fun headerMismatch(id: Any?, message: String) =
        HttpResponse.json(400, errorBody(id, -32020, "Header mismatch: $message"))

    private fun unsupportedVersion(id: Any?, requested: String) = HttpResponse.json(
        400,
        errorBody(
            id, -32022, "Unsupported protocol version",
            JSONObject().put("supported", JSONArray(SUPPORTED_PROTOCOL_VERSIONS)).put("requested", requested),
        ),
    )

    private fun resultBody(id: Any?, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun errorBody(id: Any?, code: Int, message: String, data: JSONObject? = null): JSONObject {
        val error = JSONObject().put("code", code).put("message", message)
        data?.let { error.put("data", it) }
        return JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", error)
    }

    companion object {
        const val DENIED = "The phone's owner denied this request, or didn't answer within 60 seconds."
    }
}
