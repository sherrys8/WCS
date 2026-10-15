package dev.sherry.wcs.features.items.chat_input_bar_menu

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Error
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_down
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Video_file
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
import dev.sherry.wcs.features.api.ui.WeChatInputBarMenuApi
import dev.sherry.wcs.features.api.ui.WeCurrentConversationApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.Button
import dev.sherry.wcs.ui.content.ContactsSelector
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.readTextFromClipboard
import dev.sherry.wcs.utils.android.showToast
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import dev.sherry.wcs.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.TimeUnit

object ParseVideo : ClickableFeature() {

    override val technicalId = "短视频解析"
    override val nameRes = R.string.feature_parse_video_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_parse_video_description

    private const val TAG = "ParseVideo"

    /** 主解析线路：api.51web.eu.org（抖音多清晰度无水印），token 由用户在设置里自带。 */
    private const val PARSE_API_PRIMARY = "https://api.51web.eu.org/api/parse"
    private const val PARSE_API_PRIMARY_ORIGIN = "https://api.51web.eu.org"

    /** 备用解析线路1：kit9 聚合解析。 */
    private const val PARSE_API = "https://apis.kit9.cn/api/aggregate_videos/api.php"
    private const val PARSE_API_ORIGIN = "https://apis.kit9.cn"

    /** 备用解析线路2：bug 抖音原画（data.url 是原画直链，video_backup 给降级档位）。 */
    private const val PARSE_API_BACKUP2 = "https://api.bugpk.com/api/douyin"
    private const val PARSE_API_BACKUP2_ORIGIN = "https://api.bugpk.com"

    /** 备用解析线路3：bug 聚合 svparse 网关，需要 API Key，响应结构与备用线路2 同构。 */
    private const val PARSE_API_BACKUP3 = "https://api-new.ifphp.com/api/svparse"
    private const val PARSE_API_BACKUP3_ORIGIN = "https://api-new.ifphp.com"

    private const val DEFAULT_BUFFER_SIZE = 8192

    /** 线路选择（解析弹窗右上角可切换，持久化到偏好）。auto = 按默认优先级回退；其余固定走所选线路。 */
    private const val ROUTE_AUTO = "auto"
    private const val ROUTE_PRIMARY = "primary"
    private const val ROUTE_BACKUP = "backup"
    private const val ROUTE_BACKUP2 = "backup2"
    private const val ROUTE_BACKUP3 = "backup3"

    private val urlRegex = Regex("""https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""")

