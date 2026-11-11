package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.agent.data.entity.ModelProviderType
import dev.sherry.wcs.preferences.WePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 线上协议。五个走网络的协议；[ModelProviderType.LOCAL_LLAMA] 属于 WeAgent 的本地模型，
 * 由那边的 Room 表管理，这里不参与。
 *
 * 协议不是从地址猜出来的：它同时决定末段路径、鉴权头和请求体格式，只能由用户显式选择。
 * [CUSTOM] 是唯一「不改地址」的特例，见它自己的注释。
 */
enum class ApiProtocol(
    val prefValue: String,
    val label: String,
    val type: ModelProviderType,
) {
    CHAT_COMPLETIONS(
        "chat_completions",
        "OpenAI Chat Completions",
        ModelProviderType.OPENAI_CHAT_COMPLETION,
    ),
    OPENAI_RESPONSES(
        "openai_responses",
        "OpenAI Responses",
        ModelProviderType.OPENAI_RESPONSES,
    ),
    ANTHROPIC_MESSAGES(
        "anthropic_messages",
        "Anthropic Messages",
        ModelProviderType.ANTHROPIC_MESSAGES,
    ),
    GEMINI_GENERATE_CONTENT(
        "gemini_generate_content",
        "Gemini GenerateContent",
        ModelProviderType.GEMINI_GENERATE_CONTENT,
    ),

    /**
     * 自定义：地址**原样使用**，既不剥末段也不补末段。
     *
     * 给第三方反代 / 私有网关用：这类服务的端点路径往往不是标准末段（`/api/v3/chat`、
     * `/openai/deployments/x/chat`），按协议补 `/chat/completions` 只会拼出一个 404。
     * 请求体仍按 OpenAI Chat Completions 发，因为绝大多数反代都兼容这套格式。
     */
    CUSTOM(
        "custom",
        "自定义（原样使用）",
        ModelProviderType.OPENAI_CHAT_COMPLETION,
    ),
    ;

    /** 该协议是否完全不改用户填的地址。 */
    val keepsUrlVerbatim: Boolean get() = this == CUSTOM

    /**
     * 适配器会自己补上的末段。Gemini 的末段带模型名（`/models/{model}:streamGenerateContent`），
     * 无法用固定字符串表示，由连接测试单独拼装。
     */
    val endpointSuffix: String
        get() = when (this) {
            CHAT_COMPLETIONS -> "/chat/completions"
            OPENAI_RESPONSES -> "/responses"
            ANTHROPIC_MESSAGES -> "/messages"
            GEMINI_GENERATE_CONTENT -> ""
            CUSTOM -> ""
        }

    companion object {
        fun fromPref(value: String): ApiProtocol =
            values().firstOrNull { it.prefValue == value } ?: CHAT_COMPLETIONS
    }
}

/** 配置档的两类能力：对话模型与语音合成。同一张表、同一个编辑入口，字段随能力变。 */
const val CAP_LLM = "llm"
const val CAP_TTS = "tts"

/** 语音合成的三家服务商。凭据形态各不相同：Breeze/魔方是 Key，豆包是登录 Cookie。 */
const val TTS_PROVIDER_BREEZE = "breeze"
const val TTS_PROVIDER_DOUBAO = "doubao"
const val TTS_PROVIDER_MOFANG = "mofang"

/**
 * 一条可复用的接口配置。**只描述连接**（地址、路径、凭据、协议）和**出厂模型**，
 * 不持有任何功能私有的参数——候选条数、音色、语气指令之类留在功能自己那边。
 *
 * 语音合成同样走这张表：[capability] 区分能力，[provider] 选服务商。
 * Breeze 的模型放 [defaultModelId]，音色不放这里（音色跟语言、情绪绑，属于功能侧选择）。
 */
data class ApiProfile(
    val id: String,
    val name: String,
    val protocol: ApiProtocol = ApiProtocol.CHAT_COMPLETIONS,
    val baseUrl: String = "",
    val apiPath: String = "",
    val apiKey: String = "",
    val defaultModelId: String = "",
    val capability: String = CAP_LLM,
    val provider: String = "",
    /** 登录态凭据（豆包）。Breeze 的签到 Cookie 不算配置，留在功能侧。 */
    val cookie: String = "",
) {
    val isTts: Boolean get() = capability == CAP_TTS

    /** 这条档是不是已经填了能用的凭据。豆包看 Cookie，其余看 API Key。 */
    val hasCredential: Boolean
        get() = if (provider == TTS_PROVIDER_DOUBAO) cookie.isNotBlank() else apiKey.isNotBlank()
}

/**
 * 配置档仓库：整表以 JSON 存在 MMKV 单键里。
 *
 * 为什么用「一个 JSON 整表」而不是「前缀 key 分散存」：增删改后顺序不丢、一次读取即可渲染列表、
 * 迁移逻辑简单，且 `org.json` 已是现有依赖，不引入新库。
 */
object ApiProfiles {

    /** 配置档整表。值形如 `[{id,name,protocol,baseUrl,apiPath,apiKey,defaultModelId}]`。 */
    private const val KEY_LIST = "ai_api_profiles"

    /** 自增 id 游标。id 永不复用，避免「删了再建」把旧引用指向新配置。 */
    private const val KEY_NEXT_ID = "ai_api_profiles_next_id"

    private const val KEY_MIGRATED = "ai_api_profiles_migrated"

    // 与 ProfileBackedConfig 的构造参数保持一致；迁移时要直接写这两个键，所以在这里再声明一次。
    private const val KEY_GROUP_PROFILE = "ai_reply_profile_id"
    private const val KEY_REPLY_PROFILE = "ai_reply_cand_profile_id"

