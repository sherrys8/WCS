package dev.sherry.wcs.features.items.entertain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Play_arrow
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.ui.WeCurrentConversationApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.ui.WeChatInputBarMenuApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.items.chat.ApiProfile
import dev.sherry.wcs.features.items.chat.ApiProfiles
import dev.sherry.wcs.features.items.chat.CAP_TTS
import dev.sherry.wcs.features.items.chat.TTS_PROVIDER_DOUBAO
import dev.sherry.wcs.features.items.chat.TtsProfiles
import dev.sherry.wcs.features.items.chat.localizedChatString
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.SingleContactSelector
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.fs.KnownPaths
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DoubaoMusic : ClickableFeature(), WeChatInputBarMenuApi.IActionItemsProvider {
    override val technicalId = "豆包音乐"
    override val nameRes = R.string.db_music_name
    override val descriptionRes = R.string.db_music_description
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)

    private enum class Screen { CREATE, PLAYER, LIBRARY, SETTINGS }

    private val GENRES = listOf("Pop", "Rock", "Folk", "Electronic", "Hip Hop/Rap", "Chinese Style", "DJ", "R&B/Soul", "Reggae", "Punk", "Jazz")
    private val MOODS = listOf("Happy", "Chill", "Dynamic/Energetic", "Excited", "Sentimental/Melancholic/Lonely", "Inspirational/Hopeful", "Sorrow/Sad", "Nostalgic/Memory", "Romantic")

    /** LRC 时间标记只用来对齐显示，复制出去的必须是纯歌词。 */
    private val TIME_TAG = Regex("\\[\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?]")

    private var selectedProfileId by prefOption("doubao_music_profile_id", "")
    private var savedPrompt by prefOption("doubao_music_prompt", "")
    private var savedLyric by prefOption("doubao_music_lyric", "")

    private fun accounts(): List<ApiProfile> =
        ApiProfiles.ofCapability(CAP_TTS).filter { it.provider == TTS_PROVIDER_DOUBAO }

    private fun effectiveProfileId(): String {
        val profiles = accounts()
        return profiles.firstOrNull { it.id == selectedProfileId }?.id
            ?: (profiles.firstOrNull { it.hasCredential && it.userAgent.isNotBlank() } ?: profiles.firstOrNull())?.id.orEmpty()
    }

    override fun onEnable() { WeChatInputBarMenuApi.addProvider(this) }
    override fun onDisable() { WeChatInputBarMenuApi.removeProvider(this) }
    override fun onClick(context: ComponentActivity) { open(context, "", "") }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "doubao_music", icon = MaterialSymbols.Outlined.Music_note,
            label = localizedChatString(R.string.db_music_name),
            onClick = { context, footer -> open(context, WeCurrentConversationApi.value, footer.lastText.trim()) },
        ),
    )

    private fun open(context: Context, talker: String, initialText: String) {
        TtsProfiles.ensureMigrated()
        showComposeDialog(context, dismissOnTouchOutside = false) { Content(talker, initialText) }
    }

    @Composable
    private fun ShowComposeDialogScope.Content(talker: String, initialText: String) {
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf(Screen.CREATE) }
        var profileId by remember { mutableStateOf(effectiveProfileId()) }
        var prompt by remember { mutableStateOf(initialText.ifBlank { savedPrompt }) }
        var lyric by remember { mutableStateOf(savedLyric) }
        var custom by remember { mutableStateOf(false) }
        var genre by remember { mutableStateOf("") }
        var mood by remember { mutableStateOf("") }
        var gender by remember { mutableStateOf("") }
        var tracks by remember { mutableStateOf<List<DoubaoMusicTrack>>(emptyList()) }
        var selected by remember { mutableStateOf<DoubaoMusicTrack?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(0) }
        var deleteTarget by remember { mutableStateOf<DoubaoMusicTrack?>(null) }
        val profiles = accounts()
        val profileName = profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
        LaunchedEffect(profileId, profiles.map { it.id }) {
            if (profiles.any { it.id == profileId }) selectedProfileId = profileId
            else if (profiles.isNotEmpty()) profileId = effectiveProfileId()
        }

        fun failure(e: Exception): String {
            WeLogger.w("DoubaoMusic", "operation failed: ${e.javaClass.simpleName}")
            return if (e is DoubaoMusicException) context.localizedChatString(e.textRes, e.detail)
            else context.localizedChatString(R.string.db_music_request_failed)
        }
        fun stopPlayer() {
            player?.release()
            player = null
            playing = false
            position = 0
            duration = 0
        }
        fun play(track: DoubaoMusicTrack) {
            val existing = player
            if (existing != null) {
                if (duration <= 0) return
                if (playing) existing.pause() else existing.start()
                playing = !playing
                return
            }
            val next = MediaPlayer()
            try {
                next.setDataSource(DoubaoMusicStore.audio(track).absolutePath)
                next.setOnPreparedListener { current ->
                    if (player === current) { duration = current.duration; current.start(); playing = true }
                }
                next.setOnCompletionListener { current -> playing = false; position = 0; current.seekTo(0) }
                next.setOnErrorListener { _, _, _ ->
                    error = context.localizedChatString(R.string.db_music_play_failed)
                    stopPlayer()
                    true
                }
                player = next
                next.prepareAsync()
            } catch (e: Exception) { next.release(); player = null; error = failure(e) }
        }
        DisposableEffect(Unit) { onDispose { player?.release() } }
        LaunchedEffect(playing) {
            while (playing) { position = player?.currentPosition ?: 0; delay(250) }
        }
        LaunchedEffect(profileId, revision) {
            tracks = if (profileId.isBlank()) emptyList() else withContext(Dispatchers.IO) {
                ApiProfiles.get(profileId)?.let { DoubaoMusicStore.all(it) } ?: emptyList()
            }
        }
        // 生成时封面下载失败不该永久丢图：进播放页再补一次，URL 已过期就只能保持无封面
        LaunchedEffect(selected, profileId) {
            val track = selected ?: return@LaunchedEffect
            if (track.coverUrl.isBlank() || DoubaoMusicStore.cover(track).isFile) return@LaunchedEffect
            val temporary = DoubaoMusicStore.temporaryDirectory()
            val result = withContext(Dispatchers.IO) {
                try {
                    val part = File(temporary, "cover.part")
                    DoubaoMusicClient.downloadCover(track.coverUrl, part)
                    DoubaoMusicStore.publish(part, DoubaoMusicStore.cover(track))
                    true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { WeLogger.w("DoubaoMusic", "cover fetch failed"); false }
                finally { temporary.listFiles()?.forEach { it.delete() }; temporary.delete() }
            }
            if (result) revision++
        }

        suspend fun download(track: DoubaoMusicTrack, snapshot: ApiProfile): DoubaoMusicTrack = withContext(Dispatchers.IO) {
            val temporary = DoubaoMusicStore.temporaryDirectory()
            try {
                val audio = File(temporary, "audio.part")
                DoubaoMusicClient.downloadAudio(track, snapshot, audio)
                DoubaoMusicStore.publish(audio, DoubaoMusicStore.audio(track))
                val completed = track.copy(duration = AudioUtils.getDurationMs(DoubaoMusicStore.audio(track).absolutePath) / 1000.0)
                DoubaoMusicStore.save(completed)
                if (track.coverUrl.isNotBlank()) {
                    try {
                        val cover = File(temporary, "cover.part")
                        DoubaoMusicClient.downloadCover(track.coverUrl, cover)
                        DoubaoMusicStore.publish(cover, DoubaoMusicStore.cover(track))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { WeLogger.w("DoubaoMusic", "cover download failed") }
                }
                completed
            } finally { temporary.listFiles()?.forEach { it.delete() }; temporary.delete() }
        }
        fun start(retry: DoubaoMusicTrack? = null) {
            if (busy) return
            val snapshot = ApiProfiles.get(profileId)?.takeIf { it.capability == CAP_TTS && it.provider == TTS_PROVIDER_DOUBAO }
            if (snapshot == null) { error = context.localizedChatString(R.string.db_music_need_account); screen = Screen.SETTINGS; return }
            if (snapshot.userAgent.isBlank()) { error = context.localizedChatString(R.string.db_music_need_ua); screen = Screen.SETTINGS; return }
            if (retry == null && (prompt.isBlank() || (custom && lyric.isBlank()))) {
                error = context.localizedChatString(if (prompt.isBlank()) R.string.db_music_need_prompt else R.string.db_music_need_lyric)
                return
            }
            if (retry != null && retry.owner != DoubaoMusicStore.owner(snapshot)) return
            stopPlayer()
            savedPrompt = prompt
            savedLyric = lyric
            busy = true
            error = null
            status = context.localizedChatString(if (retry == null) R.string.db_music_generating else R.string.db_music_downloading)
            job = scope.launch {
                try {
                    val generated = if (retry != null) listOf(retry) else DoubaoMusicClient.generate(
                        snapshot, prompt.trim(), lyric.trim(), genre, mood, gender, custom,
                    )
                    withContext(Dispatchers.IO) { generated.forEach { DoubaoMusicStore.save(it) } }
                    var failed = false
                    for (track in generated) {
                        selected = track
                        status = context.localizedChatString(R.string.db_music_downloading)
                        try { selected = download(track, snapshot) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed = true; error = failure(e) }
                        revision++
                    }
                    status = context.localizedChatString(if (failed) R.string.db_music_retry_download else R.string.db_music_saved)
                    if (!failed) screen = Screen.PLAYER
                } catch (e: CancellationException) {
                    status = context.localizedChatString(R.string.db_music_cancelled)
                    throw e
                } catch (e: Exception) { error = failure(e); status = "" }
                finally { busy = false; revision++ }
            }
        }
        fun saveToDownloads(track: DoubaoMusicTrack) {
            scope.launch {
                try {
                    val path = withContext(Dispatchers.IO) {
                        val target = uniqueTarget(filename(track))
                        DoubaoMusicStore.audio(track).inputStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        target.absolutePath
                    }
                    showToast(context, context.localizedChatString(R.string.db_music_saved_to, path))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { showToast(context, failure(e)) }
            }
        }
        fun sendAsVoice(track: DoubaoMusicTrack) {
            scope.launch {
                try {
                    val contacts = withContext(Dispatchers.IO) { WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups() }
                    showComposeDialog(context) {
                        SingleContactSelector(
                            title = context.localizedChatString(R.string.db_music_send_voice), contacts = contacts,
                            initialSelectedWxId = talker.takeIf { it.isNotBlank() }, onDismiss = onDismiss,
                            onConfirm = { destination ->
                                onDismiss()
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { convertAndSendVoice(destination, track) }
                                    showToast(context, context.localizedChatString(
                                        if (ok) R.string.db_music_send_queued else R.string.db_music_send_failed))
                                }
                            },
                        )
                    }
                } catch (e: Exception) { if (e is CancellationException) throw e; error = failure(e) }
            }
        }

        AlertDialogContent(
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.db_music_name), modifier = Modifier.weight(1f))
                    IconButton(onClick = { screen = if (screen == Screen.LIBRARY) Screen.CREATE else Screen.LIBRARY }, enabled = !busy) {
                        Icon(MaterialSymbols.Outlined.Photo_library, contentDescription = stringResource(R.string.db_music_library))
                    }
                    OutlinedButton(onClick = { screen = Screen.SETTINGS }, enabled = !busy, modifier = Modifier.width(104.dp)) {
                        Text(profileName.ifBlank { stringResource(R.string.db_music_select_account) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (screen) {
                        Screen.CREATE -> CreateContent(
                            prompt = prompt, lyric = lyric, custom = custom, genre = genre, mood = mood, gender = gender,
                            enabled = !busy, onPrompt = { prompt = it; savedPrompt = it }, onLyric = { lyric = it; savedLyric = it },
                            onCustom = { custom = it }, onGenre = { genre = it }, onMood = { mood = it }, onGender = { gender = it },
                            onGenerate = { start() },
                        )
                        Screen.PLAYER -> selected?.let { track ->
                            val saved = DoubaoMusicStore.audio(track).isFile
                            PlayerContent(
                                track = track, playing = playing, position = position, duration = duration,
                                onPlay = { play(track) }, onSeek = { position = it; player?.seekTo(it) },
                                onDownload = { saveToDownloads(track) }, onSend = { sendAsVoice(track) },
                                onCopyLyrics = { copyLyrics(context, track) },
                                onDelete = { deleteTarget = track },
                                onRetry = if (saved) null else { start(track) },
                            )
                        }
                        Screen.LIBRARY -> LibraryContent(
                            tracks = tracks, count = stringResource(R.string.db_music_lib_count, profileName, tracks.size),
                            emptyText = stringResource(R.string.db_music_library_empty),
                            onOpen = { stopPlayer(); selected = it; screen = Screen.PLAYER },
                        )
                        Screen.SETTINGS -> SettingsContent(
                            profiles = profiles, profileId = profileId,
                            onSelect = { stopPlayer(); profileId = it; selectedProfileId = it; selected = null; error = null; status = "" },
                        )
                    }
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { job?.cancel() }, enabled = true) { Text(stringResource(R.string.dialog_cancel)) }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (screen == Screen.CREATE) { job?.cancel(); onDismiss() } else screen = Screen.CREATE }) {
                    Text(stringResource(if (screen == Screen.CREATE) R.string.dialog_close else R.string.api_profile_back))
                }
            },
        )
        deleteTarget?.let { track ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteTarget = null }, title = { Text(stringResource(R.string.db_music_delete)) },
                text = { Text(stringResource(R.string.db_music_delete_confirm, track.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        stopPlayer()
                        deleteTarget = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { DoubaoMusicStore.delete(track) }
                                if (selected?.id == track.id) { selected = null; screen = Screen.LIBRARY }
                                revision++
                            } catch (e: Exception) { if (e is CancellationException) throw e; error = failure(e) }
                        }
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    @Composable
    private fun CreateContent(
        prompt: String, lyric: String, custom: Boolean, genre: String, mood: String, gender: String,
        enabled: Boolean, onPrompt: (String) -> Unit, onLyric: (String) -> Unit, onCustom: (Boolean) -> Unit,
        onGenre: (String) -> Unit, onMood: (String) -> Unit, onGender: (String) -> Unit, onGenerate: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.db_music_prompt), style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = prompt, onValueChange = onPrompt, enabled = enabled, minLines = 3, maxLines = 5,
                placeholder = { Text(stringResource(R.string.db_music_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.db_music_lyrics), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    listOf(false, true).forEachIndexed { index, isCustom ->
                        SegmentedButton(
                            selected = custom == isCustom, onClick = { onCustom(isCustom) }, enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) {
                            Text(stringResource(if (isCustom) R.string.db_music_custom_lyrics else R.string.db_music_ai_lyrics))
                        }
                    }
                }
            }
            OutlinedTextField(
                value = lyric, onValueChange = onLyric, enabled = enabled && custom, minLines = 3, maxLines = 5,
                placeholder = { Text(stringResource(if (custom) R.string.db_music_lyric_custom_hint else R.string.db_music_lyric_ai_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Choice(R.string.db_music_genre, genre, GENRES, enabled, onGenre)
            Choice(R.string.db_music_mood, mood, MOODS, enabled, onMood)
            // 协议要 Male/Female，行里显示中文，所以这一档单独给 value/label
            DropDownMenuWidget(
                title = stringResource(R.string.db_music_gender), description = null, value = gender, enabled = enabled,
                options = listOf(
                    DropdownOption("", stringResource(R.string.db_music_unspecified)),
                    DropdownOption("Male", stringResource(R.string.db_music_male)),
                    DropdownOption("Female", stringResource(R.string.db_music_female)),
                ),
                onValueChange = onGender,
            )
            Button(onClick = onGenerate, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.db_music_generate))
            }
            Text(stringResource(R.string.db_music_quota_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun PlayerContent(
        track: DoubaoMusicTrack, playing: Boolean, position: Int, duration: Int,
        onPlay: () -> Unit, onSeek: (Int) -> Unit, onDownload: () -> Unit, onSend: () -> Unit,
        onCopyLyrics: () -> Unit, onDelete: () -> Unit, onRetry: (() -> Unit)?,
    ) {
        val available = DoubaoMusicStore.audio(track).isFile
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val cover = DoubaoMusicStore.cover(track)
                if (cover.isFile) AsyncImage(model = cover, contentDescription = null,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    Icon(MaterialSymbols.Outlined.Music_note, contentDescription = null)
                }
                Column(Modifier.weight(1f)) {
                    Text(track.title.ifBlank { stringResource(R.string.db_music_untitled) },
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatDuration(track.duration) + " · " + stringResource(if (available) R.string.db_music_saved else R.string.db_music_retry_download),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(onClick = onPlay, enabled = available) {
                    Icon(if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                        contentDescription = stringResource(if (playing) R.string.db_music_pause else R.string.db_music_play))
                }
            }
            if (duration > 0) Slider(
                value = position.toFloat().coerceIn(0f, duration.toFloat()), valueRange = 0f..duration.toFloat(),
                onValueChange = { onSeek(it.toInt()) }, modifier = Modifier.fillMaxWidth(),
            )
            onRetry?.let {
                Button(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.db_music_retry_download)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDownload, enabled = available, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.db_music_download), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(onClick = onSend, enabled = available, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.db_music_send_voice), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.db_music_lyrics), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCopyLyrics, enabled = track.lyrics.isNotBlank()) { Text(stringResource(R.string.db_music_copy_lyrics)) }
            }
            Text(
                stripTimeTags(track.lyrics).ifBlank { stringResource(R.string.db_music_no_lyrics) },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.db_music_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    @Composable
    private fun LibraryContent(tracks: List<DoubaoMusicTrack>, count: String, emptyText: String, onOpen: (DoubaoMusicTrack) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tracks.isEmpty()) Text(emptyText)
            else {
                Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tracks.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { track ->
                            Column(
                                modifier = Modifier.weight(1f).clickable { onOpen(track) },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val cover = DoubaoMusicStore.cover(track)
                                if (cover.isFile) AsyncImage(model = cover, contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                else Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    Icon(MaterialSymbols.Outlined.Music_note, contentDescription = null)
                                }
                                Text(track.title.ifBlank { stringResource(R.string.db_music_untitled) },
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatDuration(track.duration), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsContent(profiles: List<ApiProfile>, profileId: String, onSelect: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (profiles.isEmpty()) BaseWidget(title = stringResource(R.string.db_music_select_account), enabled = false)
            else DropDownMenuWidget(
                title = stringResource(R.string.db_music_select_account), description = null,
                value = profileId.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id,
                options = profiles.map { DropdownOption(it.id, it.name) },
                onValueChange = onSelect,
            )
            Text(stringResource(R.string.db_music_account_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.db_music_cache_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Choice(titleRes: Int, value: String, values: List<String>, enabled: Boolean, change: (String) -> Unit) {
        DropDownMenuWidget(
            title = stringResource(titleRes), description = null, value = value, enabled = enabled,
            options = listOf(DropdownOption("", stringResource(R.string.db_music_unspecified))) + values.map { DropdownOption(it, it) },
            onValueChange = change,
        )
    }

    private fun convertAndSendVoice(talker: String, track: DoubaoMusicTrack): Boolean = runCatching {
        val audio = DoubaoMusicStore.audio(track)
        if (!audio.isFile) return false
        val temporary = DoubaoMusicStore.temporaryDirectory()
        // silk 交给微信后仍是异步上传，删掉会让发送半途失败，只能留给缓存目录按日期回收
        val silk = File(temporary, "voice.silk")
        if (!AudioUtils.anyToSilk(audio.absolutePath, silk.absolutePath)) return false
        WeMessageApi.sendVoice(talker, silk.absolutePath, AudioUtils.getDurationMs(silk.absolutePath).toInt())
    }.onFailure { WeLogger.e("DoubaoMusic", "send voice failed", it) }.getOrDefault(false)

    private fun copyLyrics(context: Context, track: DoubaoMusicTrack) {
        val text = stripTimeTags(track.lyrics)
        if (text.isBlank()) return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(track.title, text))
        showToast(context, context.localizedChatString(R.string.db_music_copied))
    }

    private fun stripTimeTags(lyrics: String): String = lyrics.replace(TIME_TAG, "").trim()

    private fun uniqueTarget(name: String): File {
        val directory = KnownPaths.downloads.toFile()
        val base = name.substringBeforeLast('.')
        val suffix = name.substringAfterLast('.')
        var candidate = File(directory, name)
        var index = 1
        while (candidate.exists()) { candidate = File(directory, "$base ($index).$suffix"); index++ }
        return candidate
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun filename(track: DoubaoMusicTrack): String = track.title
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80)
        .ifBlank { localizedChatString(R.string.db_music_untitled) } + ".m4a"
}
