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
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.copyToClipboard
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.android.showToastSuspend
import dev.sherry.wcs.utils.fs.KnownPaths
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
    private const val ASR_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
    private const val DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"
    private val MODELS = listOf(
        DEFAULT_MODEL to "SenseVoiceSmall",
        "TeleAI/TeleSpeechASR" to "TeleSpeechASR",
    )

    private var entryBubble by prefOption("ai_stt_entry_bubble", false)
    private var apiKey by prefOption("ai_stt_api_key", "")
    private var model by prefOption("ai_stt_model", DEFAULT_MODEL)

    private val micVector = MaterialSymbols.Outlined.Graphic_eq

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

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
                    if (apiKey.isBlank()) {
                        showToastSuspend(context, context.localizedChatString(R.string.ai_stt_toast_need_key))
                        return@launch
                    }
                    val encPath = msgInfo.imagePath
                    if (encPath.isNullOrBlank()) {
                        showToastSuspend(context, context.localizedChatString(R.string.ai_stt_toast_no_voice))
                        return@launch
                    }
                    val result = runCatching { transcribe(encPath) }
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = { text ->
                                if (text.isBlank()) {
                                    showToast(context, context.localizedChatString(R.string.ai_stt_toast_empty))
                                } else {
                                    showResultDialog(context, msgInfo.talker, text)
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
            var key by remember { mutableStateOf(apiKey) }
            var selectedModel by remember { mutableStateOf(model) }

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
                                        apiKey = it
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
                                        model = it
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

    /** silk → pcm → mp3（临时落缓存），POST 到 ASR，返回识别文本；临时文件用完即删。 */
    private fun transcribe(encPath: String): String {
        val silkPath = WeMessageApi.getVoiceFullPath(encPath)
        val stamp = System.currentTimeMillis()
        val pcm = KnownPaths.moduleCache.resolve("ai_stt_$stamp.pcm")
        val mp3 = KnownPaths.moduleCache.resolve("ai_stt_$stamp.mp3")
        try {
            check(AudioUtils.silkToPcm(silkPath, pcm.absolutePathString())) { "silk decode failed" }
            check(AudioUtils.pcmToMp3(pcm.absolutePathString(), mp3.absolutePathString())) { "mp3 encode failed" }
            return postAsr(mp3.readBytes())
        } finally {
            pcm.deleteIfExists()
            mp3.deleteIfExists()
        }
    }

    private fun postAsr(mp3: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "voice.mp3", mp3.toRequestBody("audio/mpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(ASR_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $payload")
            return JSONObject(payload).optString("text")
        }
    }
}
