package dev.sherry.wcs.activity.nuke

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_delete
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Upload
import dev.sherry.wcs.R
import dev.sherry.wcs.activity.settings.LocalComponentActivity
import dev.sherry.wcs.activity.settings.SettingsConfigActions
import dev.sherry.wcs.activity.settings.featureCategoryTitleRes
import dev.sherry.wcs.constants.Preferences
import dev.sherry.wcs.features.core.BaseFeature
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeaturesProvider
import dev.sherry.wcs.features.core.SwitchFeature
import dev.sherry.wcs.features.items.debug.ResetDexCache
import dev.sherry.wcs.i18n.LanguageSelection
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.i18n.LocalWcSLocalizedContext
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.nuke.NukeButton
import dev.sherry.wcs.ui.content.nuke.NukeCategoryIcon
import dev.sherry.wcs.ui.content.nuke.NukeCountAndChevron
import dev.sherry.wcs.ui.content.nuke.NukeDialogSurface
import dev.sherry.wcs.ui.content.nuke.NukeDivider
import dev.sherry.wcs.ui.content.nuke.NukeGlyphKind
import dev.sherry.wcs.ui.content.nuke.NukePageScaffold
import dev.sherry.wcs.ui.content.nuke.NukePreferenceRow
import dev.sherry.wcs.ui.content.nuke.NukeSelectPreference
import dev.sherry.wcs.ui.content.nuke.NukeSettingGroup
import dev.sherry.wcs.ui.content.nuke.NukeSettingGroupTitle
import dev.sherry.wcs.ui.content.nuke.NukeStatusPill
import dev.sherry.wcs.ui.content.nuke.NukeSwitch
import dev.sherry.wcs.ui.content.nuke.NukeText
import dev.sherry.wcs.ui.content.nuke.NukeTheme
import dev.sherry.wcs.ui.content.nuke.NukeVectorCategoryIcon
import dev.sherry.wcs.ui.content.nuke.nukeGroupedCardItem
import dev.sherry.wcs.utils.restartHost
import java.text.Collator
import java.util.Locale

@Composable
fun NukeDestinationPage(
    destination: NukeDestination,
    featureItems: List<SwitchFeature>,
    onBack: (Offset) -> Unit,
    onOpenDestination: (NukeDestination, Offset) -> Unit,
) {
    when (destination) {
        is NukeDestination.Category -> NukeFeatureCategoryPage(
            categoryId = destination.id,
            featureItems = featureItems,
            onBack = onBack,
        )

        NukeDestination.ModuleDebug -> NukeModuleDebugPage(onBack)
        NukeDestination.GeneralSettings -> NukeGeneralSettingsPage(onBack)
        NukeDestination.Appearance -> NukeAppearancePage(onBack)
        NukeDestination.About -> NukeAboutPage(onBack, onOpenDestination)
    }
}

@Composable
private fun NukeModuleDebugPage(onBack: (Offset) -> Unit) {
    val context = LocalWcSLocalizedContext.current
    val resolvedLocale = WcSLocaleController.resolvedLocale
    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val features = remember(resolvedLocale) {
        FeaturesProvider.ALL_FEATURES.sortedWith { first, second ->
            featureNameCollator.compare(first.localizedName(context), second.localizedName(context))
        }
    }
    var selectedFeature by remember { mutableStateOf<BaseFeature?>(null) }
    // Only the FEATURES rows visible on the first frame animate in; scrolling reveals further
    // rows statically.
    var featuresEntranceEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        featuresEntranceEnabled = false
    }

    NukePageScaffold(
        title = stringResource(R.string.nuke_module_debug_title),
        onBack = onBack,
        // FEATURES rows are individual items; 0 spacing keeps them flush and explicit spacer
        // items below restore the 12dp rhythm between sections.
        itemSpacing = 0.dp,
    ) {
        item(key = "actions") {
            NukeSettingGroup(title = stringResource(R.string.nuke_section_actions)) {
                NukePreferenceRow(
                    title = stringResource(R.string.nuke_restart_host_title),
                    description = stringResource(R.string.nuke_restart_host_summary),
                    leading = { NukeCategoryIcon(NukeGlyphKind.Restart) },
                    onClick = { restartHost() },
                )
            }
        }
        item(key = "gap_overview") { Spacer(Modifier.height(12.dp)) }
        item(key = "overview") {
            NukeSettingGroup(title = stringResource(R.string.nuke_status_overview)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NukeStatusPill(
                        stringResource(R.string.nuke_status_normal_count, features.size),
                        Color(0xFF16A34A),
                    )
                }
            }
        }
        item(key = "gap_features") { Spacer(Modifier.height(12.dp)) }
        item(key = "features_title") {
            // The section title always bounces when it appears (including the second time after
            // scrolling back to the top); only the rows are gated to first-appearance motion.
            NukeSettingGroupTitle(title = stringResource(R.string.nuke_features_heading))
        }
        itemsIndexed(features, key = { _, feature -> feature.technicalId }) { index, feature ->
            Column(
                Modifier.nukeGroupedCardItem(
                    index,
                    features.size,
                    animate = featuresEntranceEnabled,
                ),
            ) {
                NukeFeatureStatusRow(feature = feature, onClick = { selectedFeature = feature })
                if (index < features.lastIndex) NukeDivider()
            }
        }
    }
    selectedFeature?.let { feature ->
        NukeFeatureStatusDialog(feature = feature, onDismiss = { selectedFeature = null })
    }
}

