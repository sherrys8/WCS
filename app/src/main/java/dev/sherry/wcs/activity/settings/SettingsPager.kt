package dev.sherry.wcs.activity.settings


import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Auto_delete
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Colorize
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Extension
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Language
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Shield
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Swipe
import com.composables.icons.materialsymbols.outlined.Sync
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Wallpaper
import dev.sherry.wcs.BuildConfig
import dev.sherry.wcs.R
import dev.sherry.wcs.constants.Preferences
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.items.debug.ResetDexCache
import dev.sherry.wcs.features.items.system.SafeMode
import dev.sherry.wcs.i18n.LanguageSelection
import dev.sherry.wcs.i18n.LocalWcSLocalizedContext
import dev.sherry.wcs.i18n.SupportedLocale
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.m3.BaseItemContainer
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.theme.AppColorSpec
import dev.sherry.wcs.ui.utils.theme.AppPaletteStyle
import dev.sherry.wcs.ui.utils.theme.AppThemeMode
import dev.sherry.wcs.ui.utils.theme.PageTransitionAnimation
import dev.sherry.wcs.ui.utils.theme.SettingsUiEngine
import dev.sherry.wcs.ui.utils.theme.ThemeSettings
import dev.sherry.wcs.utils.android.showToastSuspend
import dev.sherry.wcs.utils.formatEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Color as AndroidColor

// ---------------------------------------------------------------------------
//  Page 2 — Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsPager() {
    val context = LocalComponentActivity.current
    val platformContext = LocalContext.current
    val currentLocalizedContext = rememberUpdatedState(LocalWcSLocalizedContext.current)

    var showClearConfirm by remember { mutableStateOf(false) }

    ClearConfigDialog(show = showClearConfirm, onDismiss = { showClearConfirm = false })

    M3ListScaffold(title = stringResource(R.string.settings_title)) {
        // Account info card.
        item {
            Spacer(Modifier.height(12.dp))
            ProfileCard()
        }

        // 界面
        item {
            ThemeSection()
        }

        // 调试
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_debug)) {
                item { SecuritySwitch(context) }
                item {
                    PrefSwitch(
                        key = Preferences.VERBOSE_LOG,
                        title = stringResource(R.string.settings_verbose_log_title),
                        summary = stringResource(R.string.settings_verbose_log_summary),
                        icon = MaterialSymbols.Outlined.Frame_bug,
                    )
                }
                item {
                    PrefSwitch(
                        key = Preferences.SHOW_STARTUP_TOAST,
                        title = stringResource(R.string.settings_startup_toast_title),
                        summary = stringResource(R.string.settings_startup_toast_summary),
                        icon = MaterialSymbols.Outlined.Notifications,
                    )
                }
                item {
                    PrefSwitch(
                        key = Preferences.MATCH_GENERIC_WXID_EXP,
                        title = stringResource(R.string.settings_generic_wxid_title),
                        summary = stringResource(R.string.settings_generic_wxid_summary),
                        icon = MaterialSymbols.Outlined.Rule_settings,
                        default = true,
                    )
                }
            }
        }

        // 兼容
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_compatibility)) {
                item {
                    PrefSwitch(
                        key = Preferences.NO_DEX_RESOLVE,
                        title = stringResource(R.string.settings_disable_resolution_title),
                        summary = stringResource(R.string.settings_disable_resolution_summary),
                        icon = MaterialSymbols.Outlined.Block,
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_reset_resolution_title),
                        summary = stringResource(R.string.settings_reset_resolution_summary),
                        icon = MaterialSymbols.Outlined.Build_circle,
                        onClick = { ResetDexCache.onClick(context) },
                    )
                }
                item {
                    PrefSwitch(
                        key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                        title = stringResource(R.string.settings_hot_update_resolution_title),
                        summary = stringResource(R.string.settings_hot_update_resolution_summary),
                        icon = MaterialSymbols.Outlined.Auto_delete,
                    )
                }
            }
        }

        // 配置
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_configuration)) {
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_export_config_title),
                        summary = stringResource(R.string.settings_export_config_summary),
                        icon = MaterialSymbols.Outlined.Upload,
                        onClick = {
                            SettingsConfigActions.export(platformContext) {
                                currentLocalizedContext.value
                            }
                        },
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_import_config_title),
                        summary = stringResource(R.string.settings_import_config_summary),
                        icon = MaterialSymbols.Outlined.Download,
                        onClick = {
                            SettingsConfigActions.importFromDocument(platformContext) {
                                currentLocalizedContext.value
                            }
                        },
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_clear_config_title),
                        summary = stringResource(R.string.settings_clear_config_summary),
                        icon = MaterialSymbols.Outlined.Delete_forever,
                        onClick = { showClearConfirm = true },
                    )
                }
            }
        }

        // 更新
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_update)) {
                item {
                    val actCtx = LocalComponentActivity.current
                    PrefArrow(
                        title = stringResource(R.string.settings_extensions_title),
                        summary = stringResource(R.string.settings_extensions_summary),
                        icon = MaterialSymbols.Outlined.Extension,
                        onClick = {
                            actCtx.startActivity(
                                Intent(context, ExtensionsSettingsActivity::class.java)
                            )
                        },
                    )
                }
            }
        }

        // 关于
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_about)) {
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_version_title),
                        summary = stringResource(R.string.home_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        icon = MaterialSymbols.Outlined.Label,
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_build_commit_time_title),
                        summary = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
                        icon = MaterialSymbols.Outlined.Build_circle,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Profile card — account info at the top of the Settings tab
