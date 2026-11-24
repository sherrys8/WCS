package dev.sherry.wcs.features.items.entertain

import android.util.Base64
import dev.sherry.wcs.R
import dev.sherry.wcs.features.items.chat.ApiProfile
import dev.sherry.wcs.utils.AudioUtils
import dev.sherry.wcs.utils.MediaFileTypeDetector
import dev.sherry.wcs.utils.WeLogger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class DoubaoMusicException(val textRes: Int, val detail: String = "") : IOException()

data class DoubaoMusicTrack(
    val id: String,
    val owner: String,
    val title: String,
    val lyrics: String,
    val audioUrl: String,
    val backupUrl: String,
    val coverUrl: String,
    val videoId: String,
    val taskId: String,
    val conversationId: String,
    val duration: Double,
    val createdAt: Long,
)

object DoubaoMusicClient {
    private const val BASE = "https://www.doubao.com"
    private const val MAX_RESPONSE = 8 * 1024 * 1024
    private val generationLock = Mutex()
    private val identities = ConcurrentHashMap<String, Pair<String, String>>()
    private val api = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS).callTimeout(360, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build()
    private val cdn = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    fun cookieValue(cookie: String, key: String): String = cookie.split(';')
        .map { it.trim() }.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=').orEmpty()

    suspend fun generate(
        profile: ApiProfile,
        prompt: String,
        lyric: String,
        genre: String,
        mood: String,
        gender: String,
        customLyrics: Boolean,
    ): List<DoubaoMusicTrack> {
        if (!generationLock.tryLock()) throw DoubaoMusicException(R.string.db_music_busy)
        try {
            validateProfile(profile)
            val variables = JSONObject().put("lyric", if (customLyrics) lyric else "")
                // 协议要求 6 个变量恒定全发，界面上没有主题输入，这一位就留空串
                .put("theme", "").put("generation_type", if (customLyrics) "custome_lyric" else "AI_lyric")
                .put("genre", genre).put("mood", mood).put("gender", gender)
            val body = payload(prompt, variables)
            val request = authenticated(profile, "/chat/completion")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val context = currentCoroutineContext()
            return execute(api, request) { response ->
                if (!response.isSuccessful) throw DoubaoMusicException(R.string.db_music_http_error, response.code.toString())
                if (response.body?.contentType()?.subtype?.contains("json") == true) {
                    val value = runCatching { JSONObject(response.body!!.string()) }.getOrNull()
                    throw upstream(value?.opt("code"))
                }
                val source = response.body?.source() ?: throw DoubaoMusicException(R.string.db_music_incomplete)
                val tasks = linkedMapOf<String, JSONObject>()
                var conversation = ""
                var event = ""
                var ended = false
                var received = 0
                val data = StringBuilder()
                fun dispatch() {
                    val raw = data.toString().trimEnd()
                    data.setLength(0)
                    if (raw.isBlank()) return
                    if (raw == "[DONE]") { ended = true; return }
                    val value = runCatching { JSONObject(raw) }.getOrElse {
                        throw DoubaoMusicException(R.string.db_music_incomplete)
                    }
                    val name = event.ifBlank { value.text("event_type") }
                    if (name == "gateway-error") throw upstream(value.opt("code"))
                    val code = value.opt("error_code")?.toString().orEmpty()
                    if ((code.isNotBlank() && code != "0" && code != "null") || value.text("error_msg").isNotBlank()) {
                        throw upstream(value.opt("error_code"))
                    }
                    if (name == "SSE_ACK") {
                        conversation = value.optJSONObject("ack_client_meta")?.text("conversation_id")
                            ?.takeIf { it.isNotBlank() } ?: conversation
                    }
                    if (name == "SSE_REPLY_END") ended = true
                    collectTasks(value, tasks)
                }
                while (true) {
                    context.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    received += line.length
                    if (received > MAX_RESPONSE) throw DoubaoMusicException(R.string.db_music_incomplete)
                    when {
                        line.isEmpty() -> { dispatch(); event = "" }
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") -> data.append(line.substringAfter(':').trimStart()).append('\n')
                    }
                }
                dispatch()
                if (!ended || tasks.isEmpty()) throw DoubaoMusicException(R.string.db_music_incomplete)
                tasks.values.map { task ->
                    if (task.optBoolean("music_gen_failed")) throw DoubaoMusicException(R.string.db_music_generation_failed)
                    val model = objectValue(task.opt("video_model"))
                    val videos = model?.optJSONObject("video_list")
                    val first = videos?.let { entries ->
                        entries.keys().asSequence().mapNotNull { entries.optJSONObject(it) }.firstOrNull()
                    }
                    val main = decodeUrl(first?.text("main_url").orEmpty())
                    val backup = decodeUrl(first?.text("backup_url_1").orEmpty())
                    val vid = model?.text("video_id").orEmpty().ifBlank { task.text("vid") }
                    if (main.isBlank() && backup.isBlank() && vid.isBlank()) {
                        throw DoubaoMusicException(R.string.db_music_incomplete)
                    }
                    DoubaoMusicTrack(
                        id = UUID.randomUUID().toString(), owner = DoubaoMusicStore.owner(profile),
                        title = task.text("title"), lyrics = task.text("lyric").ifBlank { task.text("response_for_model") },
                        audioUrl = main, backupUrl = backup,
                        coverUrl = coverUrlOf(task),
                        videoId = vid, taskId = task.text("unique_task_id"), conversationId = conversation,
                        duration = model?.optDouble("video_duration", 0.0)?.takeIf { it.isFinite() } ?: 0.0,
                        createdAt = System.currentTimeMillis(),
                    )
                }
            }
        } finally {
            generationLock.unlock()
        }
    }

    suspend fun downloadAudio(track: DoubaoMusicTrack, profile: ApiProfile, destination: File) {
        val urls = listOf(track.audioUrl, track.backupUrl).filter { it.isNotBlank() }.distinct()
        for (url in urls) {
            try {
                download(url, destination, 50L * 1024 * 1024)
                validateAudio(destination)
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // 主备两条都失败时，只有这里知道各自为什么失败
                WeLogger.w("DoubaoMusic", "audio attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                destination.delete()
            }
        }
        if (track.videoId.isNotBlank()) {
            validateProfile(profile)
            val request = authenticated(profile, "/alice/media/bigmusic/get_video")
                .post(JSONObject().put("video_id", track.videoId).toString()
                    .toRequestBody("application/json".toMediaType())).build()
            val url = execute(api, request) { response ->
                if (!response.isSuccessful) throw DoubaoMusicException(R.string.db_music_http_error, response.code.toString())
                val raw = response.body?.string().orEmpty()
                val value = runCatching { JSONObject(raw) }.getOrElse {
                    throw DoubaoMusicException(R.string.db_music_download_failed)
                }
                if (value.opt("code")?.toString() != "0") throw upstream(value.opt("code"))
                value.optJSONObject("data")?.text("url").orEmpty()
            }
            download(url, destination, 50L * 1024 * 1024)
            validateAudio(destination)
            return
        }
        throw DoubaoMusicException(R.string.db_music_download_failed)
    }

    suspend fun downloadCover(url: String, destination: File) = download(url, destination, 5L * 1024 * 1024)

    private fun validateAudio(file: File) {
        if (MediaFileTypeDetector.detectAudio(file.toPath()) == null || AudioUtils.getDurationMs(file.absolutePath) <= 0L) {
            throw DoubaoMusicException(R.string.db_music_download_failed)
        }
    }

    private suspend fun download(url: String, destination: File, limit: Long) {
        // 下载失败的原因只有落在日志里才追得动：CDN 403/404、URL 过期、非 https、超限
        // 都只会抛同一句「下载失败」，界面上看不出来。
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: run {
            WeLogger.w("DoubaoMusic", "download rejected: not https, host=${url.substringAfter("//").substringBefore('/')}")
            throw DoubaoMusicException(R.string.db_music_download_failed)
        }
        val request = Request.Builder().url(parsed).build()
        val context = currentCoroutineContext()
        execute(cdn, request) { response ->
            if (!response.isSuccessful) {
                WeLogger.w("DoubaoMusic", "download http ${response.code} host=${parsed.host}")
                throw DoubaoMusicException(R.string.db_music_download_failed)
            }
            val body = response.body ?: throw DoubaoMusicException(R.string.db_music_download_failed)
            if (body.contentLength() > limit) {
                WeLogger.w("DoubaoMusic", "download too large: ${body.contentLength()} > $limit")
                throw DoubaoMusicException(R.string.db_music_download_failed)
            }
            body.byteStream().use { input ->
                try {
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            context.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            if (total > limit) throw DoubaoMusicException(R.string.db_music_download_failed)
                            output.write(buffer, 0, size)
                        }
                        if (total == 0L) throw DoubaoMusicException(R.string.db_music_download_failed)
                    }
                } catch (e: FileNotFoundException) {
                    // 目录被谁删掉了就在这里露馅，别只剩一个类名
                    WeLogger.w("DoubaoMusic", "cannot write ${destination.absolutePath}: ${e.message}")
                    throw e
                }
            }
        }
    }

    private fun validateProfile(profile: ApiProfile) {
        if (cookieValue(profile.cookie, "sessionid").isBlank() || profile.cookie.any { it == '\r' || it == '\n' }) {
            throw DoubaoMusicException(R.string.db_music_need_account)
        }
        if (profile.userAgent.isBlank() || profile.userAgent.any { it != '\t' && it.code !in 32..126 }) {
            throw DoubaoMusicException(R.string.db_music_need_ua)
        }
    }

    private fun authenticated(profile: ApiProfile, path: String): Request.Builder {
        val (webId, deviceId) = identities.getOrPut(DoubaoMusicStore.owner(profile)) {
            (7_400_000_000_000_000_000L + kotlin.random.Random.nextLong(99_999_999_999_999_999L)).toString() to
                (7_400_000_000_000_000_000L + kotlin.random.Random.nextLong(99_999_999_999_999_999L)).toString()
        }
        val params = linkedMapOf(
            "aid" to "497858", "real_aid" to "497858", "device_id" to deviceId,
            "tea_uuid" to webId, "web_id" to webId, "device_platform" to "web",
            "doubao_device_platform" to "web", "doubao_pc_version" to "3.41.0",
            "web_platform" to "browser", "language" to "zh", "region" to "CN", "sys_region" to "CN",
            "tz_name" to java.util.TimeZone.getDefault().id, "pkg_type" to "release_version",
            "version_code" to "20800", "pc_version" to "3.41.0", "samantha_web" to "1",
            "use-olympus-account" to "1", "fp" to cookieValue(profile.cookie, "s_v_web_id"),
            "web_tab_id" to UUID.randomUUID().toString(),
        )
        val url = "$BASE$path".toHttpUrl().newBuilder().apply {
            params.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return Request.Builder().url(url).header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9").header("Agw-Js-Conv", "str")
            .header("Origin", BASE).header("Referer", "$BASE/chat/")
            .header("Cookie", profile.cookie.trim()).header("User-Agent", profile.userAgent.trim()).apply {
                cookieValue(profile.cookie, "passport_csrf_token").takeIf { it.isNotBlank() }
                    ?.let { header("x-tt-passport-csrf-token", it) }
            }
    }

    private fun payload(prompt: String, variables: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val task = JSONObject().put("runtime_type", 0).put("agent_task_param", JSONObject())
            .put("agent_task_param_change", JSONObject().put("runtime_changed", false)
                .put("device_changed", false).put("sandbox_auth_type_changed", false))
            .put("project_id", "").put("need_modify_conversation", false)
        val option = JSONObject()
        listOf("send_message_scene", "collect_id", "select_text_action", "regen_instruction", "shared_app_name", "shared_app_id").forEach { option.put(it, "") }
        listOf("is_audio", "answer_with_suggest", "tts_switch", "click_clear_context", "from_suggest", "is_regen", "is_replace", "is_from_click_option", "is_from_click_softlink", "disable_sse_cache", "is_select_text", "resend_for_regen", "no_replace_for_regen", "is_ai_playground").forEach { option.put(it, false) }
        listOf("need_deep_think", "scene_type", "start_seq", "message_from", "message_storage_type").forEach { option.put(it, 0) }
        option.put("create_time_ms", now).put("unique_key", UUID.randomUUID().toString())
            .put("need_create_conversation", true).put("regen_query_id", JSONArray()).put("edit_query_id", JSONArray())
            .put("sse_recv_event_options", JSONObject().put("support_chunk_delta", true))
            .put("support_lazy_fetch_stream", true).put("is_old_user", true).put("general_task_param", task)
            .put("recovery_option", JSONObject().put("is_recovery", false).put("req_create_time_sec", now / 1000).put("append_sse_event_scene", 0))
            .put("related_deleted_message_ids", JSONObject()).put("connector_info_list", JSONArray())
            .put("model_config", JSONObject().put("model_item_key", "").put("model_extra_params", JSONObject()))
            .put("aggregate_params", JSONObject().apply {
                put("mention_skill_list", "[]").put("mention_plugin_list", "[]").put("mention_ext", "[{}]")
                listOf("conversation_mode", "mode_id", "model_item_key", "agent_mode", "reasoning_effort", "provider_id").forEach { put(it, "") }
            })
        val text = JSONObject().put("block_type", 10000).put("content", JSONObject()
            .put("text_block", JSONObject().put("text", prompt).put("icon_url", "").put("icon_url_dark", "").put("summary", ""))
            .put("pc_event_block", "")).put("block_id", UUID.randomUUID().toString()).put("parent_id", "")
            .put("meta_info", JSONArray()).put("extra_info", JSONObject()).put("append_fields", JSONArray())
        return JSONObject().put("client_meta", JSONObject().put("local_conversation_id", "local_${UUID.randomUUID().toString().replace("-", "").take(16)}")
            .put("conversation_id", "").put("bot_id", "7338286299411103781").put("last_section_id", "").put("last_message_index", 0))
            .put("messages", JSONArray().put(JSONObject().put("local_message_id", UUID.randomUUID().toString())
                .put("content_block", JSONArray().put(text)).put("message_status", 0)))
            .put("option", option).put("chat_ability", JSONObject().put("ability_type", 2005)
                .put("ability_param", JSONObject().put("key", "").put("instruction_type", 1).put("instruction", variables.toString()).toString()))
            .put("user_context", JSONArray()).put("ext", JSONObject().put("input_skill", JSONObject()
                .put("skill_id", "9").put("skill_type", 9).put("variables", variables).toString())
                .put("general_task_param", task.toString()).put("collection_id", "").put("is_finish", "1").put("commerce_credit_config_enable", "0"))
    }

    private fun collectTasks(value: Any?, tasks: MutableMap<String, JSONObject>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("lyrics_to_songs_music_block")?.optJSONObject("lyrics_to_songs_music_content")?.let { content ->
                    content.keys().forEach { key -> content.optJSONObject(key)?.let { merge(tasks.getOrPut(key) { JSONObject() }, it) } }
                }
                value.keys().forEach { collectTasks(value.opt(it), tasks) }
            }
            is JSONArray -> for (i in 0 until value.length()) collectTasks(value.opt(i), tasks)
        }
    }

    private fun merge(target: JSONObject, patch: JSONObject) {
        patch.keys().forEach { key ->
            val next = patch.opt(key)
            if (next is JSONObject && target.opt(key) is JSONObject) merge(target.getJSONObject(key), next)
            else if (next != null && next !== JSONObject.NULL) target.put(key, next)
        }
    }

    private fun objectValue(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    private fun JSONObject.text(key: String): String = opt(key) as? String ?: ""

    /**
     * 封面的域名与容器形态都会变（抓包里域名换过 p26/p3，字段也见过 image_ori 与直接 url），
     * 所以不认域名、只按「优先清晰图，再退回第一个可用 https 链接」的顺序取。
     */
    private fun coverUrlOf(task: JSONObject): String {
        val cover = objectValue(task.opt("cover")) ?: return ""
        val nested = listOf("image_ori", "image_large", "image_default", "image_small")
            .mapNotNull { objectValue(cover.opt(it))?.let { image -> usableUrl(image.text("url")) ?: usableUrl(image.text("url_temp")) } }
        return (nested + listOfNotNull(usableUrl(cover.text("url")), firstUrl(cover))).firstOrNull().orEmpty()
    }

    private fun firstUrl(value: Any?): String? = when (value) {
        is JSONObject -> value.keys().asSequence().mapNotNull { firstUrl(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { firstUrl(value.opt(it)) }.firstOrNull()
        is String -> usableUrl(value)
        else -> null
    }

    /** 直链要么本身就是 https，要么是音频那种 Base64 包装；两种形态都能吃。 */
    private fun usableUrl(value: String?): String? {
        val direct = value?.trim().orEmpty()
        if (direct.isEmpty()) return null
        if (direct.toHttpUrlOrNull()?.isHttps == true) return direct
        return decodeUrl(direct).takeIf { it.isNotBlank() }
    }
    private fun decodeUrl(value: String): String = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
            .takeIf { it.toHttpUrlOrNull()?.isHttps == true }.orEmpty()
    }.getOrDefault("")
    private fun upstream(code: Any?): DoubaoMusicException = when (code?.toString()) {
        "710012001" -> DoubaoMusicException(R.string.db_music_login_expired)
        "710022002", "429" -> DoubaoMusicException(R.string.db_music_rate_limited)
        "710022004" -> DoubaoMusicException(R.string.db_music_verification)
        else -> DoubaoMusicException(R.string.db_music_generation_failed)
    }
    private suspend fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { withContext(Dispatchers.IO) { call.execute().use(read) } }
        finally { cancellation.cancel() }
    }
}