@Composable
private fun NukeFeatureStatusRow(feature: BaseFeature, onClick: () -> Unit) {
    val context = LocalWcSLocalizedContext.current
    NukePreferenceRow(
        title = feature.localizedName(context),
        description = feature.categoryIds
            .joinToString(" / ") { context.getString(featureCategoryTitleRes(it)) }
            .ifBlank { stringResource(R.string.nuke_feature_kind_base) },
        leading = { NukeCategoryIcon(NukeGlyphKind.CheckCircle) },
        trailing = {
            NukeStatusPill(stringResource(R.string.nuke_status_normal), Color(0xFF16A34A))
        },
        onClick = { onClick() },
    )
}

@Composable
private fun NukeFeatureStatusDialog(feature: BaseFeature, onDismiss: () -> Unit) {
    val context = LocalWcSLocalizedContext.current
    val kind = when (feature) {
        is ClickableFeature -> stringResource(R.string.nuke_feature_kind_configurable)
        is SwitchFeature -> stringResource(R.string.nuke_feature_kind_switch)
        else -> stringResource(R.string.nuke_feature_kind_base)
    }
    NukeMessageDialog(
        title = feature.localizedName(context),
        message = buildString {
            appendLine(stringResource(R.string.nuke_feature_status_line, stringResource(R.string.nuke_status_normal)))
            appendLine(stringResource(R.string.nuke_feature_type_line, kind))
            val categories = feature.categoryIds
                .joinToString(" / ") { context.getString(featureCategoryTitleRes(it)) }
                .ifBlank { stringResource(R.string.nuke_uncategorized) }
            appendLine(stringResource(R.string.nuke_feature_categories_line, categories))
            feature.localizedDescription(context).takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun NukeGeneralSettingsPage(onBack: (Offset) -> Unit) {
    val context = LocalContext.current
    val activity = LocalComponentActivity.current
    val localizedContext by rememberUpdatedState(LocalWcSLocalizedContext.current)
    var showClearConfirmation by remember { mutableStateOf(false) }

    NukePageScaffold(title = stringResource(R.string.settings_general_title), onBack = onBack) {
        item(key = "language") {
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
            NukeSettingGroup(title = null) {
                NukeSelectPreference(
                    title = stringResource(R.string.settings_language_title),
                    description = languageSummary,
                    options = LanguageSelection.entries,
                    selected = selectedLanguage,
                    optionLabel = languageLabels::getValue,
                    onSelected = WcSLocaleController::updateSelection,
                )
            }
        }
        item(key = "debug") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_debug)) {
                NukeBooleanPreference(
                    key = Preferences.VERBOSE_LOG,
                    title = stringResource(R.string.settings_verbose_log_title),
                    description = stringResource(R.string.settings_verbose_log_summary),
                    imageVector = MaterialSymbols.Outlined.Frame_bug,
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.SHOW_STARTUP_TOAST,
                    title = stringResource(R.string.settings_startup_toast_title),
                    description = stringResource(R.string.settings_startup_toast_summary),
                    imageVector = MaterialSymbols.Outlined.Notifications,
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.MATCH_GENERIC_WXID_EXP,
                    title = stringResource(R.string.settings_generic_wxid_title),
                    description = stringResource(R.string.settings_generic_wxid_summary),
                    imageVector = MaterialSymbols.Outlined.Rule_settings,
                    default = true,
                )
            }
        }
        item(key = "compatibility") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_compatibility)) {
                NukeBooleanPreference(
                    key = Preferences.NO_DEX_RESOLVE,
                    title = stringResource(R.string.settings_disable_resolution_title),
                    description = stringResource(R.string.settings_disable_resolution_summary),
                    imageVector = MaterialSymbols.Outlined.Block,
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.settings_reset_resolution_title),
                    description = stringResource(R.string.settings_reset_resolution_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Build_circle) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = { ResetDexCache.onClick(activity) },
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                    title = stringResource(R.string.settings_hot_update_resolution_title),
                    description = stringResource(R.string.settings_hot_update_resolution_summary),
                    imageVector = MaterialSymbols.Outlined.Auto_delete,
                )
            }
        }
        item(key = "configuration") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_configuration)) {
                NukePreferenceRow(
                    title = stringResource(R.string.settings_export_config_title),
                    description = stringResource(R.string.settings_export_config_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Upload) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        SettingsConfigActions.export(context) { localizedContext }
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.settings_import_config_title),
                    description = stringResource(R.string.settings_import_config_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Download) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        SettingsConfigActions.importFromDocument(context) { localizedContext }
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.settings_clear_config_title),
                    description = stringResource(R.string.settings_clear_config_summary),
                    leading = {
                        NukeVectorCategoryIcon(
                            MaterialSymbols.Outlined.Delete_forever,
                            error = true,
                        )
                    },
                    trailing = { NukeCountAndChevron(text = null, error = true) },
                    onClick = { showClearConfirmation = true },
                )
            }
        }
    }
    if (showClearConfirmation) {
        NukeConfirmDialog(
            title = stringResource(R.string.clear_config_dialog_title),
            message = stringResource(R.string.clear_config_dialog_message),
            confirmText = stringResource(R.string.action_clear),
            onDismiss = { showClearConfirmation = false },
            onConfirm = {
                SettingsConfigActions.clear()
                showClearConfirmation = false
            },
        )
    }
}

