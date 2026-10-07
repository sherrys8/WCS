package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.agent.data.entity.ModelProviderType
import dev.sherry.wcs.preferences.WePrefs

/** 一份 OpenAI 兼容的 API 配置，直接持久化到 MMKV，与 WeAgent 的模型数据库解耦。 */
interface AiApiConfig {
    var baseUrl: String
    var apiPath: String
    var apiKey: String
    var modelId: String
}

/** 完整请求前缀 = baseUrl + apiPath；apiPath 可为前缀（/v1）或完整端点路径（/v1/chat/completions） */
fun AiApiConfig.resolvedBaseUrl(): String {
    var base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return base
    // 用户可能在 baseUrl 里误填了完整端点（如 https://api.deepseek.com/v1/chat/completions），
    // 剥离掉尾部的 /chat/completions，统一由客户端拼接
    base = base.removeSuffix("/chat/completions").trimEnd('/')
    val path = apiPath.trim().trim('/')
    if (path.isEmpty()) return base
    // 防重复路径：baseUrl 已带该前缀（如 baseUrl=https://api.deepseek.com/v1 且 apiPath=/v1）
    // 时避免拼成 /v1/v1 导致 HTTP 404
    if (base.endsWith("/$path") || base == path) return base
    return "$base/$path"
}

fun AiApiConfig.providerType(): ModelProviderType = ModelProviderType.OPENAI_CHAT_COMPLETION

/**
 * 实际请求端点。与 OpenAiChatCompletionsClient 用同一条规则：已带 /chat/completions 就原样用，
 * 否则补上，避免「测试连接」测的是一个永远不会被请求的 URL。
 */
fun AiApiConfig.requestEndpoint(): String {
    val base = resolvedBaseUrl().trimEnd('/')
    if (base.isEmpty()) return base
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}

fun AiApiConfig.isConfigured(): Boolean =
    baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()

/** 群聊智能分析的配置。偏好键沿用它最初的名字 `ai_reply_*`，改名会让老设备的配置清空。 */
internal object AiModelConfig : AiApiConfig {
    override var baseUrl by WePrefs.prefOption("ai_reply_base_url", "")
    override var apiPath by WePrefs.prefOption("ai_reply_api_path", "")
    override var apiKey by WePrefs.prefOption("ai_reply_api_key", "")
    override var modelId by WePrefs.prefOption("ai_reply_model_id", "")

    /**
     * AI 分析提取消息数量上限；0 表示自动（按模型容量估算），否则取最近 N 条。
     * 上限会同时作用于默认主题与自定义主题的聊天片段。
     */
    var extractLimit by WePrefs.prefOption("ai_reply_extract_limit", 0)
}

/**
 * AI 回复候选的配置。两套上游回复功能合并前，回复与群聊分析共用 `ai_reply_*`，
 * 现在各自独立，只在首次使用时把群聊那份继承一次。
 */
object AiReplyApiConfig : AiApiConfig {
    override var baseUrl by WePrefs.prefOption("ai_reply_cand_base_url", "")
    override var apiPath by WePrefs.prefOption("ai_reply_cand_api_path", "")
    override var apiKey by WePrefs.prefOption("ai_reply_cand_api_key", "")
    override var modelId by WePrefs.prefOption("ai_reply_cand_model_id", "")

    private var inherited by WePrefs.prefOption("ai_reply_cand_inherited", false)

    fun inheritSharedConfigOnce() {
        if (inherited) return
        inherited = true
        if (baseUrl.isNotBlank() || apiKey.isNotBlank() || modelId.isNotBlank()) return
        baseUrl = AiModelConfig.baseUrl
        apiPath = AiModelConfig.apiPath
        apiKey = AiModelConfig.apiKey
        modelId = AiModelConfig.modelId
    }
}
