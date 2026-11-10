package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.agent.data.entity.ModelProviderType
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption

/**
 * 一次请求所需的连接视图。实现方只做读，值全部来自「API 配置档」，
 * 不再各自持有 prefKey —— 换 key 只改配置档，引用它的功能自动跟着变。
 */
interface AiApiConfig {
    val baseUrl: String
    val apiPath: String
    val apiKey: String
    val modelId: String
    val protocol: ApiProtocol
}

/** 适配器认识的端点末段。用户把完整端点粘进地址时先剥掉，交给适配器按协议重新补。 */
private val KNOWN_ENDPOINT_SUFFIXES = listOf("/chat/completions", "/responses", "/messages")

/**
 * 去掉地址里可能多写的端点末段。
 *
 * 三种协议的适配器写法并不一致：Chat 有 `endsWith` 判断，Responses / Anthropic 是直拼。
 * 那层属于 `agent/`（WeAgent 也在用），这里不动它，改在「配置 → 客户端」之间统一收口，
 * 因此三种协议都允许「填到版本号」和「粘完整端点」两种写法。
 */
private fun stripEndpointSuffix(raw: String): String {
    var base = raw.trim().trimEnd('/')
    for (suffix in KNOWN_ENDPOINT_SUFFIXES) {
        if (base.endsWith(suffix)) {
            base = base.removeSuffix(suffix).trimEnd('/')
        }
    }
    // Gemini 完整端点形如 .../models/gemini-2.5-flash:generateContent
    base = base.removeSuffix(":streamGenerateContent").removeSuffix(":generateContent")
    if (base.contains("/models/")) base = base.substringBefore("/models/")
    return base.trimEnd('/')
}

/** 完整请求前缀 = baseUrl + apiPath；apiPath 可为版本段（/v1）或完整端点路径。 */
fun AiApiConfig.resolvedBaseUrl(): String {
    val rawBase = baseUrl.trim()
    val rawPath = apiPath.trim()
    // 自定义协议：一个字符都不改。用户填的完整端点就是要被请求的地址，
    // 剥末段会拆掉他自己的路径，补末段又会拼出他不存在的路由。
    if (protocol.keepsUrlVerbatim) {
        val base = rawBase.trimEnd('/')
        if (base.isEmpty()) return base
        val path = rawPath.trim('/')
        if (path.isEmpty()) return base
        if (base.endsWith("/$path") || base == path) return base
        return "$base/$path"
    }
    val base = stripEndpointSuffix(rawBase)
    if (base.isEmpty()) return base
    // 路径里也可能粘了完整末段（如 apiPath = /v1/messages），先归一再拼，
    // 否则适配器会在这条路径后面再补一次 /messages
    val strippedPath = stripEndpointSuffix(rawPath)
    val path = strippedPath.trim('/')
    if (path.isEmpty()) return base
    // 防重复路径：baseUrl 已带该前缀（如 baseUrl=https://api.deepseek.com/v1 且 apiPath=/v1）
    // 时避免拼成 /v1/v1 导致 HTTP 404
    if (base.endsWith("/$path") || base == path) return base
    return "$base/$path"
}

fun AiApiConfig.providerType(): ModelProviderType = protocol.type

/**
 * 路径里写死的末段与所选协议不一致时，返回那个末段。
 *
 * 典型场景：协议选 Chat Completions，路径却填了 `/v1/messages`。归一化会把这个末段剥掉再按
 * 协议补成 `/chat/completions`——请求能发出去，但用户以为自己配的是 Anthropic。配置页拿这个
 * 返回值给一句提示，比让人对着 404 猜要省事。一致、没写末段、或选了自定义协议都返回 null。
 *
 * 末段写进地址栏（而不是路径栏）同样会被剥掉，所以两处都查。
 */
fun AiApiConfig.pathSuffixMismatch(): String? {
    if (protocol.keepsUrlVerbatim) return null
    val expected = protocol.endpointSuffix
    val typed = sequenceOf(apiPath, baseUrl)
        .map { it.trim().trimEnd('/') }
        .mapNotNull { path -> KNOWN_ENDPOINT_SUFFIXES.firstOrNull { path.endsWith(it) } }
        .firstOrNull()
        ?: return null
    // Gemini 的末段由模型名拼装，路径里写任何固定末段都会被改写
    return if (expected.isNotEmpty() && typed == expected) null else typed
}

/** Gemini 的模型名可能自带 `models/` 前缀，统一成 `models/{id}`。 */
internal fun geminiModelPath(model: String): String {
    val id = model.trim()
    return if (id.startsWith("models/")) id else "models/$id"
}