@Composable
private fun NukeBooleanPreference(
    key: String,
    title: String,
    description: String,
    imageVector: ImageVector,
    default: Boolean = false,
) {
    var checked by remember(key, default) { mutableStateOf(WePrefs.getBoolOrDef(key, default)) }
    NukePreferenceRow(
        title = title,
        description = description,
        leading = { NukeVectorCategoryIcon(imageVector) },
        trailing = {
            NukeSwitch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    WePrefs.putBool(key, it)
                },
            )
        },
        onClick = {
            checked = !checked
            WePrefs.putBool(key, checked)
        },
    )
}

@Composable
private fun NukeAboutPage(
    onBack: (Offset) -> Unit,
    onOpenDestination: (NukeDestination, Offset) -> Unit,
) {
    val context = LocalContext.current
    NukePageScaffold(title = stringResource(R.string.nuke_about_title), onBack = onBack) {
        item(key = "avatar") { NukeAboutIcon() }
        item(key = "project") {
            NukeSettingGroup(title = stringResource(R.string.nuke_about_project)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NukeText(
                        text = stringResource(R.string.nuke_about_description_one),
                        color = NukeTheme.colors.textSecondary,
                        fontSize = 13,
                        lineHeight = 19,
                    )
                    NukeText(
                        text = stringResource(R.string.nuke_about_description_two),
                        color = NukeTheme.colors.textSecondary,
                        fontSize = 13,
                        lineHeight = 19,
                    )
                }
            }
        }
    }
}

@Composable
private fun NukeAboutIcon() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(94.dp)
                .clip(CircleShape)
                .background(NukeTheme.colors.accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(86.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

)

private const val UNKNOWN_LIBRARY_AUTHOR_KEY = "\u0000unknown-author"

        ?: UNKNOWN_LIBRARY_AUTHOR_KEY

@Composable
private fun NukeConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    NukeDialogSurface(
        title = title,
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton(
                stringResource(R.string.dialog_cancel),
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
            NukeButton(
                confirmText,
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = {
                    onConfirm()
                    dismiss()
                },
            )
        },
    ) {
        NukeText(
            text = message,
            color = NukeTheme.colors.textSecondary,
            fontSize = 13,
            lineHeight = 19,
        )
    }
}

@Composable
private fun NukeMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    NukeDialogSurface(
        title = title,
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton(
                stringResource(R.string.dialog_close),
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = dismiss,
            )
        },
    ) {
        NukeText(
            text = message,
            color = NukeTheme.colors.textSecondary,
            fontSize = 13,
            lineHeight = 19,
        )
    }
}
