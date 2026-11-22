package com.mallocgfw.app.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    private val token = "test-token-0123456789"
    private var now = 1_000_000L
    private val audit = mutableListOf<AuditEntry>()
    private var policy = McpPolicy()
    private var approvals = 0
    private var answer = ApprovalDecision.Approved
    private val executed = mutableListOf<String>()

    private fun tool(name: String, level: ToolLevel) = McpTool(
        name = name, title = name, description = "test", level = level,
        inputSchema = JSONObject().put("type", "object"),
        prepare = { args ->
            if (args.optString("bad") == "yes") throw ToolError("bad argument")
            PreparedCall("summary of $name") {
                executed += name
                ToolResult(JSONObject().put("ok", true), "done $name")
            }
        },
    )

    private val protocol = McpProtocol(
        serverName = "purplebear", serverVersion = "1.0", instructions = "hi",
        auth = McpAuth(token = { token }, clock = { now }),
        policy = { policy },
        tools = { listOf(tool("read_it", ToolLevel.Read), tool("control_it", ToolLevel.Control), tool("modify_it", ToolLevel.Modify), tool("delete_it", ToolLevel.Dangerous)) },
        approver = object : Approver {
            override suspend fun approve(caller: Caller, tool: McpTool, summary: String): ApprovalDecision {
                approvals++
                return answer
            }
        },
        audit = { audit += it },
        clock = { now },
    )

    private fun post(
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
        auth: String? = "Bearer $token",
        address: String = "192.168.1.10",
        method: String = "POST",
    ) = runBlocking {
        val all = HashMap<String, String>()
        auth?.let { all["authorization"] = it }
        headers.forEach { (k, v) -> all[k.lowercase()] = v }
        protocol.handle(HttpRequest(method, "/mcp", all, body.toString(), address))
    }

    private fun rpc(method: String, params: JSONObject = JSONObject(), id: Any = 1) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    private fun modernHeaders(method: String, name: String? = null) = buildMap {
        put("MCP-Protocol-Version", MODERN_PROTOCOL_VERSION)
        put("Mcp-Method", method)
        name?.let { put("Mcp-Name", it) }
    }

    private fun call(name: String, args: JSONObject = JSONObject()) =
        post(rpc("tools/call", JSONObject().put("name", name).put("arguments", args)), mapOf("MCP-Protocol-Version" to "2025-06-18"))

    private fun result(response: HttpResponse) = JSONObject(response.body).getJSONObject("result")

    @Test
    fun missingOrWrongTokenIsRejectedAndRepeatedFailuresLockTheAddress() {
        assertEquals(401, post(rpc("tools/list"), auth = null).status)
        repeat(3) { assertEquals(401, post(rpc("tools/list"), auth = "Bearer nope").status) }
        assertEquals(401, post(rpc("tools/list"), auth = "Bearer nope").status) // 5th failure locks
        val locked = post(rpc("tools/list"))
        assertEquals(429, locked.status)
        assertEquals("60", locked.headers["Retry-After"])
        assertEquals(200, post(rpc("tools/list"), address = "192.168.1.11").status) // other address unaffected
        now += 61_000
        assertEquals(200, post(rpc("tools/list")).status)
    }

    @Test
    fun foreignOriginIsRefusedButLocalhostIsAllowed() {
        assertEquals(403, post(rpc("tools/list"), mapOf("Origin" to "http://evil.example")).status)
        assertEquals(200, post(rpc("tools/list"), mapOf("Origin" to "http://localhost:6274")).status)
    }

    @Test
    fun ipAllowlistIsEnforced() {
        policy = McpPolicy(ipAllowlist = listOf("192.168.1.20"))
        assertEquals(403, post(rpc("tools/list")).status)
        assertEquals(200, post(rpc("tools/list"), address = "192.168.1.20").status)
    }

    @Test
    fun getIsNotAllowedAndNotificationsAreAccepted() {
        assertEquals(405, post(JSONObject(), method = "GET").status)
        val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        val response = post(notification)
        assertEquals(202, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun malformedAndBatchRequestsAreRejected() {
        val bad = runBlocking { protocol.handle(HttpRequest("POST", "/mcp", mapOf("authorization" to "Bearer $token"), "{not json", "1.2.3.4")) }
        assertEquals(400, bad.status)
        assertEquals(-32700, JSONObject(bad.body).getJSONObject("error").getInt("code"))
        val batch = runBlocking { protocol.handle(HttpRequest("POST", "/mcp", mapOf("authorization" to "Bearer $token"), "[]", "1.2.3.4")) }
        assertEquals(400, batch.status)
    }

    @Test
    fun initializeEchoesASupportedVersionAndOtherwiseOffersTheLatestLegacyOne() {
        val ok = result(post(rpc("initialize", JSONObject().put("protocolVersion", "2025-06-18"))))
        assertEquals("2025-06-18", ok.getString("protocolVersion"))
        assertTrue(ok.getJSONObject("capabilities").has("tools"))
        assertEquals("purplebear", ok.getJSONObject("serverInfo").getString("name"))
        val other = result(post(rpc("initialize", JSONObject().put("protocolVersion", "1999-01-01"))))
        assertEquals(LEGACY_PROTOCOL_VERSIONS.first(), other.getString("protocolVersion"))
    }

    @Test
    fun discoverListsVersionsAndServerInfoInMeta() {
        val params = JSONObject().put("_meta", JSONObject().put("io.modelcontextprotocol/protocolVersion", MODERN_PROTOCOL_VERSION))
        val response = post(rpc("server/discover", params), modernHeaders("server/discover"))
        assertEquals(200, response.status)
        val result = result(response)
        assertEquals("complete", result.getString("resultType"))
        assertTrue(result.getJSONArray("supportedVersions").toString().contains(MODERN_PROTOCOL_VERSION))
        assertEquals("purplebear", result.getJSONObject("_meta").getJSONObject("io.modelcontextprotocol/serverInfo").getString("name"))
    }

    @Test
    fun modernRequestsMustCarryMatchingHeaders() {
        val params = JSONObject().put("_meta", JSONObject().put("io.modelcontextprotocol/protocolVersion", MODERN_PROTOCOL_VERSION))
        val noMethodHeader = post(rpc("tools/list", params), mapOf("MCP-Protocol-Version" to MODERN_PROTOCOL_VERSION))
        assertEquals(400, noMethodHeader.status)
        assertEquals(-32020, JSONObject(noMethodHeader.body).getJSONObject("error").getInt("code"))
        val noVersionHeader = post(rpc("tools/list", params), mapOf("Mcp-Method" to "tools/list"))
        assertEquals(400, noVersionHeader.status)
        val wrongName = post(
            rpc("tools/call", JSONObject(params.toString()).put("name", "read_it")),
            modernHeaders("tools/call", name = "other"),
        )
        assertEquals(400, wrongName.status)
        val good = post(rpc("tools/call", JSONObject(params.toString()).put("name", "read_it")), modernHeaders("tools/call", "read_it"))
        assertEquals(200, good.status)
        assertEquals("complete", result(good).getString("resultType"))
    }

    @Test
    fun unsupportedVersionHeaderGets400WithTheSupportedList() {
        val response = post(rpc("tools/list"), mapOf("MCP-Protocol-Version" to "1900-01-01"))
        assertEquals(400, response.status)
        val error = JSONObject(response.body).getJSONObject("error")
        assertEquals(-32022, error.getInt("code"))
        assertEquals("1900-01-01", error.getJSONObject("data").getString("requested"))
    }

    @Test
    fun toolsListCarriesAnnotations() {
        val tools = result(post(rpc("tools/list"))).getJSONArray("tools")
        assertEquals(4, tools.length())
        val delete = (0 until tools.length()).map { tools.getJSONObject(it) }.first { it.getString("name") == "delete_it" }
        assertTrue(delete.getJSONObject("annotations").getBoolean("destructiveHint"))
    }

    @Test
    fun readAndControlToolsRunWithoutApprovalByDefault() {
        assertFalse(result(call("read_it")).getBoolean("isError"))
        assertFalse(result(call("control_it")).getBoolean("isError"))
        assertEquals(0, approvals)
        assertEquals(listOf("read_it", "control_it"), executed)
    }

    @Test
    fun deniedChangesAreNeverExecuted() {
        answer = ApprovalDecision.Denied
        val modify = result(call("modify_it"))
        assertTrue(modify.getBoolean("isError"))
        answer = ApprovalDecision.TimedOut
        assertTrue(result(call("delete_it")).getBoolean("isError"))
        assertEquals(2, approvals)
        assertTrue(executed.isEmpty())
        assertEquals(listOf("error", "error"), audit.map { it.result.substringBefore(':') })
    }

    @Test
    fun dangerousToolsAskEvenWhenChangesDoNotNeedApproval() {
        policy = McpPolicy(approveModify = false)
        call("modify_it")
        assertEquals(0, approvals)
        call("delete_it")
        assertEquals(1, approvals)
        assertEquals(listOf("modify_it", "delete_it"), executed)
        assertEquals("phone", audit.last().approvedBy)
    }

    @Test
    fun badArgumentsAreToolErrorsAndUnknownToolsAreProtocolErrors() {
        val bad = result(call("modify_it", JSONObject().put("bad", "yes")))
        assertTrue(bad.getBoolean("isError"))
        assertEquals(0, approvals) // validation happens before asking the phone
        val unknown = JSONObject(call("nope").body)
        assertEquals(-32602, unknown.getJSONObject("error").getInt("code"))
    }
}
