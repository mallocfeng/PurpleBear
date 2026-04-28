package com.mallocgfw.app.model

object FakeRepository {
    val serverGroups = listOf(
        ServerGroup(
            id = ImportParser.LOCAL_GROUP_ID,
            name = "Local",
            type = ServerGroupType.Local,
            updatedAt = "未导入",
        ),
    )

    val servers = emptyList<ServerNode>()

    val subscriptions = emptyList<SubscriptionItem>()

    val protectedApps = listOf(
        ProtectedApp("org.telegram.messenger", "Telegram", "通讯", true),
        ProtectedApp("app.revanced.android.youtube", "YouTube ReVanced", "视频", true),
        ProtectedApp("com.google.android.youtube", "YouTube", "视频", true),
        ProtectedApp("com.twitter.android", "X", "社交", true),
        ProtectedApp("com.discord", "Discord", "社区", true),
        ProtectedApp("com.reddit.frontpage", "Reddit", "社区", true),
        ProtectedApp("com.instagram.android", "Instagram", "社交", true),
        ProtectedApp("com.facebook.katana", "Facebook", "社交", true),
        ProtectedApp("com.whatsapp", "WhatsApp", "通讯", true),
        ProtectedApp("com.threads.android", "Threads", "社交", true),
        ProtectedApp("com.openai.chatgpt", "ChatGPT", "AI", true),
        ProtectedApp("com.google.android.apps.youtube.music", "YouTube Music", "音乐", true),
    )

    val recentImports = emptyList<String>()

    val diagnostics = listOf(
        DiagnosticStep(
            key = "config",
            title = "配置解析",
            detail = "已成功校验订阅生成的 JSON 配置和路由标签。",
            status = DiagnosticStatus.Success,
        ),
        DiagnosticStep(
            key = "core",
            title = "Xray 内核启动",
            detail = "已加载官方编译产物并完成本地环境初始化。",
            status = DiagnosticStatus.Success,
        ),
        DiagnosticStep(
            key = "vpn",
            title = "VPN 建立",
            detail = "已获取 VpnService 授权，TUN 接口可用。",
            status = DiagnosticStatus.Success,
        ),
        DiagnosticStep(
            key = "dns",
            title = "DNS 解析",
            detail = "保护后的 DNS 通道工作正常，当前 RTT 24ms。",
            status = DiagnosticStatus.Success,
        ),
        DiagnosticStep(
            key = "handshake",
            title = "远端握手",
            detail = "成功完成目标节点握手并建立安全隧道。",
            status = DiagnosticStatus.Success,
        ),
        DiagnosticStep(
            key = "proxy",
            title = "代理可达性",
            detail = "HTTP 与 UDP 测试通过，可开始转发应用流量。",
            status = DiagnosticStatus.Success,
        ),
    )
}
