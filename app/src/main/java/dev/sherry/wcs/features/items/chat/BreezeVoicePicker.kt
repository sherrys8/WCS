package dev.sherry.wcs.features.items.chat

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Search
import dev.sherry.wcs.features.items.chat.panel.listPanelTreeFiles
import dev.sherry.wcs.features.items.chat.panel.pickPanelDirectory
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.AlertDialogContent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Breeze 的收藏与豆包那套分开存——两边音色 id 不在同一个空间里。 */
object BreezeVoiceFavorites {
    private const val PREF_KEY = "breeze_voice_favorites"

    fun load(): Set<String> = WePrefs.getStringSetOrDef(PREF_KEY, emptySet())

    /** 切换收藏，返回切换后的状态。 */
    fun toggle(id: String): Boolean {
        val current = load()
        val added = id !in current
        WePrefs.putStringSet(PREF_KEY, if (added) current + id else current - id)
        return added
    }
}

private enum class BreezeVoiceTab(val label: String) {
    OFFICIAL("官方"),
    MINE("我的"),
    FAVORITE("收藏"),
}

private val breezeMainHandler = Handler(Looper.getMainLooper())

/** 克隆预览的试听音频只用来听，落在这个目录，可随时清。 */
private fun breezePreviewDir(): File =
    File(KnownPaths.moduleCache.toFile(), "breeze_previews").apply { mkdirs() }