// ---------------------------------------------------------------------------

@Composable
private fun ProfileCard() {
    val wxId = remember { WeApi.selfWxId }

    // WeChat identity — loaded once from the local DB; doesn't change mid-session.
    data class WechatIdentity(val nickname: String, val avatarUrl: String)

    val identity by produceState(WechatIdentity("", "")) {
        withContext(Dispatchers.IO) {
            val db = dev.sherry.wcs.features.api.core.WeDatabaseApi
            val nickname = if (db.isReady) {
                db.getSelfProfileField(dev.sherry.wcs.features.api.core.models.SelfProfileField.NAME, "")
                    ?.toString().orEmpty()
            } else ""
            val avatarUrl = if (db.isReady && wxId.isNotEmpty()) db.getAvatarUrl(wxId) else ""
            value = WechatIdentity(nickname, avatarUrl)
        }
    }

    SegmentedColumn {
        item {
            BaseItemContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (identity.avatarUrl.isNotEmpty()) {
                        AsyncImage(
                            model = identity.avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        AvatarPlaceholder()
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = identity.nickname.ifEmpty { wxId.ifEmpty { "—" } },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (wxId.isNotEmpty()) {
                            Text(
                                text = wxId,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Account_circle,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeSection() {
    val localizedContext by rememberUpdatedState(LocalWcSLocalizedContext.current)
    val selectedLanguage = WcSLocaleController.selection
    val resolvedLanguage = WcSLocaleController.resolvedLocale
    val languageLabels = mapOf(
        LanguageSelection.SYSTEM to stringResource(R.string.language_follow_system),
        LanguageSelection.ENGLISH to stringResource(R.string.language_english),
        LanguageSelection.SIMPLIFIED_CHINESE to stringResource(R.string.language_simplified_chinese),
        LanguageSelection.MEOW_CHINESE to stringResource(R.string.language_meow_chinese),
    )
    val languageSummary = if (selectedLanguage == LanguageSelection.SYSTEM) {
        stringResource(
            R.string.settings_language_summary,
            stringResource(selectedLanguage.labelRes),
            stringResource(resolvedLanguage.labelRes),
        )
    } else {
        stringResource(selectedLanguage.labelRes)
    }
    val themeModeLabels = mapOf(
        AppThemeMode.SYSTEM to stringResource(R.string.theme_mode_system),
        AppThemeMode.LIGHT to stringResource(R.string.theme_mode_light),
        AppThemeMode.DARK to stringResource(R.string.theme_mode_dark),
    )
    val paletteStyleLabels = mapOf(
        AppPaletteStyle.TONAL_SPOT to stringResource(R.string.palette_style_tonal_spot),
        AppPaletteStyle.NEUTRAL to stringResource(R.string.palette_style_neutral),
        AppPaletteStyle.VIBRANT to stringResource(R.string.palette_style_vibrant),
        AppPaletteStyle.EXPRESSIVE to stringResource(R.string.palette_style_expressive),
        AppPaletteStyle.RAINBOW to stringResource(R.string.palette_style_rainbow),
        AppPaletteStyle.FRUIT_SALAD to stringResource(R.string.palette_style_fruit_salad),
        AppPaletteStyle.MONOCHROME to stringResource(R.string.palette_style_monochrome),
        AppPaletteStyle.FIDELITY to stringResource(R.string.palette_style_fidelity),
        AppPaletteStyle.CONTENT to stringResource(R.string.palette_style_content),
    )
    val colorSpecLabels = mapOf(
        AppColorSpec.SPEC_2021 to stringResource(R.string.color_spec_material_2021),
        AppColorSpec.SPEC_2025 to stringResource(R.string.color_spec_expressive_2025),
    )
    val pageTransitionLabels = mapOf(
        PageTransitionAnimation.AOSP to stringResource(R.string.settings_page_transition_animation_aosp),
        PageTransitionAnimation.MIUIX to stringResource(R.string.settings_page_transition_animation_miuix),
    )
    var dynamicWallpaper by remember { mutableStateOf(ThemeSettings.dynamicWallpaper) }
    var showColorPicker by remember { mutableStateOf(false) }
    SeedColorPickerDialog(show = showColorPicker, onDismiss = { showColorPicker = false })

    SegmentedColumn(title = stringResource(R.string.settings_section_interface)) {
        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_language_title),
                description = languageSummary,
                value = selectedLanguage,
                options = languageLabels.map { DropdownOption(it.key, it.value) },
                onValueChange = WcSLocaleController::updateSelection,
                icon = MaterialSymbols.Outlined.Language,
            )
        }

        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_ui_engine_title),
                description = null,
                value = ThemeSettings.uiEngine,
                options = SettingsUiEngine.entries.map {
                    DropdownOption(it, it.displayName)
                },
                onValueChange = ThemeSettings::updateUiEngine,
                icon = MaterialSymbols.Outlined.Style,
            )
        }

        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_theme_mode_title),
                description = null,
                value = ThemeSettings.themeMode,
                options = AppThemeMode.entries.map {
                    DropdownOption(it, themeModeLabels.getValue(it))
                },
                onValueChange = ThemeSettings::updateThemeMode,
                icon = MaterialSymbols.Outlined.Brightness_medium,
            )
        }

        item {
            SwitchWidget(
                title = stringResource(R.string.settings_predictive_back_animation_title),
                description = stringResource(R.string.settings_predictive_back_animation_summary),
                checked = ThemeSettings.predictiveBackEnabled,
                onCheckedChange = { enabled ->
                    ThemeSettings.updatePredictiveBackEnabled(enabled)
                    CoroutineScope(Dispatchers.Main).launch {
                        showToastSuspend(localizedContext.getString(R.string.restart_wechat_to_apply))
                    }
                },
                icon = MaterialSymbols.Outlined.Swipe,
            )
        }

        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_page_transition_animation_title),
                description = null,
                value = ThemeSettings.pageTransitionAnimation,
                options = PageTransitionAnimation.entries.map {
                    DropdownOption(it, pageTransitionLabels.getValue(it))
                },
                onValueChange = ThemeSettings::updatePageTransitionAnimation,
                icon = MaterialSymbols.Outlined.Style,
            )
        }

        item {
            SwitchWidget(
                title = stringResource(R.string.settings_dynamic_wallpaper_title),
                description = stringResource(R.string.settings_dynamic_wallpaper_summary),
                icon = MaterialSymbols.Outlined.Wallpaper,
                checked = dynamicWallpaper,
                onCheckedChange = {
                    dynamicWallpaper = it
                    ThemeSettings.updateDynamicWallpaper(it)
                },
            )
        }
        item(animatedVisibility = !dynamicWallpaper) {
            BaseWidget(
                title = stringResource(R.string.settings_seed_color_title),
                description = stringResource(R.string.settings_seed_color_summary),
                icon = MaterialSymbols.Outlined.Colorize,
                onClick = { showColorPicker = true },
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(ThemeSettings.seedColor)),
                )
            }
        }
        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_palette_style_title),
                description = null,
                value = ThemeSettings.paletteStyle,
                options = AppPaletteStyle.entries.map {
                    val localizedName = paletteStyleLabels.getValue(it)
                    DropdownOption(
                        it,
                        if (resolvedLanguage == SupportedLocale.ENGLISH) {
                            it.displayName
                        } else {
                            stringResource(
                                R.string.palette_style_bilingual_format,
                                localizedName,
                                it.displayName,
                            )
                        },
                    )
                },
                onValueChange = {
                    ThemeSettings.updatePaletteStyle(it)
                    // Keep the stored spec valid for the new style.
                    if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                        ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                    }
                },
                icon = MaterialSymbols.Outlined.Style,
            )
        }
        val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_color_spec_title),
                description = if (!spec2025Supported) {
                    stringResource(R.string.settings_color_spec_unsupported)
                } else null,
                value = ThemeSettings.effectiveColorSpec,
                options = (if (spec2025Supported) AppColorSpec.entries else listOf(AppColorSpec.SPEC_2021)).map {
                    DropdownOption(it, colorSpecLabels.getValue(it))
                },
                onValueChange = ThemeSettings::updateColorSpec,
                enabled = spec2025Supported,
                icon = MaterialSymbols.Outlined.Contrast,
            )
        }

        item {
            var applyToWechat by remember { mutableStateOf(ThemeSettings.applyToWechat) }
            SwitchWidget(
                title = stringResource(R.string.settings_apply_to_wechat_title),
                description = stringResource(R.string.settings_apply_to_wechat_summary),
                icon = MaterialSymbols.Outlined.Sync,
                checked = applyToWechat,
                onCheckedChange = {
                    applyToWechat = it
                    ThemeSettings.updateApplyToWechat(it)
                    CoroutineScope(Dispatchers.Main).launch {
                        showToastSuspend(localizedContext.getString(R.string.restart_wechat_to_apply))
                    }
                },
            )
        }
    }
}

