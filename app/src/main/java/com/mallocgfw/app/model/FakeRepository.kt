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

    /** Display order of the per-app categories. */
    val protectedAppCategories = listOf("AI", "通讯", "社交", "视频", "音乐", "Google 与系统", "浏览器", "办公效率", "新闻阅读")

    // Package names checked on Google Play (Nov 2026), except Google system
    // components, ReVanced/RVX and Telegram's website build, which are not on Play.
    // Every id must also be listed under <queries> in AndroidManifest.xml.
    val protectedApps = listOf(
        // AI
        ProtectedApp("com.openai.chatgpt", "ChatGPT", "AI", true),
        ProtectedApp("com.anthropic.claude", "Claude", "AI", true),
        ProtectedApp("com.google.android.apps.bard", "Gemini", "AI", true),
        ProtectedApp("ai.perplexity.app.android", "Perplexity", "AI", true),
        ProtectedApp("ai.x.grok", "Grok", "AI", true),
        ProtectedApp("com.microsoft.office.officehubrow", "Microsoft Copilot", "AI", true),
        ProtectedApp("com.microsoft.bing", "Microsoft Bing", "AI", true),
        ProtectedApp("com.poe.android", "Poe", "AI", true),
        ProtectedApp("ai.character.app", "Character.AI", "AI", true),
        ProtectedApp("com.google.android.apps.labs.language.tailwind", "Gemini Notebook（NotebookLM）", "AI", true),
        ProtectedApp("ai.mistral.chat", "Vibe（Mistral Le Chat）", "AI", true),
        ProtectedApp("ai.inflection.pi", "Pi", "AI", true),
        ProtectedApp("com.suno.android", "Suno", "AI", true),
        ProtectedApp("io.elevenlabs.readerapp", "ElevenReader（ElevenLabs）", "AI", true),
        ProtectedApp("com.facebook.stella", "Meta AI", "AI", true),
        // 通讯
        ProtectedApp("org.telegram.messenger", "Telegram", "通讯", true),
        ProtectedApp("org.telegram.messenger.web", "Telegram（官网版）", "通讯", true),
        ProtectedApp("org.thunderdog.challegram", "Telegram X", "通讯", true),
        ProtectedApp("com.whatsapp", "WhatsApp", "通讯", true),
        ProtectedApp("com.whatsapp.w4b", "WhatsApp Business", "通讯", true),
        ProtectedApp("org.thoughtcrime.securesms", "Signal", "通讯", true),
        ProtectedApp("com.facebook.orca", "Messenger", "通讯", true),
        ProtectedApp("jp.naver.line.android", "LINE", "通讯", true),
        ProtectedApp("com.viber.voip", "Viber", "通讯", true),
        ProtectedApp("com.kakao.talk", "KakaoTalk", "通讯", true),
        ProtectedApp("com.google.android.apps.tachyon", "Google Meet", "通讯", true),
        ProtectedApp("us.zoom.videomeetings", "Zoom", "通讯", true),
        // 社交
        ProtectedApp("com.twitter.android", "X", "社交", true),
        ProtectedApp("com.instagram.android", "Instagram", "社交", true),
        ProtectedApp("com.facebook.katana", "Facebook", "社交", true),
        ProtectedApp("com.instagram.barcelona", "Threads", "社交", true),
        ProtectedApp("com.zhiliaoapp.musically", "TikTok", "社交", true),
        ProtectedApp("com.discord", "Discord", "社交", true),
        ProtectedApp("com.reddit.frontpage", "Reddit", "社交", true),
        ProtectedApp("xyz.blueskyweb.app", "Bluesky", "社交", true),
        ProtectedApp("org.joinmastodon.android", "Mastodon", "社交", true),
        ProtectedApp("com.linkedin.android", "LinkedIn", "社交", true),
        ProtectedApp("com.pinterest", "Pinterest", "社交", true),
        ProtectedApp("com.snapchat.android", "Snapchat", "社交", true),
        ProtectedApp("com.tumblr", "Tumblr", "社交", true),
        ProtectedApp("com.quora.android", "Quora", "社交", true),
        // 视频
        ProtectedApp("com.google.android.youtube", "YouTube", "视频", true),
        ProtectedApp("app.revanced.android.youtube", "YouTube ReVanced", "视频", true),
        ProtectedApp("app.rvx.android.youtube", "YouTube RVX", "视频", true),
        ProtectedApp("com.netflix.mediaclient", "Netflix", "视频", true),
        ProtectedApp("com.disney.disneyplus", "Disney+", "视频", true),
        ProtectedApp("com.amazon.avod.thirdpartyclient", "Prime Video", "视频", true),
        ProtectedApp("com.wbd.stream", "HBO Max", "视频", true),
        ProtectedApp("tv.twitch.android.app", "Twitch", "视频", true),
        ProtectedApp("com.hulu.plus", "Hulu", "视频", true),
        ProtectedApp("com.cbs.app", "Paramount+", "视频", true),
        ProtectedApp("com.crunchyroll.crunchyroid", "Crunchyroll", "视频", true),
        ProtectedApp("tv.abema", "ABEMA", "视频", true),
        // 音乐
        ProtectedApp("com.google.android.apps.youtube.music", "YouTube Music", "音乐", true),
        ProtectedApp("com.spotify.music", "Spotify", "音乐", true),
        ProtectedApp("com.apple.android.music", "Apple Music", "音乐", true),
        ProtectedApp("com.soundcloud.android", "SoundCloud", "音乐", true),
        ProtectedApp("com.aspiro.tidal", "TIDAL", "音乐", true),
        ProtectedApp("deezer.android.app", "Deezer", "音乐", true),
        // Google 与系统
        ProtectedApp("com.android.vending", "Google Play", "Google 与系统", true),
        ProtectedApp("com.google.android.gms", "Google Play services", "Google 与系统", true),
        ProtectedApp("com.google.android.gsf", "Google Services Framework", "Google 与系统", true),
        ProtectedApp("com.android.providers.downloads", "Download Manager", "Google 与系统", true),
        ProtectedApp("com.google.android.googlequicksearchbox", "Google", "Google 与系统", true),
        ProtectedApp("com.google.android.gm", "Gmail", "Google 与系统", true),
        ProtectedApp("com.google.android.apps.maps", "Google Maps", "Google 与系统", true),
        ProtectedApp("com.google.android.apps.docs", "Google Drive", "Google 与系统", true),
        ProtectedApp("com.google.android.apps.photos", "Google Photos", "Google 与系统", true),
        ProtectedApp("com.google.android.apps.translate", "Google Translate", "Google 与系统", true),
        ProtectedApp("com.google.android.calendar", "Google Calendar", "Google 与系统", true),
        ProtectedApp("com.google.android.keep", "Google Keep", "Google 与系统", true),
        ProtectedApp("com.google.android.apps.docs.editors.docs", "Google Docs", "Google 与系统", true),
        ProtectedApp("com.google.android.apps.docs.editors.sheets", "Google Sheets", "Google 与系统", true),
        // 浏览器
        ProtectedApp("com.android.chrome", "Chrome", "浏览器", true),
        ProtectedApp("org.mozilla.firefox", "Firefox", "浏览器", true),
        ProtectedApp("com.brave.browser", "Brave", "浏览器", true),
        ProtectedApp("com.microsoft.emmx", "Microsoft Edge", "浏览器", true),
        ProtectedApp("com.duckduckgo.mobile.android", "DuckDuckGo", "浏览器", true),
        // 办公效率
        ProtectedApp("com.github.android", "GitHub", "办公效率", true),
        ProtectedApp("notion.id", "Notion", "办公效率", true),
        ProtectedApp("com.Slack", "Slack", "办公效率", true),
        ProtectedApp("com.microsoft.teams", "Microsoft Teams", "办公效率", true),
        ProtectedApp("com.microsoft.office.outlook", "Outlook", "办公效率", true),
        ProtectedApp("com.dropbox.android", "Dropbox", "办公效率", true),
        ProtectedApp("com.microsoft.skydrive", "OneDrive", "办公效率", true),
        ProtectedApp("md.obsidian", "Obsidian", "办公效率", true),
        ProtectedApp("ch.protonmail.android", "Proton Mail", "办公效率", true),
        ProtectedApp("com.x8bit.bitwarden", "Bitwarden", "办公效率", true),
        ProtectedApp("com.onepassword.android", "1Password", "办公效率", true),
        // 新闻阅读
        ProtectedApp("com.google.android.apps.magazines", "Google News", "新闻阅读", true),
        ProtectedApp("org.wikipedia", "Wikipedia", "新闻阅读", true),
        ProtectedApp("com.medium.reader", "Medium", "新闻阅读", true),
        ProtectedApp("com.devhd.feedly", "Feedly", "新闻阅读", true),
        ProtectedApp("com.nytimes.android", "The New York Times", "新闻阅读", true),
        ProtectedApp("bbc.mobile.news.ww", "BBC News", "新闻阅读", true),
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
