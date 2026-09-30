package dev.sherry.wcs.i18n

import java.util.Locale

object LocaleResolver {
    fun resolve(
        selection: LanguageSelection,
        systemLocales: List<Locale>,
    ): SupportedLocale = when (selection) {
        LanguageSelection.ENGLISH -> SupportedLocale.ENGLISH
        LanguageSelection.SIMPLIFIED_CHINESE -> SupportedLocale.SIMPLIFIED_CHINESE
        LanguageSelection.MEOW_CHINESE -> SupportedLocale.MEOW_CHINESE
        LanguageSelection.SYSTEM -> systemLocales.firstNotNullOfOrNull(::mapSystemLocale)
            ?: SupportedLocale.ENGLISH
    }

    private fun mapSystemLocale(locale: Locale): SupportedLocale? = when (locale.language) {
        "en" -> SupportedLocale.ENGLISH
        // 只维护简体中文资源：任何中文系统区域（含 Hant 与 TW/HK/MO）都归到简体
        "zh" -> SupportedLocale.SIMPLIFIED_CHINESE
        else -> null
    }
}
