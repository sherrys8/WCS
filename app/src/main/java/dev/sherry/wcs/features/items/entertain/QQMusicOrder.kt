package dev.sherry.wcs.features.items.entertain

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeDatabaseListenerApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageType
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.LocalizedContextFactory
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.ContactsSelector
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.fs.KnownPaths
import kotlin.io.path.absolutePathString
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 聊天里发「点歌 关键词」，把 QQ 音乐的歌以音乐卡片或语音形式发回当前会话。
 *
 * 移植自 Hchat 的 QQ 点歌，两处刻意的差异：触发只认**消息入库**这一条通路
 * （Hchat 另外 hook 了发送按钮以便「命令本身不发送」，WcS 没有对应的切入点，
 * 所以命令文本会照常出现在聊天里）；会话白名单改到设置界面里维护，
 * 不再用「开启点歌 / 关闭点歌」这种会话内命令。
 */
object QQMusicOrder : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "QQ点歌"
    override val nameRes = R.string.feature_qq_music_order_name
    override val descriptionRes = R.string.feature_qq_music_order_description
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)

    private const val TAG = "QQ点歌"
    private const val TBL_MESSAGE = "message"
    private const val MESSAGE_MAX_AGE_MS = 30_000L
    private const val MAX_THUMB_BYTES = 128 * 1024
    private const val MAX_AUDIO_BYTES = 128L * 1024L * 1024L
    private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000
    private const val AUDIO_READ_TIMEOUT_MS = 30_000
    private const val DEFAULT_APP_ID = "wx485a97c844086dc9"
    private const val STALE_FILE_MS = 30 * 60_000L

    private val SUPPORTED_AUDIO_SUFFIXES = listOf(".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".wav")

    private var triggers by WePrefs.prefOption("qq_music_order_triggers", "点歌")
    private var allowedTalkers by WePrefs.prefOption("qq_music_order_allowed_talkers", emptySet())
    private var sendAsCard by WePrefs.prefOption("qq_music_order_send_as_card", true)
    private var sendAsVoice by WePrefs.prefOption("qq_music_order_send_as_voice", false)
    private var customSingerEnabled by WePrefs.prefOption("qq_music_order_custom_singer", false)
    private var defaultSinger by WePrefs.prefOption("qq_music_order_default_singer", "")
    private var replaceSingerWithNickname by WePrefs.prefOption("qq_music_order_replace_singer_nickname", false)
    private var replaceCoverWithAvatar by WePrefs.prefOption("qq_music_order_replace_cover_avatar", false)
    private var appId by WePrefs.prefOption("qq_music_order_app_id", DEFAULT_APP_ID)

    /** 与短视频解析同级：Download/WcS/MusicOrder，方便用户自己进去看下载到了什么。 */
    private val cacheDir: File
        get() = File((KnownPaths.downloads / "MusicOrder").absolutePathString()).apply { mkdirs() }

    private val client = QQMusicClient()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WcS-QQMusicOrder").apply { isDaemon = true }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) { SettingsContent(context) }
    }

    // ==================== 触发 ====================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != TBL_MESSAGE) return
        runCatching { handleTextMessage(values) }
            .onFailure { WeLogger.w(TAG, "failed to inspect an incoming message", it) }
    }

    private fun handleTextMessage(values: ContentValues) {
        if (!isEnabled) return
        if ((values.getAsInteger("type") ?: 0) != MessageType.TEXT.code) return
        val createTime = values.getAsLong("createTime") ?: 0L
        if (createTime <= 0L || System.currentTimeMillis() - normalizeTime(createTime) >= MESSAGE_MAX_AGE_MS) return
        val talker = values.getAsString("talker")?.trim().orEmpty()
        if (talker.isBlank()) return
        val isOutgoing = (values.getAsInteger("isSend") ?: 0) == 1
        if (!isOutgoing && talker !in allowedTalkers) return

        val raw = values.getAsString("content").orEmpty()
        val group = isGroup(talker)
        val sender = when {
            isOutgoing -> WeApi.selfWxId
            group -> senderOfGroupMessage(raw) ?: talker
            else -> talker
        }
        val command = parseCommand(stripGroupPrefix(raw, group)) ?: return
        val msgId = values.getAsLong("msgId") ?: 0L
        worker.execute {
            runCatching { process(talker, msgId, sender, command.first, command.second) }
                .onFailure {
                    WeLogger.w(TAG, "failed to serve a song request", it)
                    reply(talker, msgId, string(R.string.qq_music_order_reply_failed))
                }
        }
    }

    private fun process(
        talker: String,
        msgId: Long,
        sender: String,
        keyword: String,
        requestedSinger: String?,
    ) {
        val track = when (val result = client.search(keyword)) {
            is QQMusicSearchResult.Success -> result.track
            QQMusicSearchResult.NotFound -> {
                reply(talker, msgId, string(R.string.qq_music_order_reply_not_found))
                return
            }
            QQMusicSearchResult.Unavailable -> {
                reply(talker, msgId, string(R.string.qq_music_order_reply_unavailable))
                return
            }
        }
        if (!sendAsCard && !sendAsVoice) {
            reply(talker, msgId, string(R.string.qq_music_order_reply_no_channel))
            return
        }
        val cardSent = if (sendAsCard) sendCard(talker, sender, keyword, requestedSinger, track) else null
        val voiceSent = if (sendAsVoice) sendVoice(talker, track) else null
        when {
            cardSent != false && voiceSent != false -> Unit
            cardSent == false && voiceSent == false ->
                reply(talker, msgId, string(R.string.qq_music_order_reply_both_failed))
            cardSent == false -> reply(talker, msgId, string(R.string.qq_music_order_reply_card_failed))
            voiceSent == false -> reply(talker, msgId, string(R.string.qq_music_order_reply_voice_failed))
        }
    }

    // ==================== 发送 ====================

    private fun sendCard(
        talker: String,
        sender: String,
        keyword: String,
        requestedSinger: String?,
        track: QQMusicTrack,
    ): Boolean {
        val coverUrl = if (replaceCoverWithAvatar) {
            avatarUrlOf(sender).ifBlank { track.coverUrl }
        } else {
            track.coverUrl
        }
        return WeMessageApi.shareMusic(
            talker = talker,
            title = track.title.ifBlank { keyword },
            description = resolveSinger(sender, requestedSinger, track.singer),
            musicUrl = track.landingUrl,
            musicDataUrl = track.playUrl,
            thumbData = downloadThumb(coverUrl.replace("R500x500", "R300x300")),
            appId = appId.ifBlank { DEFAULT_APP_ID },
            songLyric = track.lyric.ifBlank { "[99:99.99]${string(R.string.qq_music_order_lyric_none)}" },
            songAlbumUrl = coverUrl,
        )
    }

    /** 语音要经过 silk 转码，微信才认；任何一步失败都返回 false 让上层回执。 */
    private fun sendVoice(talker: String, track: QQMusicTrack): Boolean {
        val cache = cacheDir
        pruneStaleFiles(cache)
        val downloaded = File(cache, "qq_music_${System.currentTimeMillis()}_${System.nanoTime()}${audioSuffix(track.playUrl)}")
        val part = File(downloaded.absolutePath + ".part")
        val silk = File(cache, downloaded.name + ".silk")
        return try {
            if (!downloadAudio(track.playUrl, part, downloaded)) return false
            if (!AudioUtils.anyToSilk(downloaded.absolutePath, silk.absolutePath)) return false
            val duration = AudioUtils.getDurationMs(silk.absolutePath).toInt()
            WeMessageApi.sendVoice(talker, silk.absolutePath, duration)
        } finally {
            deleteQuietly(part)
            deleteQuietly(downloaded)
            // silk 交给微信后是异步上传的，这里删掉会让发送半途失败，只能留给缓存目录自己回收
        }
    }

    private fun resolveSinger(sender: String, requestedSinger: String?, sourceSinger: String): String {
        if (!requestedSinger.isNullOrBlank()) return requestedSinger
        if (customSingerEnabled && defaultSinger.isNotBlank()) return defaultSinger.trim()
        if (replaceSingerWithNickname && sender.isNotBlank()) {
            val nickname = displayNameOf(sender)
            if (nickname.isNotBlank() && nickname != sender) return nickname
        }
        return sourceSinger
    }

    private fun reply(talker: String, msgId: Long, content: String) {
        if (msgId > 0L && WeMessageApi.sendQuoteTextByMsgId(talker, msgId, content)) return
        WeMessageApi.sendText(talker, content)
    }

    // ==================== 下载 ====================

    private fun downloadThumb(url: String): ByteArray? {
        if (url.isBlank()) return null
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MicroMessenger Client")
            }
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_THUMB_BYTES) return@runCatching null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.onFailure { WeLogger.w(TAG, "failed to download the cover image", it) }
            .getOrNull()
            .also { connection?.disconnect() }
    }

    private fun downloadAudio(url: String, part: File, target: File): Boolean {
        if (url.isBlank()) return false
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = AUDIO_CONNECT_TIMEOUT_MS
                readTimeout = AUDIO_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MicroMessenger Client")
                setRequestProperty("Referer", "https://y.qq.com/")
            }
            if (connection.responseCode !in 200..299) return@runCatching false
            val contentType = connection.contentType.orEmpty().substringBefore(';').lowercase()
            if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                return@runCatching false
            }
            if (connection.contentLengthLong > MAX_AUDIO_BYTES) return@runCatching false
            var total = 0L
            var oversized = false
            connection.inputStream.use { input ->
                FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_AUDIO_BYTES) {
                            oversized = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (oversized || !part.isFile || part.length() <= 0L) return@runCatching false
            if (target.exists() && !target.delete()) return@runCatching false
            part.renameTo(target) && isPlayableAudio(target)
        }.onFailure { WeLogger.w(TAG, "failed to download the song audio", it) }
            .getOrDefault(false)
            .also { connection?.disconnect() }
    }

    /** 只认时长可解析的文件，避免把错误页或截断内容当歌发出去。 */
    private fun isPlayableAudio(file: File): Boolean = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) > 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(false)

    // ==================== 小工具 ====================

    /** 返回「歌名, 临时指定的歌手」；后者仅在「固定歌手名」开启且写法为「歌名&歌手」时非空。 */
    private fun parseCommand(text: String): Pair<String, String?>? {
        val clean = text.trim()
        val trigger = triggerList().firstOrNull { clean.startsWith(it) } ?: return null
        var keyword = clean.removePrefix(trigger).trim()
        var requestedSinger: String? = null
        if (customSingerEnabled) {
            val separator = keyword.indexOf('&')
            if (separator >= 0) {
                requestedSinger = keyword.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
                keyword = keyword.substring(0, separator).trim()
            }
        }
        return keyword.takeIf { it.isNotEmpty() }?.let { it to requestedSinger }
    }

    private fun triggerList(): List<String> = triggers
        .split(',', '|', ';', '\n', '，', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf("点歌") }

    private fun stripGroupPrefix(content: String, group: Boolean): String {
        if (!group) return content.trim()
        val marker = when {
            content.contains(":\n") -> ":\n"
            content.contains(":\\n") -> ":\\n"
            else -> return content.trim()
        }
        return content.substringAfter(marker).trim()
    }

    private fun senderOfGroupMessage(content: String): String? {
        val marker = when {
            content.contains(":\n") -> ":\n"
            content.contains(":\\n") -> ":\\n"
            else -> return null
        }
        return content.substringBefore(marker).trim().takeIf { it.isNotEmpty() }
    }

    private fun displayNameOf(wxId: String): String = friends().firstOrNull { it.wxId == wxId }?.displayName.orEmpty()

    private fun avatarUrlOf(wxId: String): String = friends().firstOrNull { it.wxId == wxId }?.avatarUrl.orEmpty()

    private fun friends() = runCatching { WeDatabaseApi.getFriends() }
        .onFailure { WeLogger.w(TAG, "failed to read the contact list", it) }
        .getOrDefault(emptyList())

    private fun audioSuffix(url: String): String {
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        return SUPPORTED_AUDIO_SUFFIXES.firstOrNull(path::endsWith) ?: ".audio"
    }

    /** 转好的 silk 要等微信异步上传完，所以只清明显过期的，避免目录里越堆越多。 */
    private fun pruneStaleFiles(dir: File) {
        val deadline = System.currentTimeMillis() - STALE_FILE_MS
        runCatching {
            dir.listFiles()?.filter { it.lastModified() < deadline }?.forEach { deleteQuietly(it) }
        }
    }

    private fun deleteQuietly(file: File) {
        if (file.exists() && !file.delete()) file.deleteOnExit()
    }

    private fun normalizeTime(value: Long): Long = if (value < 100_000_000_000L) value * 1000L else value

    private fun isGroup(talker: String): Boolean =
        talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")

    private fun string(resId: Int): String = localized(resId)

    private fun localized(resourceId: Int, vararg formatArgs: Any): String = LocalizedContextFactory.create(
        HostInfo.application,
        WcSLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(resourceId, *formatArgs)

    // ==================== 设置界面 ====================

    @Composable
    private fun ShowComposeDialogScope.SettingsContent(context: ComponentActivity) {
        var talkersState by remember { mutableStateOf(allowedTalkers) }
        var triggersState by remember { mutableStateOf(triggers) }
        var cardState by remember { mutableStateOf(sendAsCard) }
        var voiceState by remember { mutableStateOf(sendAsVoice) }
        var customSingerState by remember { mutableStateOf(customSingerEnabled) }
        var singerState by remember { mutableStateOf(defaultSinger) }
        var nicknameState by remember { mutableStateOf(replaceSingerWithNickname) }
        var avatarState by remember { mutableStateOf(replaceCoverWithAvatar) }
        var appIdState by remember { mutableStateOf(appId) }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.feature_qq_music_order_name)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SegmentedColumn(title = stringResource(R.string.qq_music_order_group_trigger)) {
                        item(key = "triggers") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.qq_music_order_triggers),
                                value = triggersState,
                                onValueChange = {
                                    triggersState = it
                                    triggers = it
                                },
                                dialogTitle = stringResource(R.string.qq_music_order_triggers),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.qq_music_order_triggers_hint),
                            )
                        }
                        item(key = "talkers") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.qq_music_order_talkers),
                                description = if (talkersState.isEmpty()) {
                                    stringResource(R.string.qq_music_order_talkers_none)
                                } else {
                                    stringResource(R.string.qq_music_order_talkers_selected, talkersState.size)
                                },
                                onClick = { showTalkerPicker(context) { picked -> talkersState = picked } },
                            )
                        }
                    }

                    SegmentedColumn(title = stringResource(R.string.qq_music_order_group_send)) {
                        item(key = "card") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.qq_music_order_send_card),
                                description = stringResource(R.string.qq_music_order_send_card_summary),
                                checked = cardState,
                                onCheckedChange = {
                                    cardState = it
                                    sendAsCard = it
                                },
                            )
                        }
                        item(key = "voice") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.qq_music_order_send_voice),
                                description = stringResource(R.string.qq_music_order_send_voice_summary),
                                checked = voiceState,
                                onCheckedChange = {
                                    voiceState = it
                                    sendAsVoice = it
                                },
                            )
                        }
                    }

                    SegmentedColumn(title = stringResource(R.string.qq_music_order_group_style)) {
                        item(key = "custom_singer") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.qq_music_order_custom_singer),
                                description = stringResource(R.string.qq_music_order_custom_singer_summary),
                                checked = customSingerState,
                                onCheckedChange = {
                                    customSingerState = it
                                    customSingerEnabled = it
                                },
                            )
                        }
                        item(key = "default_singer") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.qq_music_order_default_singer),
                                value = singerState,
                                onValueChange = {
                                    val trimmed = it.trim()
                                    singerState = trimmed
                                    defaultSinger = trimmed
                                },
                                dialogTitle = stringResource(R.string.qq_music_order_default_singer),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.qq_music_order_default_singer_hint),
                                enabled = customSingerState,
                            )
                        }
                        item(key = "nickname") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.qq_music_order_singer_nickname),
                                description = stringResource(R.string.qq_music_order_singer_nickname_summary),
                                checked = nicknameState,
                                enabled = !customSingerState,
                                onCheckedChange = {
                                    nicknameState = it
                                    replaceSingerWithNickname = it
                                },
                            )
                        }
                        item(key = "avatar_cover") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.qq_music_order_cover_avatar),
                                description = stringResource(R.string.qq_music_order_cover_avatar_summary),
                                checked = avatarState,
                                onCheckedChange = {
                                    avatarState = it
                                    replaceCoverWithAvatar = it
                                },
                            )
                        }
                    }

                    SegmentedColumn(title = stringResource(R.string.qq_music_order_group_advanced)) {
                        item(key = "app_id") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.qq_music_order_app_id),
                                value = appIdState,
                                onValueChange = {
                                    val trimmed = it.trim()
                                    appIdState = trimmed
                                    appId = trimmed
                                },
                                dialogTitle = stringResource(R.string.qq_music_order_app_id),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.qq_music_order_app_id_hint),
                            )
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
        )
    }

    private fun showTalkerPicker(context: ComponentActivity, onPicked: (Set<String>) -> Unit) {
        showComposeDialog(context) {
            val contacts = remember { friends() }
            ContactsSelector(
                title = stringResource(R.string.qq_music_order_talkers_picker),
                contacts = contacts,
                initialSelectedWxIds = allowedTalkers,
                onDismiss = onDismiss,
                onConfirm = { picked ->
                    allowedTalkers = picked
                    onPicked(picked)
                    onDismiss()
                },
            )
        }
    }
}