/**
 * 实际请求端点（连接测试用）。与适配器保持同一套规则：已带本协议末段就原样用，否则补上，
 * 避免「测试连接」测的是一个永远不会被请求的 URL。
 */
fun AiApiConfig.requestEndpoint(): String {
    val base = resolvedBaseUrl().trimEnd('/')
    if (base.isEmpty()) return base
    // 自定义协议：填什么就请求什么，末段一个都不补
    if (protocol.keepsUrlVerbatim) return base
    if (protocol == ApiProtocol.GEMINI_GENERATE_CONTENT) {
        return "$base/${geminiModelPath(modelId)}:generateContent"
    }
    val suffix = protocol.endpointSuffix
    return if (base.endsWith(suffix)) base else "$base$suffix"
}

fun AiApiConfig.isConfigured(): Boolean =
    baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()

/**
 * 以配置档为后端的连接视图。功能侧只额外存「引用哪一条」和「模型是否覆盖」，
 * 其余字段一律实时读配置档，因此改配置档立即对所有引用方生效。
 */
abstract class ProfileBackedConfig(
    profileKey: String,
    overrideKey: String,
) : AiApiConfig {

    private var profileIdPref by WePrefs.prefOption(profileKey, "")
    private var modelOverridePref by WePrefs.prefOption(overrideKey, "")

    /** 引用的配置档 id；空表示尚未指派（首次启动由迁移逻辑补齐）。 */
    var profileId: String
        get() {
            ApiProfiles.ensureMigrated()
            return profileIdPref
        }
        set(value) {
            profileIdPref = value.trim()
        }

    /** 非空表示本功能单独指定模型，不再跟随配置档的默认模型。 */
    var modelOverride: String
        get() = modelOverridePref
        set(value) {
            modelOverridePref = value.trim()
        }

    val profile: ApiProfile? get() = ApiProfiles.get(profileId)

    val profileName: String get() = profile?.name.orEmpty()

    val profileDefaultModelId: String get() = profile?.defaultModelId.orEmpty()

    val followsProfileModel: Boolean get() = modelOverridePref.isBlank()

    override val baseUrl: String get() = profile?.baseUrl.orEmpty()

    override val apiPath: String get() = profile?.apiPath.orEmpty()

    override val apiKey: String get() = profile?.apiKey.orEmpty()

    override val protocol: ApiProtocol get() = profile?.protocol ?: ApiProtocol.CHAT_COMPLETIONS

    override val modelId: String get() = modelOverridePref.ifBlank { profileDefaultModelId }
}

/** 把一条配置档包成 [AiApiConfig]，供「管理页里当场测试」这类不经过功能引用的场景使用。 */
fun ApiProfile.asConfig(modelOverride: String = ""): AiApiConfig = object : AiApiConfig {
    override val baseUrl: String get() = this@asConfig.baseUrl
    override val apiPath: String get() = this@asConfig.apiPath
    override val apiKey: String get() = this@asConfig.apiKey
    override val protocol: ApiProtocol get() = this@asConfig.protocol
    override val modelId: String get() = modelOverride.ifBlank { this@asConfig.defaultModelId }
}

/**
 * 群聊智能分析引用哪条配置档。偏好键沿用它最初的名字 `ai_reply_*`，改名会让老设备的引用丢失。
 */
internal object AiModelConfig : ProfileBackedConfig(
    profileKey = "ai_reply_profile_id",
    overrideKey = "ai_reply_model_override",
) {
    /**
     * AI 分析提取消息数量上限；0 表示自动（按模型容量估算），否则取最近 N 条。
     * 这是功能私有参数，不进配置档。
     */
    var extractLimit by WePrefs.prefOption("ai_reply_extract_limit", 0)
}

/**
 * AI 回复候选引用哪条配置档。两套上游功能合并前回复与群聊分析共用 `ai_reply_*`，
 * 迁移时若回复侧没有自己的老配置，会继承群聊那条，与改造前的行为一致。
 */
object AiReplyApiConfig : ProfileBackedConfig(
    profileKey = "ai_reply_cand_profile_id",
    overrideKey = "ai_reply_cand_model_override",
) {
    private var inherited by WePrefs.prefOption("ai_reply_cand_inherited", false)

    fun inheritSharedConfigOnce() {
        if (inherited) return
        inherited = true
        if (profileId.isNotBlank()) return
        if (AiModelConfig.profileId.isNotBlank()) profileId = AiModelConfig.profileId
    }
}
