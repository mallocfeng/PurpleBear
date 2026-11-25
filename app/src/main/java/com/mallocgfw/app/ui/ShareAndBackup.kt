package com.mallocgfw.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mallocgfw.app.model.NodeShare
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextSecondary

/** A QR code holds about this much text and still scans well from a phone screen. */
private const val MAX_QR_CHARS = 1_200

/** Shares one node: QR code (when it fits), copy, or the system share sheet. */
@Composable
internal fun NodeShareDialog(server: ServerNode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val shared = remember(server.id, server.rawUri, server.outboundJson, server.mihomoProxyJson) { NodeShare.of(server) }
    ChoiceDialogFrame(
        icon = Icons.Rounded.QrCode2,
        title = uiText("分享节点", "Share node", "Поделиться узлом"),
        subtitle = server.name,
        note = uiText(
            "里面有这个节点的密码或密钥，只发给你信任的人。前置代理和备用节点是本机设置，不会一起分享。",
            "This holds the node's password or key: only send it to people you trust. Its pre-proxy and fallback are settings of this phone and aren't shared.",
            "Здесь пароль или ключ узла: отправляйте только тем, кому доверяете. Предварительный прокси и резервный узел — настройки этого телефона, они не передаются.",
        ),
        canSave = true,
        onDismiss = onDismiss,
        onSave = onDismiss,
        saveText = uiText("完成", "Done", "Готово"),
        dismissText = null,
    ) {
        if (shared == null) {
            Text(
                text = uiText("这个节点的配置无法读取，不能分享。", "This node's settings can't be read, so it can't be shared.", "Настройки узла не читаются, поделиться нельзя."),
                color = Error,
                fontSize = TypeScale.Body,
                lineHeight = TypeScale.BodyLine,
            )
            return@ChoiceDialogFrame
        }
        val qrMatrix = remember(shared.text) {
            if (shared.text.length > MAX_QR_CHARS) null else runCatching {
                QRCodeWriter().encode(shared.text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
            }.getOrNull()
        }
        if (qrMatrix != null) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                ) {
                    val cell = size.minDimension / qrMatrix.width
                    for (y in 0 until qrMatrix.height) {
                        for (x in 0 until qrMatrix.width) {
                            if (qrMatrix.get(x, y)) {
                                drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = when (shared.kind) {
                NodeShare.Kind.Link -> uiText("分享链接，其他客户端也能导入。", "A share link; other clients can import it too.", "Ссылка: её импортируют и другие клиенты.")
                NodeShare.Kind.ClashYaml -> uiText(
                    "这类节点没有通用的分享链接，以 Clash 配置片段分享（PurpleBear 和 Clash Meta 类客户端可以导入）。",
                    "This kind of node has no common share link; it is shared as a Clash config snippet (PurpleBear and Clash Meta clients can import it).",
                    "У таких узлов нет общей ссылки: передаётся фрагмент конфигурации Clash (его импортируют PurpleBear и клиенты Clash Meta).",
                )
                NodeShare.Kind.XrayJson -> uiText(
                    "这个节点的参数无法完整写成分享链接，以 Xray 配置分享（PurpleBear 和 v2rayN 类客户端可以导入）。",
                    "This node's settings don't fit a share link completely; it is shared as an Xray config (PurpleBear and v2rayN-style clients can import it).",
                    "Параметры узла не помещаются в ссылку полностью: передаётся конфигурация Xray (её импортируют PurpleBear и клиенты вроде v2rayN).",
                )
            } + if (qrMatrix == null) {
                uiText(" 内容较长，不生成二维码。", " It is too long for a QR code.", " Слишком длинно для QR-кода.")
            } else {
                ""
            },
            color = TextSecondary,
            fontSize = TypeScale.Meta,
            lineHeight = TypeScale.MetaLine,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = shared.text,
            color = TextSecondary,
            fontSize = TypeScale.Tiny,
            lineHeight = TypeScale.TinyLine,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceLow)
                .padding(10.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactOutlinedActionButton(
                text = uiText("复制", "Copy", "Копировать"),
                onClick = { copySensitiveText(context, shared.text) },
                modifier = Modifier.weight(1f),
            )
            CompactOutlinedActionButton(
                text = uiText("发送到…", "Send to…", "Отправить…"),
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared.text)
                    runCatching { context.startActivity(Intent.createChooser(send, server.name)) }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Marked sensitive, so Android 13+ doesn't show it in the clipboard preview. */
private fun copySensitiveText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("PurpleBear", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, tr("已复制", "Copied", "Скопировано"), Toast.LENGTH_SHORT).show()
    }
}

/** Before writing a backup: what it holds and that it isn't encrypted. */
@Composable
internal fun ExportBackupDialog(onDismiss: () -> Unit, onContinue: () -> Unit) {
    ChoiceDialogFrame(
        icon = Icons.Rounded.CloudUpload,
        title = uiText("导出配置", "Export settings", "Экспорт настроек"),
        subtitle = uiText(
            "把节点、订阅、规则、分应用和流媒体设置，以及各项偏好保存成一个文件。换手机或重装后可以用它恢复。",
            "Saves your nodes, subscriptions, rules, per-app and media routing and preferences to one file, to restore after changing phones or reinstalling.",
            "Сохраняет узлы, подписки, правила, маршрутизацию по приложениям и медиа и настройки в один файл для восстановления на новом телефоне или после переустановки.",
        ),
        note = uiText(
            "文件没有加密，里面有节点密码、订阅链接和 Tailscale 密钥。请放在只有你能访问的地方，不要发给别人。",
            "The file isn't encrypted and holds node passwords, subscription links and the Tailscale key. Keep it where only you can reach it, and don't send it to anyone.",
            "Файл не зашифрован и содержит пароли узлов, ссылки подписок и ключ Tailscale. Храните его там, где доступ есть только у вас, и никому не отправляйте.",
        ),
        canSave = true,
        onDismiss = onDismiss,
        onSave = onContinue,
        saveText = uiText("选择保存位置", "Choose where to save", "Выбрать место"),
    ) {}
}

/** Before a restore replaces everything. */
@Composable
internal fun RestoreBackupDialog(nodeCount: Int, subscriptionCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ChoiceDialogFrame(
        icon = Icons.Rounded.CloudDownload,
        title = uiText("恢复配置", "Restore settings", "Восстановление настроек"),
        subtitle = uiText(
            "备份里有 $nodeCount 个节点、$subscriptionCount 个订阅。",
            "The backup has $nodeCount nodes and $subscriptionCount subscriptions.",
            "В копии узлов: $nodeCount, подписок: $subscriptionCount.",
        ),
        note = uiText(
            "恢复会替换现在的全部节点、订阅、规则和设置，无法撤销。规则列表会在恢复后重新下载。",
            "Restoring replaces all current nodes, subscriptions, rules and settings, and can't be undone. Rule lists are downloaded again afterwards.",
            "Восстановление заменит все текущие узлы, подписки, правила и настройки, отменить это нельзя. Списки правил загрузятся заново.",
        ),
        canSave = true,
        onDismiss = onDismiss,
        onSave = onConfirm,
        saveText = uiText("替换并恢复", "Replace and restore", "Заменить и восстановить"),
    ) {}
}
