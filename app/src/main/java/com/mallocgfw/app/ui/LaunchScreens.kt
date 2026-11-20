package com.mallocgfw.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.mallocgfw.app.R
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Success
import com.mallocgfw.app.ui.theme.Warning
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mallocgfw.app.ui.theme.Background
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.PrimaryStrong
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.Surface as SurfaceTone
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun LaunchScreen(onSkip: () -> Unit) {
    var progressStarted by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (progressStarted) 0.92f else 0.12f,
        animationSpec = tween(durationMillis = 1100),
        label = "launch-progress",
    )
    LaunchedEffect(Unit) { progressStarted = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(size = 96.dp)
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = APP_BRAND_NAME,
                fontSize = TypeScale.Hero,
                lineHeight = TypeScale.HeroLine,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Text(
                text = uiText("安全、稳定、私密的 Xray 代理"),
                color = TextSecondary,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.14f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Primary),
                )
            }
            Text(
                text = "v$APP_VERSION_BADGE",
                color = TextSecondary,
                fontSize = TypeScale.Tiny,
                lineHeight = TypeScale.TinyLine,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun BrandLogo(size: Dp) {
    val context = LocalContext.current
    val logo = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .size(size)
            .shadow(18.dp, RoundedCornerShape(size * 0.28f), ambientColor = Primary, spotColor = Primary)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Primary, PrimaryStrong))),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.9f),
            )
        } else {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

private class OnboardingPage(
    val title: String,
    val subtitle: String,
    val illustration: @Composable () -> Unit,
)

@Composable
internal fun OnboardingScreen(
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val pages = listOf(
        OnboardingPage("一键开启加密连接", "点一下即可连接，再点一下断开。") { ConnectIllustration() },
        OnboardingPage("节点轻松管理", "订阅、链接、二维码都能导入，自动测速。") { NodesIllustration() },
        OnboardingPage("智能分流", "国内网站直连，其余流量走代理。") { RoutingIllustration() },
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == pages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 20.dp),
        ) {
            if (!lastPage) {
                Text(
                    text = uiText("跳过"),
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(CircleShape)
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { index ->
            val page = pages[index]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(300.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(Primary.copy(alpha = 0.16f), Primary.copy(alpha = 0f)),
                                ),
                            ),
                    )
                    page.illustration()
                }
                Spacer(modifier = Modifier.height(36.dp))
                Text(
                    text = uiText(page.title),
                    fontSize = TypeScale.PageTitle,
                    lineHeight = TypeScale.PageTitleLine,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = uiText(page.subtitle),
                    color = TextSecondary,
                    fontSize = TypeScale.Body,
                    lineHeight = TypeScale.BodyLine,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PageDots(count = pages.size, current = pagerState.currentPage)
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryActionButton(
                text = if (lastPage) "开始使用" else "下一步",
                onClick = {
                    if (lastPage) {
                        onNext()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = index == current
            val width by animateDpAsState(if (active) 22.dp else 6.dp, label = "dot-width")
            Box(
                modifier = Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (active) Primary else TextSecondary.copy(alpha = 0.3f)),
            )
        }
    }
}

/** Small floating label used around the illustrations. */
@Composable
private fun IllustrationChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dotColor: Color? = null,
    tint: Color = Primary,
) {
    Row(
        modifier = modifier
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .clip(CircleShape)
            .background(SurfaceTone)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dotColor != null) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(
            text = uiText(text),
            color = TextPrimary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConnectIllustration() {
    val pulse = rememberInfiniteTransition(label = "connect-pulse")
    val ring by pulse.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "ring",
    )
    Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(250.dp).scale(ring).clip(CircleShape).background(Primary.copy(alpha = 0.07f)))
        Box(Modifier.size(190.dp).clip(CircleShape).background(Primary.copy(alpha = 0.10f)))
        Box(
            modifier = Modifier
                .size(124.dp)
                .shadow(24.dp, CircleShape, ambientColor = Primary, spotColor = Primary)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Primary, PrimaryStrong))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(54.dp),
            )
        }
        IllustrationChip(
            text = "已连接",
            dotColor = Success,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 30.dp),
        )
        IllustrationChip(
            text = "加密传输",
            icon = Icons.Rounded.Lock,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 0.dp, bottom = 36.dp),
        )
    }
}