    /** 文字转语音引用的那一条。放在这里是为了让 [usageCount] 能一起算上它。 */
    const val KEY_TTS_PROFILE = "tts_profile_id"

    private const val LEGACY_GROUP_PREFIX = "ai_reply_"
    private const val LEGACY_REPLY_PREFIX = "ai_reply_cand_"

    fun all(): List<ApiProfile> {
        ensureMigrated()
        return readAll()
    }

    fun get(id: String): ApiProfile? {
        if (id.isBlank()) return null
        return all().firstOrNull { it.id == id }
    }

    /** 按能力过滤。对话模型与语音合成共用一张表，但各自的入口只该看到自己那类。 */
    fun ofCapability(capability: String): List<ApiProfile> =
        all().filter { it.capability == capability }

    fun save(profile: ApiProfile) {
        val list = readAll().toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        writeAll(list)
    }

    fun delete(id: String) {
        writeAll(readAll().filterNot { it.id == id })
    }

    fun create(
        name: String = nextName(),
        capability: String = CAP_LLM,
        provider: String = "",
    ): ApiProfile {
        val profile = ApiProfile(
            id = nextId(),
            name = name,
            capability = capability,
            provider = provider,
        )
        save(profile)
        return profile
    }

    /** 有多少个功能正引用这条配置。删除前用它拦截，避免「删完功能静默失效」。 */
    fun usageCount(id: String): Int =
        listOf(KEY_GROUP_PROFILE, KEY_REPLY_PROFILE, KEY_TTS_PROFILE).count {
            WePrefs.getStringOrDef(it, "") == id
        }

    /** 「API配置1」「API配置2」……跳过已被占用的名字。 */
    fun nextName(): String {
        val existing = readAll().map { it.name }.toSet()
        var index = existing.size + 1
        while (existing.contains("API配置$index")) index++
        return "API配置$index"
    }

    /**
     * 首次启动把两套老 prefKey 迁移成配置档，并自动指派给对应功能。
     *
     * 只在「整表为空」时迁移：用户手工建过配置就不会被老值覆盖。老 key 保留不删，留回滚余地。
     */
    fun ensureMigrated() {
        if (WePrefs.getBoolOrFalse(KEY_MIGRATED)) return
        WePrefs.putBool(KEY_MIGRATED, true)
        if (readAll().isNotEmpty()) return

        var groupId = ""
        if (legacyHasContent(LEGACY_GROUP_PREFIX)) {
            val profile = legacyProfile("群聊总结", LEGACY_GROUP_PREFIX)
            save(profile)
            WePrefs.putString(KEY_GROUP_PROFILE, profile.id)
            groupId = profile.id
        }

        if (legacyHasContent(LEGACY_REPLY_PREFIX)) {
            val profile = legacyProfile("AI回复", LEGACY_REPLY_PREFIX)
            save(profile)
            WePrefs.putString(KEY_REPLY_PROFILE, profile.id)
        } else if (groupId.isNotEmpty()) {
            // 旧逻辑里「AI 回复」首次使用会继承群聊那份配置，迁移后保持同样的结果
            WePrefs.putString(KEY_REPLY_PROFILE, groupId)
        }
    }

    private fun legacyHasContent(prefix: String): Boolean =
        listOf("base_url", "api_path", "api_key", "model_id").any {
            WePrefs.getStringOrDef(prefix + it, "").isNotBlank()
        }

    private fun legacyProfile(name: String, prefix: String): ApiProfile = ApiProfile(
        id = nextId(),
        name = name,
        // 老配置只有 OpenAI 兼容一条通路，协议固定按 Chat Completions 迁移
        protocol = ApiProtocol.CHAT_COMPLETIONS,
        baseUrl = WePrefs.getStringOrDef(prefix + "base_url", "").trim(),
        apiPath = WePrefs.getStringOrDef(prefix + "api_path", "").trim(),
        apiKey = WePrefs.getStringOrDef(prefix + "api_key", "").trim(),
        defaultModelId = WePrefs.getStringOrDef(prefix + "model_id", "").trim(),
    )

    private fun nextId(): String {
        val next = WePrefs.getIntOrDef(KEY_NEXT_ID, 1)
        WePrefs.putInt(KEY_NEXT_ID, next + 1)
        return "p$next"
    }

    private fun readAll(): List<ApiProfile> {
        val raw = WePrefs.getStringOrDef(KEY_LIST, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::decode)
            }.filter { it.id.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    private fun writeAll(list: List<ApiProfile>) {
        val array = JSONArray()
        list.forEach { array.put(encode(it)) }
        WePrefs.putString(KEY_LIST, array.toString())
    }

    private fun decode(json: JSONObject): ApiProfile = ApiProfile(
        id = json.optString("id"),
        name = json.optString("name"),
        protocol = ApiProtocol.fromPref(json.optString("protocol")),
        baseUrl = json.optString("baseUrl"),
        apiPath = json.optString("apiPath"),
        apiKey = json.optString("apiKey"),
        defaultModelId = json.optString("defaultModelId"),
        // 老表里没有这几个字段：缺省就是一条对话模型配置，行为和升级前一致
        capability = json.optString("capability", CAP_LLM),
        provider = json.optString("provider", ""),
        cookie = json.optString("cookie", ""),
    )

    private fun encode(profile: ApiProfile): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put("protocol", profile.protocol.prefValue)
        .put("baseUrl", profile.baseUrl)
        .put("apiPath", profile.apiPath)
        .put("apiKey", profile.apiKey)
        .put("defaultModelId", profile.defaultModelId)
        .put("capability", profile.capability)
        .put("provider", profile.provider)
        .put("cookie", profile.cookie)
}
