package dev.sherry.wcs.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.content.ContentValues
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.sherry.wcs.R
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.fs.KnownPaths
import dev.sherry.wcs.utils.fs.createDirsSafe
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeDatabaseListenerApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageType
import dev.sherry.wcs.features.api.net.models.protobuf.ChatRoomDataProto
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.ContactsSelector
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseItemContainer
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.IntNumberPickerWidget
import dev.sherry.wcs.ui.content.m3.RadioButtonWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SegmentedColumnScope
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.reflection.BString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

/**
 * 群成员行为监控：监听 chatroom 行的写入，比对新旧成员表与 roomdata，
 * 可选地在会话里插入一条仅本机可见的系统提示，或以用户身份自动发送一条回复。
 *
 * 「生效群」名单由提示与回复共用；回复默认全部关闭，因为它是唯一会对外发送内容的行为。
 */
object MonitorGroupMemberOperations : ClickableFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "群成员行为监控"
    override val nameRes = R.string.feature_monitor_group_member_operations_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_monitor_group_member_operations_description

    // ==================== 偏好 ====================

    private var scopeAll by WePrefs.prefOption(KEY_SCOPE_ALL, true)
    private var scopeGroups by WePrefs.prefOption(KEY_SCOPE_GROUPS, emptySet())
    private var noticeLeave by WePrefs.prefOption(KEY_NOTICE_LEAVE, true)
    private var noticeJoin by WePrefs.prefOption(KEY_NOTICE_JOIN, false)
    private var noticeRename by WePrefs.prefOption(KEY_NOTICE_RENAME, true)
    private var replyEnable by WePrefs.prefOption(KEY_REPLY_ENABLE, false)
    private var replyJoin by WePrefs.prefOption(KEY_REPLY_JOIN, false)
    private var replyLeave by WePrefs.prefOption(KEY_REPLY_LEAVE, false)
    private var replyRename by WePrefs.prefOption(KEY_REPLY_RENAME, false)
    private var replyJoinText by WePrefs.prefOption(KEY_REPLY_JOIN_TEXT, "")
    private var replyLeaveText by WePrefs.prefOption(KEY_REPLY_LEAVE_TEXT, "")
    private var replyRenameText by WePrefs.prefOption(KEY_REPLY_RENAME_TEXT, "")
    private var replyPromptType by WePrefs.prefOption(KEY_REPLY_PROMPT_TYPE, PROMPT_TEXT)
    private var replyBothOrder by WePrefs.prefOption(KEY_REPLY_BOTH_ORDER, ORDER_TEXT_FIRST)
    private var replyJoinCardTitle by WePrefs.prefOption(KEY_REPLY_JOIN_CARD_TITLE, "")
    private var replyJoinCardDesc by WePrefs.prefOption(KEY_REPLY_JOIN_CARD_DESC, "")
    private var replyLeaveCardTitle by WePrefs.prefOption(KEY_REPLY_LEAVE_CARD_TITLE, "")
    private var replyLeaveCardDesc by WePrefs.prefOption(KEY_REPLY_LEAVE_CARD_DESC, "")
    private var replyRenameCardTitle by WePrefs.prefOption(KEY_REPLY_RENAME_CARD_TITLE, "")
    private var replyRenameCardDesc by WePrefs.prefOption(KEY_REPLY_RENAME_CARD_DESC, "")
    private var replyDelaySeconds by WePrefs.prefOption(KEY_REPLY_DELAY, 0)
    private var replyVoiceOrder by WePrefs.prefOption(KEY_REPLY_VOICE_ORDER, VOICE_NONE)
    private var replyJoinVoice by WePrefs.prefOption(KEY_REPLY_JOIN_VOICE, "")
    private var replyLeaveVoice by WePrefs.prefOption(KEY_REPLY_LEAVE_VOICE, "")
    private var replyRenameVoice by WePrefs.prefOption(KEY_REPLY_RENAME_VOICE, "")

    // ==================== 生命周期 ====================

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        replyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        methodHandleSpanClick.hookBefore {
            val url = args[1]!!.reflekt().firstField {
                type = BString
                modifiers(Modifiers.FINAL)
            }.get()!! as String
            if (!url.startsWith("weixin://weixinhongbao/wcs/chatroom_userinfo/")) return@hookBefore

            val wxId = url.substringAfterLast('/')
            val context = (args[0] as View).context

            WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        replyScope.cancel()
    }

    private val methodHandleSpanClick by dexMethod {
        matcher {
            declaredClass = $$"com.tencent.mm.app.plugin.URISpanHandlerSet$LuckyMoneyUriSpanHandler"
            usingEqStrings("MicroMsg.URISpanHandlerSet", "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null")
        }
    }

    // ==================== 事件处理 ====================

    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "chatroom") return

        val group = values.getAsString("chatroomname") ?: return
        if (!isGroupMonitored(group)) return

        val newRawMembers = values.getAsString("memberlist")
        val newRoomData = values.getAsByteArray("roomdata")

        // 本监听挂在 updateWithOnConflict 之前，所以这里读到的仍是更新前的行
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist,roomdata FROM chatroom WHERE chatroomname = ?",
            arrayOf(group)
        )

        runCatching {
            cursor.use { cursor ->
                if (!cursor.moveToFirst()) return

                val origRawMembers = cursor.getString(cursor.getColumnIndex("memberlist"))
                if (origRawMembers.isNullOrEmpty()) return
                val origMembers = origRawMembers.split(';').filter { it.isNotEmpty() }

                // 群昵称（群备注）以 wxId -> displayName 的形式存于 roomdata protobuf 中，
                // displayname 列只是服务端截断后的预览（最多 4 个名字），无法可靠映射到成员。
                val origDisplayNames = parseRoomData(cursor.getBlob(cursor.getColumnIndex("roomdata")))
                val newDisplayNames = if (newRoomData == null) null else parseRoomData(newRoomData)

                handleMemberChanges(group, origMembers, origDisplayNames, newRawMembers, newDisplayNames.orEmpty())
                handleDisplayNameChange(group, origDisplayNames, newDisplayNames)
            }
        }.onFailure { WeLogger.e(TAG, "failed to handle group member operations", it) }
    }

    private fun handleMemberChanges(
        group: String,
        origMembers: List<String>,
        origDisplayNames: Map<String, String>,
        newRawMembers: String?,
        newDisplayNames: Map<String, String>,
    ) {
        if (newRawMembers.isNullOrEmpty()) return
        val newMembers = newRawMembers.split(';').filter { it.isNotEmpty() }.toSet()
        if (newMembers.isEmpty()) return

        val origSet = origMembers.toSet()
        val left = origSet - newMembers
        val joined = newMembers - origSet
        if (left.isEmpty() && joined.isEmpty()) return
        // 登录同步时微信会整表重写成员：单侧大批量、另一侧一个没有，按噪声丢弃
        if (joined.size >= BULK_MEMBER_THRESHOLD && left.isEmpty()) return
        if (left.size >= BULK_MEMBER_THRESHOLD && joined.isEmpty()) return

        for (wxId in left) {
            if (!claimEvent("$group|$wxId|left")) continue
            val groupNickname = origDisplayNames[wxId].orEmpty()
            if (noticeLeave) {
                insertNotice(group, wxId, memberLabel(wxId, groupNickname), localizedChatString(R.string.chat_group_member_left))
            }
            queueReply(MemberEvent.LEAVE, group, wxId) {
                ReplyVars(group = group, wxId = wxId, groupNickname = groupNickname)
            }
        }

        for (wxId in joined) {
            if (!claimEvent("$group|$wxId|join")) continue
            val groupNickname = newDisplayNames[wxId].orEmpty()
            if (noticeJoin) {
                insertNotice(group, wxId, memberLabel(wxId, groupNickname), localizedChatString(R.string.chat_group_member_joined))
            }
            queueReply(MemberEvent.JOIN, group, wxId) {
                ReplyVars(group = group, wxId = wxId, groupNickname = groupNickname)
            }
        }
    }

    private fun handleDisplayNameChange(
        group: String,
        origDisplayNames: Map<String, String>,
        newDisplayNames: Map<String, String>?,
    ) {
        if (newDisplayNames == null) return // 本次更新未改动 roomdata
        if (origDisplayNames.isEmpty() || newDisplayNames.isEmpty()) return

        val renames = newDisplayNames.filter { (wxId, name) ->
            origDisplayNames.containsKey(wxId) && origDisplayNames[wxId] != name
        }
        // 整表刷新时所有人都会「改名」，按噪声丢弃
        if (renames.size >= BULK_MEMBER_THRESHOLD) return

        renames.forEach { (wxId, newName) ->
            val oldName = origDisplayNames.getValue(wxId)
            if (!claimEvent("$group|$wxId|rename|$oldName|$newName")) return@forEach

            if (noticeRename) {
                val oldShow = oldName.ifEmpty { localizedChatString(R.string.chat_group_member_no_nickname) }
                val newShow = newName.ifEmpty { localizedChatString(R.string.chat_group_member_no_nickname) }
                insertNotice(
                    group,
                    wxId,
                    memberLabel(wxId, newName),
                    localizedChatString(R.string.chat_group_member_nickname_changed, oldShow, newShow),
                )
            }
            queueReply(MemberEvent.RENAME, group, wxId) {
                ReplyVars(group = group, wxId = wxId, groupNickname = newName, oldGroupNickname = oldName, newGroupNickname = newName)
            }
        }
    }

    /** 会话内提示：只写本机数据库，不会发给任何人。 */
    private fun insertNotice(group: String, wxId: String, label: String, action: String) {
        val href = "weixin://weixinhongbao/wcs/chatroom_userinfo/$wxId"
        WeMessageApi.createSimpleMsgInfoAndInsert(
            type = MessageType.SYSTEM.code,
            talker = group,
            content = """<_wc_custom_link_ color="#28C445" href="$href">$label</_wc_custom_link_> $action""",
            currentTime = System.currentTimeMillis(),
        )
    }

    private fun memberLabel(wxId: String, groupNickname: String): String {
        val displayName = groupNickname.ifEmpty { WeDatabaseApi.getDisplayName(wxId) }
        return if (displayName.isNotEmpty()) "$displayName ($wxId)" else wxId
    }

    // ==================== 自动回复 ====================

    @Volatile
    private var replyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private enum class MemberEvent { JOIN, LEAVE, RENAME }

    private class ReplyTemplate(
        val text: String,
        val cardTitle: String,
        val cardDesc: String,
    )

    /**
     * 变量在延时之后再解析：刚进群的人通讯录往往还没同步下来，早取会拿到空名字。
     */
    private fun queueReply(
        event: MemberEvent,
        group: String,
        wxId: String,
        vars: () -> ReplyVars,
    ) {
        if (!replyEnable) return
        val template = templateFor(event) ?: return
        if (template.text.isBlank() && template.cardTitle.isBlank() && template.cardDesc.isBlank()) return

        replyScope.launch {
            if (replyDelaySeconds > 0) delay(replyDelaySeconds * 1000L)
            deliver(event, group, wxId, vars(), template)
        }
    }

    /** 未自定义的字段回落到当前语言的默认模板；该类回复没开时返回 null。 */
    private fun templateFor(event: MemberEvent): ReplyTemplate? = when (event) {
        MemberEvent.JOIN -> if (!replyJoin) null else ReplyTemplate(
            text = replyJoinText.ifBlank { localizedChatString(R.string.chat_group_monitor_default_join_text) },
            cardTitle = replyJoinCardTitle.ifBlank { localizedChatString(R.string.chat_group_monitor_default_join_card_title) },
            cardDesc = replyJoinCardDesc.ifBlank { localizedChatString(R.string.chat_group_monitor_default_join_card_desc) },
        )
        MemberEvent.LEAVE -> if (!replyLeave) null else ReplyTemplate(
            text = replyLeaveText.ifBlank { localizedChatString(R.string.chat_group_monitor_default_leave_text) },
            cardTitle = replyLeaveCardTitle.ifBlank { localizedChatString(R.string.chat_group_monitor_default_leave_card_title) },
            cardDesc = replyLeaveCardDesc.ifBlank { localizedChatString(R.string.chat_group_monitor_default_leave_card_desc) },
        )
        MemberEvent.RENAME -> if (!replyRename) null else ReplyTemplate(
            text = replyRenameText.ifBlank { localizedChatString(R.string.chat_group_monitor_default_rename_text) },
            cardTitle = replyRenameCardTitle.ifBlank { localizedChatString(R.string.chat_group_monitor_default_rename_card_title) },
            cardDesc = replyRenameCardDesc.ifBlank { localizedChatString(R.string.chat_group_monitor_default_rename_card_desc) },
        )
    }

    private suspend fun deliver(
        event: MemberEvent,
        group: String,
        wxId: String,
        vars: ReplyVars,
        template: ReplyTemplate,
    ) {
        val voicePath = voicePathFor(event)
        val parts = ArrayList<String>(3)
        if (voicePath.isNotEmpty() && replyVoiceOrder == VOICE_BEFORE) parts += PART_VOICE
        when (replyPromptType) {
            PROMPT_CARD -> parts += PART_CARD
            PROMPT_BOTH -> if (replyBothOrder == ORDER_CARD_FIRST) {
                parts += listOf(PART_CARD, PART_TEXT)
            } else {
                parts += listOf(PART_TEXT, PART_CARD)
            }
            else -> parts += PART_TEXT
        }
        if (voicePath.isNotEmpty() && replyVoiceOrder == VOICE_AFTER) parts += PART_VOICE
        for (part in parts) {
            val ok = when (part) {
                PART_CARD -> sendCardReply(group, wxId, vars, template.cardTitle, template.cardDesc)
                PART_VOICE -> sendVoiceReply(group, voicePath)
                else -> sendTextReply(group, wxId, vars, template.text)
            }
            if (!ok) WeLogger.w(TAG, "auto reply failed: $group/$wxId/$event/$part")
            if (parts.size > 1) delay(CARD_TEXT_GAP_MS)
        }
    }

    private fun sendTextReply(group: String, wxId: String, vars: ReplyVars, template: String): Boolean {
        val text = vars.render(template).trim()
        if (text.isEmpty()) return false
        return WeMessageApi.sendText(group, text)
    }

    /** 卡片是网页分享伪装：缩略图取对方头像，头像拿不到也照发，只是没图。 */
    private fun sendCardReply(group: String, wxId: String, vars: ReplyVars, titleTemplate: String, descTemplate: String): Boolean {
        val title = vars.render(titleTemplate).trim()
        val desc = vars.render(descTemplate).trim()
        if (title.isEmpty() && desc.isEmpty()) return false
        val avatarUrl = WeDatabaseApi.getAvatarUrl(wxId)
        return WeMessageApi.shareWebpage(group, title, desc, avatarUrl, avatarBytes(avatarUrl))
    }

    private fun voicePathFor(event: MemberEvent): String = when (event) {
        MemberEvent.JOIN -> replyJoinVoice
        MemberEvent.LEAVE -> replyLeaveVoice
        MemberEvent.RENAME -> replyRenameVoice
    }

    /** 任何音频先转 silk 再发，和文字转语音走的是同一条通路。 */
    private fun sendVoiceReply(group: String, path: String): Boolean {
        if (path.isEmpty() || !File(path).exists()) return false
        val silkPath = path.substringBeforeLast('.') + ".silk"
        if (!AudioUtils.anyToSilk(path, silkPath)) return false
        return WeMessageApi.sendVoice(group, silkPath, AudioUtils.getDurationMs(silkPath).toInt())
    }

    /** 选中的音频复制进模块资源目录：content:// 地址不能直接交给宿主。 */
    private fun importVoice(context: Context, uri: Uri): String? = runCatching {
        val dir = (KnownPaths.moduleAssets / "group_monitor_voice").createDirsSafe()
        val dest = dir.resolve(displayNameOf(context, uri))
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: return null
        dest.absolutePathString()
    }.onFailure { WeLogger.e(TAG, "failed to import voice", it) }.getOrNull()

    private fun displayNameOf(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (name != null && name.isNotBlank()) return File(name).name
        return "voice-${System.currentTimeMillis()}.mp3"
    }

    private fun avatarBytes(url: String): ByteArray? {
        if (url.isEmpty()) return null
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = AVATAR_TIMEOUT_MS
                readTimeout = AVATAR_TIMEOUT_MS
            }
            connection.inputStream.use { it.readBytes() }
        }.getOrNull()
    }

    private class ReplyVars(
        val group: String,
        val wxId: String,
        val groupNickname: String,
        val oldGroupNickname: String = "",
        val newGroupNickname: String = "",
    ) {
        fun render(template: String): String {
            val userName = WeDatabaseApi.getDisplayName(wxId)
            val nickname = groupNickname.ifEmpty { userName }
            return template
                .replace("{userName}", userName.ifEmpty { wxId })
                .replace("{userWxid}", wxId)
                .replace("{groupName}", WeDatabaseApi.getDisplayName(group))
                .replace("{oldGroupNickname}", oldGroupNickname.ifEmpty { nickname })
                .replace("{newGroupNickname}", newGroupNickname.ifEmpty { nickname })
                .replace("{groupNickname}", nickname)
                .replace("{time}", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }
    }

    // ==================== 范围与去重 ====================

    private fun isGroupMonitored(group: String): Boolean = scopeAll || group in scopeGroups

    private val recentEvents = ConcurrentHashMap<String, Long>()

    /** 同一次成员写入可能被数据库重试，5 秒内同一事件只处理一次。 */
    private fun claimEvent(key: String): Boolean {
        val now = System.currentTimeMillis()
        recentEvents.entries.removeIf { now - it.value > EVENT_DEDUP_MS }
        val previous = recentEvents[key]
        if (previous != null && now - previous < EVENT_DEDUP_MS) return false
        recentEvents[key] = now
        return true
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseRoomData(blob: ByteArray?): Map<String, String> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        return runCatching {
            ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                .members.associate { it.wxId to it.displayName }
        }.getOrElse { emptyMap() }
    }

    // ==================== 设置界面 ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            MemberMonitorSettings(context = context, onDismiss = onDismiss)
        }
    }

    @Composable
    private fun MemberMonitorSettings(context: ComponentActivity, onDismiss: () -> Unit) {
        var allGroups by remember { mutableStateOf(scopeAll) }
        var groups by remember { mutableStateOf(scopeGroups) }
        var leaveNotice by remember { mutableStateOf(noticeLeave) }
        var joinNotice by remember { mutableStateOf(noticeJoin) }
        var renameNotice by remember { mutableStateOf(noticeRename) }
        var replyOn by remember { mutableStateOf(replyEnable) }
        var replyJoinOn by remember { mutableStateOf(replyJoin) }
        var replyLeaveOn by remember { mutableStateOf(replyLeave) }
        var replyRenameOn by remember { mutableStateOf(replyRename) }
        var joinText by remember { mutableStateOf(replyJoinText.ifBlank { localizedChatString(R.string.chat_group_monitor_default_join_text) }) }
        var leaveText by remember { mutableStateOf(replyLeaveText.ifBlank { localizedChatString(R.string.chat_group_monitor_default_leave_text) }) }
        var renameText by remember { mutableStateOf(replyRenameText.ifBlank { localizedChatString(R.string.chat_group_monitor_default_rename_text) }) }
        var promptType by remember { mutableIntStateOf(replyPromptType) }
        var bothOrder by remember { mutableIntStateOf(replyBothOrder) }
        var joinCardTitle by remember { mutableStateOf(replyJoinCardTitle.ifBlank { localizedChatString(R.string.chat_group_monitor_default_join_card_title) }) }
        var joinCardDesc by remember { mutableStateOf(replyJoinCardDesc.ifBlank { localizedChatString(R.string.chat_group_monitor_default_join_card_desc) }) }
        var leaveCardTitle by remember { mutableStateOf(replyLeaveCardTitle.ifBlank { localizedChatString(R.string.chat_group_monitor_default_leave_card_title) }) }
        var leaveCardDesc by remember { mutableStateOf(replyLeaveCardDesc.ifBlank { localizedChatString(R.string.chat_group_monitor_default_leave_card_desc) }) }
        var renameCardTitle by remember { mutableStateOf(replyRenameCardTitle.ifBlank { localizedChatString(R.string.chat_group_monitor_default_rename_card_title) }) }
        var renameCardDesc by remember { mutableStateOf(replyRenameCardDesc.ifBlank { localizedChatString(R.string.chat_group_monitor_default_rename_card_desc) }) }
        var delaySeconds by remember { mutableIntStateOf(replyDelaySeconds) }
        var voiceOrder by remember { mutableIntStateOf(replyVoiceOrder) }
        var joinVoice by remember { mutableStateOf(replyJoinVoice) }
        var leaveVoice by remember { mutableStateOf(replyLeaveVoice) }
        var renameVoice by remember { mutableStateOf(replyRenameVoice) }
        var pickVoice by remember { mutableStateOf<((String) -> Unit)?>(null) }
        val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val callback = pickVoice
            pickVoice = null
            if (uri == null || callback == null) return@rememberLauncherForActivityResult
            val path = importVoice(context, uri)
            if (path == null) showToast(localizedChatString(R.string.chat_group_monitor_voice_import_failed))
            else callback(path)
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_monitor_group_member_operations_name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(
                        title = stringResource(R.string.chat_group_monitor_notice_title),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_notice_leave),
                                checked = leaveNotice,
                                onCheckedChange = { leaveNotice = it; noticeLeave = it },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_notice_join),
                                checked = joinNotice,
                                onCheckedChange = { joinNotice = it; noticeJoin = it },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_notice_rename),
                                checked = renameNotice,
                                onCheckedChange = { renameNotice = it; noticeRename = it },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    SegmentedColumn(
                        title = stringResource(R.string.chat_group_monitor_scope_title),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_scope_all),
                                selected = allGroups,
                                onSelect = { allGroups = true; scopeAll = true },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_scope_selected),
                                selected = !allGroups,
                                trailingDivider = !allGroups,
                                onSelect = { allGroups = false; scopeAll = false },
                            )
                        }
                        if (!allGroups) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_group_monitor_select_groups),
                                    description = if (groups.isEmpty()) {
                                        stringResource(R.string.chat_group_monitor_groups_empty)
                                    } else {
                                        stringResource(R.string.chat_group_monitor_groups_count, groups.size)
                                    },
                                    onClick = {
                                        showComposeDialog(context) {
                                            ContactsSelector(
                                                title = stringResource(R.string.chat_group_monitor_select_groups),
                                                contacts = WeDatabaseApi.getGroups(),
                                                initialSelectedWxIds = groups,
                                                onDismiss = onDismiss,
                                            ) { selected ->
                                                groups = selected
                                                scopeGroups = selected
                                                showToast(stringResource(R.string.chat_group_monitor_groups_count, selected.size))
                                                onDismiss()
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            MaterialSymbols.Outlined.Chevron_right,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    SegmentedColumn(
                        title = stringResource(R.string.chat_group_monitor_reply_title),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_reply_master),
                                description = stringResource(R.string.chat_group_monitor_reply_warning),
                                checked = replyOn,
                                onCheckedChange = { replyOn = it; replyEnable = it },
                            )
                        }
                        item {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_reply_prompt_type),
                                value = promptType,
                                enabled = replyOn,
                                options = listOf(
                                    DropdownOption(PROMPT_TEXT, stringResource(R.string.chat_group_monitor_prompt_text)),
                                    DropdownOption(PROMPT_CARD, stringResource(R.string.chat_group_monitor_prompt_card)),
                                    DropdownOption(PROMPT_BOTH, stringResource(R.string.chat_group_monitor_prompt_both)),
                                ),
                                onValueChange = { promptType = it; replyPromptType = it },
                            )
                        }
                        if (replyOn && promptType == PROMPT_BOTH) {
                            item {
                                DropDownMenuWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_group_monitor_reply_both_order),
                                    value = bothOrder,
                                    options = listOf(
                                        DropdownOption(ORDER_TEXT_FIRST, stringResource(R.string.chat_group_monitor_order_text_first)),
                                        DropdownOption(ORDER_CARD_FIRST, stringResource(R.string.chat_group_monitor_order_card_first)),
                                    ),
                                    onValueChange = { bothOrder = it; replyBothOrder = it },
                                )
                            }
                        }
                        item {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_reply_voice_order),
                                value = voiceOrder,
                                enabled = replyOn,
                                options = listOf(
                                    DropdownOption(VOICE_NONE, stringResource(R.string.chat_group_monitor_voice_none)),
                                    DropdownOption(VOICE_BEFORE, stringResource(R.string.chat_group_monitor_voice_before)),
                                    DropdownOption(VOICE_AFTER, stringResource(R.string.chat_group_monitor_voice_after)),
                                ),
                                onValueChange = { voiceOrder = it; replyVoiceOrder = it },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_reply_join),
                                enabled = replyOn,
                                checked = replyJoinOn,
                                onCheckedChange = { replyJoinOn = it; replyJoin = it },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_reply_leave),
                                enabled = replyOn,
                                checked = replyLeaveOn,
                                onCheckedChange = { replyLeaveOn = it; replyLeave = it },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_reply_rename),
                                enabled = replyOn,
                                checked = replyRenameOn,
                                onCheckedChange = { replyRenameOn = it; replyRename = it },
                            )
                        }
                        TemplateRows(
                            enabled = replyOn && replyJoinOn,
                            promptType = promptType,
                            textLabel = stringResource(R.string.chat_group_monitor_reply_join_template),
                            textValue = joinText,
                            onTextChange = { joinText = it; replyJoinText = it.trim() },
                            cardTitleLabel = stringResource(R.string.chat_group_monitor_reply_join_card_title),
                            cardTitleValue = joinCardTitle,
                            onCardTitleChange = { joinCardTitle = it; replyJoinCardTitle = it.trim() },
                            cardDescLabel = stringResource(R.string.chat_group_monitor_reply_join_card_desc),
                            cardDescValue = joinCardDesc,
                            onCardDescChange = { joinCardDesc = it; replyJoinCardDesc = it.trim() },
                            voiceOrder = voiceOrder,
                            voiceLabel = stringResource(R.string.chat_group_monitor_reply_join_voice),
                            voicePath = joinVoice,
                            onPickVoice = {
                                pickVoice = { path -> joinVoice = path; replyJoinVoice = path }
                                voicePicker.launch("audio/*")
                            },
                        )
                        TemplateRows(
                            enabled = replyOn && replyLeaveOn,
                            promptType = promptType,
                            textLabel = stringResource(R.string.chat_group_monitor_reply_leave_template),
                            textValue = leaveText,
                            onTextChange = { leaveText = it; replyLeaveText = it.trim() },
                            cardTitleLabel = stringResource(R.string.chat_group_monitor_reply_leave_card_title),
                            cardTitleValue = leaveCardTitle,
                            onCardTitleChange = { leaveCardTitle = it; replyLeaveCardTitle = it.trim() },
                            cardDescLabel = stringResource(R.string.chat_group_monitor_reply_leave_card_desc),
                            cardDescValue = leaveCardDesc,
                            onCardDescChange = { leaveCardDesc = it; replyLeaveCardDesc = it.trim() },
                            voiceOrder = voiceOrder,
                            voiceLabel = stringResource(R.string.chat_group_monitor_reply_leave_voice),
                            voicePath = leaveVoice,
                            onPickVoice = {
                                pickVoice = { path -> leaveVoice = path; replyLeaveVoice = path }
                                voicePicker.launch("audio/*")
                            },
                        )
                        TemplateRows(
                            enabled = replyOn && replyRenameOn,
                            promptType = promptType,
                            textLabel = stringResource(R.string.chat_group_monitor_reply_rename_template),
                            textValue = renameText,
                            onTextChange = { renameText = it; replyRenameText = it.trim() },
                            cardTitleLabel = stringResource(R.string.chat_group_monitor_reply_rename_card_title),
                            cardTitleValue = renameCardTitle,
                            onCardTitleChange = { renameCardTitle = it; replyRenameCardTitle = it.trim() },
                            cardDescLabel = stringResource(R.string.chat_group_monitor_reply_rename_card_desc),
                            cardDescValue = renameCardDesc,
                            onCardDescChange = { renameCardDesc = it; replyRenameCardDesc = it.trim() },
                            voiceOrder = voiceOrder,
                            voiceLabel = stringResource(R.string.chat_group_monitor_reply_rename_voice),
                            voicePath = renameVoice,
                            onPickVoice = {
                                pickVoice = { path -> renameVoice = path; replyRenameVoice = path }
                                voicePicker.launch("audio/*")
                            },
                        )
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_group_monitor_reply_delay),
                                    value = delaySeconds,
                                    startInt = 0,
                                    endInt = MAX_REPLY_DELAY_SECONDS,
                                    stepSize = 1,
                                    valueSuffix = stringResource(R.string.unit_seconds),
                                    enabled = replyOn,
                                    onValueChange = { delaySeconds = it; replyDelaySeconds = it },
                                )
                            }
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_monitor_variables_title),
                                description = stringResource(R.string.chat_group_monitor_variables_hint),
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }

    /**
     * 一类事件的模板行：形态为「文本」只出文本框，「卡片」只出标题与描述，「两者」三样都出。
     * 清空任一框并确定即恢复该语言下的默认文案。
     */
    @Composable
    private fun SegmentedColumnScope.TemplateRows(
        enabled: Boolean,
        promptType: Int,
        textLabel: String,
        textValue: String,
        onTextChange: (String) -> Unit,
        cardTitleLabel: String,
        cardTitleValue: String,
        onCardTitleChange: (String) -> Unit,
        cardDescLabel: String,
        cardDescValue: String,
        onCardDescChange: (String) -> Unit,
        voiceOrder: Int,
        voiceLabel: String,
        voicePath: String,
        onPickVoice: () -> Unit,
    ) {
        if (!enabled) return
        if (promptType != PROMPT_CARD) {
            item {
                TextFieldDialogWidget(
                    title = textLabel,
                    value = textValue,
                    dialogTitle = textLabel,
                    confirmLabel = stringResource(R.string.dialog_confirm),
                    dismissLabel = stringResource(R.string.dialog_cancel),
                    singleLine = false,
                    onValueChange = onTextChange,
                )
            }
        }
        if (promptType == PROMPT_CARD || promptType == PROMPT_BOTH) {
            item {
                TextFieldDialogWidget(
                    title = cardTitleLabel,
                    value = cardTitleValue,
                    dialogTitle = cardTitleLabel,
                    confirmLabel = stringResource(R.string.dialog_confirm),
                    dismissLabel = stringResource(R.string.dialog_cancel),
                    singleLine = false,
                    onValueChange = onCardTitleChange,
                )
            }
            item {
                TextFieldDialogWidget(
                    title = cardDescLabel,
                    value = cardDescValue,
                    dialogTitle = cardDescLabel,
                    confirmLabel = stringResource(R.string.dialog_confirm),
                    dismissLabel = stringResource(R.string.dialog_cancel),
                    singleLine = false,
                    onValueChange = onCardDescChange,
                )
            }
        }
        if (voiceOrder != VOICE_NONE) {
            item {
                BaseWidget(
                    iconPlaceholder = false,
                    title = voiceLabel,
                    description = if (voicePath.isEmpty()) {
                        stringResource(R.string.chat_group_monitor_voice_unset)
                    } else {
                        File(voicePath).name
                    },
                    onClick = onPickVoice,
                    trailingContent = {
                        Icon(
                            MaterialSymbols.Outlined.Chevron_right,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }

    private const val TAG = "MonitorGroupMemberOperations"
    private const val EVENT_DEDUP_MS = 5_000L
    private const val BULK_MEMBER_THRESHOLD = 10
    private const val MAX_REPLY_DELAY_SECONDS = 60

    private const val KEY_SCOPE_ALL = "group_member_monitor_scope_all"
    private const val KEY_SCOPE_GROUPS = "group_member_monitor_groups"
    private const val KEY_NOTICE_LEAVE = "group_member_monitor_notice_leave"
    private const val KEY_NOTICE_JOIN = "group_member_monitor_notice_join"
    private const val KEY_NOTICE_RENAME = "group_member_monitor_notice_rename"
    private const val KEY_REPLY_ENABLE = "group_member_monitor_reply_enable"
    private const val KEY_REPLY_JOIN = "group_member_monitor_reply_join"
    private const val KEY_REPLY_LEAVE = "group_member_monitor_reply_leave"
    private const val KEY_REPLY_RENAME = "group_member_monitor_reply_rename"
    private const val KEY_REPLY_JOIN_TEXT = "group_member_monitor_reply_join_text"
    private const val KEY_REPLY_LEAVE_TEXT = "group_member_monitor_reply_leave_text"
    private const val KEY_REPLY_RENAME_TEXT = "group_member_monitor_reply_rename_text"
    private const val KEY_REPLY_DELAY = "group_member_monitor_reply_delay_seconds"
    private const val KEY_REPLY_VOICE_ORDER = "group_member_monitor_reply_voice_order"
    private const val KEY_REPLY_JOIN_VOICE = "group_member_monitor_reply_join_voice"
    private const val KEY_REPLY_LEAVE_VOICE = "group_member_monitor_reply_leave_voice"
    private const val KEY_REPLY_RENAME_VOICE = "group_member_monitor_reply_rename_voice"
    private const val KEY_REPLY_PROMPT_TYPE = "group_member_monitor_reply_prompt_type"
    private const val KEY_REPLY_BOTH_ORDER = "group_member_monitor_reply_both_order"
    private const val KEY_REPLY_JOIN_CARD_TITLE = "group_member_monitor_reply_join_card_title"
    private const val KEY_REPLY_JOIN_CARD_DESC = "group_member_monitor_reply_join_card_desc"
    private const val KEY_REPLY_LEAVE_CARD_TITLE = "group_member_monitor_reply_leave_card_title"
    private const val KEY_REPLY_LEAVE_CARD_DESC = "group_member_monitor_reply_leave_card_desc"
    private const val KEY_REPLY_RENAME_CARD_TITLE = "group_member_monitor_reply_rename_card_title"
    private const val KEY_REPLY_RENAME_CARD_DESC = "group_member_monitor_reply_rename_card_desc"

    private const val PART_TEXT = "text"
    private const val PART_CARD = "card"
    private const val PART_VOICE = "voice"

    private const val VOICE_NONE = 0
    private const val VOICE_BEFORE = 1
    private const val VOICE_AFTER = 2

    private const val PROMPT_TEXT = 0
    private const val PROMPT_CARD = 1
    private const val PROMPT_BOTH = 2
    private const val ORDER_TEXT_FIRST = 0
    private const val ORDER_CARD_FIRST = 1

    /** 「两者」形态下，文本与卡片之间的间隔。 */
    private const val CARD_TEXT_GAP_MS = 300L
    private const val AVATAR_TIMEOUT_MS = 5_000
}
