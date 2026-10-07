package dev.sherry.wcs.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_down
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
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.ExpressiveOptionDropdown
import dev.sherry.wcs.ui.content.m3.IntNumberPickerWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.ReplyIcon
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        showComposeDialog(context, directlyDismissable = false) {
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

        AlertDialogContent(
            title = { Text(stringResource(R.string.ai_reply_menu)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )

                    DropDownMenuWidget(
                        iconPlaceholder = true,
                        title = stringResource(R.string.ai_reply_style),
                        description = null,
                        value = style,
                        options = STYLE_OPTIONS,
                        onValueChange = { chosen ->
                            style = chosen
                            lastStyle = chosen
                            stylePrompt = promptFor(chosen)
                            editingPrompt = false
                        },
                    )

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
                        Text(
                            text = stylePrompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { editingPrompt = true }) {
                            Text(stringResource(R.string.ai_reply_style_edit))
                        }
                    }

                    if (selected < 0) {
                        candidates.forEachIndexed { index, candidate ->
                            OutlinedButton(
                                onClick = {
                                    selected = index
                                    draft = candidate
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
                            Box {
                                TextButton(onClick = { pickingAnother = true }) {
                                    Text(stringResource(R.string.ai_reply_pick_another))
                                    Icon(
                                        MaterialSymbols.Outlined.Keyboard_arrow_down,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
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
                                        pickingAnother = false
                                    },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
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
                Column(horizontalAlignment = Alignment.End) {
                    // 生成/重新生成常驻原位；发送入口只在选中某条候选后出现
                    Button(onClick = { generate() }, enabled = !busy) {
                        Text(
                            stringResource(
                                if (candidates.isEmpty()) R.string.ai_reply_generate
                                else R.string.ai_reply_regenerate,
                            ),
                        )
                    }
                    if (selected >= 0) {
                        Spacer(Modifier.height(4.dp))
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
                            enabled = !busy,
                        ) { Text(stringResource(R.string.ai_reply_send)) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    private suspend fun requestCandidates(
        talker: String,
        source: String,
        style: String,
        stylePrompt: String,
    ): List<String> = withContext(Dispatchers.IO) {
        check(AiModelConfig.baseUrl.isNotBlank()) { localizedChatString(R.string.ai_reply_err_base_url) }
        check(AiModelConfig.apiKey.isNotBlank()) { localizedChatString(R.string.ai_reply_err_api_key) }
        check(AiModelConfig.modelId.isNotBlank()) { localizedChatString(R.string.ai_reply_err_model_id) }

        val count = candidateCount.coerceIn(1, MAX_CANDIDATES)
        val provider = ModelProviderEntity(
            id = "ai_reply_candidates",
            type = AiModelConfig.providerType(),
            name = technicalId,
            baseUrl = AiModelConfig.resolvedBaseUrl(),
            apiKey = AiModelConfig.apiKey.trim(),
        )
        val model = ModelEntity(
            id = "ai_reply_candidates_model",
            providerId = provider.id,
            modelIdRemote = AiModelConfig.modelId.trim(),
            reasoningEffort = null,
            customJsonOverride = null,
            displayName = AiModelConfig.modelId.trim(),
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
                    content = "对方说：$source\n\n请生成${count}条回复：",
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
