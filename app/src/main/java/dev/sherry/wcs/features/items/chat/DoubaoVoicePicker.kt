package dev.sherry.wcs.features.items.chat

import android.content.Context
import android.media.MediaPlayer
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Search
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.fs.KnownPaths
import dev.sherry.wcs.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 豆包音色库的一条记录。[rank] 是接口返回顺序，用来判定推荐位；内置 9 条没有在线
 * 记录时为 -1。[preview] 是带签名的试听地址，约一周后失效。
 */
@Serializable
data class DoubaoVoiceInfo(
    val id: String,
    val name: String,
    val lang: String = "",
    val gender: String = "",
    val age: String = "",
    val style: String = "",
    val accent: String = "",
    val role: Boolean = false,
    val builtin: Boolean = false,
    val rank: Int = -1,
    val preview: String = "",
)

private enum class DoubaoVoiceTab(val label: String) {
    BUILTIN("内置"),
    RECOMMEND("推荐"),
    FEMALE("女声"),
    MALE("男声"),
    ROLE("角色"),
    ACCENT("口音"),
}

object DoubaoVoiceStore {
    private const val TAG = "DoubaoVoiceStore"
    private const val API = "https://www.doubao.com/alice/user_voice/recommend"
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 8

    /** 服务端没有推荐标记（heat_score 恒为 0），推荐位只能按接口返回顺序取前若干条。 */
    const val RECOMMEND_WINDOW = 100

    private val cacheFile by lazy { KnownPaths.moduleCache / "doubao_voices.json" }

    fun load(): List<DoubaoVoiceInfo> = runCatching {
        if (!cacheFile.exists()) emptyList()
        else DefaultJson.decodeFromString<List<DoubaoVoiceInfo>>(cacheFile.readText())
    }.getOrElse {
        WeLogger.e(TAG, "failed to read doubao voice cache", it)
        emptyList()
    }

    fun cachedAt(): Long = runCatching {
        if (cacheFile.exists()) cacheFile.getLastModifiedTime().toMillis() else 0L
    }.getOrDefault(0L)

    /** 后台翻页拉全量，成功后覆盖缓存；回调固定在主线程。 */
    fun refresh(cookie: String, onDone: (List<DoubaoVoiceInfo>, String) -> Unit) {
        Thread {
            val result = runCatching { fetchAll(cookie) }
            val list = result.getOrNull().orEmpty()
            var error = result.exceptionOrNull()?.message ?: ""
            if (list.isNotEmpty()) {
                runCatching { cacheFile.writeText(DefaultJson.encodeToString(list)) }
                    .onFailure { WeLogger.e(TAG, "failed to write doubao voice cache", it) }
            } else if (error.isEmpty()) {
                error = "没有取到音色"
            }
            val failure = error
            Handler(Looper.getMainLooper()).post { onDone(list, failure) }
        }.start()
    }