@Composable
private fun NodesIllustration() {
    Box(modifier = Modifier.size(width = 300.dp, height = 280.dp), contentAlignment = Alignment.Center) {
        // Two cards peeking out below the front one read as a stack.
        Box(
            modifier = Modifier
                .offset(y = 26.dp)
                .width(200.dp)
                .height(170.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Primary.copy(alpha = 0.12f)),
        )
        Box(
            modifier = Modifier
                .offset(y = 13.dp)
                .width(226.dp)
                .height(172.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Primary.copy(alpha = 0.22f)),
        )
        Column(
            modifier = Modifier
                .width(250.dp)
                .shadow(20.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(22.dp))
                .background(SurfaceTone)
                .padding(vertical = 6.dp),
        ) {
            MockNodeRow("T", "Tokyo 01", "VLESS", 48, selected = true)
            MockDivider()
            MockNodeRow("S", "Singapore", "Trojan", 126, selected = false)
            MockDivider()
            MockNodeRow("L", "Los Angeles", "Hysteria2", 320, selected = false)
        }
        IllustrationChip(
            text = "扫码导入",
            icon = Icons.Rounded.QrCodeScanner,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp),
        )
        IllustrationChip(
            text = "自动测速",
            icon = Icons.Rounded.Speed,
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun MockDivider() {
    Box(
        modifier = Modifier
            .padding(start = 60.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(DividerColor),
    )
}

@Composable
private fun MockNodeRow(letter: String, name: String, protocol: String, latency: Int, selected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (selected) Primary else Primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = AccentContentColor, modifier = Modifier.size(18.dp))
            } else {
                Text(letter, color = Primary, fontWeight = FontWeight.Bold, fontSize = TypeScale.Meta)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            Text(protocol, color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine)
        }
        Text(
            text = "$latency ms",
            color = when {
                latency < 200 -> Success
                latency < 500 -> Warning
                else -> Error
            },
            fontWeight = FontWeight.SemiBold,
            fontSize = TypeScale.Tiny,
            lineHeight = TypeScale.TinyLine,
        )
    }
}

@Composable
private fun RoutingIllustration() {
    val directColor = Success
    val proxyColor = Primary
    val flow = rememberInfiniteTransition(label = "route-flow")
    val phase by flow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase",
    )
    Box(modifier = Modifier.size(width = 300.dp, height = 280.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val source = Offset(size.width / 2f, size.height - 62.dp.toPx())
            val left = Offset(size.width * 0.22f, 78.dp.toPx())
            val right = Offset(size.width * 0.78f, 78.dp.toPx())
            val dash = 10.dp.toPx()
            listOf(left to directColor, right to proxyColor).forEach { (target, color) ->
                val path = Path().apply {
                    moveTo(source.x, source.y)
                    cubicTo(source.x, source.y - 70.dp.toPx(), target.x, target.y + 80.dp.toPx(), target.x, target.y)
                }
                drawPath(path, color.copy(alpha = 0.18f), style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
                drawPath(
                    path,
                    color,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), -phase * dash * 2),
                    ),
                )
            }
        }
        RouteNode(
            icon = Icons.Rounded.Home,
            title = "直连",
            subtitle = "国内网站",
            color = directColor,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 30.dp),
        )
        RouteNode(
            icon = Icons.Rounded.Public,
            title = "代理",
            subtitle = "其他网站",
            color = proxyColor,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 30.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp)
                .size(64.dp)
                .shadow(18.dp, CircleShape, ambientColor = Primary, spotColor = Primary)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Primary, PrimaryStrong))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun RouteNode(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceTone)
            .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(uiText(title), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Meta, lineHeight = TypeScale.MetaLine)
            Text(uiText(subtitle), color = TextSecondary, fontSize = TypeScale.Tiny, lineHeight = TypeScale.TinyLine)
        }
    }
}

@Composable
internal fun PermissionScreen(
    padding: PaddingValues,
    onClose: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(
                title = "个人隧道",
                onBack = null,
                onAction = onClose,
                actionIcon = Icons.Rounded.Close,
            )
        }
        item {
            SurfaceCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassBadge(
                        icon = Icons.Rounded.VpnLock,
                        modifier = Modifier.size(86.dp),
                        innerPadding = 24.dp,
                        gradient = Brush.linearGradient(listOf(Primary, PrimaryStrong)),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        MiniIcon(icon = Icons.Rounded.Security)
                        Text("—", color = TextSecondary)
                        MiniIcon(icon = Icons.Rounded.VerifiedUser)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    ScreenHeader(
                        title = "建立安全隧道",
                        subtitle = "需要系统 VPN 权限来建立加密隧道。",
                        align = TextAlign.Center,
                    )
                }
            }
        }
        items(
            listOf(
                Triple("端到端加密", "公网传输前加密。", Icons.Rounded.Lock),
                Triple("零日志架构", "不记录访问历史、IP 或 DNS 查询内容。", Icons.Rounded.VisibilityOff),
                Triple("系统级集成", "授权后通过 Android VPN 工作。", Icons.Rounded.Security),
            ),
        ) { item ->
            InfoRow(title = item.first, subtitle = item.second, icon = item.third)
        }
    }
}
