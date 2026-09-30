package dev.sherry.wcs.i18n

import androidx.annotation.StringRes
import dev.sherry.wcs.R

enum class SupportedLocale(
    val logicalTag: String,
    val androidTag: String,
    @StringRes val labelRes: Int,
) {
    ENGLISH("en", "en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", "zh-CN", R.string.language_simplified_chinese),
    MEOW_CHINESE("zh-Hans-x-meow", "zh-CN", R.string.language_meow_chinese),
}