/**
 * HSV color-picker dialog for the custom seed color; commits to ThemeSettings on confirm.
 * Simplified vs the old miuix ColorPicker: three labeled sliders (Hue / Saturation / Value)
 * plus a live preview, no alpha channel (the seed color is opaque anyway).
 */
@Composable
private fun SeedColorPickerDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return

    val initialHsv = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(ThemeSettings.seedColor, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1] * 100f) }
    var value by remember { mutableFloatStateOf(initialHsv[2] * 100f) }
    val picked = AndroidColor.HSVToColor(floatArrayOf(hue, saturation / 100f, value / 100f))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_seed_color_title)) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(picked)),
                )
                Spacer(Modifier.height(16.dp))
                HsvSlider(
                    label = stringResource(R.string.color_picker_hue),
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                )
                HsvSlider(
                    label = stringResource(R.string.color_picker_saturation),
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..100f,
                )
                HsvSlider(
                    label = stringResource(R.string.color_picker_value),
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..100f,
                )
                TextButton(onClick = {
                    val reset = FloatArray(3).also { AndroidColor.colorToHSV(ThemeSettings.DEFAULT_SEED_COLOR, it) }
                    hue = reset[0]
                    saturation = reset[1] * 100f
                    value = reset[2] * 100f
                }) {
                    Text(stringResource(R.string.action_reset))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ThemeSettings.updateSeedColor(AndroidColor.HSVToColor(floatArrayOf(hue, saturation / 100f, value / 100f)))
                onDismiss()
            }) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun HsvSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    Column {
        Text(
            text = "$label: ${value.toInt()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

// ---------------------------------------------------------------------------
//  Preference helper composables
// ---------------------------------------------------------------------------

@Composable
private fun PrefSwitch(
    key: String,
    title: String,
    summary: String,
    icon: ImageVector,
    default: Boolean = false,
) {
    // Must match the default declared on the matching `prefOption`, otherwise the switch shows
    // "off" for a preference that is actually on until the user toggles it once.
    var checked by remember(key, default) { mutableStateOf(WePrefs.getBoolOrDef(key, default)) }
    SwitchWidget(
        title = title,
        description = summary,
        icon = icon,
        checked = checked,
        onCheckedChange = {
            checked = it
            WePrefs.putBool(key, it)
        },
    )
}

@Composable
private fun PrefArrow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        // Informational row: no trailing arrow, no ripple.
        BaseWidget(
            title = title,
            description = summary,
            icon = icon,
        )
    } else {
        BaseWidget(
            title = title,
            description = summary,
            icon = icon,
            onClick = onClick,
            trailingContent = { Icon(imageVector = MaterialSymbols.Outlined.Chevron_right, contentDescription = null) },
        )
    }
}

