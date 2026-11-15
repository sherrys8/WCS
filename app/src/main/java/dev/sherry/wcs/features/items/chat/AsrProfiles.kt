package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.preferences.WePrefs

/**
 * 语音模型（ASR）配置档读写层。
 *
 * 只有 SiliconFlow 一家，地址由协议固定。凭据与模型存在配置档里一处，功能侧（AI 回复、
 * 语音转文字）只读「第一条有 Key 的」，不各存一份。老 `ai_stt_*` 键首次访问时迁进来。
 */
object AsrProfiles {

    private const val KEY_MIGRATED = "asr_profile_migrated"

    private const val LEGACY_KEY = "ai_stt_api_key"
    private const val LEGACY_MODEL = "ai_stt_model"

    const val ASR_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"

    fun all(): List<ApiProfile> {
        ensureMigrated()
        return ApiProfiles.ofCapability(CAP_ASR)
    }

    /** 优先使用已填写凭据并选择模型的配置。 */
    fun active(): ApiProfile? {
        val list = all()
        return list.firstOrNull { it.hasCredential && it.defaultModelId.isNotBlank() }
            ?: list.firstOrNull { it.hasCredential }
            ?: list.firstOrNull()
    }

    fun ensureMigrated() {
        if (WePrefs.getBoolOrFalse(KEY_MIGRATED)) return
        WePrefs.putBool(KEY_MIGRATED, true)
        if (ApiProfiles.ofCapability(CAP_ASR).isNotEmpty()) return

        val key = WePrefs.getStringOrDef(LEGACY_KEY, "").trim()
        if (key.isNotBlank()) {
            val profile = ApiProfiles.create(
                name = "SiliconFlow",
                capability = CAP_ASR,
                provider = ASR_PROVIDER_SILICONFLOW,
            )
            ApiProfiles.save(
                profile.copy(
                    apiKey = key,
                    defaultModelId = WePrefs.getStringOrDef(LEGACY_MODEL, "").trim(),
                ),
            )
        }
    }
}