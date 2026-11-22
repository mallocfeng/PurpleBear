package com.mallocgfw.app.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.R
import com.mallocgfw.app.model.tr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks the phone's owner to allow a change requested over MCP: a dialog when
 * the app is in front, otherwise a high-priority notification. No answer in
 * 60 seconds counts as "deny". (MCP elicitation would ask the person at the
 * computer; here it is the phone's owner who must agree.)
 */
object ApprovalBroker : Approver {
    private const val CHANNEL_ID = "mallocgfw_mcp_approval"
    private const val TIMEOUT_MS = 60_000L
    private const val ALLOW_SIMILAR_MS = 10 * 60_000L

    enum class Answer { Allow, Deny, AllowSimilar }

    data class Request(
        val id: String,
        val notificationId: Int,
        val caller: String,
        val toolName: String,
        val toolTitle: String,
        val summary: String,
        val dangerous: Boolean,
    )

    private lateinit var appContext: Context
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Answer>>()
    private val allowSimilarUntil = ConcurrentHashMap<String, Long>()
    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    private var nextNotificationId = 7300

    /** Requests waiting for an answer, oldest first. The UI shows the first as a dialog. */
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    @Volatile
    private var uiInForeground = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Called by MainActivity on resume / pause. */
    fun setUiInForeground(foreground: Boolean) {
        uiInForeground = foreground
        if (!::appContext.isInitialized) return
        // Requests made while the dialog was showing still need a way to answer.
        if (!foreground) _pending.value.forEach(::notify)
        if (foreground) _pending.value.forEach { cancelNotification(it) }
    }

    override suspend fun approve(caller: Caller, tool: McpTool, summary: String): ApprovalDecision {
        val dangerous = tool.level == ToolLevel.Dangerous
        if (!dangerous && (allowSimilarUntil[tool.name] ?: 0L) > System.currentTimeMillis()) {
            return ApprovalDecision.Approved
        }
        val request = Request(
            id = UUID.randomUUID().toString(),
            notificationId = synchronized(this) { nextNotificationId++ },
            caller = caller.label(),
            toolName = tool.name,
            toolTitle = tool.title,
            summary = Redactor.text(summary).take(400),
            dangerous = dangerous,
        )
        val answer = CompletableDeferred<Answer>()
        waiters[request.id] = answer
        _pending.update { it + request }
        if (!uiInForeground) notify(request)
        val result = try {
            withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
        } finally {
            waiters.remove(request.id)
            _pending.update { list -> list.filterNot { it.id == request.id } }
            cancelNotification(request)
        }
        return when (result) {
            Answer.Allow -> ApprovalDecision.Approved
            Answer.AllowSimilar -> {
                if (!dangerous) allowSimilarUntil[tool.name] = System.currentTimeMillis() + ALLOW_SIMILAR_MS
                ApprovalDecision.Approved
            }
            Answer.Deny -> ApprovalDecision.Denied
            null -> ApprovalDecision.TimedOut
        }
    }

    fun answer(requestId: String, answer: Answer) {
        waiters[requestId]?.complete(answer)
    }

    /** Forget "allow similar" grants, e.g. when the server stops. */
    fun reset() {
        allowSimilarUntil.clear()
        waiters.values.forEach { it.complete(Answer.Deny) }
    }

    private fun notify(request: Request) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            appContext,
            request.notificationId,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(
                if (request.dangerous) {
                    tr("AI 请求高风险操作", "AI asks for a risky change", "ИИ просит рискованное действие")
                } else {
                    tr("AI 请求操作 PurpleBear", "AI asks to change PurpleBear", "ИИ просит изменить PurpleBear")
                },
            )
            .setContentText(request.summary)
            .setStyle(Notification.BigTextStyle().bigText(request.summary + "\n" + tr("来自：", "From: ", "Откуда: ") + request.caller))
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(open)
            .addAction(action(request, Answer.Allow, tr("允许", "Allow", "Разрешить")))
            .addAction(action(request, Answer.Deny, tr("拒绝", "Deny", "Отклонить")))
        if (!request.dangerous) {
            builder.addAction(action(request, Answer.AllowSimilar, tr("10 分钟内同类都允许", "Allow for 10 min", "Разрешать 10 мин")))
        }
        runCatching { manager.notify(request.notificationId, builder.build()) }
    }

    private fun action(request: Request, answer: Answer, label: String): Notification.Action {
        val intent = Intent(appContext, McpApprovalReceiver::class.java)
            .putExtra(McpApprovalReceiver.EXTRA_REQUEST, request.id)
            .putExtra(McpApprovalReceiver.EXTRA_ANSWER, answer.name)
        val pending = PendingIntent.getBroadcast(
            appContext,
            request.notificationId * 4 + answer.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun cancelNotification(request: Request) {
        if (!::appContext.isInitialized) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(request.notificationId)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                tr("AI 远程控制确认", "AI remote control approvals", "Подтверждения удалённого управления ИИ"),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}

/** Receives Allow / Deny taps on approval notifications. Not exported. */
class McpApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val answer = runCatching { ApprovalBroker.Answer.valueOf(intent.getStringExtra(EXTRA_ANSWER).orEmpty()) }
            .getOrNull() ?: return
        ApprovalBroker.answer(id, answer)
    }

    companion object {
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_ANSWER = "answer"
    }
}
