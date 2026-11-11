package dev.sherry.wcs.features.items.chat

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Graphic_eq
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Settings
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
import dev.sherry.wcs.features.api.core.models.MessageType
import dev.sherry.wcs.features.api.ui.WeChatInputBarMenuApi
import dev.sherry.wcs.features.api.ui.WeChatMessageContextMenuApi
import dev.sherry.wcs.features.api.ui.WeCurrentConversationApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.MicIcon
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.BreezeCreditsClient
import dev.sherry.wcs.utils.BreezeTtsClient
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.fs.KnownPaths
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object TextToSpeech :
    ClickableFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider,
    WeChatInputBarMenuApi.IActionItemsProvider {

    override val technicalId = "文字转语音"
    override val nameRes = R.string.feature_text_to_speech_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_text_to_speech_description

    private const val TAG = "TextToSpeech"
    private const val API_BASE = "https://peiyinmofang.com"

    const val BACKEND_MOFA = 0
    const val BACKEND_DOUBAO = 1
    const val BACKEND_BREEZE = 2

    // 豆包网页端逆向协议: 鉴权在 WebSocket 握手阶段完成, 依赖登录态 Cookie
    private const val DOUBAO_WS = "wss://ws-samantha.doubao.com/samantha/audio/tts"
    private const val DOUBAO_ORIGIN = "https://www.doubao.com"
    private const val DOUBAO_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val DOUBAO_AID = 497858
    private const val DOUBAO_VERSION_CODE = 20800
    private const val DOUBAO_PC_VERSION = "2.46.3"

    /** 服务端不保证主动关闭连接, 静默这么久即视为合成结束 */
    private const val DOUBAO_IDLE_GAP_MS = 2500L
    private const val DOUBAO_MAX_WAIT_MS = 30000L

    var selectedVoice by prefOption("tts_voice_id", "琅琊榜-梅长苏")
    var selectedEmotion by prefOption("tts_emotion", "平静")

    /**
     * 凭据与模型不再存在功能自己这边：统一从「娱乐 → API配置 → 语音合成」那条档里取。
     *
     * 本功能只记「用哪一条」（[TtsProfiles.activeId]），换 Key 去配置页改，改一处两边都生效。
     * 这里保持同名属性，是为了让合成、音色选择器那些调用点不用改签名。
     */
    private val activeProfile: ApiProfile? get() = TtsProfiles.active()

    /** 当前档的后端。没配过任何档时回落 Breeze，与老默认一致。 */
    val backend: Int get() = TtsProfiles.backendOf(activeProfile)

    /** 魔方用的 Key。当前档不是魔方就是空，合成前的校验会拦下。 */
    val apiKey: String
        get() = activeProfile?.takeIf { it.provider == TTS_PROVIDER_MOFANG }?.apiKey.orEmpty()

    /** 豆包的登录 Cookie。 */
    private val doubaoCookie: String
        get() = activeProfile?.takeIf { it.provider == TTS_PROVIDER_DOUBAO }?.cookie.orEmpty()

    /** Breeze 的 API Key（`xi-api-key`），与签到用的 Cookie 是两套凭据。 */
    private val breezeKey: String
        get() = activeProfile?.takeIf { it.provider == TTS_PROVIDER_BREEZE }?.apiKey.orEmpty()

    /** Breeze 的合成模型，来自配置档；空着用客户端默认值。 */
    private val breezeModel: String get() = TtsProfiles.modelOf(activeProfile)

    private var doubaoSpeaker by prefOption("tts_doubao_speaker", "zh_female_shuangkuai_emo_v3_wvae_bigtts")
    private var doubaoSpeakerName by prefOption("tts_doubao_speaker_name", "爽快 · 女声")

    /**
     * 网页侧积分接口用的登录会话 cookie，与 API Key 是两套凭据。
     * 它只服务于「签到领积分」这一件事，不算合成配置，所以留在功能侧而不进配置档。
     */
    private var breezeCookie by prefOption("tts_breeze_cookie", "")
    private var breezeVoice by prefOption("tts_breeze_voice", "")
    private var breezeVoiceName by prefOption("tts_breeze_voice_name", "")

    /** Breeze 没有情绪向量，只有自然语言「指令」，文档要求写在 1000 字以内 */
    private var breezeInstructions by prefOption("tts_breeze_instructions", "")

    /** 入口一: 长按聊天消息气泡的菜单 */
    private var entryBubble by prefOption("tts_entry_bubble", false)

    /** 入口二: 长按输入栏加号/发送按钮弹出的菜单 */
    private var entryInputBar by prefOption("tts_entry_input_bar", false)

    private val mh = Handler(Looper.getMainLooper())
    private var lastWavPath: String? = null

    data class TtsVoice(val voiceId: String, val label: String)

    val DEFAULT_VOICES = listOf(
        TtsVoice("琅琊榜-梅长苏", "琅琊榜-梅长苏"),
        TtsVoice("琅琊榜-靖王", "琅琊榜-靖王"),
        TtsVoice("甄嬛传-甄嬛", "甄嬛传-甄嬛"),
    )

    data class DoubaoVoice(val id: String, val label: String)

    /** 离线兜底表：这些 id 都不在豆包推荐接口里，所以只有名字，没有头像和分类标签。 */
    val DOUBAO_VOICES = listOf(
        DoubaoVoice("zh_female_shuangkuai_emo_v3_wvae_bigtts", "爽快 · 女声"),
        DoubaoVoice("zh_female_tianmei_conversation_v4_wvae_bigtts", "甜美 · 女声"),
        DoubaoVoice("zh_female_qingche_moon_bigtts", "清澈 · 女声"),
        DoubaoVoice("zh_male_yangguang_conversation_v4_wvae_bigtts", "阳光 · 男声"),
        DoubaoVoice("zh_male_chenwen_moon_bigtts", "沉稳 · 男声"),
        DoubaoVoice("zh_male_rap_mars_bigtts", "说唱 · 男声"),
        DoubaoVoice("en_female_sarah_conversation_bigtts", "Sarah · 英文女声"),
        DoubaoVoice("en_male_adam_conversation_bigtts", "Adam · 英文男声"),
    )

    private val doubaoClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    val EMOTIONS = listOf(
        "平静" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "开心" to floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "悲伤" to floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
        "愤怒" to floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f),
        "惊讶" to floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
        "害怕" to floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f),
        "温柔" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f),
    )

    /** 情绪的中文名既是存储键也是接口语义，展示名单独走资源，切英文才不会是中文。 */
    private val EMOTION_LABELS = mapOf(
        "平静" to R.string.tts_emotion_calm,
        "开心" to R.string.tts_emotion_happy,
        "悲伤" to R.string.tts_emotion_sad,
        "愤怒" to R.string.tts_emotion_angry,
        "惊讶" to R.string.tts_emotion_surprised,
        "害怕" to R.string.tts_emotion_afraid,
        "温柔" to R.string.tts_emotion_gentle,
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
        WeChatInputBarMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
        WeChatInputBarMenuApi.removeProvider(this)
    }

    @Suppress("DEPRECATION")
    fun isSupported(msgInfo: MessageInfo): Boolean =
        msgInfo.type == MessageType.TEXT || msgInfo.type == MessageType.QUOTE

    private val micImageVector = MaterialSymbols.Outlined.Graphic_eq

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        if (!entryBubble) return emptyList()

        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777025,
                localizedChatString(R.string.chat_tts_menu),
                MicIcon,
                micImageVector,
                isSupported = ::isSupported,
                onClick = { view, _, msgInfo ->
                    val text = msgInfo.humanReadableRepr.trim()
                    if (text.isEmpty()) {
                        showToast(view.context, localizedChatString(R.string.tts_toast_no_text))
                        return@MenuItem
                    }
                    showMainDialog(view.context, msgInfo.talker, text)
                }
            )
        )
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> {
        if (!entryInputBar) return emptyList()

        return listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "text_to_speech",
                icon = micImageVector,
                label = localizedChatString(R.string.chat_tts_menu),
                onClick = { context, chatFooter ->
                    showMainDialog(context, WeCurrentConversationApi.value, chatFooter.lastText.trim())
                }
            )
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var bubbleEntry by remember { mutableStateOf(entryBubble) }
            var inputBarEntry by remember { mutableStateOf(entryInputBar) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_text_to_speech_name)) },
                text = {
                    SegmentedColumn(title = stringResource(R.string.tts_entry_group_title)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.tts_entry_bubble),
                                description = stringResource(R.string.tts_entry_bubble_description),
                                checked = bubbleEntry,
                                onCheckedChange = {
                                    bubbleEntry = it
                                    entryBubble = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.tts_entry_input_bar),
                                description = stringResource(R.string.tts_entry_input_bar_description),
                                checked = inputBarEntry,
                                onCheckedChange = {
                                    inputBarEntry = it
                                    entryInputBar = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun showMainDialog(context: android.content.Context, talker: String, initialText: String) {
        // 点弹窗外面不关（避免误触丢掉已生成的语音），但返回键仍可关；显式出口是底部「关闭」
        showComposeDialog(context, dismissOnTouchOutside = false) {
            var inputText by remember { mutableStateOf(initialText) }
            // 后端来自「当前选中的语音合成配置」，整个弹窗生命周期内不变
            val backendMode = backend
            val isDoubao = backendMode == BACKEND_DOUBAO
            val isBreeze = backendMode == BACKEND_BREEZE
            // 换配置档（= 换后端）后音色必须重选：音色 id 跨服务商无效，静默带过去会发出错误音色
            var voiceId by remember(backendMode) {
                mutableStateOf(
                    when (backendMode) {
                        BACKEND_DOUBAO -> doubaoSpeaker
                        BACKEND_BREEZE -> breezeVoice
                        else -> selectedVoice
                    }
                )
            }
            var emotion by remember {
                mutableStateOf(
                    selectedEmotion.takeIf { e -> EMOTIONS.any { it.first == e } }
                        ?: EMOTIONS.first().first
                )
            }
            // 偏好委托直接读进组合不会自己刷新：选完音色、改完指令要当场变，所以各存一份局部状态
            var breezeLabel by remember { mutableStateOf(breezeVoiceName.ifBlank { breezeVoice }) }
            var doubaoLabel by remember { mutableStateOf(doubaoSpeakerName) }
            var instructions by remember { mutableStateOf(breezeInstructions) }
            var voices by remember {
                mutableStateOf(
                    if (DEFAULT_VOICES.any { it.voiceId == selectedVoice }) DEFAULT_VOICES
                    else DEFAULT_VOICES + TtsVoice(selectedVoice, selectedVoice)
                )
            }
            var customVoices by remember { mutableStateOf(emptyList<TtsVoice>()) }
            var generating by remember { mutableStateOf(false) }
            // lastWavPath 是裸成员变量、不可观察，试听按钮要「生成完就出现」得自己镜像一份
            var previewPath by remember {
                mutableStateOf(lastWavPath?.takeIf { File(it).exists() })
            }
            var credentialsRevision by remember { mutableIntStateOf(0) }

            LaunchedEffect(backendMode, credentialsRevision) {
                // 魔方音色列表按后端重新拉取: 从豆包切回魔方时不能沿用旧快照;
                // apiKey 是裸 MMKV 委托、不可观察, 所以填完 Key 由设置弹窗递增 revision 来触发重拉
                if (backendMode != BACKEND_MOFA) return@LaunchedEffect
                Thread {
                    val fetched = fetchVoices()
                    val custom = fetchUserVoices()
                    mh.post {
                        // 异步结果可能晚于用户再次切换到达, 只在仍处于魔方模式时回填
                        if (backend != BACKEND_MOFA) return@post
                        voices = fetched
                        customVoices = custom
                        val inSystem = fetched.any { it.voiceId == voiceId }
                        val inCustom = custom.any { it.voiceId == voiceId }
                        // 当前所选不在魔方列表里 (含被豆包音色污染的残留值) 时归位
                        if (!inSystem && !inCustom && fetched.isNotEmpty()) {
                            voiceId = fetched.first().voiceId
                            selectedVoice = voiceId
                        }
                    }
                }.start()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_text_to_speech_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            placeholder = { Text(stringResource(R.string.tts_input_placeholder)) },
                        )

                        Spacer(Modifier.height(8.dp))

                        val mainOptions = voices.map { DropdownOption(it.voiceId, it.label) }
                        val customOptions = customVoices.map { DropdownOption(it.voiceId, it.label) }
                        val customCurrent = customOptions.any { it.value == voiceId }
                        val emotionOptions = remember(EMOTIONS) {
                            EMOTIONS.map { (key, _) ->
                                DropdownOption(key, context.localizedChatString(EMOTION_LABELS.getValue(key)))
                            }
                        }

                        if (isBreeze) {
                            // 整行单列：自建音色收在选择弹窗的「我的」页签里，不再单独占一列
                            BaseWidget(
                                title = stringResource(R.string.tts_row_breeze_voice),
                                description = breezeLabel.ifBlank { stringResource(R.string.tts_not_selected) },
                                onClick = {
                                    showBreezeVoicePicker(context, voiceId, breezeKey) { id, name ->
                                        voiceId = id
                                        breezeVoice = id
                                        breezeVoiceName = name
                                        breezeLabel = name.ifBlank { id }
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                    )
                                },
                            )
                        } else if (isDoubao) {
                            // 豆包既不吃情绪向量，也没有魔方的自定义音色，整行只留音色
                            BaseWidget(
                                title = stringResource(R.string.tts_row_doubao_voice),
                                description = doubaoLabel,
                                onClick = {
                                    showDoubaoVoicePicker(context, voiceId, doubaoCookie) { id, name ->
                                        voiceId = id
                                        doubaoSpeaker = id
                                        doubaoSpeakerName = name
                                        doubaoLabel = name
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                    )
                                },
                            )
                        } else {
                            Row(Modifier.fillMaxWidth()) {
                                Box(Modifier.weight(1f)) {
                                    DropDownMenuWidget(
                                        title = stringResource(R.string.tts_row_system_voice),
                                        description = null,
                                        value = voiceId,
                                        options = if (mainOptions.any { it.value == voiceId }) mainOptions
                                            else mainOptions + DropdownOption(voiceId, voiceId),
                                        onValueChange = {
                                            voiceId = it
                                            selectedVoice = it
                                        },
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    DropDownMenuWidget(
                                        title = stringResource(R.string.tts_row_custom_voice),
                                        description = if (customCurrent) null
                                        else stringResource(R.string.tts_not_selected),
                                        value = voiceId,
                                        options = if (customCurrent) customOptions
                                            else customOptions + DropdownOption(voiceId, ""),
                                        onValueChange = { voiceId = it; selectedVoice = it },
                                        enabled = customVoices.isNotEmpty(),
                                    )
                                }
                            }
                        }

                        // 豆包模式下「自定义音色」和「语气」都不出现，不再置灰
                        if (!isDoubao) {
                            Spacer(Modifier.height(8.dp))
                            if (isBreeze) {
                                // Breeze 不吃情绪向量，样式全靠一句自然语言指令（文档：1000 字以内）
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.tts_row_instructions),
                                    value = instructions,
                                    onValueChange = {
                                        instructions = it
                                        breezeInstructions = it
                                    },
                                    dialogTitle = stringResource(R.string.tts_dlg_instructions_title),
                                    confirmLabel = stringResource(R.string.tts_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.tts_instructions_hint),
                                    filter = { it.take(BreezeTtsClient.INSTRUCTION_MAX) },
                                    singleLine = false,
                                )
                            } else {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.tts_row_emotion),
                                    description = null,
                                    value = emotion,
                                    options = emotionOptions,
                                    onValueChange = { emotion = it; selectedEmotion = it },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (generating) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }

                        // 三枚等分一行；内边距收到 6dp，喵化后「发送语音喵」才不会把这一行挤断
                        val footerPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        Row(Modifier.fillMaxWidth()) {
                            // 试听放最左边，只在真拿到过音频后出现；平时这行还是生成/发送语音两枚
                            previewPath?.let { path ->
                                FilledTonalButton(
                                    onClick = { showPreviewDialog(context, talker, path) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = footerPadding,
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Play_arrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.tts_btn_preview), maxLines = 1)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Button(
                                onClick = {
                                    if (inputText.trim().isEmpty()) {
                                        showToast(
                                            context,
                                            context.localizedChatString(R.string.tts_toast_enter_text),
                                        )
                                        return@Button
                                    }
                                    if (isDoubao) {
                                        if (doubaoCookie.isBlank()) {
                                            showToast(
                                                context,
                                                context.localizedChatString(R.string.tts_toast_need_doubao_cookie),
                                            )
                                            showSettingsDialog(context)
                                            return@Button
                                        }
                                        generating = true
                                        generateVoiceDoubao(inputText.trim(), voiceId) { path, err ->
                                            generating = false
                                            if (path != null) {
                                                previewPath = path
                                                showPreviewDialog(context, talker, path)
                                            } else {
                                                showToast(
                                                    context,
                                                    context.localizedChatString(
                                                        R.string.tts_toast_generate_failed,
                                                        err,
                                                    ),
                                                )
                                            }
                                        }
                                        return@Button
                                    }
                                    if (isBreeze) {
                                        if (breezeKey.isBlank()) {
                                            showToast(
                                                context,
                                                context.localizedChatString(R.string.tts_toast_need_breeze_key),
                                            )
                                            showSettingsDialog(context)
                                            return@Button
                                        }
                                        if (voiceId.isBlank()) {
                                            showToast(
                                                context,
                                                context.localizedChatString(R.string.tts_toast_need_breeze_voice),
                                            )
                                            return@Button
                                        }
                                        generating = true
                                        // 指令原文透传给接口的 instructions 字段
                                        generateVoiceBreeze(
                                            inputText.trim(),
                                            voiceId,
                                            instructions,
                                        ) { path, err ->
                                            generating = false
                                            if (path != null) {
                                                previewPath = path
                                                showPreviewDialog(context, talker, path)
                                            } else {
                                                showToast(
                                                    context,
                                                    context.localizedChatString(
                                                        R.string.tts_toast_generate_failed,
                                                        err,
                                                    ),
                                                )
                                            }
                                        }
                                        return@Button
                                    }
                                    if (apiKey.isBlank()) {
                                        showToast(
                                            context,
                                            context.localizedChatString(R.string.tts_toast_need_mofang_key),
                                        )
                                        showSettingsDialog(context) { credentialsRevision++ }
                                        return@Button
                                    }
                                    generating = true
                                    generateVoice(
                                        inputText.trim(),
                                        voiceId,
                                        EMOTIONS.first { it.first == emotion }.second,
                                    ) { wavPath, err ->
                                        generating = false
                                        if (wavPath != null) {
                                            previewPath = wavPath
                                            showPreviewDialog(context, talker, wavPath)
                                        } else {
                                            showToast(
                                                context,
                                                context.localizedChatString(
                                                    R.string.tts_toast_generate_failed,
                                                    err,
                                                ),
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = footerPadding,
                            ) {
                                Text(stringResource(R.string.tts_btn_generate), maxLines = 1)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val wav = lastWavPath
                                    if (wav == null || !File(wav).exists()) {
                                        showToast(
                                            context,
                                            context.localizedChatString(R.string.tts_toast_need_audio),
                                        )
                                        return@Button
                                    }
                                    sendVoiceTo(talker, wav) { ok ->
                                        if (ok) {
                                            showToast(
                                                context,
                                                context.localizedChatString(R.string.tts_toast_voice_sent),
                                            )
                                            onDismiss()
                                        } else {
                                            showToast(
                                                context,
                                                context.localizedChatString(R.string.tts_toast_send_failed),
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = footerPadding,
                            ) {
                                Text(stringResource(R.string.tts_btn_send_voice), maxLines = 1)
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 用哪一家由「设置 → 选择合成配置」决定：选的就是配置档，
                            // 所以这里不再单独放一个切后端的按钮，两个入口会互相打脸
                            TextButton(onClick = { showSettingsDialog(context) { credentialsRevision++ } }) {
                                Icon(
                                    MaterialSymbols.Outlined.Settings,
                                    contentDescription = stringResource(R.string.tts_cd_settings),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.tts_btn_open_settings))
                            }
                            TextButton(onClick = { onDismiss() }) {
                                Text(stringResource(R.string.dialog_close))
                            }
                        }
                    }
                }
            )
        }
    }

    /**
     * 设置只有两行：选哪条合成配置、以及 Breeze 的每日积分签到。
     *
     * 凭据与模型搬进配置档后，这里不再填 Key / Cookie / 模型 —— 那些在「娱乐 → API配置」
     * 里改，改一处所有引用方一起生效。签到依赖的是网页登录 Cookie（跟合成用的 Key
     * 是两套东西），所以它留在功能侧：点一下领积分，没填 Cookie 时先补一个。
     */
    private fun showSettingsDialog(context: android.content.Context, onApiKeySaved: () -> Unit = {}) {
        showComposeDialog(context) {
            var profileId by remember { mutableStateOf(TtsProfiles.active()?.id.orEmpty()) }
            val profile = remember(profileId) { TtsProfiles.byId(profileId) }
            // 只有 Breeze 有积分可领；豆包/魔方不显示这一行，也就不留一个点了会报错的按钮
            val isBreeze = profile == null || profile.provider == TTS_PROVIDER_BREEZE

            var breezeCookieState by remember { mutableStateOf(breezeCookie) }
            var creditsText by remember {
                mutableStateOf(
                    context.localizedChatString(
                        if (breezeCookie.isBlank()) R.string.tts_state_need_cookie
                        else R.string.tts_state_none,
                    ),
                )
            }
            var claiming by remember { mutableStateOf(false) }

            fun loadCredits() {
                if (breezeCookieState.isBlank()) {
                    creditsText = context.localizedChatString(R.string.tts_state_need_cookie)
                    return
                }
                creditsText = context.localizedChatString(R.string.tts_state_loading)
                Thread {
                    val outcome = runCatching { BreezeCreditsClient.summary(breezeCookieState) }
                    mh.post {
                        val s = outcome.getOrNull()
                        creditsText = if (s == null) {
                            context.localizedChatString(
                                R.string.tts_state_fetch_failed,
                                outcome.exceptionOrNull()?.message.orEmpty(),
                            )
                        } else {
                            val state = when {
                                s.available -> context.localizedChatString(
                                    R.string.tts_credit_available,
                                    s.amount.toInt(),
                                )
                                s.claimedToday -> context.localizedChatString(R.string.tts_credit_claimed_today)
                                else -> context.localizedChatString(R.string.tts_credit_unavailable)
                            }
                            context.localizedChatString(
                                R.string.tts_credit_summary,
                                s.balance.toInt(),
                                state,
                                beijingTime(s.nextResetAt),
                            )
                        }
                    }
                }.start()
            }

            fun claimCredits() {
                if (claiming) return
                if (breezeCookieState.isBlank()) {
                    showToast(context, context.localizedChatString(R.string.tts_toast_need_breeze_cookie))
                    return
                }
                claiming = true
                Thread {
                    val outcome = runCatching { BreezeCreditsClient.claim(breezeCookieState) }
                    mh.post {
                        claiming = false
                        val c = outcome.getOrNull()
                        val message = when {
                            c == null -> context.localizedChatString(
                                R.string.tts_toast_claim_failed,
                                outcome.exceptionOrNull()?.message.orEmpty(),
                            )
                            c.claimed -> context.localizedChatString(
                                R.string.tts_toast_claimed,
                                c.creditsIssued.toInt(),
                                c.balance.toInt(),
                            )
                            c.alreadyClaimed -> context.localizedChatString(R.string.tts_toast_already_claimed)
                            else -> context.localizedChatString(
                                R.string.tts_toast_not_claimed,
                                c.rewardTier.ifBlank { context.localizedChatString(R.string.tts_unknown) },
                            )
                        }
                        showToast(context, message)
                        loadCredits()
                    }
                }.start()
            }

            LaunchedEffect(isBreeze, breezeCookieState) {
                if (isBreeze && breezeCookieState.isNotBlank()) loadCredits()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.tts_cfg_title)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SegmentedColumn {
                            item {
                                // 选配置等于选后端：一条档就代表一家服务商，所以不需要再单独切后端
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.tts_settings_profile),
                                    description = profile?.let {
                                        context.getString(TtsProfiles.providerLabelRes(it.provider)) +
                                            " · " + it.name
                                    } ?: stringResource(R.string.api_profile_none),
                                    onClick = {
                                        showApiProfilePicker(context, profileId, CAP_TTS) { picked ->
                                            profileId = picked.id
                                            TtsProfiles.activeId = picked.id
                                            onApiKeySaved()
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Chevron_right,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                            if (isBreeze) {
                                item {
                                    BaseWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.tts_cfg_daily_credits),
                                        description = creditsText,
                                        enabled = !claiming,
                                        onClick = {
                                            if (breezeCookieState.isBlank()) {
                                                // 签到要的是网页登录态，与合成用的 Key 无关，所以按需补一次
                                                showBreezeCookieDialog(context) { saved ->
                                                    breezeCookieState = saved
                                                    claimCredits()
                                                }
                                            } else {
                                                claimCredits()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.tts_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { Button(onDismiss) { Text(stringResource(R.string.tts_btn_done)) } },
            )
        }
    }

    /** 签到专用的 Cookie 输入。只是一个会话，不写进配置档，也不参与合成。 */
    private fun showBreezeCookieDialog(context: android.content.Context, onSaved: (String) -> Unit) {
        showComposeDialog(context) {
            var value by remember { mutableStateOf(breezeCookie) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.tts_dlg_set_breeze_cookie)) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5,
                            placeholder = { Text(stringResource(R.string.tts_breeze_cookie_hint)) },
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val saved = value.trim()
                            breezeCookie = saved
                            onSaved(saved)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.tts_confirm)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }

    fun showPreviewDialog(context: android.content.Context, talker: String, wavPath: String) {
        showComposeDialog(context, dismissOnTouchOutside = false) {
            var playing by remember { mutableStateOf(false) }
            var durationMs by remember { mutableIntStateOf(0) }
            var positionMs by remember { mutableIntStateOf(0) }
            var player by remember { mutableStateOf<MediaPlayer?>(null) }

            DisposableEffect(Unit) {
                val p = MediaPlayer().apply {
                    setDataSource(wavPath)
                    prepare()
                    durationMs = duration
                    setOnCompletionListener { mh.post { playing = false } }
                }
                player = p
                onDispose {
                    runCatching { p.stop() }
                    p.release()
                }
            }

            LaunchedEffect(playing) {
                while (playing) {
                    player?.let { positionMs = it.currentPosition }
                    delay(200)
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.tts_preview_title)) },
                text = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Slider(
                            value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                            onValueChange = {
                                positionMs = it.toInt()
                                player?.seekTo(it.toInt())
                            },
                            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(positionMs))
                            Text(formatDuration(durationMs))
                        }
                        IconButton(
                            onClick = {
                                val p = player ?: return@IconButton
                                if (playing) {
                                    p.pause()
                                    playing = false
                                } else {
                                    p.seekTo(positionMs)
                                    p.start()
                                    playing = true
                                }
                            },
                        ) {
                            Icon(
                                if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                                contentDescription = if (playing) stringResource(R.string.tts_cd_pause)
                                else stringResource(R.string.tts_cd_play),
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        player?.let { runCatching { it.stop() }.onFailure { } }
                        player?.release()
                        player = null
                        sendVoiceTo(talker, wavPath) { ok ->
                            if (ok) {
                                showToast(context, context.localizedChatString(R.string.tts_toast_voice_sent))
                                onDismiss()
                            } else {
                                showToast(context, context.localizedChatString(R.string.tts_toast_send_failed))
                            }
                        }
                    }) { Text(stringResource(R.string.tts_btn_send_voice)) }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }

    /** 接口的重置时间是 UTC，按北京时间显示；解析不了就退回原文，不猜。 */
    private fun beijingTime(iso: String): String = runCatching {
        // android.jar 的 OffsetDateTime 没有 withZoneSameInstant，绕到 Instant 上换区
        OffsetDateTime.parse(iso).toInstant().atZone(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(iso)

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    fun generateVoice(
        text: String,
        voiceId: String,
        emoVec: FloatArray,
        key: String = apiKey,
        cb: (String?, String) -> Unit,
    ) {
        Thread {
            var wavPath: String? = null
            var error = ""
            try {
                val body = JSONObject().apply {
                    put("voiceId", voiceId)
                    put("text", text)
                    put("emoVec", JSONArray(emoVec.map { it.toDouble() }))
                }.toString()
                val resp = httpPostJson("$API_BASE/api/open/v1/tts/simple-generate", body, key)
                if (resp.isEmpty()) {
                    error = localizedChatString(R.string.tts_err_no_response)
                } else {
                    val root = JSONObject(resp)
                    val audioUrl = root.optJSONObject("data")?.optString("audio").orEmpty()
                    if (audioUrl.isEmpty()) {
                        val msg = root.optString("msg").ifEmpty { root.optString("message") }
                        val noUrl = localizedChatString(R.string.tts_err_no_audio_url)
                        error = "code=${root.opt("code")} ${msg.ifEmpty { noUrl }}"
                    } else {
                        val dir = File(KnownPaths.moduleCache.toFile(), "wcs_tts").apply { mkdirs() }
                        val dest = File(dir, "tts_${System.currentTimeMillis()}.wav")
                        if (download(audioUrl, dest)) {
                            wavPath = dest.absolutePath
                            lastWavPath = wavPath
                        } else {
                            error = localizedChatString(R.string.tts_err_download_failed)
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "exception"
                WeLogger.w(TAG, "generate voice failed: $error")
            }
            val result = wavPath
            val reason = error
            mh.post { cb(result, reason) }
        }.start()
    }

    private fun doubaoWsUrl(speaker: String): String {
        // 伪装成一次全新的网页会话: 三个身份字段每次随机
        val dev = (7_400_000_000_000_000_000L + Random.nextLong(999_999_999_999_999_999L)).toString()
        return "$DOUBAO_WS?speaker=$speaker&format=aac&speech_rate=0&pitch=0" +
            "&version_code=$DOUBAO_VERSION_CODE&language=zh&device_platform=web" +
            "&aid=$DOUBAO_AID&real_aid=$DOUBAO_AID&pkg_type=release_version" +
            "&device_id=$dev&pc_version=$DOUBAO_PC_VERSION&web_id=$dev&tea_uuid=$dev" +
            "&region=&sys_region=&samantha_web=1&use-olympus-account=1&web_tab_id=${UUID.randomUUID()}"
    }

    fun generateVoiceDoubao(
        text: String,
        speaker: String,
        cookie: String = doubaoCookie,
        cb: (String?, String) -> Unit,
    ) {
        Thread {
            val audio = ByteArrayOutputStream()
            val closed = CountDownLatch(1)
            var error = ""

            val request = Request.Builder()
                .url(doubaoWsUrl(speaker))
                .header("Origin", DOUBAO_ORIGIN)
                .header("User-Agent", DOUBAO_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Cookie", cookie)
                .build()

            val ws = doubaoClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("event", "text").put("text", text).toString())
                    webSocket.send(JSONObject().put("event", "finish").toString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    synchronized(audio) { audio.write(bytes.toByteArray()) }
                }

                override fun onMessage(webSocket: WebSocket, raw: String) {
                    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
                    val code = obj.optInt("code", 0)
                    if (code != 0) {
                        error = "code=$code ${obj.optString("message")}"
                        webSocket.cancel()
                        closed.countDown()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    error = t.message ?: "websocket failure"
                    closed.countDown()
                }
            })

            // 服务端不保证主动关闭连接, 因此以「不再收到数据」作为结束信号
            val deadline = System.currentTimeMillis() + DOUBAO_MAX_WAIT_MS
            var received = 0
            var lastGrowth = System.currentTimeMillis()
            while (System.currentTimeMillis() < deadline) {
                if (closed.await(500, TimeUnit.MILLISECONDS)) break
                val size = synchronized(audio) { audio.size() }
                if (size != received) {
                    received = size
                    lastGrowth = System.currentTimeMillis()
                } else if (size > 0 && System.currentTimeMillis() - lastGrowth > DOUBAO_IDLE_GAP_MS) {
                    break
                }
            }
            ws.cancel()

            val bytes = synchronized(audio) { audio.toByteArray() }
            var path: String? = null
            if (bytes.isEmpty()) {
                if (error.isEmpty()) error = localizedChatString(R.string.tts_err_no_audio_data)
            } else {
                val dir = File(KnownPaths.moduleCache.toFile(), "wcs_tts").apply { mkdirs() }
                val dest = File(dir, "doubao_${System.currentTimeMillis()}.aac")
                dest.writeBytes(bytes)
                path = dest.absolutePath
                lastWavPath = path
            }
            if (error.isNotEmpty()) WeLogger.w(TAG, "doubao tts failed: $error")

            val result = path
            val reason = error
            mh.post { cb(result, reason) }
        }.start()
    }

    /** Breeze 走同步 HTTP 拿 mp3，后面与魔方、豆包共用同一条转 silk 通路。 */
    fun generateVoiceBreeze(
        text: String,
        voiceId: String,
        instructions: String,
        key: String = breezeKey,
        model: String = breezeModel,
        cb: (String?, String) -> Unit,
    ) {
        Thread {
            var path: String? = null
            var error = ""
            try {
                val dir = File(KnownPaths.moduleCache.toFile(), "wcs_tts").apply { mkdirs() }
                val dest = File(dir, "breeze_${System.currentTimeMillis()}.mp3")
                BreezeTtsClient.synthesize(key, voiceId, model, text, instructions, dest)
                path = dest.absolutePath
                lastWavPath = path
            } catch (e: Exception) {
                error = e.message ?: "exception"
                WeLogger.w(TAG, "breeze tts failed: $error")
            }
            val result = path
            val reason = error
            mh.post { cb(result, reason) }
        }.start()
    }

    fun sendVoiceTo(talker: String, wavPath: String, cb: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                val silkPath = wavPath.substringBeforeLast('.') + ".silk"
                if (!AudioUtils.anyToSilk(wavPath, silkPath)) return@runCatching false
                val durationMs = AudioUtils.getDurationMs(silkPath).toInt()
                WeMessageApi.sendVoice(talker, silkPath, durationMs)
            }.getOrDefault(false)
            mh.post { cb(ok) }
        }.start()
    }

    /**
     * 配置页「测试连接」用：只探一次音色列表接口。
     *
     * 不能复用 [fetchVoices] 判断——它失败时回落内置列表，永远非空，探不出 Key 错没错。
     */
    fun probeMofangKey(key: String): Boolean {
        if (key.isBlank()) return false
        return runCatching {
            val resp = httpGet("$API_BASE/api/open/v1/voices", key)
            resp.isNotEmpty() && JSONObject(resp).optJSONArray("data") != null
        }.getOrDefault(false)
    }

    fun fetchVoices(key: String = apiKey): List<TtsVoice> {
        if (key.isBlank()) return DEFAULT_VOICES
        return try {
            val resp = httpGet("$API_BASE/api/open/v1/voices", key)
            val root = JSONObject(resp)
            val data = root.optJSONArray("data") ?: return DEFAULT_VOICES
            val list = DEFAULT_VOICES.toMutableList()
            for (i in 0 until data.length()) {
                val group = data.getJSONObject(i)
                val title = group.optString("title")
                val chars = group.optJSONArray("characters") ?: continue
                for (j in 0 until chars.length()) {
                    val c = chars.getJSONObject(j)
                    val vid = c.optString("voice_id")
                    if (vid.isEmpty()) continue
                    if (list.any { it.voiceId == vid }) continue
                    val name = c.optString("name")
                    val actor = c.optString("actor")
                    val label = if (title.isNotEmpty()) "$title-$name" else name
                    list.add(TtsVoice(vid, if (actor.isNotEmpty()) "$label($actor)" else label))
                }
            }
            list
        } catch (e: Exception) {
            WeLogger.w(TAG, "fetch voices failed: ${e.message}")
            DEFAULT_VOICES
        }
    }

    fun fetchUserVoices(key: String = apiKey): List<TtsVoice> {
        if (key.isBlank()) return emptyList()
        return try {
            val resp = httpGet("$API_BASE/api/open/v1/user-voices", key)
            val root = JSONObject(resp)
            val data = root.optJSONArray("data") ?: return emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val vid = item.optString("voice_id")
                    if (vid.isEmpty()) continue
                    val name = item.optString("name")
                    add(TtsVoice(vid, if (name.isEmpty()) vid else name))
                }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "fetch user voices failed: ${e.message}")
            emptyList()
        }
    }

    private fun httpGet(urlStr: String, key: String = apiKey): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $key")
        return if (c.responseCode == 200) {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            ""
        }
    }

    private fun httpPostJson(urlStr: String, jsonBody: String, key: String = apiKey): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        return if (c.responseCode == 200) {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            ""
        }
    }

    private fun download(urlStr: String, dest: File): Boolean {
        return runCatching {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            c.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            c.disconnect()
            true
        }.getOrElse {
            WeLogger.w(TAG, "download wav failed: ${it.message}")
            false
        }
    }
}