    /** 抖音分享链接（v.douyin.com 短链 / www.douyin.com / iesdouyin 等）。 */
    private val douyinUrlRegex = Regex(
        """https?://(?:[\w\-.]*douyin\.com|v\.douyin\.com|iesdouyin\.com)/[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE,
    )

    private var saveDir by prefOption("parse_video_save_dir", "")
    private var autoReply by prefOption("parse_video_auto_reply", false)

    /** 自动解析白名单：群聊与私聊都可以选，空集 = 任何会话都不解析。 */
    private var autoReplyWhitelist by prefOption("parse_video_whitelist", emptySet<String>())

    /** 当前解析线路（见 ROUTE_* 常量），手动弹窗与自动解析共用同一选择。 */
    private var parseRoute by prefOption("parse_video_route", ROUTE_AUTO)

    /** 主线路请求的 pid 参数（线路1~线路7 → 数字 1~7），手动弹窗与自动解析共用。 */
    private var parsePid by prefOption("parse_video_pid", 2)

    /** 主线路 token，与汽水线路同思路由用户自带；为空时主线路不可用，auto 直接走备用线路。 */
    private var primaryToken by prefOption("parse_video_primary_token", "")
    private var backup3Key by prefOption("parse_video_backup3_key", "")

    private fun defaultSaveDir(): String =
        (KnownPaths.downloads / "ParseVideo").absolutePathString()

    private fun currentSaveDir(): String =
        saveDir.ifBlank { defaultSaveDir() }

    private fun ensureSaveDir(): java.io.File =
        java.io.File(currentSaveDir()).apply { mkdirs() }

    /** coerceInputValues：bug 系线路在图集/实况笔记里把 `url`、`duration` 直出为 JSON null，非空属性带默认值时按默认值收。 */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "parse_video",
                icon = MaterialSymbols.Outlined.Video_file,
                label = localizedChatInputString(R.string.feature_parse_video_name),
                onClick = { context, _ ->
                    showParseDialog(context)
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookBefore {
            if (!autoReply) return@hookBefore
            val msgInfo = MessageInfo(args[0]!!)
            handleAutoReply(msgInfo)
        }
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    override fun onClick(context: androidx.activity.ComponentActivity) {
        showComposeDialog(context) {
            var autoReplyChecked by remember { mutableStateOf(autoReply) }
            var primaryTokenState by remember { mutableStateOf(primaryToken) }
            var backup3KeyState by remember { mutableStateOf(backup3Key) }
            var whitelistRevision by remember { mutableIntStateOf(0) }
            val whitelistCount = remember(whitelistRevision) { autoReplyWhitelist.size }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_parse_video_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    ) {
                        SegmentedColumn(title = stringResource(R.string.parse_video_auto_reply_group)) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.parse_video_auto_reply),
                                    description = stringResource(R.string.parse_video_auto_reply_description),
                                    checked = autoReplyChecked,
                                    onCheckedChange = {
                                        autoReplyChecked = it
                                        autoReply = it
                                    },
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.parse_video_whitelist_conversations),
                                    description = if (whitelistCount == 0) {
                                        stringResource(R.string.parse_video_whitelist_empty)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.parse_video_whitelist_count,
                                            whitelistCount,
                                            whitelistCount,
                                        )
                                    },
                                    onClick = { showWhitelistSelector(context) { whitelistRevision++ } },
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

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = stringResource(R.string.parse_video_primary_route_group)) {
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.parse_video_primary_token),
                                    value = primaryTokenState,
                                    onValueChange = {
                                        primaryTokenState = it
                                        primaryToken = it.trim()
                                    },
                                    dialogTitle = stringResource(R.string.parse_video_primary_token),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.parse_video_primary_token_hint),
                                    password = true,
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = stringResource(R.string.parse_video_backup3_route_group)) {
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.parse_video_backup3_key),
                                    value = backup3KeyState,
                                    onValueChange = {
                                        backup3KeyState = it
                                        backup3Key = it.trim()
                                    },
                                    dialogTitle = stringResource(R.string.parse_video_backup3_key),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.parse_video_backup3_key_hint),
                                    password = true,
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_close))
                    }
                },
            )
        }
    }

    /** 白名单多选：好友 + 群聊一起列出，选中即生效（空集 = 全部不生效）。 */
    private fun showWhitelistSelector(context: android.content.Context, onUpdated: () -> Unit) {
        val contacts = runCatching {
            WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        }.getOrElse {
            WeLogger.e(TAG, "failed to load contacts for whitelist", it)
            emptyList()
        }
        showComposeDialog(context) {
            ContactsSelector(
                title = stringResource(R.string.parse_video_select_whitelist),
                contacts = contacts,
                initialSelectedWxIds = autoReplyWhitelist,
                onDismiss = onDismiss,
            ) { selected ->
                autoReplyWhitelist = selected
                onUpdated()
                onDismiss()
            }
        }
    }

    // ==================== 数据模型 ====================

    @Serializable
    private data class VideoParseResult(
        val code: Int,
        val msg: String,
        val data: JsonElement?,
        /** 主线路与备用线路2返回的多清晰度列表（备用线路1为空）；(label, url) 按清晰度降序。 */
        val qualityList: List<Pair<String, String>> = emptyList(),
        /** 图集（图片列表，无视频时非空）。 */
        val imageList: List<String> = emptyList(),
        /** 主线路直出封面；若与图集任一张相同则置空（以图集为准，UI 不再显示封面）。 */
        val coverUrl: String = "",
        /** 备用线路1的多视频直链、备用线路2/3 的实况动图：不供逐档选择，下载时全部取下（qualityList 此时即视频列表，与图集同思路）。 */
        val downloadAllVideos: Boolean = false,
    ) {
        /** 有没有可下载的动态内容：单视频直链在 data.video_link，多视频与实况直链在 qualityList。 */
        fun hasVideo(): Boolean = qualityList.isNotEmpty() || parsedData()?.video_link?.isNotBlank() == true

        /** 把 data(JsonElement) 解析成 VideoData 对象，兼容 data 为字符串(错误信息)的情况 */
        fun parsedData(): VideoData? {
            if (code != 200) return null
            val element = data ?: return null
            if (element !is kotlinx.serialization.json.JsonObject) return null
            return runCatching {
                json.decodeFromString<VideoData>(element.toString())
            }.getOrNull()
        }
    }

    @Serializable
    private data class VideoData(
        val video_id: String = "",
        val video_title: String = "",
        val video_time: Long = 0,
        val video_cover: String = "",
        val video_desc: String = "",
        val video_word: String = "",
        val video_link: String = "",
        val author: AuthorData? = null,
    )

    @Serializable
    private data class AuthorData(
        val user_id: String = "",
        val name: String = "",
        val avatar: String = "",
    )

    // ==================== 主线路（api.51web.eu.org）数据模型 ====================

    /** 51web 解析响应：{code, msg, data:{title, video_list:[{url,level,isDisclaimer}], cover, images}}（music 字段已不使用，靠 ignoreUnknownKeys 忽略） */
    @Serializable
    private data class PrimaryParseResult(
        val code: Int = 0,
        val msg: String = "",
        val data: PrimaryParseData? = null,
    )

    @Serializable
    private data class PrimaryParseData(
        val title: String = "",
        val cover: String = "",
        val video_list: List<PrimaryVideoEntry> = emptyList(),
        val images: List<String> = emptyList(),
    )

    @Serializable
    private data class PrimaryVideoEntry(
        val url: String = "",
        val level: String = "",
        val isDisclaimer: Boolean = false,
    )

    // ==================== bug 系线路（备用线路2 / 备用线路3）共用数据模型 ====================

    /**
     * bug 系解析响应：{code, msg, platform, data:{title, cover, url, quality, label, images,
     * live_photo, video_backup:[{label,quality,url}], author}}。svparse 网关多给一个 `data.label`（如「原画」），
     * 抖音原画接口没有则回落到 quality。
     *
     * 三种笔记形态实测（2026-10-15，两条线路各 3 条链接）：
     * - 视频：`type=video`，`url` 为原画直链，`images`/`live_photo` 为空数组。
     * - 图集：`type=image`，`images` 是**字符串数组**（原图直链），`url`/`duration` 为 null，`video_backup` 空。
     * - 实况：`type=live`，`images` 仍是静帧字符串数组，动图在 `live_photo[{image,video}]`，`url` 同样为 null。
     */
    @Serializable
    private data class BugParseResult(
        val code: Int = 0,
        val msg: String = "",
        val message: String = "",
        val data: BugData? = null,
    )

    @Serializable
    private data class BugData(
        val title: String = "",
        val cover: String = "",
        val url: String = "",
        val quality: String = "",
        val label: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val size_label: String = "",
        val images: List<JsonElement> = emptyList(),
        val live_photo: List<BugLivePhoto> = emptyList(),
        val video_backup: List<BugVideo> = emptyList(),
        val author: BugAuthor? = null,
    )

    /** 实况图：静帧 + 对应的动图 mp4。 */
    @Serializable
    private data class BugLivePhoto(
        val image: String = "",
        val video: String = "",
    )

    @Serializable
    private data class BugVideo(
        val label: String = "",
        val quality: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val size_label: String = "",
        val url: String = "",
    )

    @Serializable
    private data class BugAuthor(
        val name: String = "",
        val id: String = "",
        val avatar: String = "",
    )

    // ==================== 解析 + 下载 + 发送 ====================

    private val webUserAgent =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SQ3A.220705.003) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    /**
     * 抖音等平台源站做了防盗链 (Referer) 校验, 缺头会返回 403。
     * 按视频直链所在域名推导对应的 Referer; 返回 null 时不注入 (走默认)。
     */
    private fun refererFor(url: String): String? = when {
        url.contains("douyinvod") || url.contains("douyin") || url.contains("iesdouyin") ->
            "https://www.douyin.com/"
        url.contains("upos-sz") || url.contains("bilivideo") || url.contains("hdslb.com") ->
            "https://www.bilibili.com/"
        url.contains("kuaishou") || url.contains("yjsub") ->
            "https://www.kuaishou.com/"
        url.contains("ixigua") ->
            "https://www.ixigua.com/"
        url.contains("qq.com") ->
            "https://weishi.qq.com/"
        else -> null
    }

    private fun buildRequest(url: String): Request {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", webUserAgent)
        refererFor(url)?.let { builder.header("Referer", it) }
        return builder.build()
    }

    /**
     * 解析接口请求：51web 对来源按请求头打分，OkHttp 默认 UA 得 0 分直接 403（响应仍是 HTTP 200，
     * 封禁文案塞在 code=403 的 msg 里），补齐移动端浏览器的 Accept / Accept-Language / Referer /
     * Sec-Fetch-* 后才会进入业务逻辑。Cookie 与 TLS 指纹实测不参与打分，无需处理。
     */
    private fun browserParseRequest(apiUrl: String, origin: String): Request = Request.Builder()
        .url(apiUrl).get()
        .header("User-Agent", webUserAgent)
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .header("Referer", "$origin/")
        .header("Origin", origin)
        .header("Sec-Fetch-Dest", "empty")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Site", "same-origin")
        .build()

    private fun parseVideo(link: String): Result<VideoParseResult> = runCatching {
        when (parseRoute) {
            // 用户手动指定线路：严格只走所选线路，失败直接报错，不再静默回退
            ROUTE_PRIMARY -> {
                requirePrimaryToken()
                parseByPrimary(link).getOrElse { throw it }
            }
            ROUTE_BACKUP -> parseByBackup(link).getOrElse { throw it }
            ROUTE_BACKUP2 -> parseByBackup2(link).getOrElse { throw it }
            ROUTE_BACKUP3 -> parseByBackup3(link).getOrElse { throw it }
            // 默认：主线路优先（多清晰度无水印）；失败后依次回退备用线路1、备用线路2
            else -> {
                if (primaryToken.isBlank()) {
                    WeLogger.i(TAG, "primary token not configured; using backup routes")
                    return@runCatching parseByBackups(link)
                }
                parseByPrimary(link).getOrElse { primaryError ->
                    WeLogger.w(TAG, "primary parse failed, falling back to backups: ${primaryError.message}")
                    parseByBackups(link)
                }
            }
        }
    }

    private fun requirePrimaryToken() {
        if (primaryToken.isBlank()) error("未设置主线路 token，请在「短视频解析」设置中填写")
    }

    /** 主线路：api.51web.eu.org。结果映射成统一的 VideoParseResult 供 UI 层无感消费。 */
    private fun parseByPrimary(link: String): Result<VideoParseResult> = runCatching {
        // pid 为线路编号（1~7，用户左下角「线路N」选择框决定），传数字而非「线路N」文本
        val url = PARSE_API_PRIMARY +
            "?token=" + java.net.URLEncoder.encode(primaryToken, "UTF-8") +
            "&pid=" + parsePid +
            "&url=" + java.net.URLEncoder.encode(link, "UTF-8")
        val request = browserParseRequest(url, PARSE_API_PRIMARY_ORIGIN)
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("主线路请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("主线路响应为空")
            val result = json.decodeFromString<PrimaryParseResult>(body)
            require(result.code == 200) { result.msg.ifBlank { "主线路解析失败 (code=${result.code})" } }
            val data = result.data ?: error("主线路返回数据为空")
            // 图集（slides）帖子：images 非空时以图片为主，video_list 里可能是幻灯片合成视频（可空）
            val images = data.images.filter { it.startsWith("http") }
            val videoEntries = data.video_list.filter { !it.isDisclaimer && it.url.startsWith("http") }
            val videoUrl = videoEntries.firstOrNull()?.url
            if (videoUrl == null && images.isEmpty()) error("主线路未返回可用视频地址")
            WeLogger.i(TAG, "primary parse ok, levels=${data.video_list.map { it.level }}, images=${images.size}")
            val qualities = videoEntries
                .map { (it.level.ifBlank { "视频" }) to it.url }
            // 视频帖接口也会把封面塞进 images：封面与图集重复时只保留图集，不再单独显示封面
            val primaryCoverUrl = data.cover
                .takeIf { it.startsWith("http") && images.none { img -> img == it } }
                .orEmpty()
            VideoParseResult(
                code = 200,
                msg = "success",
                data = json.parseToJsonElement(
                    json.encodeToString(
                        VideoData(
                            video_title = data.title,
                            video_cover = data.cover,
                            video_link = videoUrl ?: "",
                        ),
                    ),
                ),
                qualityList = qualities,
                imageList = images,
                coverUrl = primaryCoverUrl,
                // 实况笔记走主线路时，video_list 里是每张静帧各自的动图（彼此独立），不是同一视频的清晰度；
                // 不置位就会被当成档位，下载/发送只取选中的那一条，其余动图静默丢失。
                // 普通视频帖的 images 只有封面一张，多清晰度仍按档位走，不受这里影响。
                downloadAllVideos = images.size > 1 && videoEntries.size > 1,
            )
        }
    }

    /** 依次尝试备用线路1、2、3；备用线路3 没配 Key 时不参与，抛出最后一个真正尝试过的线路的错误。 */
    private fun parseByBackups(link: String): VideoParseResult =
        parseByBackup(link).getOrElse { backupError ->
            WeLogger.w(TAG, "backup1 parse failed, falling back: ${backupError.message}")
            parseByBackup2(link).getOrElse { backup2Error ->
                if (backup3Key.isBlank()) {
                    WeLogger.w(TAG, "backup2 also failed and backup3 key is not set: ${backup2Error.message}")
                    throw backup2Error
                }
                WeLogger.w(TAG, "backup2 parse failed, fallback to backup3: ${backup2Error.message}")
                parseByBackup3(link).getOrElse { backup3Error ->
                    WeLogger.w(TAG, "backup3 parse also failed: ${backup3Error.message}")
                    throw backup3Error
                }
            }
        }

    /** 备用线路2：bug 抖音原画，无需 Key。 */
    private fun parseByBackup2(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API_BACKUP2 + "?url=" + java.net.URLEncoder.encode(link, "UTF-8")
        fetchBugRoute(url, PARSE_API_BACKUP2_ORIGIN, "备用线路2")
    }

    /** 备用线路3：bug 聚合 svparse 网关，Key 走查询参数。 */
    private fun parseByBackup3(link: String): Result<VideoParseResult> = runCatching {
        requireBackup3Key()
        val url = PARSE_API_BACKUP3 +
            "?key=" + java.net.URLEncoder.encode(backup3Key, "UTF-8") +
            "&url=" + java.net.URLEncoder.encode(link, "UTF-8")
        fetchBugRoute(url, PARSE_API_BACKUP3_ORIGIN, "备用线路3")
    }

    private fun requireBackup3Key() {
        if (backup3Key.isBlank()) error("未设置备用线路3 API Key，请在「短视频解析」设置中填写")
    }

    private fun fetchBugRoute(url: String, origin: String, routeName: String): VideoParseResult {
        val request = browserParseRequest(url, origin)
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("$routeName 请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("$routeName 响应为空")
            val result = json.decodeFromString<BugParseResult>(body)
            val failure = result.msg.ifBlank { result.message }
            require(result.code == 200) { failure.ifBlank { "$routeName 解析失败 (code=${result.code})" } }
            return toBugParseResult(result.data ?: error("$routeName 返回数据为空"), routeName)
        }
    }

    /** 原画直链在 data.url，video_backup 是降级档位，实况动图在 live_photo，一并映射成清晰度列表。 */
    private fun toBugParseResult(data: BugData, routeName: String): VideoParseResult {
        val images = data.images.filterIsInstance<JsonPrimitive>()
            .map { it.content }
            .filter { it.startsWith("http") }
        val mainLabel = data.label.ifBlank {
            data.quality.takeUnless { it.isBlank() || it.equals("original", ignoreCase = true) }
                ?: localizedChatInputString(R.string.parse_video_quality_original)
        }
        val livePhotos = data.live_photo.filter { it.video.startsWith("http") }
        val liveLabel = localizedChatInputString(R.string.parse_video_quality_live)
        val qualities = buildList {
            if (data.url.startsWith("http")) {
                add(bugQualityLabel(mainLabel, data.width, data.height, data.size_label) to data.url)
            }
            data.video_backup.forEach { entry ->
                if (entry.url.startsWith("http")) {
                    add(
                        bugQualityLabel("", entry.width, entry.height, entry.size_label)
                            .ifBlank { entry.quality.ifBlank { entry.label }.ifBlank { mainLabel } } to entry.url,
                    )
                }
            }
            livePhotos.forEachIndexed { index, entry ->
                val label = if (livePhotos.size > 1) "$liveLabel ${index + 1}" else liveLabel
                add(label to entry.video)
            }
        }
        if (qualities.isEmpty() && images.isEmpty()) error("$routeName 未返回可用的视频/图片地址")
        WeLogger.i(
            TAG,
            "$routeName parse ok, qualities=${qualities.map { it.first }}, images=${images.size}, live=${livePhotos.size}",
        )
        // 只有视频帖单独显示封面：图集与实况的 cover 是同一画面的 360p 缩略图，已由下方图集行呈现
        val coverUrl = if (images.isEmpty()) data.cover.takeIf { it.startsWith("http") }.orEmpty() else ""
        return VideoParseResult(
            code = 200,
            msg = "success",
            data = json.parseToJsonElement(
                json.encodeToString(
                    VideoData(
                        video_title = data.title,
                        video_cover = data.cover,
                        video_link = data.url,
                        author = data.author?.takeIf { it.name.isNotBlank() }?.let {
                            AuthorData(user_id = it.id, name = it.name, avatar = it.avatar)
                        },
                    ),
                ),
            ),
            qualityList = qualities,
            imageList = images,
            coverUrl = coverUrl,
            // 实况动图是彼此独立的视频，不是同一视频的清晰度：走 kit9 的「全部下载」通路，不出口档位选择
            downloadAllVideos = livePhotos.size > 1,
        )
    }

    /**
     * bug 系档位命名。降级档没有可靠的语义名——同一个 576×1024 的转码产物，抖音原画接口标 `1024p`、
     * svparse 标 `576p`，只有宽高对得上，所以直接用分辨率当名字；再统一补上分辨率与体积，
     * 免得两条线路同名的「原画」（6.12MB 与 39.26MB）在下拉里看不出区别。
     */
    private fun bugQualityLabel(name: String, width: Int, height: Int, sizeLabel: String): String {
        val spec = if (width > 0 && height > 0) "$width×$height" else ""
        val head = listOf(name, spec).filter { it.isNotBlank() }.joinToString(" ")
        return when {
            head.isBlank() -> sizeLabel
            sizeLabel.isBlank() -> head
            else -> "$head · $sizeLabel"
        }
    }

    /** kit9 直链里的 br 查询参数（码率 kbps），用于给多档位直链命名；解析不出返回 null。 */
    private fun kit9BitrateLabel(url: String): String? =
        Regex("[?&]br=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { "${it}kbps" }

    /** kit9 部分响应在 data 顶层提供 image 字段（字符串或数组），作图集兜底。 */
    private fun kit9FallbackImages(dataElement: JsonObject): List<String> =
        when (val img = dataElement["image"]) {
            is JsonPrimitive -> listOfNotNull(img.content.takeIf { it.startsWith("http") })
            is JsonArray -> img.mapNotNull { entry ->
                ((entry as? JsonPrimitive)?.content)
                    ?: ((entry as? JsonObject)?.get("url")?.let { (it as? JsonPrimitive)?.content })
            }.filter { it.startsWith("http") }
            else -> emptyList()
        }

    /**
     * 备用线路：kit9 聚合解析。video_link 可能是 [{type:"video|image",url}] 混合数组：
     * 视频条目按 br 码率降序全部保留（>1 条时置 downloadAllVideos，下载时全选、不做档位选择），
     * 图片条目全部进图集列表（视频存在时不再丢弃，修复 mixed 内容只能下载首个视频的问题）。
     */
    private fun parseByBackup(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API + "?link=" + java.net.URLEncoder.encode(link, "UTF-8")
        val request = browserParseRequest(url, PARSE_API_ORIGIN)
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("响应为空")
            val result = json.decodeFromString<VideoParseResult>(body)
            if (result.code != 200) return@runCatching result
            val dataElement = result.data ?: return@runCatching result
            if (dataElement !is JsonObject) return@runCatching result
            val rawVideoLink = dataElement["video_link"]
            // 字符串形态：单视频，不动；图集取 image 字段兜底，兜底也没有时用 video_cover 充作图集图，
            // 使视频帖统一呈现「图集+视频」（封面不占封面位，与主线路 images 含封面的行为对齐）
            if (rawVideoLink !is JsonArray) {
                val hasVideo = (rawVideoLink as? JsonPrimitive)?.content?.startsWith("http") == true
                val strCover = (dataElement["video_cover"] as? JsonPrimitive)?.content ?: ""
                val strImages = kit9FallbackImages(dataElement)
                    .ifEmpty { if (hasVideo && strCover.startsWith("http")) listOf(strCover) else emptyList() }
                return@runCatching result.copy(imageList = strImages)
            }
            val entries = rawVideoLink.mapNotNull { entry ->
                (entry as? JsonObject)?.let { obj ->
                    val type = (obj["type"] as? JsonPrimitive)?.content
                    val u = (obj["url"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") }
                    u?.let { type to it }
                }
            }
            val videoUrls = entries.filter { (type, _) -> type != "image" }.map { it.second }
            val imageUrls = entries.filter { (type, _) -> type == "image" }.map { it.second }
            val title = (dataElement["video_title"] as? JsonPrimitive)?.content ?: ""
            val cover = (dataElement["video_cover"] as? JsonPrimitive)?.content ?: ""
            // 视频档位按 br 码率降序（kit9 数组顺序不保证），第一档=最高清
            val sortedVideos = videoUrls.sortedByDescending { kit9BitrateLabel(it)?.removeSuffix("kbps")?.toIntOrNull() ?: 0 }
            val qualityList = if (sortedVideos.size > 1) {
                sortedVideos.mapIndexed { index, u ->
                    (kit9BitrateLabel(u)?.let { "备用 $it" } ?: "备用视频 ${index + 1}") to u
                }
            } else emptyList()
            // 图集优先取 image 条目，其次 image 字段兜底；两者皆空时用 video_cover 充作图集图（与主线路视频帖同语义）
            val galleryImages = imageUrls
                .ifEmpty { kit9FallbackImages(dataElement) }
                .ifEmpty { if (sortedVideos.isNotEmpty() && cover.startsWith("http")) listOf(cover) else emptyList() }
            val normalized = VideoData(
                video_title = title,
                video_cover = cover,
                video_link = sortedVideos.firstOrNull() ?: "",
                author = (dataElement["author"] as? JsonObject)?.let {
                    json.decodeFromJsonElement(AuthorData.serializer(), it)
                },
            )
            VideoParseResult(
                code = result.code,
                msg = result.msg,
                data = json.parseToJsonElement(json.encodeToString(normalized)),
                qualityList = qualityList,
                imageList = galleryImages,
                downloadAllVideos = sortedVideos.size > 1,
            )
        }
    }

    /**
     * 从粘贴的分享文案中提取首个 http(s) 链接。
     * 抖音/快手等平台复制出来的是整段分享文案（含口令、表情、说明文字），
     * 直接把整段文本交给解析 API 会因无法识别链接而失败。
     * 链接外层标注的双引号（含中文弯引号）一并去除。
     */
    private fun extractVideoUrl(raw: String): String {
        return urlRegex.find(raw)?.value?.trim('"', '“', '”') ?: ""
    }

    private fun downloadVideo(
        url: String,
        outPath: java.io.File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<java.io.File> = runCatching {
        val request = buildRequest(url)
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败: HTTP ${resp.code}")
            val body = resp.body ?: error("下载内容为空")
            val totalBytes = body.contentLength()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            outPath.outputStream().use { output ->
                body.byteStream().use { input ->
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
        }
        if (outPath.length() < 1024) error("下载文件过小，可能无效")
        outPath
    }

    // ==================== 抖音链接自动解析回复 ====================

    /** 临时发送用目录：外部存储 Download/WcS/ParseVideoTemp（不再落微信内部 cache，便于用户查看与清理） */
    private fun tempSendDir(): java.io.File =
        (KnownPaths.downloads / "ParseVideoTemp").toFile()

    private val autoReplyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 打开时信号量的灯以串行化下载，避免并发重复拉取 */
    private val autoReplyMutex = Mutex()

    /** 记录最近已处理的 (talker, link 摘要) 防止重复回复 */
    private val autoReplySeen = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * 当某条新消息满足条件时自动触发:
     * 会话在白名单中 + 非自己发送 + 文本含抖音分享链接 -> 解析 -> 下载到临时目录 -> 发回该会话。
     * 白名单为空时全部不生效。由 hookBefore 在消息入库前调用, 不阻塞入库流程。
     */
    private fun handleAutoReply(msgInfo: MessageInfo) {
        try {
            // 白名单模式：仅选中的会话（群聊或私聊用户）生效；未选择则全部不生效
            if (autoReplyWhitelist.isEmpty() || msgInfo.talker !in autoReplyWhitelist) return
            if (msgInfo.type?.isText != true) return
            if (msgInfo.isSelfSender) return

            val text = msgInfo.humanReadableRepr
            val link = extractDouyinUrl(text)
            if (link.isEmpty()) return

            val dedupKey = "${msgInfo.talker}|$link"
            if (!autoReplySeen.add(dedupKey)) return

            val talker = msgInfo.talker
            autoReplyScope.launch {
                autoReplyMutex.withLock {
                    doAutoReply(talker, link)
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "handleAutoReply failed", e)
        }
    }

    /** 从消息文本中提取抖音分享链接；非抖音链接返回空串。链接外层标注的双引号一并去除。 */
    private fun extractDouyinUrl(raw: String): String =
        douyinUrlRegex.find(raw)?.value?.trim('"', '“', '”') ?: ""

    /** 真正执行解析 + 下载 + 发送。所有流程在地线程执行, 调用方已持锁。 */
    private suspend fun doAutoReply(talker: String, link: String) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val parsed = parseVideo(link).getOrElse { throw it }
                if (parsed.code != 200) {
                    error("parse failed: ${parsed.msg}")
                }
                sendParseResult(talker, parsed, tempSendDir())
            }
        }
        if (result.isFailure) {
            WeLogger.e(TAG, "auto reply failed", result.exceptionOrNull() ?: error("auto reply failed"))
        }
    }

    /**
     * 下载并发送一条解析结果：视频与图集**全部**发送（自动回复与弹窗一键发送同语义，不考虑刷屏）。
     * 备用线路多视频直链（downloadAllVideos）按码率降序逐条全发；其余线路最多一条视频
     * （弹窗入口可传 videoUrlOverride 指定当前选中清晰度）；随后按 imageList 逐张发图片。
     * 单个媒体失败仅记日志跳过，全部失败才抛异常；返回成功发送的媒体数量。
     */
    private fun sendParseResult(
        talker: String,
        parsed: VideoParseResult,
        dir: java.io.File,
        videoUrlOverride: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Int {
        val data = parsed.parsedData() ?: error("no video data")
        dir.mkdirs()
        var sentCount = 0
        val videoUrls: List<String> =
            if (parsed.downloadAllVideos && parsed.qualityList.size > 1) {
                parsed.qualityList.map { it.second }
            } else {
                listOfNotNull(
                    (videoUrlOverride?.takeIf { it.isNotBlank() } ?: data.video_link)
                        .takeIf { it.isNotBlank() },
                )
            }
        for ((index, vUrl) in videoUrls.withIndex()) {
            val out = java.io.File(dir, "auto-${UUID.randomUUID()}-v$index.mp4")
            downloadVideo(vUrl, out, onProgress).getOrElse { e ->
                WeLogger.w(TAG, "video $index download failed: ${e.message}")
                continue
            }
            val sent = WeMessageApi.sendVideo(talker, out.absolutePath)
            if (sent) sentCount++ else out.delete()
        }
        for ((index, imgUrl) in parsed.imageList.withIndex()) {
            val out = java.io.File(dir, "auto-${UUID.randomUUID()}-$index.jpg")
            downloadVideo(imgUrl, out, onProgress).getOrElse { e ->
                WeLogger.w(TAG, "image $index download failed: ${e.message}")
                continue
            }
            val sent = WeMessageApi.sendImage(talker, out.absolutePath)
            if (sent) sentCount++ else out.delete()
        }
        if (sentCount == 0) error("no media sent")
        return sentCount
    }

    /** 图集横滑缩略图行：最多露 4 张，其余折成末张的「+N」蒙层（混合内容独立区块与纯图集结果共用）。 */
    @Composable
    private fun GalleryThumbsRow(images: List<String>) {
        val shown = images.take(4)
        val hidden = images.size - shown.size
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            shown.forEachIndexed { index, imgUrl ->
                Box {
                    AsyncImage(
                        model = imgUrl,
                        contentDescription = "$index",
                        modifier = Modifier
                            .size(width = 72.dp, height = 96.dp)
                            .clip(RoundedCornerShape(9.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    if (hidden > 0 && index == shown.lastIndex) {
                        Box(
                            modifier = Modifier
                                .size(width = 72.dp, height = 96.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "+$hidden",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

fun showParseDialog(context: android.content.Context) {
        showComposeDialog(context, dismissOnTouchOutside = false) {
            var link by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            var errorMsg by remember { mutableStateOf<String?>(null) }
            var parseResult by remember { mutableStateOf<VideoParseResult?>(null) }
            var downloadedFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
            // 多清晰度选择：主线路返回的档位列表 + 当前选中 URL（默认第一档=最高清）
            var qualityList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var selectedQualityUrl by remember { mutableStateOf("") }
            // 主线路直出的封面地址（可一键保存）
            var directCoverUrl by remember { mutableStateOf("") }
            var savingCover by remember { mutableStateOf(false) }
            var downloading by remember { mutableStateOf(false) }
            var downloadProgress by remember { mutableFloatStateOf(0f) }
            var sending by remember { mutableStateOf(false) }
            var pendingSendAfterParse by remember { mutableStateOf(false) }
            // 线路切换（右上角齿轮）：selectedRoute 驱动菜单 UI 刷新，parseRoute 持久化到偏好
            var selectedRoute by remember { mutableStateOf(parseRoute) }
            var routeMenuExpanded by remember { mutableStateOf(false) }
            // 主线路 pid（左下角「线路N」选择框，1~7）：仅自动/主线路时展示，选择持久化
            var selectedPid by remember { mutableIntStateOf(parsePid) }
            val scope = rememberCoroutineScope()
            val appContext = LocalContext.current.applicationContext

            // 自动读取剪贴板中的链接
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val clip = readTextFromClipboard(appContext) ?: return@LaunchedEffect
                val extracted = extractVideoUrl(clip)
                if (extracted.isNotEmpty()) {
                    link = extracted
                }
            }

            fun downloadAndSend(result: VideoParseResult) {
                val data = result.parsedData()
                if (data == null || data.video_link.isBlank() && result.imageList.isEmpty()) {
                    pendingSendAfterParse = false
                    errorMsg = localizedChatInputString(R.string.parse_video_api_error)
                    return
                }
                val talker = WeCurrentConversationApi.value
                if (talker.isBlank()) {
                    pendingSendAfterParse = false
                    errorMsg = localizedChatInputString(R.string.parse_video_no_conversation)
                    return
                }
                if (sending) return
                sending = true
                downloadProgress = 0f
                errorMsg = null
                scope.launch {
                    val sendResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = tempSendDir()
                            // 跟随弹窗中当前选中的清晰度档位（默认第一档/视频直链）
                            sendParseResult(
                                talker,
                                result,
                                dir,
                                videoUrlOverride = selectedQualityUrl.ifBlank { result.parsedData()?.video_link },
                            ) { downloaded, total ->
                                if (total > 0 && downloaded > 0) {
                                    downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                    sending = false
                    sendResult.fold(
                        onSuccess = { count ->
                            showToast(localizedChatInputString(R.string.parse_video_sent))
                            onDismiss()
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "send parse result failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_send_failed)
                        },
                    )
                }
            }

            fun doParse() {
                val trimmed = extractVideoUrl(link)
                if (trimmed.isEmpty()) {
                    showToast(localizedChatInputString(R.string.parse_video_link_empty))
                    return
                }
                loading = true
                errorMsg = null
                parseResult = null
                downloadedFiles = emptyList()
                qualityList = emptyList()
                selectedQualityUrl = ""
                directCoverUrl = ""
                scope.launch {
                    val parsed = withContext(Dispatchers.IO) { parseVideo(trimmed) }
                    loading = false
                    parsed.fold(
                        onSuccess = { r ->
                            if (r.code != 200 || (!r.hasVideo() && r.imageList.isEmpty())) {
                                pendingSendAfterParse = false
                                errorMsg = r.msg.ifBlank { localizedChatInputString(R.string.parse_video_api_error) }
                                return@fold
                            }
                            parseResult = r
                            // 多清晰度与直出封面（仅主线路携带）
                            qualityList = r.qualityList
                            selectedQualityUrl = r.qualityList.firstOrNull()?.second
                                ?: r.parsedData()?.video_link.orEmpty()
                            // 封面直链在解析时已按「与图集重复则置空」规则归一，直接取用
                            // （原实现把 VideoData 形态的 r.data 按 PrimaryParseData 重解码，字段名不匹配恒为空，封面从不出现在弹窗）
                            directCoverUrl = r.coverUrl
                            if (pendingSendAfterParse) {
                                pendingSendAfterParse = false
                                downloadAndSend(r)
                            }
                        },
                        onFailure = { e ->
                            pendingSendAfterParse = false
                            WeLogger.e(TAG, "parse failed", e)
                            errorMsg = e.message ?: localizedChatInputString(R.string.parse_video_api_error)
                        },
                    )
                }
            }

            fun doConvert() {
                pendingSendAfterParse = false
                doParse()
            }

            fun doSendNow() {
                val r = parseResult
                if (r != null) {
                    val data = r.parsedData()
                    if ((data != null && data.video_link.isNotBlank()) || r.imageList.isNotEmpty()) {
                        downloadAndSend(r)
                        return
                    }
                }
                pendingSendAfterParse = true
                doParse()
            }

            /** images=true 强制只下图集；否则优先下当前选中的清晰度档位（无视频时自动转图集）。 */
            fun doDownload(images: Boolean = false) {
                val r = parseResult ?: return
                val data = r.parsedData() ?: return
                val videoUrl = selectedQualityUrl.ifBlank { data.video_link }
                val isGallery = images || videoUrl.isBlank()
                if (isGallery && r.imageList.isEmpty()) return
                downloading = true
                downloadProgress = 0f
                errorMsg = null
                scope.launch {
                    val saveResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = ensureSaveDir()
                            if (isGallery) {
                                val files = mutableListOf<java.io.File>()
                                r.imageList.forEachIndexed { index, imgUrl ->
                                    val out = java.io.File(dir, "image-${UUID.randomUUID()}-$index.jpg")
                                    downloadVideo(imgUrl, out) { downloaded, total ->
                                        if (total > 0 && downloaded > 0) {
                                            downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                        }
                                    }.onSuccess { files += it }
                                }
                                if (files.isEmpty()) error("无图片下载成功")
                                files
                            } else if (r.downloadAllVideos && r.qualityList.size > 1) {
                                // 备用线路多视频直链：全部下载（与图集同思路），无需逐档选择
                                val files = mutableListOf<java.io.File>()
                                r.qualityList.forEachIndexed { index, (_, vUrl) ->
                                    val out = java.io.File(dir, "video-${UUID.randomUUID()}-$index.mp4")
                                    downloadVideo(vUrl, out) { downloaded, total ->
                                        if (total > 0 && downloaded > 0) {
                                            downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                        }
                                    }.onSuccess { files += it }
                                }
                                if (files.isEmpty()) error("无视频下载成功")
                                files
                            } else {
                                val out = java.io.File(dir, "video-${UUID.randomUUID()}.mp4")
                                downloadVideo(videoUrl, out) { downloaded, total ->
                                    if (total > 0 && downloaded > 0) {
                                        downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                    }
                                }.getOrThrow()
                                listOf(out)
                            }
                        }
                    }
                    downloading = false
                    saveResult.fold(
                        onSuccess = { files ->
                            // 同类累加覆盖（重下图集/重选清晰度替换旧文件），异类保留：视频+图集可同时持有再一起发送
                            val newIsImages = files.firstOrNull()?.name?.startsWith("image-") == true
                            downloadedFiles = downloadedFiles.filter {
                                it.name.startsWith(if (newIsImages) "video-" else "image-")
                            } + files
                            showToast(localizedChatInputString(R.string.parse_video_downloaded))
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "download failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_download_failed, e.message.orEmpty())
                        },
                    )
                }
            }

            /** 一键下载全部文件：全部视频直链（备用线路多选）或选中清晰度 + 全部图集图片；部分失败不阻断其余。 */
            fun doDownloadAll() {
                val r = parseResult ?: return
                val data = r.parsedData() ?: return
                val videoUrl = selectedQualityUrl.ifBlank { data.video_link }
                if (videoUrl.isBlank() && r.imageList.isEmpty()) return
                downloading = true
                downloadProgress = 0f
                errorMsg = null
                scope.launch {
                    val saveResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = ensureSaveDir()
                            val files = mutableListOf<java.io.File>()
                            val onProg: (Long, Long) -> Unit = { downloaded, total ->
                                if (total > 0 && downloaded > 0) {
                                    downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                }
                            }
                            if (r.downloadAllVideos && r.qualityList.size > 1) {
                                r.qualityList.forEachIndexed { index, (_, vUrl) ->
                                    val out = java.io.File(dir, "video-${UUID.randomUUID()}-$index.mp4")
                                    downloadVideo(vUrl, out, onProg).onSuccess { files += it }
                                }
                            } else if (videoUrl.isNotBlank()) {
                                val out = java.io.File(dir, "video-${UUID.randomUUID()}.mp4")
                                downloadVideo(videoUrl, out, onProg).onSuccess { files += it }
                            }
                            r.imageList.forEachIndexed { index, imgUrl ->
                                val out = java.io.File(dir, "image-${UUID.randomUUID()}-$index.jpg")
                                downloadVideo(imgUrl, out, onProg).onSuccess { files += it }
                            }
                            if (files.isEmpty()) error("无文件下载成功")
                            files
                        }
                    }
                    downloading = false
                    saveResult.fold(
                        onSuccess = { files ->
                            // 同类替换、异类保留：视频/图片分别覆盖旧文件，另一类继续持有
                            val newKinds = files.map { it.name.substringBefore("-") }.distinct()
                            downloadedFiles = downloadedFiles.filter { old ->
                                newKinds.none { old.name.startsWith("$it-") }
                            } + files
                            showToast(localizedChatInputString(R.string.parse_video_downloaded))
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "download all failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_download_failed, e.message.orEmpty())
                        },
                    )
                }
            }

            fun sendDownloadedFiles() {
                val r = parseResult ?: return
                if (downloadedFiles.isEmpty()) return
                val talker = WeCurrentConversationApi.value
                if (talker.isBlank()) {
                    errorMsg = localizedChatInputString(R.string.parse_video_no_conversation)
                    return
                }
                scope.launch {
                    val sent = withContext(Dispatchers.IO) {
                        downloadedFiles.all { file ->
                            if (file.name.startsWith("image-")) {
                                WeMessageApi.sendImage(talker, file.absolutePath)
                            } else {
                                WeMessageApi.sendVideo(talker, file.absolutePath)
                            }
                        }
                    }
                    if (sent) {
                        showToast(localizedChatInputString(R.string.parse_video_sent))
                        onDismiss()
                    } else {
                        errorMsg = localizedChatInputString(R.string.parse_video_send_failed)
                    }
                }
            }

            fun deleteDownloadedFile() {
                downloadedFiles.forEach { file ->
                    runCatching { file.delete() }
                }
                downloadedFiles = emptyList()
                showToast(localizedChatInputString(R.string.parse_video_deleted))
            }

            val routeOptions = listOf(
                ROUTE_AUTO to R.string.parse_video_route_auto,
                ROUTE_PRIMARY to R.string.parse_video_route_primary,
                ROUTE_BACKUP to R.string.parse_video_route_backup,
                ROUTE_BACKUP2 to R.string.parse_video_route_backup2,
                ROUTE_BACKUP3 to R.string.parse_video_route_backup3,
            )

            /** 从剪贴板重读一次链接：弹窗打开时只自动读过一次，用户中途复制就得靠这里。 */
            fun pasteFromClipboard() {
                val extracted = readTextFromClipboard(appContext)?.let { extractVideoUrl(it) }.orEmpty()
                if (extracted.isEmpty()) {
                    showToast(localizedChatInputString(R.string.parse_video_link_empty))
                    return
                }
                link = extracted
            }

            fun saveCover() {
                val url = directCoverUrl
                if (url.isBlank() || savingCover) return
                savingCover = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = ensureSaveDir()
                            val ext = if (url.contains(".png")) "png" else "jpg"
                            val out = java.io.File(dir, "cover-${UUID.randomUUID()}.$ext")
                            val req = Request.Builder().url(url).get().build()
                            httpClient.newCall(req).execute().use { resp ->
                                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                                resp.body.byteStream().use { ins -> out.outputStream().use { ins.copyTo(it) } }
                            }
                            out
                        }
                    }
                    savingCover = false
                    result.fold(
                        onSuccess = { file ->
                            showToast(
                                localizedChatInputString(R.string.parse_video_cover_saved) +
                                    " (${"%.1f".format(file.length() / 1024.0)}KB)",
                            )
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "save cover failed", e)
                            errorMsg = e.message ?: localizedChatInputString(R.string.parse_video_download_failed, e.toString())
                        },
                    )
                }
            }

            val footerStatus = when {
                sending -> localizedChatInputString(R.string.parse_video_sending)
                downloading -> localizedChatInputString(R.string.parse_video_downloading) +
                    downloadProgress.takeIf { it > 0f }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                downloadedFiles.isNotEmpty() -> buildString {
                    append(localizedChatInputString(R.string.parse_video_downloaded))
                    if (downloadedFiles.size > 1) {
                        append(" ×")
                        append(downloadedFiles.size)
                    }
                    append(" (")
                    append("%.1f".format(downloadedFiles.sumOf { it.length() } / 1024.0 / 1024.0))
                    append("MB)")
                }
                else -> ""
            }

            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) {
                Column {
                    // ===== 头部：标题 + 线路药丸（线路与线路N 合一菜单）+ 关闭 =====
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.feature_parse_video_name),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                        Box {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                FilledTonalButton(
                                    onClick = { routeMenuExpanded = true },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                ) {
                                    Icon(
                                        MaterialSymbols.Outlined.Tune,
                                        contentDescription = stringResource(R.string.parse_video_route_settings),
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = routePillLabel(selectedRoute, selectedPid),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 150.dp),
                                    )
                                    Icon(
                                        MaterialSymbols.Outlined.Keyboard_arrow_down,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = routeMenuExpanded,
                                onDismissRequest = { routeMenuExpanded = false },
                            ) {
                                routeOptions.forEach { (value, labelRes) ->
                                    val label = stringResource(labelRes)
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (selectedRoute == value) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Check_circle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedRoute = value
                                            parseRoute = value
                                            routeMenuExpanded = false
                                            showToast(
                                                localizedChatInputString(R.string.parse_video_route_changed, label),
                                            )
                                        },
                                    )
                                    // 线路N 只属于自动/主线路：内联在选中项下面，选完不关菜单
                                    if (selectedRoute == value && (value == ROUTE_AUTO || value == ROUTE_PRIMARY)) {
                                        RoutePidRow(selectedPid) { n ->
                                            selectedPid = n
                                            parsePid = n
                                        }
                                    }
                                }
                            }
                        }
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            IconButton(
                                onClick = onDismiss,
                                enabled = !loading && !sending,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    MaterialSymbols.Outlined.Close,
                                    contentDescription = stringResource(R.string.dialog_close),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))

                    // ===== 正文（滚动）=====
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextField(
                            value = link,
                            onValueChange = { link = it },
                            enabled = !loading,
                            label = { Text(stringResource(R.string.parse_video_link_hint)) },
                            placeholder = { Text(stringResource(R.string.parse_video_link_placeholder)) },
                            trailingIcon = {
                                IconButton(onClick = { pasteFromClipboard() }, enabled = !loading) {
                                    Icon(
                                        MaterialSymbols.Outlined.Content_copy,
                                        contentDescription = stringResource(R.string.parse_video_link_hint),
                                    )
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = { doConvert() },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                    enabled = !loading && !downloading && !sending,
                                ) {
                                    Text(
                                        stringResource(R.string.parse_video_convert),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Button(
                                    onClick = { doSendNow() },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                    enabled = !loading && !downloading && !sending,
                                ) {
                                    Text(
                                        stringResource(R.string.parse_video_send),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }

                        if (loading) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 3.dp,
                                    )
                                    Text(
                                        stringResource(R.string.parse_video_loading),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }

                        errorMsg?.let { err ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    Icon(
                                        MaterialSymbols.Outlined.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = err,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }

                        parseResult?.let { r ->
                            val data = r.parsedData() ?: return@let
                            ParseResultCard(
                                result = r,
                                data = data,
                                qualityList = qualityList,
                                selectedQualityUrl = selectedQualityUrl,
                                onQuality = { selectedQualityUrl = it },
                                directCoverUrl = directCoverUrl,
                                savingCover = savingCover,
                                onSaveCover = { saveCover() },
                                busy = downloading || sending,
                                onDownloadImages = { doDownload(images = true) },
                                onDownloadVideo = { doDownload(images = false) },
                            )
                        }
                    }

                    // ===== 吸底：进度条 + 状态位 + 唯一主操作 =====
                    val busy = downloading || sending
                    if (busy) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = footerStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (downloadedFiles.isNotEmpty() && !busy) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            if (downloadedFiles.isNotEmpty()) {
                                IconButton(onClick = { deleteDownloadedFile() }, enabled = !busy) {
                                    Icon(
                                        MaterialSymbols.Outlined.Delete,
                                        contentDescription = stringResource(R.string.parse_video_delete),
                                    )
                                }
                                Button(
                                    onClick = { sendDownloadedFiles() },
                                    enabled = !busy,
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Text(stringResource(R.string.parse_video_send_files))
                                }
                            } else {
                                parseResult?.let { r ->
                                    val gallery = r.imageList.isNotEmpty()
                                    Button(
                                        onClick = {
                                            if (gallery) doDownloadAll() else doDownload(images = false)
                                        },
                                        enabled = !busy,
                                        modifier = Modifier.height(40.dp),
                                    ) {
                                        Text(
                                            if (downloading) {
                                                stringResource(R.string.parse_video_downloading)
                                            } else {
                                                stringResource(
                                                    if (gallery) R.string.parse_video_download_all_files
                                                    else R.string.parse_video_download,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 线路药丸文案：只取线路名的短前缀（切掉括号里的说明），完整名在菜单里，避免药丸被省略号截成一截废话。 */
    @Composable
    private fun routePillLabel(route: String, pid: Int): String {
        val labelRes = when (route) {
            ROUTE_PRIMARY -> R.string.parse_video_route_primary
            ROUTE_BACKUP -> R.string.parse_video_route_backup
            ROUTE_BACKUP2 -> R.string.parse_video_route_backup2
            ROUTE_BACKUP3 -> R.string.parse_video_route_backup3
            else -> R.string.parse_video_route_auto
        }
        val base = stringResource(labelRes).substringBefore('（').substringBefore('(').trim()
        return if (route == ROUTE_AUTO || route == ROUTE_PRIMARY) {
            "$base · ${stringResource(R.string.parse_video_line_option, pid)}"
        } else {
            base
        }
    }

    /** 线路N（主线路 pid 1~7）：跟在选中线路那一行下面，点完不关菜单。 */
    @Composable
    private fun RoutePidRow(selectedPid: Int, onPick: (Int) -> Unit) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (1..7).forEach { n ->
                    if (n == selectedPid) {
                        Button(
                            onClick = { onPick(n) },
                            modifier = Modifier.size(30.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(n.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onPick(n) },
                            modifier = Modifier.size(30.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(n.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    /** 解析结果卡：图集与视频各自一张子卡（子卡头带数量与自己的下载动作），纯视频不渲染段头。 */
    @Composable
    private fun ParseResultCard(
        result: VideoParseResult,
        data: VideoData,
        qualityList: List<Pair<String, String>>,
        selectedQualityUrl: String,
        onQuality: (String) -> Unit,
        directCoverUrl: String,
        savingCover: Boolean,
        onSaveCover: () -> Unit,
        busy: Boolean,
        onDownloadImages: () -> Unit,
        onDownloadVideo: () -> Unit,
    ) {
        val gallery = result.imageList
        val mixed = result.hasVideo() && gallery.isNotEmpty()
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (gallery.isNotEmpty()) {
                    ResultSection(
                        title = stringResource(R.string.parse_video_section_gallery),
                        detail = localizedChatInputString(R.string.parse_video_gallery_detected, gallery.size),
                        action = if (mixed) {
                            {
                                SectionAction(
                                    label = stringResource(R.string.parse_video_download_images_btn),
                                    enabled = !busy,
                                    onClick = onDownloadImages,
                                )
                            }
                        } else {
                            null
                        },
                        content = { GalleryThumbsRow(gallery) },
                    )
                }
                if (result.hasVideo()) {
                    if (mixed) {
                        ResultSection(
                            title = stringResource(R.string.parse_video_section_video),
                            detail = localizedChatInputString(
                                R.string.parse_video_videos_detected,
                                qualityList.size.takeIf { it > 0 } ?: 1,
                            ),
                            action = {
                                SectionAction(
                                    label = stringResource(R.string.parse_video_download_video_btn),
                                    enabled = !busy,
                                    onClick = onDownloadVideo,
                                )
                            },
                            content = {
                                VideoBlock(
                                    data = data,
                                    qualityList = qualityList,
                                    downloadAllVideos = result.downloadAllVideos,
                                    selectedQualityUrl = selectedQualityUrl,
                                    onQuality = onQuality,
                                    directCoverUrl = directCoverUrl,
                                    savingCover = savingCover,
                                    onSaveCover = onSaveCover,
                                )
                            },
                        )
                    } else {
                        VideoBlock(
                            data = data,
                            qualityList = qualityList,
                            downloadAllVideos = result.downloadAllVideos,
                            selectedQualityUrl = selectedQualityUrl,
                            onQuality = onQuality,
                            directCoverUrl = directCoverUrl,
                            savingCover = savingCover,
                            onSaveCover = onSaveCover,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ResultSection(
        title: String,
        detail: String,
        action: (@Composable () -> Unit)?,
        content: @Composable () -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    action?.invoke()
                }
                content()
            }
        }
    }

    @Composable
    private fun SectionAction(label: String, enabled: Boolean, onClick: () -> Unit) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            TextButton(onClick = onClick, enabled = enabled) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    @Composable
    private fun VideoBlock(
        data: VideoData,
        qualityList: List<Pair<String, String>>,
        downloadAllVideos: Boolean,
        selectedQualityUrl: String,
        onQuality: (String) -> Unit,
        directCoverUrl: String,
        savingCover: Boolean,
        onSaveCover: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (directCoverUrl.isNotBlank()) {
                    AsyncImage(
                        model = directCoverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 96.dp, height = 128.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (data.video_title.isNotBlank()) {
                        Text(
                            text = data.video_title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    data.author?.let { author ->
                        Text(
                            text = author.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (directCoverUrl.isNotBlank()) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            OutlinedButton(
                                onClick = onSaveCover,
                                enabled = !savingCover,
                                modifier = Modifier.height(34.dp),
                                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                            ) {
                                Text(
                                    if (savingCover) {
                                        stringResource(R.string.parse_video_saving_cover)
                                    } else {
                                        stringResource(R.string.parse_video_save_cover)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
            // 多视频直链/实况动图（downloadAllVideos）不是档位，不给选
            if (qualityList.size > 1 && !downloadAllVideos) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    qualityList.forEach { (label, url) ->
                        FilterChip(
                            selected = url == selectedQualityUrl,
                            onClick = { onQuality(url) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        }
    }
}
