package dev.sherry.wcs.features.items.entertain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Library_music
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Settings
import dev.sherry.wcs.R
import dev.sherry.wcs.activity.TransparentActivity
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
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
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

    private var selectedProfileId by prefOption("doubao_music_profile_id", "")
    private var savedPrompt by prefOption("doubao_music_prompt", "")
    private var savedLyric by prefOption("doubao_music_lyric", "")
    private var savedTheme by prefOption("doubao_music_theme", "")

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
        var profileId by remember { mutableStateOf(effectiveProfileId()) }
        var prompt by remember { mutableStateOf(initialText.ifBlank { savedPrompt }) }
        var lyric by remember { mutableStateOf(savedLyric) }
        var theme by remember { mutableStateOf(savedTheme) }
        var custom by remember { mutableStateOf(false) }
        var genre by remember { mutableStateOf("") }
        var mood by remember { mutableStateOf("") }
        var gender by remember { mutableStateOf("") }
        var settings by remember { mutableStateOf(false) }
        var library by remember { mutableStateOf(false) }
        var tracks by remember { mutableStateOf<List<DoubaoMusicTrack>>(emptyList()) }
        var selected by remember { mutableStateOf<DoubaoMusicTrack?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(0) }
        var expandedLyrics by remember { mutableStateOf(false) }
        var expandedStyle by remember { mutableStateOf(false) }
        var deleteTarget by remember { mutableStateOf<DoubaoMusicTrack?>(null) }
        val profiles = accounts()
        val profile = profiles.firstOrNull { it.id == profileId }
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
            tracks = if (profile == null) emptyList() else withContext(Dispatchers.IO) { DoubaoMusicStore.all(profile) }
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
            if (snapshot == null) { error = context.localizedChatString(R.string.db_music_need_account); settings = true; return }
            if (snapshot.userAgent.isBlank()) { error = context.localizedChatString(R.string.db_music_need_ua); settings = true; return }
            if (retry == null && (prompt.isBlank() || (custom && lyric.isBlank()))) {
                error = context.localizedChatString(if (prompt.isBlank()) R.string.db_music_need_prompt else R.string.db_music_need_lyric)
                return
            }
            if (retry != null && retry.owner != DoubaoMusicStore.owner(snapshot)) return
            stopPlayer()
            savedPrompt = prompt
            savedLyric = lyric
            savedTheme = theme
            busy = true
            error = null
            progress = context.localizedChatString(if (retry == null) R.string.db_music_generating else R.string.db_music_downloading)
            job = scope.launch {
                try {
                    val generated = if (retry != null) listOf(retry) else DoubaoMusicClient.generate(
                        snapshot, prompt.trim(), lyric.trim(), theme.trim(), genre, mood, gender, custom,
                    )
                    withContext(Dispatchers.IO) { generated.forEach { DoubaoMusicStore.save(it) } }
                    var failed = false
                    for (track in generated) {
                        selected = track
                        progress = context.localizedChatString(R.string.db_music_downloading)
                        try { selected = download(track, snapshot) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed = true; error = failure(e) }
                        revision++
                    }
                    progress = context.localizedChatString(if (failed) R.string.db_music_retry_download else R.string.db_music_saved)
                } catch (e: CancellationException) {
                    progress = context.localizedChatString(R.string.db_music_cancelled)
                    throw e
                } catch (e: Exception) { error = failure(e); progress = "" }
                finally { busy = false; revision++ }
            }
        }
        fun send(track: DoubaoMusicTrack) {
            scope.launch {
                try {
                    val contacts = withContext(Dispatchers.IO) { WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups() }
                    showComposeDialog(context) {
                        SingleContactSelector(
                            title = context.localizedChatString(R.string.db_music_send), contacts = contacts,
                            initialSelectedWxId = talker.takeIf { it.isNotBlank() }, onDismiss = onDismiss,
                            onConfirm = { destination ->
                                onDismiss()
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        WeMessageApi.sendFile(destination, DoubaoMusicStore.audio(track).absolutePath, filename(track))
                                    }
                                    progress = context.localizedChatString(if (ok) R.string.db_music_send_queued else R.string.db_music_send_failed)
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
                    Text(
                        stringResource(if (settings) R.string.db_music_settings else if (library) R.string.db_music_library else R.string.db_music_name),
                        modifier = Modifier.weight(1f),
                    )
                    if (!settings) IconButton(onClick = { library = !library }, enabled = !busy) {
                        Icon(MaterialSymbols.Outlined.Library_music, contentDescription = stringResource(if (library) R.string.db_music_create else R.string.db_music_library))
                    }
                    IconButton(onClick = { settings = !settings }, enabled = !busy) {
                        Icon(MaterialSymbols.Outlined.Settings, contentDescription = stringResource(R.string.db_music_settings))
                    }
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (settings) {
                        SegmentedColumn(title = stringResource(R.string.db_music_account)) {
                            item {
                                if (profiles.isEmpty()) {
                                    BaseWidget(title = stringResource(R.string.db_music_select_account), enabled = false)
                                } else {
                                    DropDownMenuWidget(
                                        title = stringResource(R.string.db_music_select_account), description = null,
                                        value = profileId.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id,
                                        options = profiles.map { DropdownOption(it.id, it.name) },
                                        onValueChange = { stopPlayer(); profileId = it; selectedProfileId = it; selected = null; error = null; progress = "" },
                                    )
                                }
                            }
                        }
                        Text(stringResource(R.string.db_music_account_note), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.db_music_cache_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        if (library) {
                            if (tracks.isEmpty()) Text(stringResource(R.string.db_music_library_empty))
                            tracks.forEach { track ->
                                SegmentedColumn {
                                    item {
                                        BaseWidget(
                                            title = track.title.ifBlank { stringResource(R.string.db_music_untitled) },
                                            description = formatDuration(track.duration) + " · " + stringResource(if (DoubaoMusicStore.audio(track).isFile) R.string.db_music_saved else R.string.db_music_retry_download),
                                            enabled = !busy,
                                            onClick = { stopPlayer(); selected = track; library = false; expandedLyrics = false },
                                        )
                                    }
                                    item { BaseWidget(title = stringResource(R.string.db_music_delete), enabled = !busy, isError = true, onClick = { deleteTarget = track }) }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = prompt,
                                onValueChange = { prompt = it; savedPrompt = it },
                                label = { Text(stringResource(R.string.db_music_prompt)) },
                                enabled = !busy,
                                minLines = 4,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilterChip(selected = !custom, onClick = { custom = false }, enabled = !busy, label = { Text(stringResource(R.string.db_music_ai_lyrics)) }, modifier = Modifier.weight(1f))
                                FilterChip(selected = custom, onClick = { custom = true }, enabled = !busy, label = { Text(stringResource(R.string.db_music_custom_lyrics)) }, modifier = Modifier.weight(1f))
                            }
                            if (custom) OutlinedTextField(
                                value = lyric,
                                onValueChange = { lyric = it; savedLyric = it },
                                label = { Text(stringResource(R.string.db_music_lyrics)) },
                                enabled = !busy,
                                minLines = 4,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SegmentedColumn {
                                item {
                                    BaseWidget(
                                        title = stringResource(R.string.db_music_parameters),
                                        description = listOf(genre, mood).filter { it.isNotBlank() }.joinToString(" / ")
                                            .ifBlank { stringResource(R.string.db_music_unspecified) },
                                        enabled = !busy,
                                        onClick = { expandedStyle = !expandedStyle },
                                        trailingContent = {
                                            Icon(if (expandedStyle) MaterialSymbols.Outlined.Expand_less else MaterialSymbols.Outlined.Expand_more, contentDescription = null)
                                        },
                                    )
                                }
                                if (expandedStyle) {
                                    item { MusicField(R.string.db_music_theme, theme, !busy) { theme = it; savedTheme = it } }
                                    item { Choice(R.string.db_music_genre, genre, listOf("Pop", "Rock", "Folk", "Electronic", "Hip Hop/Rap", "Chinese Style", "DJ", "R&B/Soul", "Reggae", "Punk", "Jazz"), !busy) { genre = it } }
                                    item { Choice(R.string.db_music_mood, mood, listOf("Happy", "Chill", "Dynamic/Energetic", "Excited", "Sentimental/Melancholic/Lonely", "Inspirational/Hopeful", "Sorrow/Sad", "Nostalgic/Memory", "Romantic"), !busy) { mood = it } }
                                    item {
                                        DropDownMenuWidget(
                                            title = stringResource(R.string.db_music_gender), description = null, value = gender, enabled = !busy,
                                            options = listOf(DropdownOption("", stringResource(R.string.db_music_unspecified)), DropdownOption("Male", stringResource(R.string.db_music_male)), DropdownOption("Female", stringResource(R.string.db_music_female))),
                                            onValueChange = { gender = it },
                                        )
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { start() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.db_music_generate)) }
                                if (busy) OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dialog_cancel)) }
                            }
                            Text(stringResource(R.string.db_music_quota_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            selected?.let { track ->
                                val available = DoubaoMusicStore.audio(track).isFile
                                SegmentedColumn(title = stringResource(R.string.db_music_result)) {
                                    item {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            val cover = DoubaoMusicStore.cover(track)
                                            if (cover.isFile) AsyncImage(model = cover, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                            else Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Icon(MaterialSymbols.Outlined.Music_note, contentDescription = null) }
                                            Column(Modifier.weight(1f)) {
                                                Text(track.title.ifBlank { stringResource(R.string.db_music_untitled) }, style = MaterialTheme.typography.titleSmall)
                                                Text(formatDuration(track.duration), style = MaterialTheme.typography.bodySmall)
                                                Text(stringResource(if (available) R.string.db_music_saved else R.string.db_music_retry_download), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    if (available) {
                                        item {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                OutlinedButton(onClick = { play(track) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(if (playing) R.string.db_music_pause else R.string.db_music_play)) }
                                                OutlinedButton(onClick = { export(context, track) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.db_music_export)) }
                                            }
                                        }
                                        if (duration > 0) item {
                                            Slider(value = position.toFloat().coerceIn(0f, duration.toFloat()), valueRange = 0f..duration.toFloat(), onValueChange = { position = it.toInt(); player?.seekTo(position) })
                                        }
                                        item {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                OutlinedButton(onClick = {
                                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(track.title, track.lyrics))
                                                    progress = context.localizedChatString(R.string.db_music_copied)
                                                }, enabled = track.lyrics.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.db_music_copy_lyrics)) }
                                                Button(onClick = { send(track) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.db_music_send)) }
                                            }
                                        }
                                    } else item { Button(onClick = { start(track) }, enabled = !busy) { Text(stringResource(R.string.db_music_retry_download)) } }
                                    item { BaseWidget(title = stringResource(if (expandedLyrics) R.string.db_music_hide_lyrics else R.string.db_music_show_lyrics), onClick = { expandedLyrics = !expandedLyrics }) }
                                    if (expandedLyrics) item { Text(track.lyrics.ifBlank { stringResource(R.string.db_music_no_lyrics) }, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                    if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (settings) settings = false else if (library) library = false else { job?.cancel(); onDismiss() } }) {
                    Text(stringResource(if (settings || library) R.string.api_profile_back else R.string.dialog_close))
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
                                if (selected?.id == track.id) selected = null
                                revision++
                            } catch (e: Exception) { if (e is CancellationException) throw e; error = failure(e) }
                        }
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    @Composable
    private fun MusicField(titleRes: Int, value: String, enabled: Boolean, multiline: Boolean = false, change: (String) -> Unit) {
        TextFieldDialogWidget(title = stringResource(titleRes), value = value, onValueChange = change,
            dialogTitle = stringResource(titleRes), confirmLabel = stringResource(R.string.dialog_confirm),
            dismissLabel = stringResource(R.string.dialog_cancel), enabled = enabled, singleLine = !multiline)
    }

    @Composable
    private fun Choice(titleRes: Int, value: String, values: List<String>, enabled: Boolean, change: (String) -> Unit) {
        DropDownMenuWidget(title = stringResource(titleRes), description = null, value = value, enabled = enabled,
            options = listOf(DropdownOption("", stringResource(R.string.db_music_unspecified))) + values.map { DropdownOption(it, it) }, onValueChange = change)
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun filename(track: DoubaoMusicTrack): String = track.title
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80)
        .ifBlank { localizedChatString(R.string.db_music_untitled) } + ".m4a"

    private fun export(context: Context, track: DoubaoMusicTrack) {
        TransparentActivity.launch(context) {
            val activity = this
            val launcher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
                if (uri == null) { finish(); return@registerForActivityResult }
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = runCatching {
                        contentResolver.openOutputStream(uri, "w")!!.use { output -> DoubaoMusicStore.audio(track).inputStream().use { it.copyTo(output) } }
                    }.isSuccess
                    withContext(Dispatchers.Main) {
                        showToast(activity, activity.localizedChatString(if (ok) R.string.db_music_exported else R.string.db_music_export_failed))
                        finish()
                    }
                }
            }
            launcher.launch(filename(track))
        }
    }
}
