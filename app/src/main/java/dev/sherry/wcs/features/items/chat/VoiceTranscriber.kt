package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.fs.KnownPaths
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
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
 * 凭据与模型来自「娱乐 → API配置 → 语音模型」的配置档，转写结果按 msgSvrId
 * 落到 `cache/WcS/ai_stt_cache.json` 缓存，同一条语音不重复计费。
 */
object VoiceTranscriber {

    private const val TAG = "VoiceTranscriber"

    private const val ASR_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"

    /** ASR 凭据与模型都在「娱乐 → API配置 → 语音模型」里，这里只读。 */
    private val apiKey: String get() = AsrProfiles.active()?.apiKey.orEmpty()

    private val model: String get() = AsrProfiles.active()?.defaultModelId.orEmpty()

    val isConfigured: Boolean get() = AsrProfiles.active()?.let {
        it.hasCredential && it.defaultModelId.isNotBlank()
    } == true

    /** 转写文本缓存：msgSvrId → 文本，放在 moduleCache（文件管理器可见、可手动删）。 */
    private const val CACHE_MAX = 200
    private val cacheFile get() = KnownPaths.moduleCache.resolve("ai_stt_cache.json")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 转写一条语音消息（有 native MsgInfo，路径走新旧接口 + 落地校验）。
     *
     * 返回 null 表示这条语音目前取不到可用文件（未下载完、路径解析失败），调用方应提示
     * 「没有可读取的语音」而不是「识别失败」；网络、解码、服务端错误一律抛异常。
     */
    suspend fun transcribe(msgInfo: MessageInfo): String? = withContext(Dispatchers.IO) {
        cacheGet(msgInfo.serverId)?.let { return@withContext it }
        val encPath = msgInfo.imagePath
        if (encPath.isNullOrBlank()) return@withContext null
        val voicePath = WeMessageApi.resolveVoicePath(msgInfo, encPath) ?: return@withContext null
        val text = runAsr(voicePath)
        cachePut(msgInfo.serverId, text)
        text
    }

    /**
     * 只有加密路径、没有 native MsgInfo 的转写入口，给 AI 回复的上下文语音用。
     * 路径解析只走「按名称」那条（getVoiceFullPath），并在本地校验文件确实存在。
     * 返回 null 表示取不到文件；解码/网络/服务端错误仍抛异常。
     */
    suspend fun transcribeByEncPath(msgSvrId: Long, encPath: String): String? =
        withContext(Dispatchers.IO) {
            cacheGet(msgSvrId)?.let { return@withContext it }
            if (encPath.isBlank()) return@withContext null
            val voicePath = WeMessageApi.getVoiceFullPath(encPath)
                .takeIf { it.isNotBlank() && File(it).let { f -> f.isFile && f.length() > 0L } }
                ?: return@withContext null
            val text = runAsr(voicePath)
            cachePut(msgSvrId, text)
            text
        }

    /** 解码 + 转 mp3 + POST；临时 pcm/mp3/silk 落在缓存目录，用完即删。 */
    private fun runAsr(voicePath: String): String {
        val stamp = System.currentTimeMillis()
        val pcm = KnownPaths.moduleCache.resolve("ai_stt_$stamp.pcm")
        val mp3 = KnownPaths.moduleCache.resolve("ai_stt_$stamp.mp3")
        val silk = KnownPaths.moduleCache.resolve("ai_stt_$stamp.silk")
        try {
            decodeToPcm(voicePath, pcm.absolutePathString(), silk.absolutePathString())
            check(AudioUtils.pcmToMp3(pcm.absolutePathString(), mp3.absolutePathString())) {
                "mp3 encode failed"
            }
            return post(mp3.readBytes())
        } finally {
            pcm.deleteIfExists()
            mp3.deleteIfExists()
            silk.deleteIfExists()
        }
    }

    /**
     * 解成 24 kHz 单声道 PCM。
     *
     * 微信落盘的语音不一定是 silk：8.0.76 上抓到的是 `msg_xxx.amr`，直接丢给 silk 解码器会
     * 报 `Invalid Silk Header`，请求根本发不出去。所以先按 silk 试；不是 silk（或结构坏了）
     * 就用 [AudioUtils.anyToSilk] 归一——它内部先解成任意采样率的 PCM，再重采样到 24 kHz
     * 写回标准 silk，于是后面 `silkToPcm → pcmToMp3` 这条固定 24 kHz 的链路仍然成立。
     */
    private fun decodeToPcm(source: String, pcmPath: String, silkPath: String) {
        if (AudioUtils.silkToPcm(source, pcmPath)) return

        WeLogger.i(TAG, "not a silk voice, converting: $source")
        val ext = source.substringAfterLast('.', "").ifBlank { "未知" }
        check(AudioUtils.anyToSilk(source, silkPath)) { "音频解码失败（$ext 不被系统解码器支持）" }
        check(AudioUtils.silkToPcm(silkPath, pcmPath)) { "silk 二次解码失败" }
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

    private fun cacheGet(msgSvrId: Long): String? = runCatching {
        val file = cacheFile
        if (!file.exists()) return null
        JSONObject(file.readText()).optString(msgSvrId.toString(), "").ifBlank { null }
    }.onFailure { WeLogger.w(TAG, "read stt cache failed", it) }.getOrNull()

    private fun cachePut(msgSvrId: Long, text: String) {
        if (text.isBlank()) return
        runCatching {
            val file = cacheFile
            val map = LinkedHashMap<String, String>()
            if (file.exists()) {
                val existing = JSONObject(file.readText())
                existing.keys().forEach { key -> map[key] = existing.optString(key, "") }
            }
            map[msgSvrId.toString()] = text
            while (map.size > CACHE_MAX) map.remove(map.keys.first())
            file.writeText(JSONObject(map).toString())
        }.onFailure { WeLogger.w(TAG, "write stt cache failed", it) }
    }
}