@Composable
private fun SecuritySwitch(context: Context) {
    var checked by remember { mutableStateOf(SafeMode.isEnabled) }
    SwitchWidget(
        title = stringResource(R.string.settings_safe_mode_title),
        description = stringResource(R.string.settings_safe_mode_summary),
        icon = MaterialSymbols.Outlined.Shield,
        checked = checked,
        onCheckedChange = {
            if (it) {
                SafeMode.showEnableConfirmDialog(context) {
                    checked = true
                    SafeMode.setEnabled(true)
                }
            } else {
                checked = false
                SafeMode.setEnabled(false)
            }
        },
    )
}
// ---------------------------------------------------------------------------
//  Dialogs (Material 3 AlertDialog)
// ---------------------------------------------------------------------------

@Composable
private fun ClearConfigDialog(show: Boolean, onDismiss: () -> Unit) {
    val localizedContext by rememberUpdatedState(LocalWcSLocalizedContext.current)
    ConfirmDialog(
        show = show,
        title = stringResource(R.string.clear_config_dialog_title),
        message = stringResource(R.string.clear_config_dialog_message),
        confirmText = stringResource(R.string.action_clear),
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            CoroutineScope(Dispatchers.IO).launch {
                showToastSuspend(localizedContext.getString(R.string.config_clearing))
                SettingsConfigActions.clear()
                showToastSuspend(localizedContext.getString(R.string.config_clear_success))
            }
        },
    )
}

/** Two-button (cancel / confirm) dialog. */
@Composable
private fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissText: String? = null,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText ?: stringResource(R.string.dialog_cancel)) }
        },
    )
}
