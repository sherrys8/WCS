package dev.sherry.wcs.features.items.entertain

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.sherry.wcs.R
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.items.chat.ApiProfile
import dev.sherry.wcs.features.items.chat.ApiProfiles
import dev.sherry.wcs.features.items.chat.ApiProtocol
import dev.sherry.wcs.features.items.chat.AiModelConnection
import dev.sherry.wcs.features.items.chat.asConfig
import dev.sherry.wcs.features.items.chat.requestEndpoint
import dev.sherry.wcs.features.items.chat.pathSuffixMismatch
import dev.sherry.wcs.features.items.chat.showApiModelPicker
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.Button
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.android.showToast
import kotlinx.coroutines.launch

/**
 * 「娱乐 → API配置」：统一管理 AI 功能共用的接口配置。
 *
 * 配置档是资产，功能是消费者：这里维护「地址 / 路径 / 密钥 / 协议 / 默认模型」，
 * 各功能只引用一条并可选覆盖模型。改这里，所有引用方一起生效。
 *
 * 纯配置页，没有实际 hook，所以 `noSwitchWidget = true` —— 整行点开管理界面，不带开关。
 */
object ApiProfileManager : ClickableFeature() {

    override val technicalId = "API配置"
    override val nameRes = R.string.api_profile_feature_name
    override val descriptionRes = R.string.api_profile_feature_description
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) { ProfileListContent() }
    }

    @Composable
    private fun ShowComposeDialogScope.ProfileListContent() {
        var profiles by remember { mutableStateOf(ApiProfiles.all()) }
        var editingId by remember { mutableStateOf<String?>(null) }

        val editing = editingId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (editing != null) {
            ProfileEditor(
                profile = editing,
                onChanged = { profiles = ApiProfiles.all() },
                onBack = { editingId = null },
            )
            return
        }

        AlertDialogContent(
            title = {
                Text(
                    text = stringResource(R.string.api_profile_feature_name),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (profiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.api_profile_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        SegmentedColumn {
                            profiles.forEach { profile ->
                                item {
                                    BaseWidget(
                                        iconPlaceholder = false,
                                        title = profile.name,
                                        description = buildString {
                                            append(profile.protocol.label)
                                            if (profile.baseUrl.isNotBlank()) {
                                                append(" · ")
                                                append(profile.baseUrl)
                                            }
                                        },
                                        onClick = { editingId = profile.id },
                                        trailingContent = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                                contentDescription = null,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val created = ApiProfiles.create()
                        profiles = ApiProfiles.all()
                        editingId = created.id
                    },
                ) {
                    Text(stringResource(R.string.api_profile_new))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }

    @Composable
    private fun ShowComposeDialogScope.ProfileEditor(
        profile: ApiProfile,
        onChanged: () -> Unit,
        onBack: () -> Unit,
    ) {
        var draft by remember(profile.id) { mutableStateOf(profile) }
        var testing by remember { mutableStateOf(false) }
        var testOutcome by remember { mutableStateOf<Boolean?>(null) }
        var testError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        // 引用中的条数在打开编辑页时算一次即可：真要改引用得先离开这个界面
        val usage = remember(profile.id) { ApiProfiles.usageCount(profile.id) }
        val inUseMessage = stringResource(R.string.api_profile_in_use, usage)
        val deletedMessage = stringResource(R.string.api_profile_deleted)

        fun persist(updated: ApiProfile) {
            draft = updated
            ApiProfiles.save(updated)
            onChanged()
        }

        AlertDialogContent(
            title = {
                Text(
                    text = draft.name.ifBlank { stringResource(R.string.api_profile_feature_name) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    SegmentedColumn {
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.api_profile_name),
                                value = draft.name,
                                onValueChange = { persist(draft.copy(name = it.trim())) },
                                dialogTitle = stringResource(R.string.api_profile_name),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                            )
                        }
                        item {
                            DropDownMenuWidget(
                                title = stringResource(R.string.api_profile_protocol),
                                description = null,
                                value = draft.protocol,
                                options = ApiProtocol.values().map { DropdownOption(it, it.label) },
                                onValueChange = { persist(draft.copy(protocol = it)) },
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_base_url),
                                value = draft.baseUrl,
                                onValueChange = { persist(draft.copy(baseUrl = it.trim())) },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_base_url),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_base_url_hint),
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_path),
                                value = draft.apiPath,
                                onValueChange = { persist(draft.copy(apiPath = it.trim())) },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_path),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_path_hint),
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_api_key),
                                value = draft.apiKey,
                                onValueChange = { persist(draft.copy(apiKey = it.trim())) },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_api_key),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_api_key_hint),
                                password = true,
                            )
                        }
                        item {
                            // 点这一行 = 用当前地址和 Key 去拉模型列表，选完直接写回默认模型。
                            // 拉不到（Anthropic 无该端点、自建网关没实现）就用下面那行手填。
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.api_profile_default_model),
                                description = draft.defaultModelId.ifBlank {
                                    stringResource(R.string.api_profile_default_model_hint)
                                },
                                onClick = {
                                    // 点进来就开始拉：地址和 Key 已经在上面填好了，不该再点一次
                                    showApiModelPicker(
                                        context = context,
                                        config = draft.asConfig(),
                                        current = draft.defaultModelId,
                                        autoFetch = true,
                                    ) { picked ->
                                        persist(draft.copy(defaultModelId = picked))
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.api_profile_model_manual),
                                value = draft.defaultModelId,
                                onValueChange = { persist(draft.copy(defaultModelId = it.trim())) },
                                dialogTitle = stringResource(R.string.api_profile_model_manual),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.api_profile_default_model_hint),
                            )
                        }
                    }

                    // 把「填的」和「程序补的」物理分开显示：补出来的那段用灰字跟在后面
                    val endpoint = draft.asConfig().requestEndpoint()
                    if (endpoint.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.api_profile_endpoint_preview, endpoint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (draft.protocol.keepsUrlVerbatim) {
                        Text(
                            text = stringResource(R.string.api_profile_custom_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 适配器只对「已经以 /chat/completions 结尾」的地址免补，其余情况会补一段
                        if (endpoint.isNotEmpty() && !endpoint.endsWith("/chat/completions")) {
                            Text(
                                text = stringResource(R.string.api_profile_custom_tail_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // 路径里的末段和协议不一致时，实际请求会被改写成协议末段——与其让人对着 404 猜
                    draft.asConfig().pathSuffixMismatch()?.let { typed ->
                        Text(
                            text = stringResource(R.string.api_profile_suffix_mismatch, typed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    testOutcome?.let { ok ->
                        Text(
                            text = if (ok) {
                                stringResource(R.string.ui_group_ai_settings_test_ok)
                            } else {
                                stringResource(
                                    R.string.ui_group_ai_settings_test_failed_toast,
                                    testError.orEmpty(),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SegmentedColumn {
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.api_profile_delete),
                                description = if (usage > 0) inUseMessage else null,
                                isError = true,
                                onClick = {
                                    if (usage > 0) {
                                        showToast(context, inUseMessage)
                                    } else {
                                        ApiProfiles.delete(draft.id)
                                        onChanged()
                                        showToast(context, deletedMessage)
                                        onBack()
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        testing = true
                        testOutcome = null
                        testError = null
                        scope.launch {
                            val result = AiModelConnection.testConnection(draft.asConfig())
                            testing = false
                            result.fold(
                                onSuccess = { testOutcome = true },
                                onFailure = { failure ->
                                    testOutcome = false
                                    testError = failure.message
                                },
                            )
                        }
                    },
                    enabled = !testing,
                ) {
                    Text(
                        stringResource(
                            if (testing) R.string.ui_group_ai_settings_testing
                            else R.string.ui_group_ai_settings_test,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.api_profile_back)) }
            },
        )
    }
}
