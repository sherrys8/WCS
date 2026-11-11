package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.R
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.utils.BreezeTtsClient

/**
 * 语音合成配置档的读写层。
 *
 * 凭据与模型只存在配置档里一处，功能侧只记「用哪一家」。所以：
 * 文字转语音记一条 `tts_profile_id`，AI 回复只记后端（再按后端找回同服务商的那条档）。
 * 改 Key、改模型都去「娱乐 → API配置」，不会出现同一个 Key 要填两遍。
 */
object TtsProfiles {

    private const val KEY_MIGRATED = "tts_profile_migrated"

    private const val LEGACY_BACKEND = "tts_backend"
    private const val LEGACY_BREEZE_KEY = "tts_breeze_api_key"
    private const val LEGACY_BREEZE_MODEL = "tts_breeze_model_id"
    private const val LEGACY_DOUBAO_COOKIE = "tts_doubao_cookie"
    private const val LEGACY_MOFANG_KEY = "tts_api_key"

    // AI 回复当年自己存过一套同样的凭据；迁移时优先用文字转语音那份，没有再拿它的兜底
    private const val LEGACY_REPLY_BREEZE_KEY = "ai_reply_tts_breeze_key"
    private const val LEGACY_REPLY_BREEZE_MODEL = "ai_reply_tts_breeze_model"
    private const val LEGACY_REPLY_DOUBAO_COOKIE = "ai_reply_tts_doubao_cookie"
    private const val LEGACY_REPLY_MOFANG_KEY = "ai_reply_tts_api_key"

    /** 当前文字转语音用哪一条。存 id 而不是存后端：同一家可以有两条档（不同 Key/模型）。 */
    var activeId: String
        get() = WePrefs.getStringOrDef(ApiProfiles.KEY_TTS_PROFILE, "")
        set(value) = WePrefs.putString(ApiProfiles.KEY_TTS_PROFILE, value)

    fun all(): List<ApiProfile> {
        ensureMigrated()
        return ApiProfiles.ofCapability(CAP_TTS)
    }

    fun byId(id: String): ApiProfile? {
        if (id.isBlank()) return null
        return all().firstOrNull { it.id == id }
    }

    /**
     * 按服务商找回那条配置。[backend] 来自功能侧的「选择后端」。
     *
     * 同一服务商有多条时取第一条填了凭据的：功能侧只选了「哪一家」，没选「哪一条」，
     * 所以不能因为第二条是空的就把可用的一条盖掉。
     */
    fun forProvider(provider: String): ApiProfile? {
        val list = all().filter { it.provider == provider }
        return list.firstOrNull { it.hasCredential } ?: list.firstOrNull()
    }

    fun forBackend(backend: Int): ApiProfile? = forProvider(providerOf(backend))

    /** 文字转语音当前那条。没选过就退回「老后端对应的那家」，保证升级后还能直接合成。 */
    fun active(): ApiProfile? {
        byId(activeId)?.let { return it }
        val legacyBackend = WePrefs.getIntOrDef(LEGACY_BACKEND, TextToSpeech.BACKEND_BREEZE)
        return forBackend(legacyBackend)
    }

    /** 这条档的模型：Breeze 用它选引擎，空着就回到客户端默认值。 */
    fun modelOf(profile: ApiProfile?): String =
        profile?.defaultModelId?.takeIf { it.isNotBlank() } ?: BreezeTtsClient.DEFAULT_MODEL

    fun providerOf(backend: Int): String = when (backend) {
        TextToSpeech.BACKEND_DOUBAO -> TTS_PROVIDER_DOUBAO
        TextToSpeech.BACKEND_MOFANG -> TTS_PROVIDER_MOFANG
        else -> TTS_PROVIDER_BREEZE
    }

    fun backendOf(profile: ApiProfile?): Int = when (profile?.provider) {
        TTS_PROVIDER_DOUBAO -> TextToSpeech.BACKEND_DOUBAO
        TTS_PROVIDER_MOFANG -> TextToSpeech.BACKEND_MOFANG
        else -> TextToSpeech.BACKEND_BREEZE
    }

    /** 只读展示用：这三家的地址是协议固定的，不让填也不让改。 */
    fun endpointOf(provider: String): String = when (provider) {
        TTS_PROVIDER_BREEZE -> "https://api.breeze.blue/v1/text-to-speech"
        TTS_PROVIDER_DOUBAO -> "wss://ws-samantha.doubao.com/samantha/audio/tts"
        else -> "https://peiyinmofang.com/api/open/v1/tts/simple-generate"
    }

    fun providerLabelRes(provider: String): Int = when (provider) {
        TTS_PROVIDER_DOUBAO -> R.string.tts_backend_doubao
        TTS_PROVIDER_MOFANG -> R.string.tts_backend_mofa
        else -> R.string.tts_backend_breeze
    }

    /**
     * 首次启动把老的三套凭据搬成配置档，并让文字转语音指过去。
     *
     * 只在「一条语音合成档都没有」时迁移：用户自己建过就不动。
     * 老 key 不删，留回滚余地；迁移完成后功能侧不再读它们。
     */
    fun ensureMigrated() {
        if (WePrefs.getBoolOrFalse(KEY_MIGRATED)) return
        WePrefs.putBool(KEY_MIGRATED, true)
        if (ApiProfiles.ofCapability(CAP_TTS).isNotEmpty()) return

        val created = mutableListOf<Pair<Int, ApiProfile>>()

        fun createFor(backend: Int, provider: String, name: String): ApiProfile =
            ApiProfiles.create(name = name, capability = CAP_TTS, provider = provider).also {
                created += backend to it
            }

        fun legacy(primary: String, secondary: String): String =
            WePrefs.getStringOrDef(primary, "").trim()
                .ifBlank { WePrefs.getStringOrDef(secondary, "").trim() }

        val breezeKey = legacy(LEGACY_BREEZE_KEY, LEGACY_REPLY_BREEZE_KEY)
        if (breezeKey.isNotBlank()) {
            val profile = createFor(TextToSpeech.BACKEND_BREEZE, TTS_PROVIDER_BREEZE, "Breeze")
            ApiProfiles.save(
                profile.copy(
                    apiKey = breezeKey,
                    defaultModelId = legacy(LEGACY_BREEZE_MODEL, LEGACY_REPLY_BREEZE_MODEL),
                ),
            )
        }

        val doubaoCookie = legacy(LEGACY_DOUBAO_COOKIE, LEGACY_REPLY_DOUBAO_COOKIE)
        if (doubaoCookie.isNotBlank()) {
            val profile = createFor(TextToSpeech.BACKEND_DOUBAO, TTS_PROVIDER_DOUBAO, "豆包")
            ApiProfiles.save(profile.copy(cookie = doubaoCookie))
        }

        val mofangKey = legacy(LEGACY_MOFANG_KEY, LEGACY_REPLY_MOFANG_KEY)
        if (mofangKey.isNotBlank()) {
            val profile = createFor(TextToSpeech.BACKEND_MOFANG, TTS_PROVIDER_MOFANG, "魔方")
            ApiProfiles.save(profile.copy(apiKey = mofangKey))
        }

        val legacyBackend = WePrefs.getIntOrDef(LEGACY_BACKEND, TextToSpeech.BACKEND_BREEZE)
        val picked = created.firstOrNull { it.first == legacyBackend } ?: created.firstOrNull()
        if (picked != null) activeId = picked.second.id
    }
}
