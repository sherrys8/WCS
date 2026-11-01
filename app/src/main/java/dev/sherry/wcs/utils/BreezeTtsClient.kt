package dev.sherry.wcs.utils

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Breeze Blue 开发者接口（api.breeze.blue）的裸 HTTP 客户端。
 *
 * 官方只提供 Python SDK（httpx + asyncio，要 Py>=3.10），跑不进 Xposed 进程，所以按它
 * 声明的端点与字段在这里重写一遍。认证只有一个 xi-api-key 请求头，没有 cookie、没有请求
 * 签名，因此也不需要伪装浏览器。方法全部阻塞，调用方照 TextToSpeech 的既有做法自己起线程。
 */
object BreezeTtsClient {

    private const val TAG = "BreezeTtsClient"
    private const val BASE = "https://api.breeze.blue"
    private const val KEY_HEADER = "xi-api-key"
    private val JSON_TYPE = "application/json".toMediaType()

    /** 微信语音本身是 24 kHz 单声道，直接要 24k mp3，省一次重采样。 */
    const val OUTPUT_FORMAT = "mp3_24000_128"
    const val DEFAULT_MODEL = "breeze-tts-2"

    /** 文档硬约束：一条样本，3 秒以上、5 MB 以内。 */
    const val CLONE_MAX_BYTES = 5L * 1024 * 1024
    const val CLONE_MIN_MS = 3_000L
    val CLONE_SUFFIXES = setOf("mp3", "wav", "m4a", "flac", "aac", "ogg", "opus", "webm", "wma", "aiff", "aif")

    /** 参考语言文档只点名支持中文，其余一律交给服务端自动检测。 */
    val CLONE_LANGUAGES = listOf("" to "自动检测", "zh" to "中文")

    /** 指令上限：文档要求写在 1000 字以内。 */
    const val INSTRUCTION_MAX = 1000

    /** 一页多少个音色。文档上限 100、默认 50，界面按这个数换算页数。 */
    const val VOICE_PAGE_SIZE = 50

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 服务端 4xx/5xx 与传输失败都抛这个。消息里带上接口自己的 code、detail 和 429 的
     * retry-after，调用方原样塞进 toast——额度不足和密钥错误必须能一眼分开。
     */
    class BreezeError(message: String) : Exception(message)