    private fun fetchAll(cookie: String): List<DoubaoVoiceInfo> {
        val device = (7_400_000_000_000_000_000L + (System.nanoTime() and 999_999_999_999_999_999L)).toString()
        val out = ArrayList<DoubaoVoiceInfo>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = JSONObject()
                .put("page_index", page)
                .put("page_size", PAGE_SIZE)
                .put("recommend_type", 1)
                .toString()
            val json = JSONObject(post(url(device), body, cookie))
            if (json.optInt("code") != 0) {
                error("豆包接口返回 code=${json.optInt("code")} ${json.optString("msg")}")
            }
            val data = json.optJSONObject("data") ?: error("豆包接口没有 data")
            val items = data.optJSONArray("ugc_voice_list") ?: error("豆包接口没有音色列表")
            for (i in 0 until items.length()) {
                out += parse(items.getJSONObject(i), out.size)
            }
            if (!data.optBoolean("has_more")) break
            page++
        }
        return out
    }

    private fun url(device: String): String =
        "$API?version_code=20800&language=zh&device_platform=web&doubao_device_platform=web" +
            "&aid=497858&real_aid=497858&pkg_type=release_version&device_id=$device" +
            "&pc_version=3.41.0&doubao_pc_version=3.41.0&web_id=$device&tea_uuid=$device" +
            "&region=CN&sys_region=CN&samantha_web=1&web_platform=browser&use-olympus-account=1" +
            "&web_tab_id=${UUID.randomUUID()}"

    private fun post(endpoint: String, body: String, cookie: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Origin", "https://www.doubao.com")
            setRequestProperty("Referer", "https://www.doubao.com/chat/")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = (if (conn.responseCode == 200) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        return text
    }

    private fun parse(item: JSONObject, rank: Int): DoubaoVoiceInfo {
        fun tag(type: Int): String {
            val tags = item.optJSONArray("tag_list") ?: return ""
            val found = ArrayList<String>()
            for (i in 0 until tags.length()) {
                val entry = tags.getJSONObject(i)
                if (entry.optInt("tag_type") == type) {
                    entry.optString("tag_value").takeIf { it.isNotBlank() }?.let(found::add)
                }
            }
            return found.joinToString("/")
        }

        val id = item.optString("style_id")
        return DoubaoVoiceInfo(
            id = id,
            name = item.optString("name").ifBlank { id },
            lang = item.optString("language"),
            gender = tag(1),
            age = tag(2),
            accent = tag(3),
            style = tag(11),
            role = id.startsWith("ICL_"),
            rank = rank,
            preview = item.optJSONObject("preview")?.optString("preview_audio").orEmpty(),
        )
    }
}

/** 内置 9 条 + 缓存的在线库；同一 id 以在线记录为准并保留内置标记。 */
private fun mergedVoices(): List<DoubaoVoiceInfo> {
    val online = DoubaoVoiceStore.load().associateBy { it.id }
    val builtin = TextToSpeech.DOUBAO_VOICES.map { entry ->
        online[entry.id]?.copy(builtin = true)
            ?: DoubaoVoiceInfo(id = entry.id, name = entry.label, builtin = true)
    }
    val builtinIds = builtin.mapTo(HashSet()) { it.id }
    return builtin + online.values.filterNot { it.id in builtinIds }
}

private fun matchesTab(voice: DoubaoVoiceInfo, tab: DoubaoVoiceTab): Boolean = when (tab) {
    DoubaoVoiceTab.BUILTIN -> voice.builtin
    DoubaoVoiceTab.RECOMMEND -> voice.rank in 0 until DoubaoVoiceStore.RECOMMEND_WINDOW
    DoubaoVoiceTab.FEMALE -> voice.gender == "女"
    DoubaoVoiceTab.MALE -> voice.gender == "男"
    DoubaoVoiceTab.ROLE -> voice.role
    DoubaoVoiceTab.ACCENT -> voice.accent.isNotBlank()
}

private fun voiceMeta(voice: DoubaoVoiceInfo): String =
    listOf(voice.gender, voice.age, voice.accent, voice.style, if (voice.lang == "英语") "英语" else "")
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/** 音色 id 的展示名：先查缓存库，再退内置表，最后退回到 id 本身。 */
fun doubaoVoiceLabel(id: String): String =
    DoubaoVoiceStore.load().firstOrNull { it.id == id }?.name
        ?: TextToSpeech.DOUBAO_VOICES.firstOrNull { it.id == id }?.label
        ?: id

