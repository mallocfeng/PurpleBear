package com.mallocgfw.app.model

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsrSupportTest {
    @Test
    fun ssrShareLinkBuildsLocalSocksBridgeNode() {
        val link = ssrLink(
            server = "ssr.example.com",
            port = 443,
            protocol = "auth_sha1_v4",
            method = "aes-256-cfb",
            obfs = "tls1.2_ticket_auth",
            password = "secret",
            remarks = "SSR Hong Kong",
            obfsParam = "cdn.example.com",
            protocolParam = "32:password",
        )

        assertTrue(ManualNodeFactory.supportsShareLink(link))

        val node = ManualNodeFactory.buildNodeFromShareLink(link)
        val outbound = JSONObject(node.outboundJson)
        val settings = outbound.getJSONObject("settings")

        assertEquals("SSR Hong Kong", node.name)
        assertEquals("SSR", node.protocol)
        assertEquals("ssr.example.com", node.address)
        assertEquals("443", node.port)
        assertEquals("auth_sha1_v4 / tls1.2_ticket_auth", node.flow)
        assertTrue(node.rawUri.startsWith("ssr://"))
        assertEquals("socks", outbound.getString("protocol"))
        assertEquals("127.0.0.1", settings.getString("address"))
        assertEquals(17891, settings.getInt("port"))
    }

    @Test
    fun clashSsrProxyImportsAsVisibleNode() {
        val preview = ImportParser.buildFilePreview(
            fileName = "ssr.yaml",
            content = """
                proxies:
                  - name: "SSR Japan"
                    type: ssr
                    server: ssr.example.com
                    port: 8443
                    cipher: chacha20-ietf
                    password: "secret"
                    obfs: tls1.2_ticket_auth
                    protocol: auth_sha1_v4
                    obfs-param: cdn.example.com
                    protocol-param: "32:password"
                    udp: true
            """.trimIndent(),
        )

        val node = preview.nodes.single()
        val outbound = JSONObject(node.outboundJson)

        assertFalse(node.hiddenUnsupported)
        assertEquals("SSR Japan", node.name)
        assertEquals("SSR", node.protocol)
        assertEquals("ssr.example.com", node.address)
        assertEquals("8443", node.port)
        assertEquals("socks", outbound.getString("protocol"))
    }

    @Test
    fun ssrWrappedShadowsocks2022LinkUsesNativeShadowsocksOutbound() {
        val link = "ssr://bnBpZXBsc3RkLnRlY2hmZW5nLm5ldDoxMTE0Mjpub25lOjIwMjItYmxha2UzLWFlcy0xMjgtZ2NtOm5vbmU6Vms1alZIbHdiMHBPS3l0TVNWUTJabWwyVlZWWlFUMDlPbFZ0T1hoaGEwTlRSRE5wYVRoeE1UUlpaVmhsY1ZFOVBRLz9yZW1hcmtzPVZqSWdmQ0Rudm83bG03MGdXMGxGVUV3eExqVllYUSZwcm90b3BhcmFtPSZvYmZzcGFyYW09"

        val node = ManualNodeFactory.buildNodeFromShareLink(link)
        val outbound = JSONObject(node.outboundJson)
        val settings = outbound.getJSONObject("settings")

        assertEquals("SHADOWSOCKS", node.protocol)
        assertEquals("shadowsocks", outbound.getString("protocol"))
        assertEquals("npieplstd.techfeng.net", settings.getString("address"))
        assertEquals(11142, settings.getInt("port"))
        assertEquals("2022-blake3-aes-128-gcm", settings.getString("method"))
        assertEquals("VNcTypoJN++LIT6fivUUYA==:Um9xakCSD3ii8q14YeXeqQ==", settings.getString("password"))
    }

    @Test
    fun staleSsrWrappedShadowsocks2022NodeRebuildsOutboundFromRawUri() {
        val link = "ssr://bnBpZXBsc3RkLnRlY2hmZW5nLm5ldDoxMTE0Mjpub25lOjIwMjItYmxha2UzLWFlcy0xMjgtZ2NtOm5vbmU6Vms1alZIbHdiMHBPS3l0TVNWUTJabWwyVlZWWlFUMDlPbFZ0T1hoaGEwTlRSRE5wYVRoeE1UUlpaVmhsY1ZFOVBRLz9yZW1hcmtzPVZqSWdmQ0Rudm83bG03MGdXMGxGVUV3eExqVllYUSZwcm90b3BhcmFtPSZvYmZzcGFyYW09"
        val staleNode = ManualNodeFactory.buildNodeFromShareLink(ssrLink(
            server = "ssr.example.com",
            port = 443,
            protocol = "auth_sha1_v4",
            method = "aes-256-cfb",
            obfs = "tls1.2_ticket_auth",
            password = "secret",
            remarks = "Old SSR",
            obfsParam = "",
            protocolParam = "",
        )).copy(rawUri = link)

        val outbound = ManualNodeFactory.buildOutboundConfig(staleNode)

        assertEquals("shadowsocks", outbound?.getString("protocol"))
        assertEquals("2022-blake3-aes-128-gcm", outbound?.getJSONObject("settings")?.getString("method"))
        assertEquals(null, ManualNodeFactory.ssrEndpointFromServer(staleNode))
    }

    private fun ssrLink(
        server: String,
        port: Int,
        protocol: String,
        method: String,
        obfs: String,
        password: String,
        remarks: String,
        obfsParam: String,
        protocolParam: String,
    ): String {
        val payload = "$server:$port:$protocol:$method:$obfs:${base64Url(password)}/" +
            "?obfsparam=${base64Url(obfsParam)}" +
            "&protoparam=${base64Url(protocolParam)}" +
            "&remarks=${base64Url(remarks)}"
        return "ssr://${base64Url(payload)}"
    }

    private fun base64Url(value: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    }
}
