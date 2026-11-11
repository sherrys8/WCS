package dev.sherry.wcs.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Graphic_eq
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
import dev.sherry.wcs.features.api.core.models.MessageType
import dev.sherry.wcs.features.api.ui.WeChatMessageContextMenuApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.MicIcon
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.copyToClipboard
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 语音转文字：长按语音消息，走云端 ASR（SiliconFlow）转成文字，可复制或以文本发送。
 * 与「自动语音转文字」（宿主本地转写、只触发不读回）是两条独立通路，互不依赖。
 */
object AiSpeechToText : ClickableFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "AI语音转文字"
    override val nameRes = R.string.feature_ai_speech_to_text_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_speech_to_text_description

    private const val TAG = "AiSpeechToText"
    private val MODELS = listOf(
        VoiceTranscriber.DEFAULT_MODEL to "SenseVoiceSmall",
        "TeleAI/TeleSpeechASR" to "TeleSpeechASR",
    )

    private var entryBubble by prefOption("ai_stt_entry_bubble", false)

    private val micVector = MaterialSymbols.Outlined.Graphic_eq

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    private fun isSupported(msgInfo: MessageInfo): Boolean = msgInfo.type == MessageType.VOICE

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        if (!entryBubble) return emptyList()
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777026,
                localizedChatString(R.string.ai_stt_menu),
                MicIcon,
                micVector,
                isSupported = ::isSupported,
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val context = view.context
                    if (!VoiceTranscriber.isConfigured) {
                        showToastSuspend(context, context.localizedChatString(R.string.ai_stt_toast_need_key))
                        return@launch
                    }
                    val result = runCatching { VoiceTranscriber.transcribe(msgInfo) }
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = { text ->
                                when {
                                    // null 是「这条语音还没有可读文件」，和识别失败要分开提示
                                    text == null -> showToast(
                                        context,
                                        context.localizedChatString(R.string.ai_stt_toast_no_voice),
                                    )

                                    text.isBlank() -> showToast(
                                        context,
                                        context.localizedChatString(R.string.ai_stt_toast_empty),
                                    )

                                    else -> showResultDialog(context, msgInfo.talker, text)
                                }
                            },
                            onFailure = {
                                WeLogger.e(TAG, "transcribe failed", it)
                                showToast(
                                    context,
                                    context.localizedChatString(R.string.ai_stt_toast_failed, it.message.orEmpty()),
                                )
                            },
                        )
                    }
                }
            },
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var bubbleEntry by remember { mutableStateOf(entryBubble) }
            var key by remember { mutableStateOf(VoiceTranscriber.apiKey) }
            var selectedModel by remember { mutableStateOf(VoiceTranscriber.model) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_ai_speech_to_text_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(title = stringResource(R.string.ai_stt_entry_group_title)) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.ai_stt_entry_bubble),
                                    description = stringResource(R.string.ai_stt_entry_bubble_description),
                                    checked = bubbleEntry,
                                    onCheckedChange = {
                                        bubbleEntry = it
                                        entryBubble = it
                                    },
                                )
                            }
                        }
                        SegmentedColumn(title = stringResource(R.string.ai_stt_cfg_group)) {
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.ai_stt_api_key),
                                    value = key,
                                    onValueChange = {
                                        key = it
                                        VoiceTranscriber.apiKey = it
                                    },
                                    dialogTitle = stringResource(R.string.ai_stt_api_key),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    password = true,
                                    valueHint = stringResource(R.string.ai_stt_api_key_hint),
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.ai_stt_model),
                                    description = null,
                                    value = selectedModel,
                                    options = MODELS.map { DropdownOption(it.first, it.second) },
                                    onValueChange = {
                                        selectedModel = it
                                        VoiceTranscriber.model = it
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun showResultDialog(context: android.content.Context, talker: String, text: String) {
        showComposeDialog(context, dismissOnTouchOutside = false) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.ai_stt_result_title)) },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        Text(text)
                        TextButton(onClick = {
                            copyToClipboard(context, text)
                            showToast(context, context.localizedChatString(R.string.ai_stt_toast_copied))
                        }) { Text(stringResource(R.string.ai_stt_btn_copy)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (WeMessageApi.sendText(talker, text)) {
                            showToast(context, context.localizedChatString(R.string.ai_stt_toast_sent))
                            onDismiss()
                        } else {
                            showToast(context, context.localizedChatString(R.string.ai_stt_toast_send_failed))
                        }
                    }) { Text(stringResource(R.string.ai_stt_btn_send_text)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