fun showDoubaoVoicePicker(
    context: Context,
    currentId: String,
    cookie: String,
    onPicked: (id: String, name: String) -> Unit,
) {
    showComposeDialog(context) {
        var all by remember { mutableStateOf(mergedVoices()) }
        var tab by remember {
            mutableStateOf(if (all.any { !it.builtin }) DoubaoVoiceTab.RECOMMEND else DoubaoVoiceTab.BUILTIN)
        }
        var query by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf(currentId) }
        var busy by remember { mutableStateOf(false) }
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

        fun playPreview(voice: DoubaoVoiceInfo) {
            stopPreview()
            if (voice.preview.isBlank()) {
                showToast(context, "这个音色没有试听链接")
                return
            }
            busy = true
            val next = MediaPlayer()
            player = next
            playing = voice.id
            next.setOnPreparedListener {
                busy = false
                it.start()
            }
            next.setOnErrorListener { _, _, _ ->
                busy = false
                playing = null
                showToast(context, "试听失败，链接可能已过期，可点右侧刷新")
                true
            }
            next.setOnCompletionListener { playing = null }
            runCatching {
                next.setDataSource(voice.preview)
                next.prepareAsync()
            }.onFailure {
                busy = false
                playing = null
                showToast(context, "试听失败：${it.message}")
            }
        }

        fun refresh() {
            if (cookie.isBlank()) {
                status = "请先在设置里填写豆包 Cookie"
                return
            }
            stopPreview()
            busy = true
            status = "正在拉取豆包音色库…"
            DoubaoVoiceStore.refresh(cookie) { list, error ->
                busy = false
                if (list.isNotEmpty()) {
                    all = mergedVoices()
                    status = ""
                    showToast(context, "已更新 ${list.size} 个音色")
                } else {
                    status = "刷新失败：$error"
                }
            }
        }

        val shown = all.filter { matchesTab(it, tab) }.filter { voice ->
            query.isBlank() || (voice.name + " " + voice.id + " " + voice.style + " " + voice.accent)
                .contains(query, ignoreCase = true)
        }

        AlertDialogContent(
            title = { Text("选择音色") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DoubaoVoiceTab.entries.forEach { entry ->
                            FilterChip(
                                selected = entry == tab,
                                onClick = {
                                    tab = entry
                                    query = ""
                                },
                                label = { Text(entry.label) },
                            )
                        }
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

                    val cachedAt = DoubaoVoiceStore.cachedAt()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("已加载 ${all.count { !it.builtin }} 个在线音色")
                            Text(
                                when {
                                    status.isNotBlank() -> status
                                    cachedAt == 0L -> "还没有缓存，点右侧按钮联网拉取"
                                    else -> "最近缓存 " +
                                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(cachedAt)) +
                                        "，可点击刷新"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { refresh() }, enabled = !busy) {
                            Icon(MaterialSymbols.Outlined.Refresh, contentDescription = "刷新音色库")
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                        items(shown, key = { it.id }) { voice ->
                            VoiceRow(
                                voice = voice,
                                selected = voice.id == picked,
                                playing = playing == voice.id,
                                busy = busy && playing == voice.id,
                                onClick = { picked = voice.id },
                                onPlay = {
                                    if (playing == voice.id) stopPreview() else playPreview(voice)
                                },
                            )
                        }
                        if (shown.isEmpty()) {
                            item {
                                Text(
                                    if (all.all { it.builtin }) {
                                        "这个分类还没有缓存，点上面的刷新按钮联网拉取"
                                    } else {
                                        "没有匹配的音色"
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text("当前选择：" + doubaoVoiceLabel(picked))
                }
            },
            dismissButton = { TextButton(onDismiss) { Text("关闭") } },
            confirmButton = {
                Button(onClick = {
                    stopPreview()
                    onPicked(picked, doubaoVoiceLabel(picked))
                    onDismiss()
                }) { Text("确定") }
            },
        )
    }
}

@Composable
private fun VoiceRow(
    voice: DoubaoVoiceInfo,
    selected: Boolean,
    playing: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
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
                voiceMeta(voice).ifBlank { voice.id },
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
        IconButton(onClick = onPlay, enabled = voice.preview.isNotBlank() && !busy) {
            Icon(
                if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                contentDescription = if (playing) "停止试听" else "试听",
            )
        }
    }
}
