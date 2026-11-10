package dev.sherry.wcs.features.items.chat

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 配置的连接测试与模型列表获取。
 *
 * 这里按 [ApiProtocol] 分四条通路，而不是复用 `agent/` 里的客户端：那层的适配器是流式的，
 * 且同时服务 WeAgent，改它对那边有连带影响。测试只需要「一条最小请求 + 一个状态码」，
 * 在本层单独实现更安全，也能给出带完整 URL 的报错。
 */
internal object AiModelConnection {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Anthropic 必填头，缺了直接 400。 */
    private const val ANTHROPIC_VERSION = "2023-06-01"

    private const val PROBE_TEXT = "仅回复 OK"

    /**
     * 测试连接：按协议发一条最小请求，HTTP 2xx 即视为可用。
     *
     * 成功判据从「响应里包含 OK」放宽成「2xx」：Anthropic 与 Gemini 的响应体结构不同，
     * 再按文本匹配会误判；真正要排除的是 404（地址/路径写错）和 401/403（key 不对）。
     */
    suspend fun testConnection(config: AiApiConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = config.resolvedBaseUrl()
            check(base.isNotEmpty()) { "未配置 API 地址" }
            check(config.apiKey.isNotBlank()) { "未配置 API Key" }
            check(config.modelId.isNotBlank()) { "未配置模型名称" }

            when (config.protocol) {
                ApiProtocol.CHAT_COMPLETIONS -> postJson(
                    endpoint = "$base/chat/completions",
                    headers = bearer(config.apiKey),
                    body = chatBody(config.modelId),
                )

                ApiProtocol.OPENAI_RESPONSES -> postJson(
                    endpoint = "$base/responses",
                    headers = bearer(config.apiKey),
                    body = responsesBody(config.modelId),
                )

                ApiProtocol.ANTHROPIC_MESSAGES -> postJson(
                    endpoint = "$base/messages",
                    headers = mapOf(
                        "x-api-key" to config.apiKey.trim(),
                        "anthropic-version" to ANTHROPIC_VERSION,
                    ),
                    body = anthropicBody(config.modelId),
                )

                ApiProtocol.GEMINI_GENERATE_CONTENT -> postJson(
                    endpoint = "$base/${geminiModelPath(config.modelId)}:generateContent",
                    headers = mapOf("x-goog-api-key" to config.apiKey.trim()),
                    body = geminiBody(),
                )
            }
            "OK"
        }
    }

    /**
     * 获取模型列表。
     *
     * - OpenAI 系（Chat / Responses）：`GET {base}/models`，读 `data[].id`
     * - Gemini：`GET {base}/models`，读 `models[].name`（去掉 `models/` 前缀）
     * - Anthropic：官方没有该端点，直接给明确提示，让用户手填，避免报一个看不懂的 404
     */
    suspend fun fetchModels(config: AiApiConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = config.resolvedBaseUrl()
            check(base.isNotEmpty()) { "未配置 API 地址" }
            check(config.apiKey.isNotBlank()) { "未配置 API Key" }
            check(config.protocol != ApiProtocol.ANTHROPIC_MESSAGES) {
                "Anthropic 没有模型列表接口，请手动填写模型名"
            }

            val endpoint = "$base/models"
            val content = if (config.protocol == ApiProtocol.GEMINI_GENERATE_CONTENT) {
                get(endpoint, mapOf("x-goog-api-key" to config.apiKey.trim()))
            } else {
                get(endpoint, bearer(config.apiKey))
            }

            val json = JSONObject(content)
            val ids = mutableListOf<String>()

            json.optJSONArray("data")?.let { data ->
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(ids::add)
                }
            }
            if (ids.isEmpty()) {
                json.optJSONArray("models")?.let { models ->
                    for (index in 0 until models.length()) {
                        models.optJSONObject(index)?.optString("name")
                            ?.removePrefix("models/")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(ids::add)
                    }
                }
            }

            check(ids.isNotEmpty()) { "模型列表为空" }
            ids
        }
    }

    // ==================== 请求体 ====================

    private fun chatBody(model: String): String = JSONObject()
        .put("model", model.trim())
        .put("stream", false)
        .put("max_tokens", 16)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", PROBE_TEXT),
            ),
        )
        .toString()

    private fun responsesBody(model: String): String = JSONObject()
        .put("model", model.trim())
        .put("input", PROBE_TEXT)
        .put("max_output_tokens", 16)
        .toString()

    /** Anthropic 的 system 是顶层字段，`max_tokens` 必填，这里只发一条 user 消息。 */
    private fun anthropicBody(model: String): String = JSONObject()
        .put("model", model.trim())
        .put("max_tokens", 16)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", PROBE_TEXT),
            ),
        )
        .toString()

    private fun geminiBody(): String = JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", PROBE_TEXT)),
                    ),
            ),
        )
        .toString()

    // ==================== HTTP ====================

    private fun bearer(key: String): Map<String, String> =
        mapOf("Authorization" to "Bearer ${key.trim()}")

    private fun postJson(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
    ): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            doOutput = true
        }
        try {
            conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
            return readResponse(conn, endpoint)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(endpoint: String, headers: Map<String, String>): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            return readResponse(conn, endpoint)
        } finally {
            conn.disconnect()
        }
    }

    /** 非 2xx 一律抛异常，并把实际请求的 URL 带上——404 时这是唯一能看出拼错哪一段的线索。 */
    private fun readResponse(conn: HttpURLConnection, endpoint: String): String {
        val code = conn.responseCode
        val content = if (code in 200..299) {
            conn.inputStream.use { readAll(it) }
        } else {
            (conn.errorStream?.use { readAll(it) }).orEmpty()
        }
        check(code in 200..299) {
            if (content.isBlank()) "HTTP $code @ $endpoint" else "HTTP $code @ $endpoint: ${content.take(160)}"
        }
        return content
    }

    private fun readAll(input: InputStream): String =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
}
