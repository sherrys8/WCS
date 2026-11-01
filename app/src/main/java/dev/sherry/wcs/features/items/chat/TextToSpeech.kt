package dev.sherry.wcs.features.items.chat

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.composables.icons.materialsymbols.outlined.Compare_arrows
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
import dev.sherry.wcs.ui.content.m3.RadioButtonWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.MicIcon
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.AudioUtils
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

    /** 底部「切换后端」菜单的顺序与文案 */
    private val BACKEND_LABELS = listOf(
        BACKEND_MOFA to "魔方",
        BACKEND_DOUBAO to "豆包",
        BACKEND_BREEZE to "Breeze",
    )

    /** 切换弹窗里每个后端的一行说明：要什么凭据、能做什么 */
    private val BACKEND_TIPS = mapOf(
        BACKEND_MOFA to "API Key · 七种情绪预设",
        BACKEND_DOUBAO to "登录 Cookie · 在线音色库",
        BACKEND_BREEZE to "API Key · 可克隆音色，支持自然语言指令",
    )

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

    var apiKey by prefOption("tts_api_key", "")
    var selectedVoice by prefOption("tts_voice_id", "琅琊榜-梅长苏")
    var selectedEmotion by prefOption("tts_emotion", "平静")

    private var backend by prefOption("tts_backend", BACKEND_MOFA)
    private var doubaoCookie by prefOption("tts_doubao_cookie", "")
    private var doubaoSpeaker by prefOption("tts_doubao_speaker", "zh_female_shuangkuai_emo_v3_wvae_bigtts")
    private var doubaoSpeakerName by prefOption("tts_doubao_speaker_name", "爽快 · 女声")

    private var breezeKey by prefOption("tts_breeze_api_key", "")
    private var breezeModel by prefOption("tts_breeze_model_id", BreezeTtsClient.DEFAULT_MODEL)
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
                        showToast(view.context, "该消息没有可转语音的文本")
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
            var backendMode by remember { mutableIntStateOf(backend) }
            val isDoubao = backendMode == BACKEND_DOUBAO
            val isBreeze = backendMode == BACKEND_BREEZE
            var voiceId by remember {
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
            var instructions by remember { mutableStateOf(breezeInstructions) }
            var voices by remember {
                mutableStateOf(
                    if (DEFAULT_VOICES.any { it.voiceId == selectedVoice }) DEFAULT_VOICES
                    else DEFAULT_VOICES + TtsVoice(selectedVoice, selectedVoice)
                )
            }
            var customVoices by remember { mutableStateOf(emptyList<TtsVoice>()) }
            var generating by remember { mutableStateOf(false) }
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
                title = { Text("文字转语音") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            placeholder = { Text("输入要转成语音的文本") },
                        )

                        Spacer(Modifier.height(8.dp))

                        val mainOptions = voices.map { DropdownOption(it.voiceId, it.label) }
                        val customOptions = customVoices.map { DropdownOption(it.voiceId, it.label) }
                        val customCurrent = customOptions.any { it.value == voiceId }

                        if (isBreeze) {
                            // 整行单列：自建音色收在选择弹窗的「我的」页签里，不再单独占一列
                            BaseWidget(
                                title = "Breeze 音色",
                                description = breezeLabel.ifBlank { "未选择" },
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
                        } else {
                            Row(Modifier.fillMaxWidth()) {
                                Box(Modifier.weight(1f)) {
                                    if (isDoubao) {
                                        BaseWidget(
                                            title = "豆包音色",
                                            description = doubaoSpeakerName,
                                            onClick = {
                                                showDoubaoVoicePicker(context, voiceId, doubaoCookie) { id, name ->
                                                    voiceId = id
                                                    doubaoSpeaker = id
                                                    doubaoSpeakerName = name
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
                                        DropDownMenuWidget(
                                            title = "系统音色",
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
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    DropDownMenuWidget(
                                        title = "自定义音色",
                                        description = if (customCurrent) null else "未选择",
                                        value = voiceId,
                                        options = if (customCurrent) customOptions
                                            else customOptions + DropdownOption(voiceId, ""),
                                        onValueChange = { voiceId = it; selectedVoice = it },
                                        enabled = !isDoubao && customVoices.isNotEmpty(),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        if (isBreeze) {
                            // Breeze 不吃情绪向量，样式全靠一句自然语言指令（文档：1000 字以内）
                            TextFieldDialogWidget(
                                title = "指令",
                                value = instructions,
                                onValueChange = {
                                    instructions = it
                                    breezeInstructions = it
                                },
                                dialogTitle = "填写 Breeze 指令",
                                confirmLabel = "确认",
                                dismissLabel = "取消",
                                valueHint = "未设置。用自然语言描述怎么念，例如：开心地说，带点惊喜的上扬",
                                filter = { it.take(BreezeTtsClient.INSTRUCTION_MAX) },
                                singleLine = false,
                            )
                        } else {
                            DropDownMenuWidget(
                                title = "语气",
                                description = if (isDoubao) emotion else null,
                                value = emotion,
                                options = if (isDoubao) listOf(DropdownOption(emotion, emotion))
                                    else EMOTIONS.map { DropdownOption(it.first, it.first) },
                                onValueChange = { emotion = it; selectedEmotion = it },
                                enabled = !isDoubao,
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        if (generating) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }

                        Row(Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (inputText.trim().isEmpty()) {
                                        showToast(context, "请输入文本")
                                        return@Button
                                    }
                                    if (isDoubao) {
                                        if (doubaoCookie.isBlank()) {
                                            showToast(context, "请先在设置中填写豆包 Cookie")
                                            showSettingsDialog(context)
                                            return@Button
                                        }
                                        generating = true
                                        generateVoiceDoubao(inputText.trim(), voiceId) { path, err ->
                                            generating = false
                                            if (path != null) {
                                                showPreviewDialog(context, talker, path)
                                            } else {
                                                showToast(context, "生成失败：$err")
                                            }
                                        }
                                        return@Button
                                    }
                                    if (isBreeze) {
                                        if (breezeKey.isBlank()) {
                                            showToast(context, "请先在设置中填写 Breeze API Key")
                                            showSettingsDialog(context)
                                            return@Button
                                        }
                                        if (voiceId.isBlank()) {
                                            showToast(context, "请先选择一个 Breeze 音色")
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
                                                showPreviewDialog(context, talker, path)
                                            } else {
                                                showToast(context, "生成失败：$err")
                                            }
                                        }
                                        return@Button
                                    }
                                    if (apiKey.isBlank()) {
                                        showToast(context, "请先在设置中填写 API Key")
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
                                            showPreviewDialog(context, talker, wavPath)
                                        } else {
                                            showToast(context, "生成失败：$err")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("生成")
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    val wav = lastWavPath
                                    if (wav == null || !File(wav).exists()) {
                                        showToast(context, "请先生成语音")
                                        return@Button
                                    }
                                    sendVoiceTo(talker, wav) { ok ->
                                        if (ok) {
                                            showToast(context, "语音已发送")
                                            onDismiss()
                                        } else {
                                            showToast(context, "发送失败")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("发送语音")
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                showBackendPickerDialog(
                                    context,
                                    backendMode,
                                    apiKey.isNotBlank(),
                                    doubaoCookie.isNotBlank(),
                                    breezeKey.isNotBlank(),
                                ) { mode ->
                                    if (mode == backendMode) return@showBackendPickerDialog
                                    // 离开前把当前选中写回原后端，切回来才不串台
                                    when (backendMode) {
                                        BACKEND_DOUBAO -> doubaoSpeaker = voiceId
                                        BACKEND_BREEZE -> breezeVoice = voiceId
                                        else -> selectedVoice = voiceId
                                    }
                                    backendMode = mode
                                    voiceId = when (mode) {
                                        BACKEND_DOUBAO -> doubaoSpeaker
                                        BACKEND_BREEZE -> breezeVoice
                                        else -> selectedVoice
                                    }
                                    backend = mode
                                }
                            }) {
                                Icon(
                                    MaterialSymbols.Outlined.Compare_arrows,
                                    contentDescription = "切换后端",
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(BACKEND_LABELS.first { it.first == backendMode }.second)
                            }
                            Row {
                                TextButton(onClick = { showSettingsDialog(context) { credentialsRevision++ } }) {
                                    Icon(
                                        MaterialSymbols.Outlined.Settings,
                                        contentDescription = "设置",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("打开设置")
                                }
                                TextButton(onClick = { onDismiss() }) {
                                    Text("关闭")
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    /**
     * 后端选择弹窗。三选一、选中即切并关闭（没有「保存」这一步）；
     * 描述里写清各自要什么凭据，没填的当场标出来，省得切过去才发现生成不了。
     */
    private fun showBackendPickerDialog(
        context: android.content.Context,
        current: Int,
        mofangReady: Boolean,
        doubaoReady: Boolean,
        breezeReady: Boolean,
        onPicked: (Int) -> Unit,
    ) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("切换后端") },
                text = {
                    Column {
                        Text(
                            "换后端时把当前选中的音色写回原来那一档，切回来还是原来那个。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        SegmentedColumn(title = "语音合成服务") {
                            BACKEND_LABELS.forEach { (mode, label) ->
                                val ready = when (mode) {
                                    BACKEND_DOUBAO -> doubaoReady
                                    BACKEND_BREEZE -> breezeReady
                                    else -> mofangReady
                                }
                                item {
                                    RadioButtonWidget(
                                        title = label,
                                        description = BACKEND_TIPS.getValue(mode) +
                                            if (ready) "" else " · 凭据还没填",
                                        selected = mode == current,
                                        onSelect = {
                                            onPicked(mode)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    private fun showSettingsDialog(context: android.content.Context, onApiKeySaved: () -> Unit = {}) {
        showComposeDialog(context) {
            var apiKeyState by remember { mutableStateOf(apiKey) }
            var cookieState by remember { mutableStateOf(doubaoCookie) }
            var breezeKeyState by remember { mutableStateOf(breezeKey) }
            var breezeModelState by remember { mutableStateOf(breezeModel) }
            // DropDownMenuWidget 内部是 options.first { it.value == value }，当前值必须在选项里
            var modelOptions by remember {
                mutableStateOf(listOf(DropdownOption(breezeModel, breezeModel)))
            }
            var quotaText by remember { mutableStateOf("未获取") }

            fun loadBreezeAccount() {
                if (breezeKeyState.isBlank()) {
                    quotaText = "未填写 API Key"
                    return
                }
                quotaText = "拉取中…"
                Thread {
                    val quota = runCatching { BreezeTtsClient.quota(breezeKeyState) }.getOrNull()
                    val models = runCatching { BreezeTtsClient.models(breezeKeyState) }.getOrNull()
                    mh.post {
                        quotaText = quota?.let {
                            "余额 ${it.balance} · 套餐 ${it.plan.ifBlank { "未知" }} · 每月 ${it.monthlyCredits}" +
                                " · 自建上限 ${it.savedVoiceLimit} · " +
                                if (it.commercialUseAllowed) "可商用" else "不可商用"
                        } ?: "未获取"
                        if (!models.isNullOrEmpty()) {
                            val list = models.map { DropdownOption(it.id, it.name) }
                            modelOptions = if (list.any { it.value == breezeModelState }) list
                                else listOf(DropdownOption(breezeModelState, breezeModelState)) + list
                        }
                    }
                }.start()
            }

            LaunchedEffect(breezeKeyState) {
                if (breezeKeyState.isNotBlank()) loadBreezeAccount()
            }
            AlertDialogContent(
                title = { Text("文字转语音设置") },
                text = {
                    Column {
                        SegmentedColumn(title = "配音魔方") {
                            item {
                                TextFieldDialogWidget(
                                    title = "API Key",
                                    value = apiKeyState,
                                    onValueChange = {
                                        apiKeyState = it
                                        apiKey = it
                                        onApiKeySaved()
                                    },
                                    dialogTitle = "设置 API Key",
                                    confirmLabel = "确认",
                                    dismissLabel = "取消",
                                    valueHint = "未设置，请前往 peiyinmofang.com 获取",
                                    password = true,
                                )
                            }
                        }
                        Text(
                            "用于调用配音魔方 TTS 接口，在官网「API Key 管理」中创建",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SegmentedColumn(title = "豆包") {
                            item {
                                TextFieldDialogWidget(
                                    title = "Cookie",
                                    value = cookieState,
                                    onValueChange = {
                                        cookieState = it
                                        doubaoCookie = it
                                    },
                                    dialogTitle = "设置豆包 Cookie",
                                    confirmLabel = "确认",
                                    dismissLabel = "取消",
                                    valueHint = "未设置，需包含 sessionid / sid_guard / uid_tt",
                                    password = true,
                                )
                            }
                        }
                        Text(
                            "在已登录的 doubao.com 开发者工具 (Application → Cookies) 中复制三个字段, 形如 sessionid=…; sid_guard=…; uid_tt=…, 有效期约 30 天",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SegmentedColumn(title = "Breeze Blue") {
                            item {
                                TextFieldDialogWidget(
                                    title = "API Key",
                                    value = breezeKeyState,
                                    onValueChange = {
                                        breezeKeyState = it
                                        breezeKey = it
                                    },
                                    dialogTitle = "设置 Breeze API Key",
                                    confirmLabel = "确认",
                                    dismissLabel = "取消",
                                    valueHint = "未设置，在 breezeblue.ai 的 Developer → API Keys 创建",
                                    password = true,
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    title = "模型",
                                    description = null,
                                    value = breezeModelState,
                                    options = modelOptions,
                                    onValueChange = {
                                        breezeModelState = it
                                        breezeModel = it
                                    },
                                )
                            }
                            item {
                                BaseWidget(
                                    title = "余额",
                                    description = quotaText,
                                    onClick = { loadBreezeAccount() },
                                )
                            }
                        }
                        Text(
                            "Breeze 按额度计费，合成与造音色都会扣减；换 Key 后点「余额」重新拉取模型与额度。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { Button(onDismiss) { Text("完成") } }
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
                title = { Text("试听语音") },
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
                                contentDescription = if (playing) "暂停" else "播放",
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
                                showToast(context, "语音已发送")
                                onDismiss()
                            } else {
                                showToast(context, "发送失败")
                            }
                        }
                    }) { Text("发送语音") }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }) { Text("关闭") }
                }
            )
        }
    }

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
                    error = "接口无响应 (HTTP 非 200 或网络异常)"
                } else {
                    val root = JSONObject(resp)
                    val audioUrl = root.optJSONObject("data")?.optString("audio").orEmpty()
                    if (audioUrl.isEmpty()) {
                        val msg = root.optString("msg").ifEmpty { root.optString("message") }
                        error = "code=${root.opt("code")} ${msg.ifEmpty { "未返回音频地址" }}"
                    } else {
                        val dir = File(KnownPaths.moduleCache.toFile(), "wcs_tts").apply { mkdirs() }
                        val dest = File(dir, "tts_${System.currentTimeMillis()}.wav")
                        if (download(audioUrl, dest)) {
                            wavPath = dest.absolutePath
                            lastWavPath = wavPath
                        } else {
                            error = "音频下载失败"
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
                if (error.isEmpty()) error = "未收到音频数据"
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
