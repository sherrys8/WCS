package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.fs.KnownPaths
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 语音消息 → 文本。AI 语音转文字与 AI 回复共用这一条云端 ASR 通道。
 *
 * 三步都在这里：解析真实文件（新旧接口 + 落地校验）、silk → pcm → mp3、POST 到 SiliconFlow。
 * 凭据与模型沿用 `ai_stt_*` 这组键，设置入口仍在「AI 语音转文字」，避免两个功能各填一次 Key。
 */
object VoiceTranscriber {

    private const val ASR_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"

    const val DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"

    /** ASR Key，配置页由「AI 语音转文字」读写。 */
    var apiKey by prefOption("ai_stt_api_key", "")

    /** ASR 模型，配置页由「AI 语音转文字」读写。 */
    var model by prefOption("ai_stt_model", DEFAULT_MODEL)

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 转写一条语音消息。
     *
     * 返回 null 表示这条语音目前**取不到可用文件**（未下载完、路径解析失败），调用方应提示
     * 「没有可读取的语音」而不是「识别失败」；网络、解码、服务端错误一律抛异常。
     * 临时 pcm/mp3 落在缓存目录，用完即删。
     */
    suspend fun transcribe(msgInfo: MessageInfo): String? = withContext(Dispatchers.IO) {
        val encPath = msgInfo.imagePath
        if (encPath.isNullOrBlank()) return@withContext null
        val voicePath = WeMessageApi.resolveVoicePath(msgInfo, encPath) ?: return@withContext null

        val stamp = System.currentTimeMillis()
        val pcm = KnownPaths.moduleCache.resolve("ai_stt_$stamp.pcm")
        val mp3 = KnownPaths.moduleCache.resolve("ai_stt_$stamp.mp3")
        try {
            check(AudioUtils.silkToPcm(voicePath, pcm.absolutePathString())) { "silk decode failed" }
            check(AudioUtils.pcmToMp3(pcm.absolutePathString(), mp3.absolutePathString())) { "mp3 encode failed" }
            post(mp3.readBytes())
        } finally {
            pcm.deleteIfExists()
            mp3.deleteIfExists()
        }
    }

    private fun post(mp3: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "voice.mp3", mp3.toRequestBody("audio/mpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(ASR_ENDPOINT)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $payload")
            return JSONObject(payload).optString("text")
        }
    }
}
