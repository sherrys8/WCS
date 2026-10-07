package dev.sherry.wcs.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_down
import com.composables.icons.materialsymbols.outlined.Settings
import dev.sherry.wcs.R
import dev.sherry.wcs.agent.data.entity.ModelEntity
import dev.sherry.wcs.agent.data.entity.ModelProviderEntity
import dev.sherry.wcs.agent.model.LlmMessage
import dev.sherry.wcs.agent.model.LlmRole
import dev.sherry.wcs.agent.model.LlmStreamEvent
import dev.sherry.wcs.agent.model.ModelProviderManager
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.ui.WeChatMessageContextMenuApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.m3.BaseItemContainer
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.ExpressiveOptionDropdown
import dev.sherry.wcs.ui.content.m3.IntNumberPickerWidget
import dev.sherry.wcs.ui.content.m3.RadioButtonWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.ReplyIcon
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Long-press a message to generate reply candidates for it.
 *
 * Fuses the two upstream variants: 帮我回's editable style/relationship presets (persisted per
 * style) and the other one's message-anchored input, numbered output contract, and explicit
 * model-configuration errors.
 */
object AiReplyCandidates : ClickableFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "AI回复候选"
    override val nameRes = R.string.feature_ai_reply_candidates_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_reply_candidates_description

    private const val TAG = "AiReplyCandidates"
    private const val MENU_ITEM_ID = 777030
    private const val STYLE_PROMPT_PREFIX = "ai_reply_style_prompt_"
    private const val CONTEXT_WINDOW_MS = 7L * 24 * 3600 * 1000
    private const val MAX_CANDIDATES = 20
    private const val DEFAULT_CANDIDATES = 3
    private const val MAX_CONTEXT_LINES = 50
    private const val MAX_CONTEXT_LINE_CHARS = 300
    private const val SOURCE_MAX_LINES = 4
    private const val MAX_SOURCE_CHARS = 300

    /** 模型列表一屏露出的行数，与 M3 单行 ListItem 的默认最小高度一起决定弹窗限高。 */
    private const val MODEL_ROWS_VISIBLE = 8
    private const val LIST_ITEM_MIN_HEIGHT_DP = 56

    private val STYLES = listOf(
        "智能全能" to "分析当前对话氛围，给出最得体、自然的回复。",
        "高情商" to "说话非常有艺术，能够化解尴尬，照顾对方感受，充满智慧。",
        "轻松闲聊" to "语气随性自然，带一点点幽默感，不要官方和生硬。",
        "严谨正式" to "语气礼貌、专业、客观，适用于职场或正式商务沟通。",
        "幽默/阴阳" to "说话风趣，带点俏皮甚至一点点阴阳怪气，非常有意思。",
        "同理/安慰" to "语气非常温柔，站在对方立场思考，给予对方情感上的支撑。",
        "客气周到" to "非常有礼貌，多使用敬语，保持一定的礼貌距离。",
        "霸道/冷酷" to "言简意赅，语气带有一点压迫感和冷酷的霸总风格。",
        "可爱/萌化" to "说话活泼，多用呀、哒、呢，增加适量颜文字，非常可爱。",
        "委婉拒绝" to "礼貌地拒绝对方的要求，不让对方感到难堪，语气委婉。",
        "亲人" to "对方是亲人。语气温暖、直接、日常化，关心具体事情，亲近而不客套。",
        "朋友" to "对方是朋友。自然平等、接住话题，语气轻松，熟悉程度以聊天为准。",
        "同事" to "对方是同事。友好、清楚、简洁，就事论事，边界明确。",
        "恋人" to "对方是恋人。可亲近、简短、有生活感，称呼和撒娇程度沿用实际聊天。",
        "长辈" to "对方是长辈。尊重、清楚、亲切，用词礼貌，称呼依据聊天。",
        "弟弟妹妹" to "对方是弟弟妹妹。亲近平等、关心具体事情，不居高临下。",
        "暗恋对象" to "对方是我暗恋的人，不代表对方也喜欢我。自然表达关注，轻松而有分寸，不默认暧昧。",
        "暧昧对象" to "以轻松、有来有往的口吻交流，调侃需结合对方实际回应。",
    )

    private val STYLE_OPTIONS = STYLES.map { DropdownOption(it.first, it.first) }

    private var entryBubble by prefOption("ai_reply_entry_bubble", true)
    private var contextLimit by prefOption("ai_reply_context_limit", 10)
    private var candidateCount by prefOption("ai_reply_count", DEFAULT_CANDIDATES)
    private var lastStyle by prefOption("ai_reply_style", STYLES.first().first)

    // 语音回复只复用文字转语音的合成/发送/试听实现，凭据与音色设置各自独立一套键。
    private var ttsEnabled by prefOption("ai_reply_tts_enabled", false)
    private var ttsBackend by prefOption("ai_reply_tts_backend", TextToSpeech.BACKEND_MOFA)
    private var ttsApiKey by prefOption("ai_reply_tts_api_key", "")
    private var ttsDoubaoCookie by prefOption("ai_reply_tts_doubao_cookie", "")
    private var ttsVoiceId by prefOption("ai_reply_tts_voice_id", "")
    private var ttsDoubaoSpeaker by prefOption("ai_reply_tts_doubao_speaker", "")
    private var ttsEmotion by prefOption("ai_reply_tts_emotion", TextToSpeech.EMOTIONS.first().first)

    private fun isDoubaoBackend(): Boolean = ttsBackend == TextToSpeech.BACKEND_DOUBAO

    private fun effectiveVoiceId(): String =
        if (isDoubaoBackend()) ttsDoubaoSpeaker.ifBlank { TextToSpeech.DOUBAO_VOICES.first().id }
        else ttsVoiceId.ifBlank { TextToSpeech.DEFAULT_VOICES.first().voiceId }

    private fun emotionVector(): FloatArray =
        TextToSpeech.EMOTIONS.firstOrNull { it.first == ttsEmotion }?.second
            ?: TextToSpeech.EMOTIONS.first().second

    private fun ttsCredentialsReady(): Boolean =
        if (isDoubaoBackend()) ttsDoubaoCookie.isNotBlank() else ttsApiKey.isNotBlank()

    private fun defaultPromptFor(style: String): String =
        STYLES.firstOrNull { it.first == style }?.second ?: STYLES.first().second

    private fun savedPromptFor(style: String): String =
        WePrefs.getString(STYLE_PROMPT_PREFIX + style).orEmpty()

    private fun promptFor(style: String): String =
        savedPromptFor(style).ifBlank { defaultPromptFor(style) }

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) { SettingsContent() }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        if (!entryBubble) return emptyList()
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                MENU_ITEM_ID,
                localizedChatString(R.string.ai_reply_menu),
                ReplyIcon,
                MaterialSymbols.Outlined.Auto_awesome,
                isSupported = { msgInfo -> msgInfo.type?.isText == true },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
                onClick = { view, _, msgInfo ->
                    val text = msgInfo.humanReadableRepr.trim()
                    if (text.isEmpty()) {
                        showToast(view.context, localizedChatString(R.string.ai_reply_no_text))
                        return@MenuItem
                    }
                    showCandidateDialog(view.context, msgInfo.talker, text)
                },
            ),
        )
    }

    @Composable
    private fun ShowComposeDialogScope.SettingsContent() {
        var entryChecked by remember { mutableStateOf(entryBubble) }
        var contextValue by remember { mutableIntStateOf(contextLimit) }
        var countValue by remember { mutableIntStateOf(candidateCount) }
        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_reply_candidates_name)) },
            text = {
                Column {
                    SegmentedColumn {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.ai_reply_entry_bubble),
                                checked = entryChecked,
                                onCheckedChange = {
                                    entryChecked = it
                                    entryBubble = it
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    SegmentedColumn(title = stringResource(R.string.ai_reply_generation_title)) {
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    iconPlaceholder = true,
                                    title = stringResource(R.string.ai_reply_count),
                                    description = stringResource(R.string.ai_reply_count_description),
                                    value = countValue,
                                    startInt = 1,
                                    endInt = MAX_CANDIDATES,
                                    stepSize = 1,
                                    onValueChange = {
                                        countValue = it
                                        candidateCount = it
                                    },
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    iconPlaceholder = true,
                                    title = stringResource(R.string.ai_reply_context_limit),
                                    value = contextValue,
                                    startInt = 0,
                                    endInt = MAX_CONTEXT_LINES,
                                    stepSize = 1,
                                    onValueChange = {
                                        contextValue = it
                                        contextLimit = it
                                    },
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }

    private fun showCandidateDialog(context: android.content.Context, talker: String, source: String) {
        AiReplyApiConfig.inheritSharedConfigOnce()
        showComposeDialog(context, dismissOnTouchOutside = false) {
            CandidateContent(talker, source)
        }
    }

    @Composable
    private fun ShowComposeDialogScope.CandidateContent(talker: String, source: String) {
        val scope = rememberCoroutineScope()
        var style by remember {
            mutableStateOf(lastStyle.takeIf { saved -> STYLES.any { it.first == saved } } ?: STYLES.first().first)
        }
        var stylePrompt by remember(style) { mutableStateOf(promptFor(style)) }
        var editingPrompt by remember(style) { mutableStateOf(false) }
        var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
        var selected by remember { mutableIntStateOf(-1) }
        var draft by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var sourceExpanded by remember { mutableStateOf(false) }
        var sourceClipped by remember { mutableStateOf(false) }
        var audioPath by remember { mutableStateOf<String?>(null) }
        var voiceBusy by remember { mutableStateOf(false) }
        // 语音开关是裸 MMKV 委托、不可观察；从本弹窗打开配置改了它，要靠回调把新值带进组合
        var ttsOn by remember { mutableStateOf(ttsEnabled) }

        fun generate() {
            if (busy) return
            busy = true
            error = null
            scope.launch {
                val result = runCatching { requestCandidates(talker, source, style, stylePrompt.trim()) }
                busy = false
                result.fold(
                    onSuccess = {
                        candidates = it
                        // 不预选：草稿框只在用户点某条候选后才出现
                        selected = -1
                        draft = ""
                    },
                    onFailure = { failure ->
                        WeLogger.w(TAG, "candidate generation failed: ${failure.message}")
                        error = failure.message
                    },
                )
            }
        }

        // 语音合成走文字转语音的实现，但凭据/音色/语气用的是本功能自己那套偏好
        fun synthesize(text: String) {
            if (voiceBusy || text.isEmpty()) return
            if (!ttsCredentialsReady()) {
                error = localizedChatString(R.string.ai_reply_tts_missing_credentials)
                return
            }
            voiceBusy = true
            error = null
            val callback: (String?, String) -> Unit = { path, failure ->
                voiceBusy = false
                if (path != null) {
                    audioPath = path
                    TextToSpeech.showPreviewDialog(context, talker, path)
                } else {
                    WeLogger.w(TAG, "reply voice generation failed: $failure")
                    error = failure
                }
            }
            if (isDoubaoBackend()) {
                TextToSpeech.generateVoiceDoubao(text, effectiveVoiceId(), cookie = ttsDoubaoCookie, cb = callback)
            } else {
                TextToSpeech.generateVoice(text, effectiveVoiceId(), emotionVector(), key = ttsApiKey, cb = callback)
            }
        }

        // 语气并进标题行：整页少一行控件，候选区拿到更多高度
        var styleMenuExpanded by remember { mutableStateOf(false) }

        AlertDialogContent(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 齿轮靠 Row 末端：中间用 spacer 吃掉剩余宽度。
                    // 给标题 Row 加 weight(fill = false) 不行 —— 摆放按实际宽度推进，齿轮会贴到语气后面。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = stringResource(R.string.ai_reply_menu))
                        Text(
                            text = "·",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 锚点跟着手指：DropdownMenuPopup 锚在父 layout 上，所以在按下位置
                        // 放一个零尺寸锚点 Box，气泡就从点击处展开（同 DropDownMenuWidget 的做法）
                        var pressPosition by remember { mutableStateOf(Offset.Zero) }
                        Box(
                            modifier = Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    pressPosition = awaitFirstDown(requireUnconsumed = false).position
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { styleMenuExpanded = true }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = style,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Keyboard_arrow_down,
                                    contentDescription = stringResource(R.string.ai_reply_style),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Box(
                                modifier = Modifier.offset {
                                    IntOffset(pressPosition.x.roundToInt(), pressPosition.y.roundToInt())
                                },
                            ) {
                                ExpressiveOptionDropdown(
                                    expanded = styleMenuExpanded,
                                    value = style,
                                    options = STYLE_OPTIONS,
                                    onDismissRequest = { styleMenuExpanded = false },
                                    onValueChange = { chosen ->
                                        style = chosen
                                        lastStyle = chosen
                                        stylePrompt = promptFor(chosen)
                                        editingPrompt = false
                                        styleMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showApiSettingsDialog(context) { ttsOn = it } }) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Settings,
                            contentDescription = stringResource(R.string.ai_reply_config_title),
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 被长按的原文：一根主色竖条就够，别再压一整块色底
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (sourceExpanded) Int.MAX_VALUE else SOURCE_MAX_LINES,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { result ->
                                    // maxLines 会把 lineCount 自身也压在 4 以内，只能问「最后一行有没有被省略号截断」
                                    if (!sourceExpanded) {
                                        sourceClipped = result.lineCount >= SOURCE_MAX_LINES &&
                                            result.isLineEllipsized(result.lineCount - 1)
                                    }
                                },
                            )
                            if (sourceClipped || sourceExpanded) {
                                Text(
                                    text = stringResource(
                                        if (sourceExpanded) R.string.ai_reply_source_collapse
                                        else R.string.ai_reply_source_expand,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { sourceExpanded = !sourceExpanded }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    if (editingPrompt) {
                        OutlinedTextField(
                            value = stylePrompt,
                            onValueChange = { stylePrompt = it },
                            label = { Text(stringResource(R.string.ai_reply_style_prompt)) },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    WePrefs.putString(STYLE_PROMPT_PREFIX + style, stylePrompt.trim())
                                    editingPrompt = false
                                    showToast(context, localizedChatString(R.string.ai_reply_style_saved))
                                },
                            ) { Text(stringResource(R.string.ai_reply_style_save)) }
                            TextButton(
                                onClick = {
                                    WePrefs.remove(STYLE_PROMPT_PREFIX + style)
                                    stylePrompt = defaultPromptFor(style)
                                    editingPrompt = false
                                },
                            ) { Text(stringResource(R.string.ai_reply_style_reset)) }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stylePrompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.ai_reply_style_edit),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { editingPrompt = true }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (selected < 0) {
                        candidates.forEachIndexed { index, candidate ->
                            OutlinedButton(
                                onClick = {
                                    selected = index
                                    draft = candidate
                                    audioPath = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = candidate, maxLines = 3)
                            }
                        }
                        if (candidates.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.ai_reply_pick_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // 选中后收成一行摘要 + 「换一条」下拉，草稿紧跟其后，不必滚动即可发送
                        var pickingAnother by remember { mutableStateOf(false) }
                        var anotherPressPosition by remember { mutableStateOf(Offset.Zero) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = candidates.getOrElse(selected) { draft },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier.pointerInput(Unit) {
                                    awaitEachGesture {
                                        anotherPressPosition =
                                            awaitFirstDown(requireUnconsumed = false).position
                                    }
                                },
                            ) {
                                TextButton(onClick = { pickingAnother = true }) {
                                    Text(stringResource(R.string.ai_reply_pick_another))
                                    Icon(
                                        MaterialSymbols.Outlined.Keyboard_arrow_down,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier.offset {
                                        IntOffset(
                                            anotherPressPosition.x.roundToInt(),
                                            anotherPressPosition.y.roundToInt(),
                                        )
                                    },
                                ) {
                                    ExpressiveOptionDropdown(
                                        expanded = pickingAnother,
                                        value = selected,
                                        options = candidates.mapIndexed { index, candidate ->
                                            DropdownOption(index, candidate)
                                        },
                                        onDismissRequest = { pickingAnother = false },
                                        onValueChange = { index ->
                                            selected = index
                                            draft = candidates[index]
                                            audioPath = null
                                            pickingAnother = false
                                        },
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                // 草稿一改，之前那段语音就对不上文字了
                                audioPath = null
                            },
                            label = { Text(stringResource(R.string.ai_reply_draft)) },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (busy) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 3.dp)
                            Text(
                                text = stringResource(
                                    if (candidates.isEmpty()) R.string.ai_reply_generating
                                    else R.string.ai_reply_regenerating,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                // 一行放不下就换行，避免按钮文字被挤成竖排（同短视频解析的 FlowRow 做法）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 生成/重新生成常驻原位；发送入口只在选中某条候选后出现
                    Button(onClick = { generate() }, enabled = !busy && !voiceBusy) {
                        Text(
                            stringResource(
                                if (candidates.isEmpty()) R.string.ai_reply_generate
                                else R.string.ai_reply_regenerate,
                            ),
                        )
                    }
                    if (selected >= 0 && audioPath != null) {
                        Button(
                            onClick = {
                                val path = audioPath ?: return@Button
                                TextToSpeech.sendVoiceTo(talker, path) { ok ->
                                    if (ok) onDismiss()
                                    else error = localizedChatString(R.string.ai_reply_send_failed)
                                }
                            },
                            enabled = !busy && !voiceBusy,
                        ) { Text(stringResource(R.string.ai_reply_send_voice)) }
                    }
                    if (selected >= 0) {
                        Button(
                            onClick = {
                                val text = draft.trim()
                                if (text.isEmpty()) {
                                    error = localizedChatString(R.string.ai_reply_empty_draft)
                                    return@Button
                                }
                                scope.launch {
                                    val sent = withContext(Dispatchers.IO) { WeMessageApi.sendText(talker, text) }
                                    if (sent) onDismiss()
                                    else error = localizedChatString(R.string.ai_reply_send_failed)
                                }
                            },
                            enabled = !busy && !voiceBusy,
                        ) { Text(stringResource(R.string.ai_reply_send)) }
                    }
                    if (selected >= 0 && ttsOn) {
                        Button(
                            onClick = {
                                val text = draft.trim().ifEmpty { candidates.getOrElse(selected) { "" } }
                                if (text.isEmpty()) {
                                    error = localizedChatString(R.string.ai_reply_empty_draft)
                                    return@Button
                                }
                                synthesize(text)
                            },
                            enabled = !busy && !voiceBusy,
                        ) {
                            Text(
                                stringResource(
                                    if (voiceBusy) R.string.ai_reply_tts_generating
                                    else if (audioPath == null) R.string.ai_reply_generate_voice
                                    else R.string.ai_reply_regenerate_voice,
                                ),
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    private fun showApiSettingsDialog(
        context: android.content.Context,
        onTtsChanged: (Boolean) -> Unit = {},
    ) {
        showComposeDialog(context) { ApiSettingsContent(onTtsChanged) }
    }

    private fun showModelPickerDialog(
        context: android.content.Context,
        current: String,
        onPicked: (String) -> Unit,
    ) {
        showComposeDialog(context) { ModelPickerContent(current, onPicked) }
    }

    /**
     * 模型选择弹窗：右下角先只有「获取模型」，取回列表并点选一条后原位变成「确定」。
     * 列表最多露出 [MODEL_ROWS_VISIBLE] 行，其余上下滑动。
     */
    @Composable
    private fun ShowComposeDialogScope.ModelPickerContent(current: String, onPicked: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var fetching by remember { mutableStateOf(false) }
        var fetchError by remember { mutableStateOf<String?>(null) }
        var picked by remember { mutableStateOf("") }

        AlertDialogContent(
            title = {
                Text(
                    text = stringResource(R.string.ui_group_ai_model_picker_title),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (models.isEmpty()) {
                        if (fetching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 3.dp)
                                Text(
                                    text = stringResource(R.string.ui_group_ai_settings_fetching),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            val error = fetchError
                            Text(
                                text = if (error == null) {
                                    stringResource(R.string.ai_reply_model_fetch_hint)
                                } else {
                                    stringResource(R.string.ui_group_ai_settings_fetch_failed_toast, error)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (error == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.ui_group_ai_model_picker_summary, models.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .heightIn(max = (LIST_ITEM_MIN_HEIGHT_DP * MODEL_ROWS_VISIBLE).dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            models.forEach { name ->
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = name,
                                    selected = name == picked || (picked.isEmpty() && name == current),
                                    onClick = { picked = name },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (picked.isEmpty()) {
                    Button(
                        onClick = {
                            fetching = true
                            fetchError = null
                            scope.launch {
                                val result = AiModelConnection.fetchModels(AiReplyApiConfig)
                                fetching = false
                                result.fold(
                                    onSuccess = { models = it },
                                    onFailure = { failure -> fetchError = failure.message },
                                )
                            }
                        },
                        enabled = !fetching,
                    ) {
                        Text(
                            stringResource(
                                if (fetching) R.string.ui_group_ai_settings_fetching
                                else R.string.ui_group_ai_settings_fetch_models,
                            ),
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            onPicked(picked)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                }
            },
        )
    }

    @Composable
    private fun ShowComposeDialogScope.ApiSettingsContent(onTtsChanged: (Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        var baseUrl by remember { mutableStateOf(AiReplyApiConfig.baseUrl) }
        var apiPath by remember { mutableStateOf(AiReplyApiConfig.apiPath) }
        var apiKey by remember { mutableStateOf(AiReplyApiConfig.apiKey) }
        var modelId by remember { mutableStateOf(AiReplyApiConfig.modelId) }
        var testing by remember { mutableStateOf(false) }
        var testOutcome by remember { mutableStateOf<Boolean?>(null) }
        var testError by remember { mutableStateOf<String?>(null) }

        var ttsOn by remember { mutableStateOf(ttsEnabled) }
        var ttsBackendState by remember { mutableStateOf(ttsBackend) }
        var ttsApiKeyState by remember { mutableStateOf(ttsApiKey) }
        var ttsCookieState by remember { mutableStateOf(ttsDoubaoCookie) }
        var ttsVoiceState by remember { mutableStateOf(effectiveVoiceId()) }
        var ttsEmotionState by remember { mutableStateOf(ttsEmotion) }
        var mofangVoices by remember { mutableStateOf(TextToSpeech.DEFAULT_VOICES) }
        var mofangCustomVoices by remember { mutableStateOf<List<TextToSpeech.TtsVoice>>(emptyList()) }
        val doubaoTts = ttsBackendState == TextToSpeech.BACKEND_DOUBAO

        // 魔方音色要按这套独立凭据现拉；失败就保留内置列表，与文字转语音的降级一致
        LaunchedEffect(ttsOn, ttsBackendState, ttsApiKeyState) {
            if (!ttsOn || doubaoTts || ttsApiKeyState.isBlank()) return@LaunchedEffect
            val key = ttsApiKeyState
            val loaded = withContext(Dispatchers.IO) {
                runCatching { TextToSpeech.fetchVoices(key) to TextToSpeech.fetchUserVoices(key) }.getOrNull()
            } ?: return@LaunchedEffect
            mofangVoices = loaded.first
            mofangCustomVoices = loaded.second
        }

        val systemVoiceOptions = (if (doubaoTts) {
            TextToSpeech.DOUBAO_VOICES.map { DropdownOption(it.id, it.label) }
        } else {
            mofangVoices.map { DropdownOption(it.voiceId, it.label) }
        } + DropdownOption(ttsVoiceState, ttsVoiceState)).distinctBy { it.value }
        val customVoiceOptions = mofangCustomVoices.map { DropdownOption(it.voiceId, it.label) }
        val emotionOptions = TextToSpeech.EMOTIONS.map { DropdownOption(it.first, it.first) }

        AlertDialogContent(
            title = {
                Text(
                    text = stringResource(R.string.ai_reply_config_title),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    SegmentedColumn {
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_base_url),
                                value = baseUrl,
                                onValueChange = {
                                    baseUrl = it.trim()
                                    AiReplyApiConfig.baseUrl = baseUrl
                                },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_base_url),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_base_url_hint),
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_path),
                                value = apiPath,
                                onValueChange = {
                                    apiPath = it.trim()
                                    AiReplyApiConfig.apiPath = apiPath
                                },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_path),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_path_hint),
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_api_key),
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it.trim()
                                    AiReplyApiConfig.apiKey = apiKey
                                },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_api_key),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_api_key_hint),
                                password = true,
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.ui_group_ai_settings_model_id),
                                description = modelId.ifBlank {
                                    stringResource(R.string.ai_reply_model_row_hint)
                                },
                                onClick = {
                                    showModelPickerDialog(context, modelId) { picked ->
                                        modelId = picked
                                        AiReplyApiConfig.modelId = picked
                                    }
                                },
                            )
                        }
                    }

                    testOutcome?.let { ok ->
                        Text(
                            text = if (ok) {
                                stringResource(R.string.ui_group_ai_settings_test_ok)
                            } else {
                                stringResource(
                                    R.string.ui_group_ai_settings_test_failed_toast,
                                    testError.orEmpty(),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    SegmentedColumn(title = stringResource(R.string.ai_reply_voice_group)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.ai_reply_tts_switch),
                                description = stringResource(R.string.ai_reply_tts_switch_summary),
                                checked = ttsOn,
                                onCheckedChange = {
                                    ttsOn = it
                                    ttsEnabled = it
                                    onTtsChanged(it)
                                },
                            )
                        }
                        if (ttsOn) {
                            item {
                                Column {
                                    RadioButtonWidget(
                                        title = stringResource(R.string.ai_reply_tts_backend_mofang),
                                        description = stringResource(R.string.ai_reply_tts_backend_mofang_summary),
                                        selected = !doubaoTts,
                                        onSelect = {
                                            ttsBackendState = TextToSpeech.BACKEND_MOFA
                                            ttsBackend = TextToSpeech.BACKEND_MOFA
                                            ttsVoiceState = effectiveVoiceId()
                                        },
                                    )
                                    RadioButtonWidget(
                                        title = stringResource(R.string.ai_reply_tts_backend_doubao),
                                        description = stringResource(R.string.ai_reply_tts_backend_doubao_summary),
                                        selected = doubaoTts,
                                        onSelect = {
                                            ttsBackendState = TextToSpeech.BACKEND_DOUBAO
                                            ttsBackend = TextToSpeech.BACKEND_DOUBAO
                                            ttsVoiceState = effectiveVoiceId()
                                        },
                                    )
                                }
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.ai_reply_tts_api_key),
                                    value = ttsApiKeyState,
                                    onValueChange = {
                                        ttsApiKeyState = it.trim()
                                        ttsApiKey = ttsApiKeyState
                                    },
                                    dialogTitle = stringResource(R.string.ai_reply_tts_api_key),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.ai_reply_tts_api_key_hint),
                                    password = true,
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.ai_reply_tts_cookie),
                                    value = ttsCookieState,
                                    onValueChange = {
                                        ttsCookieState = it.trim()
                                        ttsDoubaoCookie = ttsCookieState
                                    },
                                    dialogTitle = stringResource(R.string.ai_reply_tts_cookie),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.ai_reply_tts_cookie_hint),
                                    password = true,
                                    enabled = doubaoTts,
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.ai_reply_tts_voice),
                                    description = null,
                                    value = ttsVoiceState,
                                    options = systemVoiceOptions,
                                    onValueChange = {
                                        ttsVoiceState = it
                                        if (doubaoTts) ttsDoubaoSpeaker = it else ttsVoiceId = it
                                    },
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.ai_reply_tts_custom_voice),
                                    description = null,
                                    value = ttsVoiceState,
                                    options = (customVoiceOptions + DropdownOption(ttsVoiceState, ttsVoiceState))
                                        .distinctBy { it.value },
                                    onValueChange = {
                                        ttsVoiceState = it
                                        ttsVoiceId = it
                                    },
                                    enabled = !doubaoTts && customVoiceOptions.isNotEmpty(),
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.ai_reply_tts_emotion),
                                    description = null,
                                    value = ttsEmotionState,
                                    options = emotionOptions,
                                    onValueChange = {
                                        ttsEmotionState = it
                                        ttsEmotion = it
                                    },
                                    enabled = !doubaoTts,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        testing = true
                        testOutcome = null
                        testError = null
                        scope.launch {
                            val result = AiModelConnection.testConnection(AiReplyApiConfig)
                            testing = false
                            result.fold(
                                onSuccess = { testOutcome = true },
                                onFailure = { failure ->
                                    testOutcome = false
                                    testError = failure.message
                                },
                            )
                        }
                    },
                    enabled = !testing,
                ) {
                    Text(
                        stringResource(
                            if (testing) R.string.ui_group_ai_settings_testing
                            else R.string.ui_group_ai_settings_test,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }

    private suspend fun requestCandidates(
        talker: String,
        source: String,
        style: String,
        stylePrompt: String,
    ): List<String> = withContext(Dispatchers.IO) {
        check(AiReplyApiConfig.baseUrl.isNotBlank()) { localizedChatString(R.string.ai_reply_err_base_url) }
        check(AiReplyApiConfig.apiKey.isNotBlank()) { localizedChatString(R.string.ai_reply_err_api_key) }
        check(AiReplyApiConfig.modelId.isNotBlank()) { localizedChatString(R.string.ai_reply_err_model_id) }

        val count = candidateCount.coerceIn(1, MAX_CANDIDATES)
        // 界面可以展开看全文，送进模型的只取前 MAX_SOURCE_CHARS 字，并在提示词里说明
        val sourceForModel = if (source.length > MAX_SOURCE_CHARS) {
            "${source.take(MAX_SOURCE_CHARS)}……（这条消息过长，只取前 $MAX_SOURCE_CHARS 字）"
        } else {
            source
        }
        val provider = ModelProviderEntity(
            id = "ai_reply_candidates",
            type = AiReplyApiConfig.providerType(),
            name = technicalId,
            baseUrl = AiReplyApiConfig.resolvedBaseUrl(),
            apiKey = AiReplyApiConfig.apiKey.trim(),
        )
        val model = ModelEntity(
            id = "ai_reply_candidates_model",
            providerId = provider.id,
            modelIdRemote = AiReplyApiConfig.modelId.trim(),
            reasoningEffort = null,
            customJsonOverride = null,
            displayName = AiReplyApiConfig.modelId.trim(),
        )

        val systemPrompt = buildString {
            append("你是微信聊天助手。语气要求：")
            append(stylePrompt.ifBlank { defaultPromptFor(style) })
            append("\n\n针对对方最后一条消息，生成")
            append(count)
            append("条风格不同的候选回复。每条回复单独一行，用「1. 」「2. 」这样的数字序号开头，序号之外不要任何前缀或解释。")
            loadContext(talker).takeIf { it.isNotBlank() }?.let {
                append("\n\n最近聊天记录参考：\n")
                append(it)
            }
        }

        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = listOf(
                LlmMessage(role = LlmRole.SYSTEM, content = systemPrompt),
                LlmMessage(
                    role = LlmRole.USER,
                    content = "对方说：$sourceForModel\n\n请生成${count}条回复：",
                ),
            ),
            tools = emptyList(),
            stream = true,
        )

        var text = ""
        ModelProviderManager.clientFor(provider).stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> text += event.text
                is LlmStreamEvent.Completed -> if (text.isBlank()) text = event.message.content.orEmpty()
                is LlmStreamEvent.Failed -> throw event.error
                else -> Unit
            }
        }
        parseNumbered(text).take(count)
    }

    /** Recent text lines, oldest first, prefixed by who sent them. */
    private fun loadContext(talker: String): String {
        val limit = contextLimit.coerceIn(0, MAX_CONTEXT_LINES)
        if (limit == 0 || talker.isEmpty()) return ""
        return runCatching {
            val now = System.currentTimeMillis()
            WeDatabaseApi.getMessagesInRangeDesc(talker, now - CONTEXT_WINDOW_MS, now, limit)
                .reversed()
                .filter { it.typeCode == 1 }
                .joinToString("\n") { msg ->
                    val who = if (msg.isSend != 0) "我" else "对方"
                    // Group messages store "sender:\ntext"; the payload is the last line.
                    val body = msg.content.lineSequence().last().trim().take(MAX_CONTEXT_LINE_CHARS)
                    "$who：$body"
                }
        }.getOrElse {
            WeLogger.w(TAG, "failed to load reply context", it)
            ""
        }
    }

    private fun parseNumbered(text: String): List<String> {
        val numbered = Regex("""^\s*\d+[.、)]\s*(.+)$""", setOf(RegexOption.MULTILINE))
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (numbered.isNotEmpty()) return numbered
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