fun showBreezeVoicePicker(
    context: Context,
    currentId: String,
    apiKey: String,
    onPicked: (id: String, name: String) -> Unit,
) {
    showComposeDialog(context) {
        var official by remember { mutableStateOf<List<BreezeTtsClient.Voice>>(emptyList()) }
        var mine by remember { mutableStateOf<List<BreezeTtsClient.Voice>>(emptyList()) }
        var tab by remember { mutableStateOf(BreezeVoiceTab.OFFICIAL) }
        var query by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf(currentId) }
        var favorites by remember { mutableStateOf(BreezeVoiceFavorites.load()) }
        var loading by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var playing by remember { mutableStateOf<String?>(null) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }

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
                showToast(context, "这个音色没有试听音频")
                return
            }
            val next = MediaPlayer()
            player = next
            playing = tag
            next.setOnPreparedListener { it.start() }
            next.setOnErrorListener { _, _, _ ->
                playing = null
                showToast(context, "试听失败，链接可能已过期")
                true
            }
            next.setOnCompletionListener { playing = null }
            runCatching {
                next.setDataSource(url)
                next.prepareAsync()
            }.onFailure {
                playing = null
                showToast(context, "试听失败：${it.message}")
            }
        }

        fun refresh(onlyMine: Boolean) {
            if (apiKey.isBlank()) {
                status = "先在文字转语音设置里填写 Breeze API Key"
                return
            }
            loading = true
            status = ""
            Thread {
                val failure = StringBuilder()
                val officialList = if (onlyMine) null else runCatching { BreezeTtsClient.voices(apiKey, false) }
                    .onFailure { failure.append("官方音色：").append(it.message).append(' ') }
                    .getOrNull()
                val mineList = runCatching { BreezeTtsClient.voices(apiKey, true) }
                    .onFailure { failure.append("我的音色：").append(it.message).append(' ') }
                    .getOrNull()
                breezeMainHandler.post {
                    loading = false
                    officialList?.let { official = it }
                    mineList?.let { mine = it }
                    if (failure.isNotEmpty()) status = "拉取失败：$failure"
                }
            }.start()
        }

        LaunchedEffect(Unit) {
            if (apiKey.isBlank()) status = "先在文字转语音设置里填写 Breeze API Key"
            else if (official.isEmpty() && mine.isEmpty()) refresh(onlyMine = false)
        }

        val pool = when (tab) {
            BreezeVoiceTab.OFFICIAL -> official
            BreezeVoiceTab.MINE -> mine
            BreezeVoiceTab.FAVORITE -> (official + mine).filter { it.id in favorites }
        }
        val shown = pool.filter { voice ->
            query.isBlank() || (voice.name + " " + voice.id + " " + voice.meta)
                .contains(query, ignoreCase = true)
        }
        // 选中项可能在别的页签上，取名字要跨两边找
        val pickedName = (official + mine).firstOrNull { it.id == picked }?.name ?: picked.ifBlank { "未选择" }

        AlertDialogContent(
            title = { Text("选择 Breeze 音色") },
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
                                label = { Text(entry.label) },
                            )
                        }
                        if (tab == BreezeVoiceTab.MINE) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    showBreezeCloneDialog(context, apiKey) { refresh(onlyMine = true) }
                                },
                                label = { Text("＋ 克隆音色") },
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { refresh(onlyMine = tab == BreezeVoiceTab.MINE) },
                            label = {
                                Icon(
                                    MaterialSymbols.Outlined.Refresh,
                                    contentDescription = "重新拉取",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("搜索名称、ID 或标签") },
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        when {
                            status.isNotBlank() -> status
                            loading -> "正在拉取音色列表…"
                            else -> "官方 ${official.size} 个 · 我的 ${mine.size} 个"
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
                                    favorites = if (BreezeVoiceFavorites.toggle(voice.id)) {
                                        favorites + voice.id
                                    } else {
                                        favorites - voice.id
                                    }
                                },
                            )
                        }
                        if (loading) {
                            item {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        if (shown.isEmpty() && !loading) {
                            item {
                                Text(
                                    when {
                                        tab == BreezeVoiceTab.FAVORITE && favorites.isEmpty() && query.isBlank() ->
                                            "还没有收藏，点每个音色右侧的心形把它收进来"

                                        tab == BreezeVoiceTab.MINE && query.isBlank() ->
                                            "还没有自建音色，点上面的「＋ 克隆音色」用一条参考音频克隆一个"

                                        else -> "没有匹配的音色"
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text("当前选择：$pickedName")
                }
            },
            dismissButton = { TextButton(onDismiss) { Text("关闭") } },
            confirmButton = {
                Button(onClick = {
                    stopPreview()
                    onPicked(picked, (official + mine).firstOrNull { it.id == picked }?.name ?: picked)
                    onDismiss()
                }) { Text("确定") }
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
            Text(voice.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
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
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onPlay, enabled = voice.previewUrl.isNotBlank()) {
            Icon(
                if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                contentDescription = if (playing) "停止试听" else "试听",
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                MaterialSymbols.Outlined.Favorite,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
        var language by remember { mutableStateOf(CLONE_LANGUAGES.first().first) }
        var denoise by remember { mutableStateOf(false) }
        var consent by remember { mutableStateOf(false) }
        // null = 默认目录 Download/WcS/；换成别的目录时这里是 SAF 的 tree uri
        var sampleTree by remember { mutableStateOf<Uri?>(null) }
        var samples by remember { mutableStateOf(defaultSamples()) }
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

        /** 换到用户自选目录：走和语音面板同一个系统目录选择器，列完就 finish 掉透明 Activity。 */
        fun browseSamples() {
            pickPanelDirectory(context) { treeUri, activity ->
                busy = true
                status = "正在列目录…"
                Thread {
                    val outcome = runCatching {
                        listPanelTreeFiles(activity.contentResolver, treeUri)
                            .filter {
                                it.name.substringAfterLast('.', "").lowercase() in BreezeTtsClient.CLONE_SUFFIXES
                            }
                            .map { CloneSample(it.name, "", null, it.uri) }
                    }
                    breezeMainHandler.post {
                        busy = false
                        activity.finish()
                        outcome.fold(
                            onSuccess = {
                                sampleTree = treeUri
                                samples = it
                                sample = null
                                status = if (it.isEmpty()) "这个目录里没有合规的音频文件" else ""
                            },
                            onFailure = { status = "列目录失败：${it.message}" },
                        )
                    }
                }.start()
            }
        }

        fun useDefaultSamples() {
            sampleTree = null
            samples = defaultSamples()
            sample = null
        }

        fun audition(target: BreezeTtsClient.ClonePreview) {
            if (playing) {
                stop()
                return
            }
            busy = true
            status = "正在取试听音频…"
            Thread {
                val outcome = runCatching {
                    val file = File(breezePreviewDir(), "clone_${target.generatedId}.mp3")
                    if (!file.exists()) BreezeTtsClient.previewAudio(apiKey, target.generatedId, file)
                    file.absolutePath
                }
                breezeMainHandler.post {
                    busy = false
                    val path = outcome.getOrElse {
                        status = "试听音频取不到：${it.message}"
                        return@post
                    }
                    val next = MediaPlayer()
                    player = next
                    playing = true
                    next.setOnErrorListener { _, _, _ ->
                        playing = false
                        status = "试听失败"
                        true
                    }
                    next.setOnCompletionListener { playing = false }
                    runCatching {
                        next.setDataSource(path)
                        next.prepare()
                        next.start()
                    }.onFailure {
                        playing = false
                        status = "试听失败：${it.message}"
                    }
                }
            }.start()
        }

        fun clone() {
            val chosen = sample
            if (chosen == null) {
                status = "先选一条参考音频"
                return
            }
            if (!consent) {
                status = "先勾选声音授权确认"
                return
            }
            stop()
            busy = true
            status = "正在克隆…（接口同步处理，可能要十几秒）"
            Thread {
                val outcome = runCatching {
                    BreezeTtsClient.clonePreview(
                        apiKey, fileFor(context, chosen), voiceName.trim(), language, denoise,
                    )
                }
                breezeMainHandler.post {
                    busy = false
                    outcome.fold(
                        onSuccess = {
                            preview = it
                            status = if (it.requiresVerification) {
                                "预览已生成，但这个样本需要先在 breezeblue.ai 完成本人验证，否则保存会被拒"
                            } else {
                                "预览已生成，先试听再决定保存"
                            }
                        },
                        onFailure = { status = "克隆失败：${it.message}" },
                    )
                }
            }.start()
        }

        fun save(target: BreezeTtsClient.ClonePreview) {
            stop()
            busy = true
            status = "正在保存到账号…"
            val name = voiceName.trim().ifBlank { sample?.stem ?: "Cloned Voice" }
            Thread {
                val outcome = runCatching {
                    BreezeTtsClient.savePreview(apiKey, target.generatedId, name, language)
                }
                breezeMainHandler.post {
                    busy = false
                    outcome.fold(
                        onSuccess = {
                            showToast(context, "已保存音色「${it.name}」")
                            WeLogger.i("BreezeClone", "saved voice ${it.id}")
                            onSaved()
                            onDismiss()
                        },
                        onFailure = { status = "保存失败：${it.message}" },
                    )
                }
            }.start()
        }

        val done = preview
        AlertDialogContent(
            title = { Text(if (done == null) "克隆音色" else "克隆结果") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (done == null) {
                        OutlinedTextField(
                            value = voiceName,
                            onValueChange = { voiceName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("音色名字") },
                            placeholder = { Text("留空则用文件名，最长 80 字") },
                        )
                        Spacer(Modifier.height(10.dp))
                        DropDownMenuWidget(
                            title = "参考语言",
                            description = "中文样本必须选中文，否则识别不到内容",
                            value = language,
                            options = CLONE_LANGUAGES.map { DropdownOption(it.first, it.second) },
                            onValueChange = { language = it },
                        )
                        SwitchWidget(
                            iconPlaceholder = false,
                            title = "先做降噪",
                            description = "样本里有底噪时打开",
                            checked = denoise,
                            onCheckedChange = { denoise = it },
                        )

                        Spacer(Modifier.height(10.dp))
                        Text("参考音频", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "单人声、无背景音乐、3 秒以上 5 MB 以内。接口只分析前 60 秒，会自动截成不超过 30 秒的片段。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (sampleTree == null) "默认目录 Download/WcS/" else "来自自选目录",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { browseSamples() }, enabled = !busy) { Text("浏览其他目录") }
                            if (sampleTree != null) {
                                TextButton(onClick = { useDefaultSamples() }, enabled = !busy) { Text("回默认") }
                            }
                        }
                        if (samples.isEmpty()) {
                            Text(
                                if (sampleTree == null) {
                                    "默认目录里还没有可用的音频文件。可以先用「保存语音到本地」导出 mp3，或点「浏览其他目录」。"
                                } else {
                                    "这个目录里没有合规的音频文件。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        samples.forEach { item ->
                            val chosen = item.key == sample?.key
                            Row(
                                Modifier.fillMaxWidth().clickable { sample = item }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.detail.isNotBlank()) {
                                        Text(
                                            item.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (chosen) {
                                    Icon(
                                        MaterialSymbols.Outlined.Check,
                                        contentDescription = "已选择",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = consent, onCheckedChange = { consent = it })
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "我确认这段声音属于我自己，或已获得声音本人的明确同意用于克隆。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(voiceName.ifBlank { sample?.stem ?: "Cloned Voice" })
                                Text(
                                    "预览 ${done.generatedId} · 还没有占用音色槽位",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { audition(done) }, enabled = !busy) {
                                Icon(
                                    if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                                    contentDescription = "试听克隆预览",
                                )
                            }
                        }
                        Text(
                            "试听脚本由服务端自动生成，不由我们提供文本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (done.requiresVerification) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "需要本人验证：到 breezeblue.ai 完成后再回来保存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = {
                                Uri.parse("https://breezeblue.ai").openInSystem(context)
                            }) { Text("打开 breezeblue.ai") }
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
                }) { Text(if (done == null) "取消" else "重新克隆") }
            },
            confirmButton = {
                if (done == null) {
                    Button(onClick = { clone() }, enabled = !busy && consent && sample != null) { Text("开始克隆") }
                } else {
                    Button(onClick = { save(done) }, enabled = !busy) { Text("保存到账号") }
                }
            },
        )
    }
}

/** 参考语言只放文档点名的两种加自动，不在这里铺一长串猜不出来的代码。 */
private val CLONE_LANGUAGES = listOf(
    "" to "自动检测",
    "zh" to "中文",
    "en" to "英文",
)

/** 一条候选样本：默认目录里是 File，自选目录里是 SAF 的 uri。 */
private data class CloneSample(
    val name: String,
    val detail: String,
    val file: File?,
    val uri: Uri?,
) {
    val key: String get() = file?.absolutePath ?: uri.toString()
    val stem: String get() = name.substringBeforeLast('.')
}

/** 默认目录 Download/WcS/：只列格式与体积合规的音频，最新的排前面。 */
private fun defaultSamples(): List<CloneSample> =
    KnownPaths.downloads.toFile().listFiles()
        ?.filter {
            it.isFile &&
                it.extension.lowercase() in BreezeTtsClient.CLONE_SUFFIXES &&
                it.length() <= BreezeTtsClient.CLONE_MAX_BYTES
        }
        ?.sortedByDescending { it.lastModified() }
        ?.map { file ->
            CloneSample(
                name = file.name,
                detail = "${file.length() / 1024} KB · " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                file = file,
                uri = null,
            )
        }
        .orEmpty()

/** 自选目录里的文件要先落到本地才能进 multipart；扩展名沿用原文件名。 */
private fun fileFor(context: Context, sample: CloneSample): File {
    sample.file?.let { return it }
    val uri = sample.uri ?: throw BreezeTtsClient.BreezeError("样本既没有路径也没有 uri")
    val suffix = sample.name.substringAfterLast('.', "mp3").lowercase()
    val dest = File(breezePreviewDir(), "sample-${System.currentTimeMillis()}.$suffix")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use(input::copyTo)
    } ?: throw BreezeTtsClient.BreezeError("读不到选中的文件")
    return dest
}
