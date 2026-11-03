package dev.sherry.wcs.features.items.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Search
import dev.sherry.wcs.R
import dev.sherry.wcs.features.items.chat.panel.pickPanelFile
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.BreezeTtsClient
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.fs.KnownPaths
import dev.sherry.wcs.utils.openInSystem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

/** Breeze 的收藏与豆包那套分开存——两边音色 id 不在同一个空间里。 */
object BreezeVoiceFavorites {
    private const val PREF_KEY = "breeze_voice_favorites"

    /**
     * 存成 `id|名字`。官方目录上万条且按游标翻页，收藏的音色多半不在已加载的那几页里，
     * 只存 id 会让「收藏」页签把它整个漏掉。
     */
    fun load(): Map<String, String> = WePrefs.getStringSetOrDef(PREF_KEY, emptySet())
        .mapNotNull { entry ->
            val sep = entry.indexOf('|')
            when {
                sep == 0 -> null
                sep < 0 -> entry to ""
                else -> entry.substring(0, sep) to entry.substring(sep + 1)
            }
        }.toMap()

    /** 切换收藏，返回切换后的状态。 */
    fun toggle(id: String, name: String): Boolean {
        val current = load()
        val added = id !in current
        val kept = current.keys - id
        WePrefs.putStringSet(PREF_KEY, if (added) kept + "$id|$name" else kept)
        return added
    }
}

private enum class BreezeVoiceTab(@StringRes val labelRes: Int) {
    OFFICIAL(R.string.tts_tab_official),
    MINE(R.string.tts_tab_mine),
    FAVORITE(R.string.tts_tab_favorite),
}

/**
 * 官方目录的分类。万级音色光翻页翻不动，所以分类交给服务端筛，总数与页数跟着缩小。
 * 每颗 chip 只动一个维度（性别 / 语言 / 内容分类），取值照 docs.breezeblue.ai 的 list-voices。
 */
private enum class BreezeCategory(
    @StringRes val labelRes: Int,
    val gender: String?,
    val language: String?,
    val category: String?,
) {
    ALL(R.string.tts_cat_all, null, null, null),
    MALE(R.string.tts_cat_male, "male", null, null),
    FEMALE(R.string.tts_cat_female, "female", null, null),
    CHINESE(R.string.tts_cat_chinese, null, "zh", null),
    ENGLISH(R.string.tts_cat_english, null, "en", null),
    ANIMATION(R.string.tts_cat_animation, null, null, "animation"),
    GAMING(R.string.tts_cat_gaming, null, null, "gaming"),
    CINEMATIC(R.string.tts_cat_cinematic, null, null, "cinematic"),
    STYLIZED(R.string.tts_cat_stylized, null, null, "stylized"),
    NARRATION(R.string.tts_cat_narration, null, null, "narration"),
    SOCIAL(R.string.tts_cat_social, null, null, "social-media"),
    LEARNING(R.string.tts_cat_learning, null, null, "learning"),
    WELLNESS(R.string.tts_cat_wellness, null, null, "wellness"),
    ADS(R.string.tts_cat_ads, null, null, "ads-brand"),
    PODCAST(R.string.tts_cat_podcast, null, null, "podcast"),
    SUPPORT(R.string.tts_cat_support, null, null, "support-agents"),
    ROLEPLAY(R.string.tts_cat_roleplay, null, null, "roleplay"),
}

private val breezeMainHandler = Handler(Looper.getMainLooper())

/** 与语音面板一致：SAF 的 mime 过滤在各家 ROM 上表现不一，先放开再自己验格式。 */
private val BREEZE_SAMPLE_MIME = arrayOf("*/*")

/** 克隆预览的试听音频与样本副本都只是中间件，落在这个目录，可随时清。 */
private fun breezePreviewDir(): File =
    File(KnownPaths.moduleCache.toFile(), "breeze_previews").apply { mkdirs() }

/**
 * 音色选择器。官方目录是万级，所以那一栏走服务端搜索 + 底部翻页条（每页 50 个，
 * 第 1 页 50 个、第 2 页另 50 个，不是往下接），列表里始终只有一页；
 * 另外两栏量小，本地过滤。
 */