    data class Voice(
        val id: String,
        val name: String,
        val gender: String,
        val age: String,
        val language: String,
        val accent: String,
        val tone: String,
        val previewUrl: String,
        val personal: Boolean,
    ) {
        val meta: String
            get() = listOf(gender, age, language, accent, tone)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" · ")
    }

    data class Model(val id: String, val name: String)

    data class Quota(
        val balance: Double,
        val plan: String,
        val monthlyCredits: Int,
        val savedVoiceLimit: Int,
        val maxConcurrent: Int,
        val commercialUseAllowed: Boolean,
    )

    /** 克隆的预览。[requiresVerification] 为真时，得先去网页端完成本人验证，保存才会通过。 */
    data class ClonePreview(val generatedId: String, val requiresVerification: Boolean)

    // ---- 音色列表 ----

    /**
     * 一页音色。[total] 是整个筛选集的数量，界面靠它算「共几页」。
     * 翻页用数字页码而不是文档更推荐的 `next_page_token`：游标只能一路往前翻，
     * 而这里的翻页条要能点「上一页」退回去。
     */
    data class VoicePage(
        val voices: List<Voice>,
        val hasMore: Boolean,
        val total: Int,
    )

    /**
     * [search] 非空时走服务端搜索（公开目录是语义+文本混合匹配，本地筛不到没拉下来的音色）。
     * 三个分类参数照 docs.breezeblue.ai 的 list-voices 取值：gender 为 male/female/neutral，
     * languageCode 用 ISO 639-1 两字母，primaryCategory 取 narration/social-media/ads-brand/
     * podcast/learning/support-agents/roleplay/gaming/animation/cinematic/wellness/stylized。
     * 筛选是服务端做的，所以总数与页数会跟着缩小。
     */
    fun voicesPage(
        key: String,
        personal: Boolean,
        search: String = "",
        page: Int = 1,
        gender: String? = null,
        languageCode: String? = null,
        primaryCategory: String? = null,
    ): VoicePage {
        val query = LinkedHashMap<String, String>()
        query["voice_type"] = if (personal) "personal" else "default"
        query["page_size"] = VOICE_PAGE_SIZE.toString()
        query["page"] = page.coerceAtLeast(1).toString()
        if (search.isNotBlank()) {
            query["search"] = search
        } else if (!personal) {
            // 文档：sort 只用于非搜索的浏览，且 trend 只限官方目录
            query["sort"] = "trend"
        }
        if (!gender.isNullOrEmpty()) query["gender"] = gender
        if (!languageCode.isNullOrEmpty()) query["language_code"] = languageCode
        if (!primaryCategory.isNullOrEmpty()) query["primary_category_code"] = primaryCategory

        val json = json("GET", "/v1/voices", key, query, null)
        val arr = json.optJSONArray("voices") ?: JSONArray()
        return VoicePage(
            voices = (0 until arr.length()).map { parseVoice(arr.getJSONObject(it), personal) },
            hasMore = json.optBoolean("has_more"),
            total = json.optInt("total"),
        )
    }

    /** 自建音色就那几个，跟着游标一次翻完；官方目录太大，不走这条路。 */
    fun allVoices(key: String, personal: Boolean): List<Voice> {
        val out = ArrayList<Voice>()
        var page = 1
        while (true) {
            val result = voicesPage(key, personal, "", page)
            out += result.voices
            if (!result.hasMore || result.voices.isEmpty()) break
            page++
        }
        return out
    }

    private fun parseVoice(o: JSONObject, personal: Boolean): Voice {
        val tone = o.optJSONArray("tone")
        val toneText = if (tone == null) "" else (0 until tone.length()).joinToString("/") { tone.optString(it) }
        return Voice(
            id = o.optString("voice_id"),
            name = o.optString("name"),
            gender = o.optString("gender"),
            age = o.optString("age"),
            language = o.optString("language_code"),
            accent = o.optString("accent"),
            tone = toneText,
            previewUrl = o.optString("preview_url"),
            personal = personal,
        )
    }

    fun deleteVoice(key: String, voiceId: String) {
        call("DELETE", "/v1/voices/$voiceId", key, null, null)
    }

    // ---- 合成 ----

    /** 同步合成，音频落到 [dest]。[instructions] 是自然语言风格提示，空串就不传。 */
    fun synthesize(
        key: String,
        voiceId: String,
        modelId: String,
        text: String,
        instructions: String,
        dest: File,
    ) {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", modelId)
        if (instructions.isNotBlank()) body.put("instructions", instructions)

        val (bytes, headers) = call(
            "POST", "/v1/text-to-speech/$voiceId", key,
            mapOf("output_format" to OUTPUT_FORMAT), body.toString().toRequestBody(JSON_TYPE),
        )
        if (bytes.isEmpty()) throw BreezeError("接口返回了空音频")
        dest.writeBytes(bytes)
        WeLogger.i(TAG, "breeze tts ok: ${bytes.size} bytes, history=${headers["history-item-id"]}")
    }

    // ---- 克隆音色 ----

    /**
     * 用一条参考样本生成克隆预览，同步返回。字段名与官方 SDK 保持一致：文件放 `files`，
     * 布尔值传小写字符串。试听脚本由服务端按检测到的参考语言自动生成，不由我们提供。
     */
    fun clonePreview(
        key: String,
        sample: File,
        name: String,
        referenceLanguageCode: String,
        removeBackgroundNoise: Boolean,
    ): ClonePreview {
        if (!sample.isFile) throw BreezeError("样本文件读不到：${sample.name}")
        val size = sample.length()
        // 只挡文档写明的上限；「至少 3 秒」是时长而非体积，低码率样本可能很小，交给接口判
        if (size > CLONE_MAX_BYTES) throw BreezeError("样本超过 5 MB（当前 ${size / 1024} KB）")

        val suffix = sample.extension.lowercase()
        if (suffix !in CLONE_SUFFIXES) throw BreezeError("不支持的样本格式 .$suffix")

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", sample.name, sample.readBytes().toRequestBody(contentTypeOf(suffix)))
        if (name.isNotBlank()) form.addFormDataPart("name", name)
        if (referenceLanguageCode.isNotBlank()) {
            form.addFormDataPart("reference_language_code", referenceLanguageCode)
        }
        form.addFormDataPart("remove_background_noise", if (removeBackgroundNoise) "true" else "false")

        val json = json("POST", "/v1/voice-previews/clone", key, null, form.build())
        return ClonePreview(
            generatedId = json.optString("generated_voice_id"),
            requiresVerification = json.optBoolean("requires_verification"),
        )
    }

    /** 下载克隆预览的试听音频（服务端自动生成的那段脚本）。 */
    fun previewAudio(key: String, generatedId: String, dest: File) {
        val (bytes, _) = call(
            "GET", "/v1/voice-previews/$generatedId/stream", key,
            mapOf("output_format" to OUTPUT_FORMAT), null,
        )
        if (bytes.isEmpty()) throw BreezeError("预览音频为空")
        dest.writeBytes(bytes)
    }

    /** 保存预览会占用一个音色槽位；文档要求带 language_code。 */
    fun savePreview(key: String, generatedId: String, name: String, languageCode: String): Voice {
        val body = JSONObject().put("voice_name", name)
        if (languageCode.isNotBlank()) body.put("language_code", languageCode)
        val json = json(
            "POST", "/v1/voice-previews/$generatedId/save", key, null,
            body.toString().toRequestBody(JSON_TYPE),
        )
        return parseVoice(json, personal = true)
    }

    private fun contentTypeOf(suffix: String): okhttp3.MediaType = when (suffix) {
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "webm" -> "audio/webm"
        "wma" -> "audio/x-ms-wma"
        "aiff", "aif" -> "audio/aiff"
        else -> "audio/mpeg"
    }.toMediaType()

    // ---- 账户 ----

    /** /v1/models 直接返回数组，不包壳。 */
    fun models(key: String): List<Model> {
        val text = String(call("GET", "/v1/models", key, null, null).first, Charsets.UTF_8)
        val arr = runCatching { JSONArray(text) }.getOrElse { throw BreezeError("模型列表不是数组：${text.take(120)}") }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val id = o.optString("model_id")
            Model(id, o.optString("name").ifEmpty { id })
        }
    }

    /** 余额与套餐分两个端点，都要拿到才返回。 */
    fun quota(key: String): Quota {
        val balance = json("GET", "/v1/balance", key, null, null)
        val ent = json("GET", "/v1/entitlements", key, null, null)
        return Quota(
            balance = balance.optDouble("balance", 0.0),
            plan = ent.optString("plan_code"),
            monthlyCredits = ent.optInt("monthly_creative_credits"),
            savedVoiceLimit = ent.optInt("saved_voice_limit"),
            maxConcurrent = ent.optInt("max_concurrent_generations"),
            commercialUseAllowed = ent.optBoolean("commercial_use_allowed"),
        )
    }

    // ---- 传输 ----

    private fun json(
        method: String,
        path: String,
        key: String,
        query: Map<String, String>?,
        body: RequestBody?,
    ): JSONObject {
        val text = String(call(method, path, key, query, body).first, Charsets.UTF_8)
        return runCatching { JSONObject(text) }
            .getOrElse { throw BreezeError("响应不是 JSON：${text.take(120)}") }
    }

    private fun call(
        method: String,
        path: String,
        key: String,
        query: Map<String, String>?,
        body: RequestBody?,
    ): Pair<ByteArray, Map<String, String>> {
        if (key.isBlank()) throw BreezeError("未填写 Breeze API Key")

        val builder = Request.Builder()
            .url(BASE + path + queryString(query))
            .header(KEY_HEADER, key)
            .header("Accept", "application/json, audio/mpeg, */*")
        builder.method(method, body)

        val response = runCatching { http.newCall(builder.build()).execute() }
            .getOrElse {
                WeLogger.w(TAG, "breeze request failed: ${it.message}")
                throw BreezeError("网络失败：${it.message}")
            }
        response.use {
            val bytes = it.body?.bytes() ?: ByteArray(0)
            val headers = it.headers.toMultimap().mapValues { entry -> entry.value.joinToString(",") }
            if (!it.isSuccessful) throw BreezeError(describe(it.code, bytes, headers))
            return bytes to headers
        }
    }

    private fun queryString(query: Map<String, String>?): String {
        if (query.isNullOrEmpty()) return ""
        // joinToString 的默认分隔符是 ", "，漏了 separator 会把整串塞进第一个参数值里
        return query.entries.joinToString(separator = "&", prefix = "?") { (name, value) ->
            "$name=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    /** 接口的错误载荷是 {code, detail|error, meta}；429 的等待秒数在 retry-after 头上。 */
    private fun describe(status: Int, bytes: ByteArray, headers: Map<String, String>): String {
        val text = String(bytes, Charsets.UTF_8)
        val root = runCatching { JSONObject(text) }.getOrNull()
        val code = root?.optString("code").orEmpty()
        val detail = root?.let { it.optString("detail").ifEmpty { it.optString("error") } }.orEmpty()
        val retry = headers["retry-after"].orEmpty()
        return buildString {
            append("HTTP ").append(status)
            if (code.isNotBlank()) append(' ').append(code)
            if (detail.isNotBlank()) append(' ').append(detail.take(200))
            if (retry.isNotBlank()) append("（").append(retry).append(" 秒后可重试）")
        }
    }
}
