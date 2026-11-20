package dev.sherry.wcs.features.items.chat

import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeDatabaseListenerApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageType
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.ContactsSelector
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.fs.KnownPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.io.path.div

/**
 * 自动回复。
 *
 * 不是独立功能，也不单开设置页：入口是「AI 回复」配置页的第一组，开关落 `ai_auto_reply_enabled`。
 * 监听器随 AI 回复功能一起注册/注销，因此这里没有 `onEnable` —— 它只是 AI 回复的一种触发方式。
 */
object AiAutoReply : WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AiAutoReply"
    private const val PREFIX = "ai_auto_reply_"
    private const val FRESH_WINDOW_MS = 30_000L
    private const val FUTURE_TOLERANCE_MS = 60_000L
    private const val VOICE_SEND_TIMEOUT_MS = 30_000L
    private const val LOG_RETENTION_DAYS = 3
    private const val LOG_LINES_FOR_PAGE = 200

    var enabled by prefOption("${PREFIX}enabled", false)
    var talkers by prefOption(PREFIX + "talkers", emptySet<String>())
    var groupEnabled by prefOption(PREFIX + "group_enabled", true)
    var groupAtOnly by prefOption(PREFIX + "group_at_only", false)
    var skipAnnounceAll by prefOption(PREFIX + "skip_announce_all", true)
    var voiceReply by prefOption(PREFIX + "voice_reply", false)

    private var debounceMs by prefOption(PREFIX + "debounce_ms", 3000L)
    private var maxWaitMs by prefOption(PREFIX + "max_wait_ms", 15000L)
    private var cooldownMs by prefOption(PREFIX + "cooldown_ms", 10000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var scheduler: AutoReplyScheduler? = null

    /** AI 回复功能开启时挂上；关闭时摘掉。重复调用安全。 */
    fun attach() {
        AutoReplyLedger.ensureLoaded()
        if (scheduler != null) return
        scheduler = AutoReplyScheduler(
            scope = scope,
            timing = {
                AutoReplyScheduler.Timing(
                    debounceMs = debounceMs.coerceIn(500L, 30_000L),
                    maxWaitMs = maxWaitMs.coerceIn(1000L, 120_000L),
                    cooldownMs = cooldownMs.coerceIn(0L, 120_000L),
                    // 攒批上限就是「参考上下文条数」：刷屏超过这个数只保留最新的
                    maxBatch = AiReplyCandidates.autoReplyBatchLimit(),
                )
            },
            handler = { batch -> handleBatch(batch) },
        )
        val account = WeApi.selfWxId
        if (account.isNotBlank()) scope.launch { AutoReplyLog.prune(account) }
        WeDatabaseListenerApi.addListener(this)
    }

    fun detach() {
        WeDatabaseListenerApi.removeListener(this)
        scheduler?.shutdown()
        scheduler = null
    }

    // ==================== 入站过滤（宿主 DB 线程，只做字段读取与集合判断） ====================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val account = WeApi.selfWxId
        if (account.isBlank()) return

        val isSend = values.getAsInteger("isSend") ?: return
        val talker = values.getAsString("talker") ?: return
        if (talker.isBlank()) return

        // isSend=1 有两种来源：本人手动发，或本模块刚发出去的那条。
        // 前者算人工接管（不该再插嘴），后者必须放过 —— 否则 AI 每发一条都会把
        // 同一会话里刚排上的下一批作废，表现为「只回一句就哑了」。
        if (isSend != 0) {
            if (enabled) {
                val echo = scheduler?.consumeSelfEcho(
                    account,
                    talker,
                    values.getAsString("content").orEmpty(),
                    values.getAsInteger("type") ?: MessageType.TEXT.code,
                ) == true
                if (!echo) scheduler?.onTakeover(talker, account)
            }
            return
        }

        if (!enabled) return
        if (talker !in talkers) return

        val typeCode = values.getAsInteger("type") ?: return
        if (!isSupportedType(typeCode)) return

        val createTime = values.getAsLong("createTime") ?: return
        val now = System.currentTimeMillis()
        if (now - createTime > FRESH_WINDOW_MS) return
        if (createTime - now > FUTURE_TOLERANCE_MS) return

        val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
        val msgId = values.getAsLong("msgId") ?: 0L
        val content = values.getAsString("content").orEmpty()
        val imagePath = values.getAsString("imgPath")?.takeIf { it.isNotBlank() }

        var isAtMe = false
        var isAnnounceAll = false
        if (talker.endsWith("@chatroom")) {
            if (!groupEnabled) return
            val atUsers = atUserList(content = values.getAsString("msgsource").orEmpty())
            isAnnounceAll = atUsers.contains(ANNOUNCE_ALL)
            isAtMe = account in atUsers
            if (skipAnnounceAll && isAnnounceAll) return
            if (groupAtOnly && !isAtMe) return
        }

        val svrKey = if (msgSvrId != 0L) "svr:$msgSvrId" else "local:$msgId"
        val key = "$account|$talker|$svrKey"
        if (!AutoReplyLedger.claim(key)) return
        if (msgSvrId != 0L && msgId != 0L) AutoReplyLedger.alias("$account|$talker|local:$msgId")

        val senderWxId = if (talker.endsWith("@chatroom")) content.substringBefore(":\n", "") else talker
        val senderLabel = when {
            talker.endsWith("@chatroom").not() -> "对方"
            senderWxId.isBlank() || senderWxId.contains(' ') -> "群友"
            senderWxId == account -> "我"
            else -> senderWxId
        }

        scheduler?.submit(
            AutoReplyScheduler.Inbound(
                account = account,
                talker = talker,
                msgId = msgId,
                msgSvrId = msgSvrId,
                createTime = createTime,
                typeCode = typeCode,
                content = content,
                imagePath = imagePath,
                senderWxId = senderWxId,
                senderLabel = senderLabel,
                isAtMe = isAtMe,
                isAnnounceAll = isAnnounceAll,
            ),
        )
    }

    private fun isSupportedType(typeCode: Int): Boolean {
        val type = MessageType.fromCode(typeCode) ?: return false
        return type.isText || type == MessageType.VOICE || AiReplyEngine.isVisual(type)
    }

    private fun kindOf(typeCode: Int): String = when (MessageType.fromCode(typeCode)) {
        MessageType.IMAGE -> "image"
        MessageType.VOICE -> "voice"
        MessageType.VIDEO, MessageType.MICRO_VIDEO -> "video"
        else -> if (MessageType.fromCode(typeCode)?.isSticker == true) "sticker" else "text"
    }

    /** `msgsource` 里的 `atuserlist` 是 CSV：`wxid_a,wxid_b` 或 `announcement@all`。 */
    private fun atUserList(content: String): String {
        if (content.isBlank()) return ""
        val match = Regex("""<atuserlist>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</atuserlist>""")
            .find(content)
        return match?.groupValues?.get(1).orEmpty()
    }

    // ==================== 批次处理 ====================

    private suspend fun handleBatch(batch: AutoReplyScheduler.Batch) {
        val account = WeApi.selfWxId
        if (!enabled) {
            AutoReplyLog.append(batch.account, Stage.SKIP, batch.talker, "disabled", "text")
            return
        }
        if (account != batch.account) {
            AutoReplyLog.append(batch.account, Stage.SKIP, batch.talker, "account_changed", "text")
            return
        }
        if (batch.talker.endsWith("@chatroom") && !groupEnabled) {
            AutoReplyLog.append(batch.account, Stage.SKIP, batch.talker, "group_off", "text")
            return
        }
        if (batch.talker !in talkers) {
            AutoReplyLog.append(batch.account, Stage.SKIP, batch.talker, "not_whitelisted", "text")
            return
        }

        val settings = AiReplyCandidates.autoReplyEngineSettings(batch.account)
        if (settings == null) {
            AutoReplyLog.append(
                batch.account, Stage.FAILED, batch.talker, "config_incomplete", "text",
            )
            return
        }
        val targets = batch.items.map { item ->
            AiReplyEngine.Target(
                msgId = item.msgId,
                msgSvrId = item.msgSvrId,
                talker = item.talker,
                senderWxId = item.senderWxId,
                senderLabel = item.senderLabel,
                isSend = false,
                typeCode = item.typeCode,
                createTime = item.createTime,
                content = item.content,
                imagePath = item.imagePath,
            )
        }

        if (batch.talker.endsWith("@chatroom")) {
            if (skipAnnounceAll && batch.items.any { it.isAnnounceAll }) {
                AutoReplyLog.append(batch.account, Stage.SKIP, batch.talker, "announce_all", "text")
                return
            }
            if (groupAtOnly && batch.items.none { it.isAtMe }) {
                AutoReplyLog.append(batch.account, Stage.SKIP, batch.talker, "at_required", "text")
                return
            }
        }

        if (batch.dropped > 0) {
            AutoReplyLog.append(
                batch.account, Stage.SKIP, batch.talker, "overflow:${batch.dropped}", "text",
            )
        }
        AutoReplyLog.append(
            batch.account, Stage.BUFFERED, batch.talker, "batch:${targets.size}",
            kindOf(batch.items.last().typeCode),
        )

        val started = System.currentTimeMillis()
        val result = runCatching {
            AiReplyEngine.generate(
                AiReplyEngine.Request(targets = targets, settings = settings),
                isStillValid = {
                    enabled && account == batch.account &&
                        batch.version == (scheduler?.versionFor(batch.account, batch.talker) ?: -1)
                },
            )
        }.getOrElse { failure ->
            if (failure is CancellationException) {
                // 被接管 / 关开关 / 退出打断。原来直接抛出去，日志里只剩一条 BUFFERED，
                // 事后分不清「没发出去」和「发出去了没记」。补一条再放行取消。
                AutoReplyLog.append(
                    batch.account, Stage.SKIP, batch.talker, "cancelled",
                    kindOf(batch.items.last().typeCode),
                )
                throw failure
            }
            WeLogger.w(TAG, "engine failed", failure)
            null
        }

        if (result == null || !result.ok || result.text.isNullOrBlank()) {
            val reason = result?.failures?.firstOrNull()?.reason ?: "cancelled"
            AutoReplyLog.append(
                batch.account, Stage.FAILED, batch.talker, reason,
                kindOf(batch.items.last().typeCode),
            )
            return
        }

        val text = result.text.trim()
        if (text.isEmpty()) {
            AutoReplyLog.append(batch.account, Stage.SKIP, batch.talker, "empty", "text")
            return
        }

        val cost = System.currentTimeMillis() - started

        // 一旦生成成功就必须发出去并记账。这段放进 NonCancellable：接管或关开关
        // 随时可能把协程掐断，被掐在发送与记账之间就会出现「微信里已经发出去了，
        // 日志还停在等待回复、账本也没记」。
        withContext(NonCancellable) {
            val voiceOk = withTimeoutOrNull(VOICE_SEND_TIMEOUT_MS) {
                maybeSendVoice(batch.account, batch.talker, text)
            } ?: false

            if (voiceOk) {
                scheduler?.markCooldown(batch.talker, batch.account)
                scheduler?.markSelfSent(batch.account, batch.talker, text, voice = true)
                AutoReplyLedger.record(
                    AutoReplyLedger.Task(
                        id = "${batch.account}|${batch.talker}|${System.currentTimeMillis()}",
                        account = batch.account,
                        talker = batch.talker,
                        batch = targets.map { if (it.msgSvrId != 0L) "s:${it.msgSvrId}" else "l:${it.msgId}" },
                        state = "QUEUE_ACCEPTED",
                        at = System.currentTimeMillis(),
                        chars = text.length,
                        model = settings.modelId,
                        note = "voice",
                    ),
                )
                AutoReplyLog.append(
                    batch.account, Stage.SENT, batch.talker, "voice", "voice",
                    chars = text.length, costMs = cost, model = settings.modelId,
                    profile = settings.profileName,
                )
                return@withContext
            }

            val sent = WeMessageApi.sendText(batch.talker, text, redactLog = true)
            if (sent) scheduler?.markSelfSent(batch.account, batch.talker, text)
            scheduler?.markCooldown(batch.talker, batch.account)
            AutoReplyLedger.record(
                AutoReplyLedger.Task(
                    id = "${batch.account}|${batch.talker}|${System.currentTimeMillis()}",
                    account = batch.account,
                    talker = batch.talker,
                    batch = targets.map { if (it.msgSvrId != 0L) "s:${it.msgSvrId}" else "l:${it.msgId}" },
                    state = if (sent) "QUEUE_ACCEPTED" else "FAILED",
                    at = System.currentTimeMillis(),
                    chars = text.length,
                    model = settings.modelId,
                    note = "",
                ),
            )
            AutoReplyLog.append(
                batch.account,
                if (sent) Stage.SENT else Stage.FAILED,
                batch.talker,
                if (sent) "" else "send_failed",
                "text",
                chars = text.length,
                costMs = cost,
                model = settings.modelId,
                profile = settings.profileName,
            )
        }
    }

    /**
     * 双开关与关系：`voice_reply` 且语音配置的「开启语音回复」都开才发语音。
     * 合成失败降级文字，不因为 TTS 挂了就不回。
     */
    private suspend fun maybeSendVoice(account: String, talker: String, text: String): Boolean {
        if (!voiceReply) return false
        val spec = AiReplyCandidates.autoReplyVoiceSpec() ?: return false
        val path = runCatching { synthesize(spec, text) }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            WeLogger.w(TAG, "tts failed: ${failure.message}")
            null
        } ?: return false
        val sent = suspendCancellableCoroutine<Boolean> { cont ->
            TextToSpeech.sendVoiceTo(talker, path) { ok -> if (cont.isActive) cont.resume(ok) }
        }
        if (!sent) WeLogger.w(TAG, "voice send failed, fallback to text")
        return sent
    }

    private suspend fun synthesize(spec: AutoReplyVoiceSpec, text: String): String? =
        suspendCancellableCoroutine<String?> { cont ->
            val callback: (String?, String) -> Unit = { path, error ->
                if (cont.isActive) {
                    if (path == null) WeLogger.w(TAG, "tts: $error")
                    cont.resume(path)
                }
            }
            when (spec.backend) {
                TextToSpeech.BACKEND_BREEZE -> TextToSpeech.generateVoiceBreeze(
                    text, spec.voiceId, spec.breezeInstructions,
                    key = spec.apiKey, model = spec.model, cb = callback,
                )

                TextToSpeech.BACKEND_DOUBAO -> TextToSpeech.generateVoiceDoubao(
                    text, spec.voiceId, cookie = spec.cookie, userAgent = spec.userAgent, cb = callback,
                )

                else -> TextToSpeech.generateVoice(
                    text, spec.voiceId, spec.emotion, key = spec.apiKey, cb = callback,
                )
            }
        }

    // ==================== 界面 ====================

    fun sessionsLabel(): String = if (talkers.isEmpty()) {
        localizedChatString(R.string.ai_auto_reply_sessions_none)
    } else {
        localizedChatString(R.string.ai_auto_reply_sessions_count, talkers.size)
    }

    fun rhythmLabel(): String {
        val seconds = (debounceMs / 1000).coerceAtLeast(1)
        return localizedChatString(R.string.ai_auto_reply_rhythm_summary, seconds)
    }

    fun openSessionsDialog(context: Context, onDone: () -> Unit = {}) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        showComposeDialog(context) {
            ContactsSelector(
                title = localizedChatString(R.string.ai_auto_reply_sessions),
                contacts = contacts,
                initialSelectedWxIds = talkers,
                onDismiss = onDismiss,
            ) { selected ->
                talkers = selected
                // 先回调再关窗：关窗会销毁这段 composition，之后再写状态就不触发重组了。
                onDone()
                onDismiss()
            }
        }
    }

    fun openRhythmDialog(context: Context, onDone: () -> Unit = {}) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.ai_auto_reply_rhythm)) },
                text = {
                    SegmentedColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.ai_auto_reply_rhythm_loose),
                                description = null,
                                onClick = {
                                    applyRhythm(5000L, 25000L, 30000L)
                                    onDone()
                                    onDismiss()
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.ai_auto_reply_rhythm_normal),
                                description = null,
                                onClick = {
                                    applyRhythm(3000L, 15000L, 10000L)
                                    onDone()
                                    onDismiss()
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.ai_auto_reply_rhythm_tight),
                                description = null,
                                onClick = {
                                    applyRhythm(1000L, 8000L, 5000L)
                                    onDone()
                                    onDismiss()
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }

    private fun applyRhythm(debounce: Long, maxWait: Long, cooldown: Long) {
        debounceMs = debounce
        maxWaitMs = maxWait
        cooldownMs = cooldown
    }

    fun openLogDialog(context: Context) {
        val account = WeApi.selfWxId
        showComposeDialog(context) {
            var filter by remember { mutableStateOf(0) }
            var entries by remember { mutableStateOf<List<AutoReplyLog.Entry>>(emptyList()) }
            var loading by remember { mutableStateOf(true) }
            var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

            LaunchedEffect(account) {
                withIo {
                    names = runCatching {
                        (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups())
                            .associate { it.wxId to it.displayName }
                    }.getOrDefault(emptyMap())
                    entries = AutoReplyLog.today(account, LOG_LINES_FOR_PAGE)
                }
                loading = false
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.ai_auto_reply_log_title)) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        val labels = listOf(
                            R.string.ai_auto_reply_log_filter_all,
                            R.string.ai_auto_reply_log_filter_sent,
                            R.string.ai_auto_reply_log_filter_skip,
                            R.string.ai_auto_reply_log_filter_failed,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            labels.forEachIndexed { index, res ->
                                TextButton(onClick = { filter = index }) {
                                    Text(
                                        stringResource(res),
                                        color = if (filter == index) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.ai_auto_reply_log_retention),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        val shown = entries.filter {
                            when (filter) {
                                1 -> it.stage == Stage.SENT
                                2 -> it.stage == Stage.SKIP
                                3 -> it.stage == Stage.FAILED
                                else -> true
                            }
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (loading) {
                                Text(stringResource(R.string.ai_auto_reply_log_loading))
                            } else if (shown.isEmpty()) {
                                Text(stringResource(R.string.ai_auto_reply_log_empty))
                            } else {
                                shown.forEach { entry ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = timeOf(entry.t),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Column(Modifier.padding(start = 8.dp)) {
                                            Text(
                                                text = names[entry.talker] ?: entry.talker,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = descriptionOf(entry),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            AutoReplyLog.clear(account)
                            entries = emptyList()
                        },
                    ) { Text(stringResource(R.string.ai_auto_reply_log_clear)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun timeOf(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun descriptionOf(entry: AutoReplyLog.Entry): String {
        val stage = when (entry.stage) {
            Stage.SENT -> localizedChatString(R.string.ai_auto_reply_stage_sent)
            Stage.SKIP -> localizedChatString(R.string.ai_auto_reply_stage_skip)
            Stage.FAILED -> localizedChatString(R.string.ai_auto_reply_stage_failed)
            Stage.RECEIVED -> localizedChatString(R.string.ai_auto_reply_stage_received)
            else -> localizedChatString(R.string.ai_auto_reply_stage_buffered)
        }
        val detail = when {
            entry.reason.isBlank() -> ""
            entry.reason.startsWith("batch:") ->
                localizedChatString(R.string.ai_auto_reply_detail_batch, entry.reason.removePrefix("batch:"))

            entry.reason.startsWith("overflow:") ->
                localizedChatString(R.string.ai_auto_reply_detail_overflow, entry.reason.removePrefix("overflow:"))

            else -> reasonLabel(entry.reason)
        }
        return if (detail.isBlank()) stage else "$stage · $detail"
    }

    private fun reasonLabel(reason: String): String = when (reason) {
        "cooldown" -> localizedChatString(R.string.ai_auto_reply_reason_cooldown)
        "announce_all" -> localizedChatString(R.string.ai_auto_reply_reason_announce_all)
        "at_required" -> localizedChatString(R.string.ai_auto_reply_reason_at_required)
        "asr_failed" -> localizedChatString(R.string.ai_auto_reply_reason_asr_failed)
        "finish_length" -> localizedChatString(R.string.ai_auto_reply_reason_finish_length)
        "config_incomplete" -> localizedChatString(R.string.ai_auto_reply_reason_config)
        "not_whitelisted" -> localizedChatString(R.string.ai_auto_reply_reason_not_whitelisted)
        "send_failed" -> localizedChatString(R.string.ai_auto_reply_reason_send_failed)
        else -> reason
    }

    /** 日志页读盘不能占主线程。 */
    private suspend fun withIo(block: () -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    fun todayCounts(account: String): Pair<Int, Int> {
        val entries = AutoReplyLog.today(account, 500)
        // overflow 那行是「丢了多少条」的注释，不是一次回复尝试，不计入条数
        val attempts = entries.count { !it.reason.startsWith("overflow:") }
        return attempts to entries.count { it.stage == Stage.SENT }
    }

    object Stage {
        const val RECEIVED = "RECEIVED"
        const val BUFFERED = "BUFFERED"
        const val SENT = "SENT"
        const val SKIP = "SKIP"
        const val FAILED = "FAILED"
    }

    internal const val ANNOUNCE_ALL = "announcement@all"
}

/** 语音合成所需的全部参数，由 AI 回复配置页快照出来。 */
internal data class AutoReplyVoiceSpec(
    val backend: Int,
    val voiceId: String,
    val emotion: FloatArray,
    val breezeInstructions: String,
    val apiKey: String,
    val cookie: String,
    val model: String,
    val userAgent: String = "",
)

/**
 * 自动回复的独立日志。
 *
 * `moduleCache/auto-reply/<账号>/<会话>/yyyy-MM-dd.log`，一行一条 JSON，只记结论。
 * 与账本 `moduleData/auto_reply/state.json` 是两回事：账本要可靠，日志给人看。
 */
internal object AutoReplyLog {

    private const val TAG = "AutoReplyLog"
    private const val RETENTION_DAYS = 3

    data class Entry(
        val t: Long,
        val talker: String,
        val stage: String,
        val reason: String,
        val kind: String,
        val chars: Int,
        val costMs: Long,
        val model: String,
        val profile: String,
    )

    private fun dir(account: String): File =
        (KnownPaths.moduleCache / "auto-reply" / sanitize(account)).toFile()

    /** 一个白名单会话一个子目录：`auto-reply/<账号>/<会话>/yyyy-MM-dd.log`。 */
    private fun sessionDir(account: String, talker: String): File =
        File(dir(account), sanitize(talker))

    /** 目录名要能落在文件系统上：wxid 本身安全，但群 id 与备注名可能带分隔符。 */
    private fun sanitize(raw: String): String = raw.trim()
        .map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '@' || ch == '.') ch else '_'
        }
        .joinToString("")
        .take(64)
        .ifBlank { "unknown" }

    private fun dateName(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    /** 日志文件名 `yyyy-MM-dd.log`。写入与读取必须共用这一个函数，避免两边拼得不一样。 */
    private fun logName(at: Long): String = dateName(at) + ".log"

    private fun file(
        account: String,
        talker: String,
        at: Long = System.currentTimeMillis(),
    ): File = File(sessionDir(account, talker), logName(at))

    fun append(
        account: String,
        stage: String,
        talker: String,
        reason: String,
        kind: String,
        chars: Int = 0,
        costMs: Long = 0,
        model: String = "",
        profile: String = "",
    ) {
        runCatching {
            val target = file(account, talker)
            target.parentFile?.mkdirs()
            val line = buildString {
                append('{')
                append("\"t\":").append(System.currentTimeMillis())
                append(",\"talker\":\"").append(AiReplyEngine.jsonEscape(talker)).append('"')
                append(",\"stage\":\"").append(stage).append('"')
                append(",\"reason\":\"").append(AiReplyEngine.jsonEscape(reason)).append('"')
                append(",\"kind\":\"").append(kind).append('"')
                if (chars > 0) append(",\"chars\":").append(chars)
                if (costMs > 0) append(",\"costMs\":").append(costMs)
                if (model.isNotBlank()) append(",\"model\":\"").append(AiReplyEngine.jsonEscape(model)).append('"')
                if (profile.isNotBlank()) append(",\"profile\":\"").append(AiReplyEngine.jsonEscape(profile)).append('"')
                append('}')
            }
            target.appendText(line + "\n")
        }.onFailure { WeLogger.w(TAG, "append failed", it) }
    }

    /**
     * 当天全部会话的日志，跨文件按时间倒序合并。
     *
     * 分文件之后不能再只读一个路径：账号目录下的旧文件是升级前的结构，一并读进来，
     * 免得刚升级当天界面上突然空了。
     */
    fun today(account: String, limit: Int): List<Entry> {
        val folder = dir(account)
        // 带后缀：这里曾漏掉 `.log`，`File(child, "2026-11-20")` 永远 isFile=false，
        // 于是文件在盘上、界面却是 0 条。文件名统一由 [logName] 生成，别再手拼。
        val name = logName(System.currentTimeMillis())
        val files = ArrayList<File>(4)
        File(folder, name).takeIf { it.isFile }?.let { files.add(it) }
        folder.listFiles()?.forEach { child ->
            if (child.isDirectory) File(child, name).takeIf { it.isFile }?.let { files.add(it) }
        }
        if (files.isEmpty()) return emptyList()
        return runCatching {
            files.asSequence()
                // 每个文件只取最后 limit 行：合并后总共也只留 limit 条，
                // 单个文件里更早的行无论如何都会被淘汰，没必要解析。
                .flatMap { it.readLines().asReversed().take(limit).asSequence() }
                .mapNotNull { parse(it) }
                .sortedByDescending { it.t }
                .take(limit)
                .toList()
        }.getOrDefault(emptyList())
    }

    fun prune(account: String) {
        val folder = dir(account)
        if (!folder.isDirectory) return
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600 * 1000
        folder.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".log") && it.lastModified() < cutoff }
            .forEach { runCatching { it.delete() } }
        // 顺手收掉空掉的会话目录，免得白名单改来改去在盘上留一堆空壳
        folder.walkBottomUp()
            .filter { it.isDirectory && it != folder && it.listFiles().isNullOrEmpty() }
            .forEach { runCatching { it.delete() } }
    }

    fun clear(account: String) {
        dir(account).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun parse(line: String): Entry? = runCatching {
        val obj = org.json.JSONObject(line)
        Entry(
            t = obj.optLong("t"),
            talker = obj.optString("talker"),
            stage = obj.optString("stage"),
            reason = obj.optString("reason"),
            kind = obj.optString("kind"),
            chars = obj.optInt("chars"),
            costMs = obj.optLong("costMs"),
            model = obj.optString("model"),
            profile = obj.optString("profile"),
        )
    }.getOrNull()
}