fun showBreezeVoicePicker(
    context: Context,
    currentId: String,
    apiKey: String,
    onPicked: (id: String, name: String) -> Unit,
) {
    showComposeDialog(context) {
        var tab by remember { mutableStateOf(BreezeVoiceTab.OFFICIAL) }
        var category by remember { mutableStateOf(BreezeCategory.ALL) }
        var query by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf(currentId) }
        var favorites by remember { mutableStateOf(BreezeVoiceFavorites.load()) }
        var official by remember { mutableStateOf<List<BreezeTtsClient.Voice>>(emptyList()) }
        var officialTotal by remember { mutableStateOf(0) }
        var officialPage by remember { mutableStateOf(1) }
        var officialSearch by remember { mutableStateOf("") }
        var mine by remember { mutableStateOf<List<BreezeTtsClient.Voice>>(emptyList()) }
        // 两栏会各自并发拉一次，所以用计数而不是布尔，免得先回来的那个把转圈提前关掉
        var pending by remember { mutableStateOf(0) }
        var status by remember { mutableStateOf("") }
        var playing by remember { mutableStateOf<String?>(null) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        // 换关键词、翻页会并发，两条链各自只认自己最新一代的回包
        var officialGen by remember { mutableStateOf(0) }
        var mineGen by remember { mutableStateOf(0) }

        DisposableEffect(Unit) {
            onDispose { player?.release() }
        }

        fun stopPreview() {
            player?.let { runCatching { it.stop() } }
            player?.release()
            player = null
            playing = null
        }

        fun playUrl(url: String, tag: String) {
            stopPreview()
            if (url.isBlank()) {
                showToast(context, context.localizedChatString(R.string.tts_toast_no_preview_audio))
                return
            }
            val next = MediaPlayer()
            player = next
            playing = tag
            next.setOnPreparedListener { it.start() }
            next.setOnErrorListener { _, _, _ ->
                playing = null
                showToast(context, context.localizedChatString(R.string.tts_toast_preview_expired))
                true
            }
            next.setOnCompletionListener { playing = null }
            runCatching {
                next.setDataSource(url)
                next.prepareAsync()
            }.onFailure {
                playing = null
                showToast(
                    context,
                    context.localizedChatString(R.string.tts_toast_preview_failed, it.message.orEmpty()),
                )
            }
        }

        /** 换成第 [page] 页：翻页、首开都走这里，换关键词由调用方传 1。 */
        fun loadOfficial(search: String, page: Int) {
            if (apiKey.isBlank()) {
                status = context.localizedChatString(R.string.tts_breeze_status_need_key)
                return
            }
            val gen = ++officialGen
            // 分类在起线程前就定下来，后台线程不回去读组合里的状态
            val filter = category
            pending += 1
            status = ""
            Thread {
                val outcome = runCatching {
                    BreezeTtsClient.voicesPage(
                        apiKey, false, search, page, filter.gender, filter.language, filter.category
                    )
                }
                breezeMainHandler.post {
                    pending -= 1
                    if (gen != officialGen) return@post
                    outcome.fold(
                        onSuccess = { result ->
                            official = result.voices
                            officialTotal = result.total
                            officialPage = page
                            officialSearch = search
                        },
                        onFailure = {
                            status = context.localizedChatString(
                                R.string.tts_breeze_load_failed, it.message.orEmpty()
                            )
                        },
                    )
                }
            }.start()
        }

        fun loadMine() {
            if (apiKey.isBlank()) {
                status = context.localizedChatString(R.string.tts_breeze_status_need_key)
                return
            }
            val gen = ++mineGen
            pending += 1
            status = ""
            Thread {
                val outcome = runCatching { BreezeTtsClient.allVoices(apiKey, true) }
                breezeMainHandler.post {
                    pending -= 1
                    if (gen != mineGen) return@post
                    outcome.fold(
                        onSuccess = { mine = it },
                        onFailure = {
                            status = context.localizedChatString(
                                R.string.tts_breeze_load_failed, it.message.orEmpty()
                            )
                        },
                    )
                }
            }.start()
        }

        fun refresh() {
            when (tab) {
                // ⟳ 只重拉当前这一页，不跳回第一页
                BreezeVoiceTab.OFFICIAL -> loadOfficial(officialSearch, officialPage)
                BreezeVoiceTab.MINE -> loadMine()
                BreezeVoiceTab.FAVORITE -> {
                    loadOfficial(officialSearch, officialPage)
                    loadMine()
                }
            }
        }

        // 打开就把两栏都拉了：官方只拉第一页，我的量小一次翻完，不用先点 ⟳
        LaunchedEffect(Unit) {
            if (apiKey.isBlank()) {
                status = context.localizedChatString(R.string.tts_breeze_status_need_key)
            } else {
                if (official.isEmpty()) loadOfficial("", 1)
                if (mine.isEmpty()) loadMine()
            }
        }

        // 官方那一栏由服务端搜：停手 400ms 再发，别每敲一个字打一次接口；换词回到第 1 页
        LaunchedEffect(query, tab) {
            if (tab != BreezeVoiceTab.OFFICIAL || apiKey.isBlank()) return@LaunchedEffect
            delay(400)
            if (query != officialSearch) loadOfficial(query, 1)
        }

        val loaded = official + mine
        val favoriteRows = buildList {
            loaded.forEach { voice -> if (voice.id in favorites) add(voice) }
            val ids = loaded.mapTo(mutableSetOf()) { it.id }
            favorites.forEach { (id, name) ->
                if (id !in ids) add(
                    BreezeTtsClient.Voice(
                        id = id,
                        name = name.ifBlank { id },
                        gender = "",
                        age = "",
                        language = "",
                        accent = "",
                        tone = "",
                        previewUrl = "",
                        personal = false,
                    )
                )
            }
        }.distinctBy { it.id }
        val pool = when (tab) {
            BreezeVoiceTab.OFFICIAL -> official
            BreezeVoiceTab.MINE -> mine
            BreezeVoiceTab.FAVORITE -> favoriteRows
        }
        val shown = if (tab == BreezeVoiceTab.OFFICIAL) pool else pool.filter { voice ->
            query.isBlank() || (voice.name + " " + voice.id + " " + voice.meta)
                .contains(query, ignoreCase = true)
        }

        val officialPages =
            (officialTotal + BreezeTtsClient.VOICE_PAGE_SIZE - 1) / BreezeTtsClient.VOICE_PAGE_SIZE

        fun nameOf(id: String): String = loaded.firstOrNull { it.id == id }?.name
            ?: favorites[id]?.takeIf { it.isNotBlank() }
            ?: id.ifBlank { context.localizedChatString(R.string.tts_not_selected) }

        AlertDialogContent(
            title = { Text(stringResource(R.string.tts_breeze_pick_title)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BreezeVoiceTab.entries.forEach { entry ->
                            FilterChip(
                                selected = entry == tab,
                                onClick = {
                                    tab = entry
                                    query = ""
                                },
                                label = { Text(stringResource(entry.labelRes)) },
                            )
                        }
                        if (tab == BreezeVoiceTab.MINE) {
                            FilterChip(
                                selected = false,
                                onClick = { showBreezeCloneDialog(context, apiKey) { loadMine() } },
                                label = { Text(stringResource(R.string.tts_breeze_clone_chip)) },
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { refresh() },
                            label = {
                                Icon(
                                    MaterialSymbols.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.tts_cd_refresh),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // 第二行只在官方那栏出现：数据源 × 分类，两个维度不混在同一排里
                    if (tab == BreezeVoiceTab.OFFICIAL) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BreezeCategory.entries.forEach { entry ->
                                FilterChip(
                                    selected = entry == category,
                                    onClick = {
                                        // 再点一次已选中的那颗 = 取消筛选，回到「全部」
                                        val next = if (entry == category) BreezeCategory.ALL else entry
                                        if (next != category) {
                                            category = next
                                            loadOfficial(officialSearch, 1)
                                        }
                                    },
                                    label = { Text(stringResource(entry.labelRes)) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = null) },
                        placeholder = {
                            Text(
                                stringResource(
                                    if (tab == BreezeVoiceTab.OFFICIAL) R.string.tts_breeze_search_official
                                    else R.string.tts_search_generic
                                )
                            )
                        },
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        when {
                            status.isNotBlank() -> status
                            pending > 0 -> stringResource(R.string.tts_breeze_loading)
                            tab == BreezeVoiceTab.OFFICIAL -> stringResource(
                                R.string.tts_breeze_total, officialTotal
                            ) + " · " +
                                (if (category == BreezeCategory.ALL) "" else
                                    stringResource(category.labelRes) + " · ") +
                                (if (officialSearch.isBlank()) stringResource(R.string.tts_sort_newest)
                                else stringResource(R.string.tts_searching_for, officialSearch))

                            tab == BreezeVoiceTab.MINE ->
                                stringResource(R.string.tts_mine_count, mine.size)

                            else -> stringResource(R.string.tts_fav_count, favorites.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(4.dp))

                    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                        items(shown, key = { it.id }) { voice ->
                            BreezeVoiceRow(
                                voice = voice,
                                selected = voice.id == picked,
                                playing = playing == voice.id,
                                favorite = voice.id in favorites,
                                onClick = { picked = voice.id },
                                onPlay = {
                                    if (playing == voice.id) stopPreview() else playUrl(voice.previewUrl, voice.id)
                                },
                                onFavorite = {
                                    BreezeVoiceFavorites.toggle(voice.id, voice.name)
                                    favorites = BreezeVoiceFavorites.load()
                                },
                            )
                        }
                        if (pending > 0) {
                            item {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        if (shown.isEmpty() && pending == 0) {
                            item {
                                Text(
                                    when {
                                        tab == BreezeVoiceTab.FAVORITE && favorites.isEmpty() ->
                                            stringResource(R.string.tts_empty_favorites)

                                        tab == BreezeVoiceTab.MINE ->
                                            stringResource(R.string.tts_breeze_empty_mine)

                                        else -> stringResource(R.string.tts_empty_match)
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // 翻页条只管官方那栏；另两栏是一次全量加载的，没有下一页
                    if (tab == BreezeVoiceTab.OFFICIAL && officialPages > 0) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { loadOfficial(officialSearch, officialPage - 1) },
                                enabled = officialPage > 1 && pending == 0,
                            ) { Text(stringResource(R.string.tts_btn_prev_page)) }
                            Text(
                                stringResource(R.string.tts_page_indicator, officialPage, officialPages),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { loadOfficial(officialSearch, officialPage + 1) },
                                enabled = officialPage < officialPages && pending == 0,
                            ) { Text(stringResource(R.string.tts_btn_next_page)) }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.tts_current_selection, nameOf(picked)))
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            confirmButton = {
                Button(onClick = {
                    stopPreview()
                    onPicked(picked, nameOf(picked))
                    onDismiss()
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
        )
    }
}

@Composable
private fun BreezeVoiceRow(
    voice: BreezeTtsClient.Voice,
    selected: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(voice.name.take(1), color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                voice.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                voice.meta.ifBlank { voice.id },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                MaterialSymbols.Outlined.Check,
                contentDescription = stringResource(R.string.tts_cd_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onPlay, enabled = voice.previewUrl.isNotBlank()) {
            Icon(
                if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                contentDescription = stringResource(
                    if (playing) R.string.tts_cd_stop_preview else R.string.tts_btn_preview
                ),
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                MaterialSymbols.Outlined.Favorite,
                contentDescription = stringResource(
                    if (favorite) R.string.tts_cd_unfavorite else R.string.tts_cd_favorite
                ),
                tint = if (favorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 克隆音色：一条参考样本换一次预览，试听脚本由服务端按检测到的参考语言自动生成。
 * 文档写明 consent required，所以授权勾选做成硬门槛；预览本身不占槽位，保存才占。
 */
private fun showBreezeCloneDialog(
    context: Context,
    apiKey: String,
    onSaved: () -> Unit,
) {
    showComposeDialog(context) {
        var voiceName by remember { mutableStateOf("") }
        var language by remember { mutableStateOf(BreezeTtsClient.CLONE_LANGUAGES.first().first) }
        var denoise by remember { mutableStateOf(false) }
        var consent by remember { mutableStateOf(false) }
        var sample by remember { mutableStateOf<CloneSample?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<BreezeTtsClient.ClonePreview?>(null) }
        var playing by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }

        DisposableEffect(Unit) {
            onDispose { player?.release() }
        }

        fun stop() {
            player?.let { runCatching { it.stop() } }
            player?.release()
            player = null
            playing = false
        }

        /**
         * 选完立刻复制进缓存并探时长：3 秒与 5 MB 是文档写死的门槛，本地先挡住，
         * 省一次必然失败的上传。探不出时长（返回 0）不拦，交给接口判。
         */
        fun pickSample() {
            pickPanelFile(context, BREEZE_SAMPLE_MIME) { name, uri, activity ->
                busy = true
                status = context.localizedChatString(R.string.tts_reading_audio)
                Thread {
                    val outcome = runCatching {
                        val suffix = name.substringAfterLast('.', "wav").lowercase()
                        val dest = File(breezePreviewDir(), "sample-${System.currentTimeMillis()}.$suffix")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use(input::copyTo)
                        } ?: throw BreezeTtsClient.BreezeError(
                            context.localizedChatString(R.string.tts_clone_err_unreadable)
                        )
                        val bytes = dest.length()
                        if (bytes > BreezeTtsClient.CLONE_MAX_BYTES) {
                            throw BreezeTtsClient.BreezeError(
                                context.localizedChatString(
                                    R.string.tts_clone_err_too_big, sizeText(bytes)
                                )
                            )
                        }
                        val durationMs = probeDurationMs(dest)
                        if (durationMs in 1 until BreezeTtsClient.CLONE_MIN_MS) {
                            throw BreezeTtsClient.BreezeError(
                                context.localizedChatString(
                                    R.string.tts_clone_err_too_short,
                                    secondText(context, durationMs),
                                )
                            )
                        }
                        CloneSample(name, dest, bytes, durationMs)
                    }
                    breezeMainHandler.post {
                        busy = false
                        activity.finish()
                        outcome.fold(
                            onSuccess = {
                                sample = it
                                status = ""
                            },
                            onFailure = {
                                sample = null
                                status = context.localizedChatString(
                                    R.string.tts_clone_err_unusable, it.message.orEmpty()
                                )
                            },
                        )
                    }
                }.start()
            }
        }

        fun audition(target: BreezeTtsClient.ClonePreview) {
            if (playing) {
                stop()
                return
            }
            busy = true
            status = context.localizedChatString(R.string.tts_fetching_preview)
            Thread {
                val outcome = runCatching {
                    val file = File(breezePreviewDir(), "clone_${target.generatedId}.mp3")
                    if (!file.exists()) BreezeTtsClient.previewAudio(apiKey, target.generatedId, file)
                    file.absolutePath
                }
                breezeMainHandler.post {
                    busy = false
                    val path = outcome.getOrElse {
                        status = context.localizedChatString(
                            R.string.tts_clone_err_no_preview, it.message.orEmpty()
                        )
                        return@post
                    }
                    val next = MediaPlayer()
                    player = next
                    playing = true
                    next.setOnErrorListener { _, _, _ ->
                        playing = false
                        status = context.localizedChatString(R.string.tts_preview_failed)
                        true
                    }
                    next.setOnCompletionListener { playing = false }
                    runCatching {
                        next.setDataSource(path)
                        next.prepare()
                        next.start()
                    }.onFailure {
                        playing = false
                        status = context.localizedChatString(
                            R.string.tts_toast_preview_failed, it.message.orEmpty()
                        )
                    }
                }
            }.start()
        }

        fun clone() {
            val chosen = sample
            if (chosen == null) {
                status = context.localizedChatString(R.string.tts_clone_need_sample)
                return
            }
            if (!consent) {
                status = context.localizedChatString(R.string.tts_clone_need_consent)
                return
            }
            stop()
            busy = true
            status = context.localizedChatString(R.string.tts_clone_running)
            Thread {
                val outcome = runCatching {
                    BreezeTtsClient.clonePreview(apiKey, chosen.file, voiceName.trim(), language, denoise)
                }
                breezeMainHandler.post {
                    busy = false
                    outcome.fold(
                        onSuccess = {
                            preview = it
                            status = if (it.requiresVerification) {
                                context.localizedChatString(R.string.tts_clone_preview_need_verify)
                            } else {
                                context.localizedChatString(R.string.tts_clone_preview_ready)
                            }
                        },
                        onFailure = {
                            status = context.localizedChatString(R.string.tts_clone_failed, it.message.orEmpty())
                        },
                    )
                }
            }.start()
        }

        fun save(target: BreezeTtsClient.ClonePreview) {
            stop()
            busy = true
            status = context.localizedChatString(R.string.tts_saving)
            val name = voiceName.trim().ifBlank { sample?.stem ?: "Cloned Voice" }
            Thread {
                val outcome = runCatching {
                    BreezeTtsClient.savePreview(apiKey, target.generatedId, name, language)
                }
                breezeMainHandler.post {
                    busy = false
                    outcome.fold(
                        onSuccess = {
                            showToast(
                                context,
                                context.localizedChatString(R.string.tts_clone_saved, it.name),
                            )
                            WeLogger.i("BreezeClone", "saved voice ${it.id}")
                            onSaved()
                            onDismiss()
                        },
                        onFailure = {
                            status = context.localizedChatString(
                                R.string.tts_clone_save_failed, it.message.orEmpty()
                            )
                        },
                    )
                }
            }.start()
        }

        val done = preview
        AlertDialogContent(
            title = {
                Text(
                    stringResource(
                        if (done == null) R.string.tts_clone_title
                        else R.string.tts_clone_result_title
                    )
                )
            },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (done == null) {
                        OutlinedTextField(
                            value = voiceName,
                            onValueChange = { voiceName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.tts_clone_name_label)) },
                            placeholder = { Text(stringResource(R.string.tts_clone_name_hint)) },
                        )
                        Spacer(Modifier.height(10.dp))
                        DropDownMenuWidget(
                            title = stringResource(R.string.tts_clone_language),
                            description = stringResource(R.string.tts_clone_language_hint),
                            value = language,
                            options = BreezeTtsClient.CLONE_LANGUAGES.map {
                                DropdownOption(
                                    it.first,
                                    stringResource(
                                        when (it.first) {
                                            "zh" -> R.string.tts_clone_lang_zh
                                            else -> R.string.tts_clone_lang_auto
                                        }
                                    ),
                                )
                            },
                            onValueChange = { language = it },
                        )
                        SwitchWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.tts_clone_denoise),
                            description = stringResource(R.string.tts_clone_denoise_hint),
                            checked = denoise,
                            onCheckedChange = { denoise = it },
                        )

                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.tts_clone_sample),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.tts_clone_sample_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BaseWidget(
                            title = stringResource(R.string.tts_clone_pick_sample),
                            description = sample?.let {
                                val duration = if (it.durationMs > 0) secondText(context, it.durationMs)
                                else context.localizedChatString(R.string.tts_duration_unknown)
                                "${it.name} · ${sizeText(it.bytes)} · $duration"
                            } ?: stringResource(R.string.tts_clone_no_sample),
                            enabled = !busy,
                            onClick = { pickSample() },
                        )

                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = consent, onCheckedChange = { consent = it })
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.tts_clone_consent),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(voiceName.ifBlank { sample?.stem ?: "Cloned Voice" })
                                Text(
                                    stringResource(R.string.tts_clone_preview_info, done.generatedId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { audition(done) }, enabled = !busy) {
                                Icon(
                                    if (playing) MaterialSymbols.Outlined.Pause
                                        else MaterialSymbols.Outlined.Play_arrow,
                                    contentDescription = stringResource(R.string.tts_cd_preview_clone),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.tts_clone_preview_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (done.requiresVerification) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.tts_clone_verify_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = {
                                Uri.parse("https://breezeblue.ai").openInSystem(context)
                            }) { Text(stringResource(R.string.tts_clone_open_site)) }
                        }
                    }

                    if (status.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    stop()
                    if (done == null) onDismiss() else preview = null
                }) {
                    Text(
                        stringResource(
                            if (done == null) R.string.dialog_cancel
                            else R.string.tts_clone_again
                        )
                    )
                }
            },
            confirmButton = {
                if (done == null) {
                    Button(
                        onClick = { clone() },
                        enabled = !busy && consent && sample != null,
                    ) { Text(stringResource(R.string.tts_clone_start)) }
                } else {
                    Button(onClick = { save(done) }, enabled = !busy) {
                        Text(stringResource(R.string.tts_clone_save))
                    }
                }
            },
        )
    }
}

/** 一条参考样本：选完就复制进缓存，克隆接口要的是本地文件。 */
private data class CloneSample(
    val name: String,
    val file: File,
    val bytes: Long,
    val durationMs: Long,
) {
    val stem: String get() = name.substringBeforeLast('.')
}

/** 探不到时长就报 0，让调用方自己决定要不要放行。 */
private fun probeDurationMs(file: File): Long = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        runCatching { retriever.release() }
    }
}.getOrDefault(0L)

private fun sizeText(bytes: Long): String = if (bytes >= 1024 * 1024) {
    String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
} else {
    "${bytes / 1024} KB"
}

private fun secondText(context: Context, ms: Long): String = context.localizedChatString(
    R.string.tts_seconds,
    String.format(Locale.ROOT, "%.1f", ms / 1000.0),
)
