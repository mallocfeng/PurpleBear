package com.mallocgfw.app.ui

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

    LaunchedEffect(Unit) {
        progressStarted = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(LaunchGradientStart, Background),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = "跳过",
            color = TextSecondary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable(onClick = onSkip),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassBadge(
                icon = Icons.Rounded.Security,
                modifier = Modifier.size(108.dp),
                innerPadding = 28.dp,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = APP_BRAND_NAME,
                    fontSize = TypeScale.Hero,
                    lineHeight = TypeScale.HeroLine,
                    fontWeight = FontWeight.Bold,
                    color = BrandTitleColor,
                )
                Text(
                    text = APP_VERSION_BADGE,
                    color = BrandTitleColor,
                    fontSize = TypeScale.Hero,
                    lineHeight = TypeScale.HeroLine,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "安全、稳定、私密的 Xray 代理",
                color = TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(modifier = Modifier.height(48.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(ControlSurfaceStrongColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Secondary, PrimaryStrong),
                            ),
                        ),
                )
            }
            Text(
                text = "初始化安全隧道、订阅源与诊断模块…",
                color = Secondary,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        SmallPill(
            text = "AES-256 加密保护",
            icon = Icons.Rounded.VerifiedUser,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
internal fun OnboardingScreen(
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(LaunchGradientStart, Background),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = "跳过",
            color = TextSecondary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable(onClick = onSkip),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SurfaceCard(
                modifier = Modifier.fillMaxWidth(),
                background = Brush.verticalGradient(
                    listOf(SurfaceLow, SurfaceTone),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(ControlSurfaceColor.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(190.dp)
                            .height(126.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(ControlSurfaceStrongColor)
                            .border(1.dp, ControlSurfaceTrackColor, RoundedCornerShape(24.dp)),
                    )
                    FloatingTag(
                        text = "隐私至上",
                        icon = Icons.Rounded.Security,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 18.dp, top = 34.dp),
                    )
                    FloatingTag(
                        text = "简单易用",
                        icon = Icons.Rounded.Bolt,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 18.dp, bottom = 40.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "守护你的数字足迹",
                fontSize = TypeScale.PageTitle,
                lineHeight = TypeScale.PageTitleLine,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "一键开启加密隧道。",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
            Spacer(modifier = Modifier.height(32.dp))
            PrimaryActionButton(
                text = "下一步",
                onClick = onNext,
            )
        }
        SmallPill(
            text = "无日志 · 高速连接 · 主流节点",
            icon = Icons.Rounded.GppGood,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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

