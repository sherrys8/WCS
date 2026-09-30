package dev.sherry.wcs.features.items.chat_input_bar_menu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_down
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_up
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
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_parse_video_description

    private const val TAG = "ParseVideo"

    /** 主解析线路：dy.51web.eu.org（抖音多清晰度无水印，token=dyyy）。 */
    private const val PARSE_API_PRIMARY = "https://dy.51web.eu.org/api/parse"
    private const val PARSE_API_PRIMARY_ORIGIN = "https://dy.51web.eu.org"
    private const val PARSE_API_PRIMARY_TOKEN = "dyyy"

    /** 备用解析线路：kit9 聚合解析（主线路失败时自动切换）。 */
    private const val PARSE_API = "https://apis.kit9.cn/api/aggregate_videos/api.php"
    private const val PARSE_API_ORIGIN = "https://apis.kit9.cn"

    /** 小红书解析线路：dovis（识别到小红书链接时直接使用，不经前两条线路）。 */
    private const val PARSE_API_XHS = "http://api.dovis.work/api/xhs.php?url="
    private const val PARSE_API_XHS_ORIGIN = "http://api.dovis.work"
    private const val DEFAULT_BUFFER_SIZE = 8192

    /** 线路选择（解析弹窗右上角可切换，持久化到偏好）。auto = 按默认优先级回退；其余固定走所选线路。 */
    private const val ROUTE_AUTO = "auto"
    private const val ROUTE_PRIMARY = "primary"
    private const val ROUTE_BACKUP = "backup"
    private const val ROUTE_XHS = "xhs"

    private val urlRegex = Regex("""https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""")

    /** 抖音分享链接（v.douyin.com 短链 / www.douyin.com / iesdouyin 等）。 */
    private val douyinUrlRegex = Regex(
        """https?://(?:[\w\-.]*douyin\.com|v\.douyin\.com|iesdouyin\.com)/[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE,
    )

    /** 小红书分享链接（xiaohongshu.com / xhslink.com / xhslink.cn 短链）。 */
    private val xhsUrlRegex = Regex(
        """https?://(?:[\w\-.]*xiaohongshu\.com|xhslink\.com|xhslink\.cn)/[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE,
    )

    private var saveDir by prefOption("parse_video_save_dir", "")
    private var autoReply by prefOption("parse_video_auto_reply", false)

    /** 自动解析白名单：空 = 所有群聊生效；非空 = 仅选中的会话（群聊或私聊用户）生效。 */
    private var autoReplyWhitelist by prefOption("parse_video_whitelist", emptySet<String>())

    /** 当前解析线路（见 ROUTE_* 常量），手动弹窗与群聊自动回复共用同一选择。 */
    private var parseRoute by prefOption("parse_video_route", ROUTE_AUTO)

    /** 主线路请求的 pid 参数（线路1~线路7 → 数字 1~7），手动弹窗与群聊自动回复共用。 */
    private var parsePid by prefOption("parse_video_pid", 2)

    private fun defaultSaveDir(): String =
        (KnownPaths.downloads / "ParseVideo").absolutePathString()

    private fun currentSaveDir(): String =
        saveDir.ifBlank { defaultSaveDir() }

    private fun ensureSaveDir(): java.io.File =
        java.io.File(currentSaveDir()).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }
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
            var whitelistRevision by remember { mutableIntStateOf(0) }
            val whitelistCount = remember(whitelistRevision) { autoReplyWhitelist.size }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_parse_video_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        SwitchWidget(
                            title = stringResource(R.string.parse_video_auto_reply),
                            description = stringResource(R.string.parse_video_auto_reply_description),
                            checked = autoReplyChecked,
                            onCheckedChange = {
                                autoReplyChecked = it
                                autoReply = it
                            },
                        )
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
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

    /** 白名单多选：好友 + 群聊一起列出，选中即生效（空集 = 全部群聊生效）。 */
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
        /** 主线路返回的多清晰度列表（备用线路为空）；(label, url) 按清晰度降序。 */
        val qualityList: List<Pair<String, String>> = emptyList(),
        /** 图集（图片列表，无视频时非空）。 */
        val imageList: List<String> = emptyList(),
        /** 主线路直出封面；若与图集任一张相同则置空（以图集为准，UI 不再显示封面）。 */
        val coverUrl: String = "",
        /** 备用线路多视频直链：不供逐档选择，下载时全部取下（qualityList 此时即视频列表，与图集同思路）。 */
        val downloadAllVideos: Boolean = false,
    ) {
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

    // ==================== 主线路（dy.51web.eu.org）数据模型 ====================

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

    // ==================== 小红书线路（dovis）数据模型 ====================

    /**
     * dovis 小红书解析响应：{code, msg, data:{author, authorID, title, desc, avatar, cover, url, imgurl}}。
     * 视频笔记：直链在 `url`；图集笔记：`url` 缺省，图片直链列表在 `imgurl`。
     */
    @Serializable
    private data class XhsParseResult(
        val code: Int = 0,
        val msg: String = "",
        val data: XhsParseData? = null,
    )

    @Serializable
    private data class XhsParseData(
        val author: String? = null,
        val title: String? = null,
        val desc: String? = null,
        val avatar: String? = null,
        val cover: String? = null,
        val url: String? = null,
        val imgurl: List<String> = emptyList(),
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
        // 小红书链接（xiaohongshu.com / xhslink 短链）固定走 dovis 小红书线路：
        // 前两条线路不支持小红书，逐级失败回退只会白等两轮超时
        val isXhsLink = xhsUrlRegex.containsMatchIn(link)
        if (isXhsLink && parseRoute != ROUTE_XHS) {
            return@runCatching parseByXhs(link).getOrElse { throw it }
        }
        when (parseRoute) {
            // 用户手动指定线路：严格只走所选线路，失败直接报错，不再静默回退
            ROUTE_PRIMARY -> parseByPrimary(link).getOrElse { throw it }
            ROUTE_BACKUP -> parseByBackup(link).getOrElse { throw it }
            ROUTE_XHS -> parseByXhs(link).getOrElse { throw it }
            // 默认：主线路优先（多清晰度无水印）；失败/无有效地址自动回退 kit9 聚合解析
            else -> parseByPrimary(link).getOrElse { primaryError ->
                WeLogger.w(TAG, "primary parse failed, fallback to backup: ${primaryError.message}")
                parseByBackup(link).getOrElse { backupError ->
                    WeLogger.w(TAG, "backup parse also failed: ${backupError.message}")
                    throw backupError
                }
            }
        }
    }

    /** 主线路：dy.51web.eu.org。结果映射成统一的 VideoParseResult 供 UI 层无感消费。 */
    private fun parseByPrimary(link: String): Result<VideoParseResult> = runCatching {
        // pid 为线路编号（1~7，用户左下角「线路N」选择框决定），传数字而非「线路N」文本
        val url = PARSE_API_PRIMARY +
            "?token=" + java.net.URLEncoder.encode(PARSE_API_PRIMARY_TOKEN, "UTF-8") +
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
            )
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

    /** 小红书解析线路：dovis（http 接口，视频直链无水印；支持 xiaohongshu.com / xhslink 短链）。 */
    private fun parseByXhs(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API_XHS + java.net.URLEncoder.encode(link, "UTF-8")
        // 后端用请求方 UA 抓取小红书页面，默认 okhttp UA 会得到 502
        val request = browserParseRequest(url, PARSE_API_XHS_ORIGIN)
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("小红书线路请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("小红书线路响应为空")
            val result = json.decodeFromString<XhsParseResult>(body)
            require(result.code == 200) { result.msg.ifBlank { "小红书线路解析失败 (code=${result.code})" } }
            val data = result.data ?: error("小红书线路返回数据为空")
            val videoUrl = data.url.orEmpty().takeIf { it.startsWith("http") }
            val images = data.imgurl.filter { it.startsWith("http") }
            if (videoUrl == null && images.isEmpty()) error("小红书线路未返回可用的视频/图片地址")
            WeLogger.i(TAG, "xhs parse ok, video=${videoUrl != null}, images=${images.size}")
            VideoParseResult(
                code = 200,
                msg = "success",
                data = json.parseToJsonElement(
                    json.encodeToString(
                        VideoData(
                            video_title = data.title.orEmpty().ifBlank { data.desc.orEmpty() },
                            video_cover = data.cover.orEmpty(),
                            video_link = videoUrl.orEmpty(),
                            author = data.author.takeIf { !it.isNullOrBlank() }?.let {
                                AuthorData(name = it, avatar = data.avatar.orEmpty())
                            },
                        ),
                    ),
                ),
                qualityList = emptyList(),
                imageList = images,
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

    // ==================== 群聊抖音链接自动解析回复 ====================

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
     * 会话在白名单中 + 非自己发送 + 文本含抖音/小红书分享链接 -> 解析 -> 下载到临时目录 -> 发回该会话。
     * 白名单为空时全部不生效。由 hookBefore 在消息入库前调用, 不阻塞入库流程。
     */
    private fun handleAutoReply(msgInfo: MessageInfo) {
        try {
            // 白名单模式：仅选中的会话（群聊或私聊用户）生效；未选择则全部不生效
            if (autoReplyWhitelist.isEmpty() || msgInfo.talker !in autoReplyWhitelist) return
            if (msgInfo.type?.isText != true) return
            if (msgInfo.isSelfSender) return

            val text = msgInfo.humanReadableRepr
            val link = extractDouyinUrl(text).ifEmpty { extractXhsUrl(text) }
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

    /** 从消息文本中提取小红书分享链接；非小红书链接返回空串。链接外层标注的双引号一并去除。 */
    private fun extractXhsUrl(raw: String): String =
        xhsUrlRegex.find(raw)?.value?.trim('"', '“', '”') ?: ""

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

    /** 图集横滑缩略图行（混合内容独立区块与纯图集结果共用）。 */
    @Composable
    private fun GalleryThumbsRow(images: List<String>) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            images.forEachIndexed { index, imgUrl ->
                AsyncImage(
                    model = imgUrl,
                    contentDescription = "$index",
                    modifier = Modifier
                        .size(width = 96.dp, height = 128.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }

fun showParseDialog(context: android.content.Context) {
        showComposeDialog(context, directlyDismissable = false) {
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
            var pidMenuExpanded by remember { mutableStateOf(false) }
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
                            val hasVideo = r.parsedData()?.video_link?.isNotBlank() == true
                            if (r.code != 200 || (!hasVideo && r.imageList.isEmpty())) {
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
                ROUTE_XHS to R.string.parse_video_route_xhs,
            )

            AlertDialogContent(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.feature_parse_video_name),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 右上角齿轮：像文字转语音切音色一样手动切换解析线路（选择持久化，手动弹窗与自动回复共用）
                        Box {
                            IconButton(onClick = { routeMenuExpanded = true }) {
                                Icon(
                                    MaterialSymbols.Outlined.Tune,
                                    contentDescription = stringResource(R.string.parse_video_route_settings),
                                )
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
                                }
                            }
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            enabled = !loading,
                            label = { Text(stringResource(R.string.parse_video_link_hint)) },
                            placeholder = {
                                Text(stringResource(R.string.parse_video_link_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { doConvert() },
                                modifier = Modifier.weight(1f),
                                enabled = !loading && !downloading && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_convert))
                            }
                            Button(
                                onClick = { doSendNow() },
                                modifier = Modifier.weight(1f),
                                enabled = !loading && !downloading && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_send))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        if (loading) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 12.dp),
                                    strokeWidth = 3.dp,
                                )
                                Text(stringResource(R.string.parse_video_loading))
                            }
                        }

                        errorMsg?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        // ===== 解析结果卡片（封面 + 信息 + 清晰度 + 保存封面） =====
                        parseResult?.let { r ->
                            val data = r.parsedData() ?: return@let
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    // 混合内容（视频+图集并存）：图集区块在前、视频区块在下，各带独立下载按钮
                                    val isMixed = data.video_link.isNotBlank() && r.imageList.isNotEmpty()
                                    if (isMixed) {
                                        Text(
                                            text = stringResource(R.string.parse_video_section_gallery),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        GalleryThumbsRow(r.imageList)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = localizedChatInputString(R.string.parse_video_gallery_detected, r.imageList.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = { doDownload(images = true) },
                                            enabled = !downloading && !sending,
                                        ) {
                                            Text(stringResource(R.string.parse_video_download_images_btn))
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        HorizontalDivider()
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = stringResource(R.string.parse_video_section_video),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    Row(verticalAlignment = Alignment.Top) {
                                        if (directCoverUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = directCoverUrl,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(width = 108.dp, height = 144.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                                contentScale = ContentScale.Crop,
                                            )
                                            Spacer(Modifier.width(12.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            if (data.video_title.isNotBlank()) {
                                                Text(
                                                    text = data.video_title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 4,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            data.author?.let { author ->
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = author.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (qualityList.size > 1 && !r.downloadAllVideos) {
                                                Spacer(Modifier.height(8.dp))
                                                var expanded by remember { mutableStateOf(false) }
                                                val selectedLabel = qualityList
                                                    .firstOrNull { it.second == selectedQualityUrl }?.first
                                                    ?: qualityList.firstOrNull()?.first
                                                    ?: "选择清晰度"
                                                Box {
                                                    OutlinedTextField(
                                                        value = selectedLabel,
                                                        onValueChange = {},
                                                        readOnly = true,
                                                        label = { Text(stringResource(R.string.parse_video_quality_label)) },
                                                        trailingIcon = {
                                                            IconButton(onClick = { expanded = !expanded }) {
                                                                Icon(
                                                                    if (expanded) MaterialSymbols.Outlined.Keyboard_arrow_up
                                                                    else MaterialSymbols.Outlined.Keyboard_arrow_down,
                                                                    null,
                                                                )
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                    DropdownMenu(
                                                        expanded = expanded,
                                                        onDismissRequest = { expanded = false },
                                                    ) {
                                                        qualityList.forEach { (label, url) ->
                                                            DropdownMenuItem(
                                                                text = { Text(label) },
                                                                onClick = {
                                                                    selectedQualityUrl = url
                                                                    expanded = false
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    if (directCoverUrl.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                savingCover = true
                                                scope.launch {
                                                    val result = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            val dir = ensureSaveDir()
                                                            val ext = if (directCoverUrl.contains(".png")) "png" else "jpg"
                                                            val out = java.io.File(dir, "cover-${UUID.randomUUID()}.$ext")
                                                            val req = Request.Builder().url(directCoverUrl).get().build()
                                                            httpClient.newCall(req).execute().use { resp ->
                                                                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                                                                resp.body.byteStream().use { ins ->
                                                                    out.outputStream().use { ins.copyTo(it) }
                                                                }
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
                                                            errorMsg = e.message ?: "保存封面失败"
                                                        },
                                                    )
                                                }
                                            },
                                            enabled = !savingCover,
                                        ) {
                                            Text(
                                                if (savingCover) stringResource(R.string.parse_video_saving_cover)
                                                else stringResource(R.string.parse_video_save_cover),
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }

                                    // ===== 混合内容：视频区块尾部的数量提示 + 独立下载按钮 =====
                                    if (isMixed) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = localizedChatInputString(
                                                R.string.parse_video_videos_detected,
                                                qualityList.size.takeIf { it > 0 } ?: 1,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = { doDownload(images = false) },
                                            enabled = !downloading && !sending,
                                        ) {
                                            Text(
                                                stringResource(
                                                    if (r.downloadAllVideos) R.string.parse_video_download_all_videos_btn
                                                    else R.string.parse_video_download_video_btn,
                                                ),
                                            )
                                        }
                                    }

                                    // ===== 图集预览（纯图集结果保持原位展示；混合内容已上移为独立区块） =====
                                    if (!isMixed && r.imageList.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        GalleryThumbsRow(r.imageList)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = localizedChatInputString(R.string.parse_video_gallery_detected, r.imageList.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                Spacer(Modifier.height(8.dp))

                                // ===== 下载状态 =====

                                when {
                                    downloading || sending -> {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            val percent = (downloadProgress * 100).toInt()
                                            LinearProgressIndicator(
                                                progress = { downloadProgress.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = if (sending) {
                                                    localizedChatInputString(R.string.parse_video_sending)
                                                } else if (percent >= 100) {
                                                    localizedChatInputString(R.string.parse_video_downloading)
                                                } else {
                                                    localizedChatInputString(R.string.parse_video_downloading) +
                                                        " $percent%"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    downloadedFiles.isNotEmpty() -> {
                                        val totalSize = downloadedFiles.sumOf { it.length() }
                                        Text(
                                            text = buildString {
                                                append(localizedChatInputString(R.string.parse_video_downloaded))
                                                if (downloadedFiles.size > 1) {
                                                    append(" ×")
                                                    append(downloadedFiles.size)
                                                }
                                                append(" (")
                                                append("%.1f".format(totalSize / 1024.0 / 1024.0))
                                                append("MB)")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                },
                dismissButton = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 左下角线路选择：当前线路为自动/主线路时可选线路1~线路7（数字作为 pid 传给主线路接口）
                        if (selectedRoute == ROUTE_AUTO || selectedRoute == ROUTE_PRIMARY) {
                            Box {
                                OutlinedButton(onClick = { pidMenuExpanded = true }) {
                                    Text(stringResource(R.string.parse_video_line_option, selectedPid))
                                    Icon(
                                        MaterialSymbols.Outlined.Keyboard_arrow_down,
                                        contentDescription = null,
                                    )
                                }
                                DropdownMenu(
                                    expanded = pidMenuExpanded,
                                    onDismissRequest = { pidMenuExpanded = false },
                                ) {
                                    (1..7).forEach { n ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.parse_video_line_option, n)) },
                                            trailingIcon = {
                                                if (selectedPid == n) {
                                                    Icon(
                                                        MaterialSymbols.Outlined.Check_circle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedPid = n
                                                parsePid = n
                                                pidMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(onClick = onDismiss, enabled = !loading && !sending) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    }
                },
                confirmButton = {
                    // ===== 按钮组：解析成功后视频/图集下载按钮常驻，下载完再追加发送/删除 =====
                    val r = parseResult
                    val data = r?.parsedData()
                    val hasVideo = data?.video_link?.isNotBlank() == true
                    if (r != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            // 纯视频保持单下载按钮；有图集（含混合）改为「下载全部文件」一键取视频+图集
                            if (hasVideo && r.imageList.isEmpty()) {
                                Button(
                                    onClick = { doDownload(images = false) },
                                    enabled = !downloading && !sending,
                                ) {
                                    Text(
                                        stringResource(
                                            if (r.downloadAllVideos) R.string.parse_video_download_all_videos_btn
                                            else R.string.parse_video_download,
                                        ),
                                    )
                                }
                            }
                            if (r.imageList.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Button(
                                    onClick = { doDownloadAll() },
                                    enabled = !downloading && !sending,
                                ) {
                                    Text(
                                        if (downloading) stringResource(R.string.parse_video_downloading)
                                        else stringResource(R.string.parse_video_download_all_files),
                                    )
                                }
                            }
                            if (downloadedFiles.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { sendDownloadedFiles() },
                                        modifier = Modifier.weight(1f),
                                        enabled = !downloading && !sending,
                                    ) {
                                        Text(stringResource(R.string.parse_video_send_files))
                                    }
                                    OutlinedButton(
                                        onClick = { deleteDownloadedFile() },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            stringResource(R.string.parse_video_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
