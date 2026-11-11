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
import dev.sherry.wcs.features.items.chat.AiModelConnection
import dev.sherry.wcs.features.items.chat.ApiProfile
import dev.sherry.wcs.features.items.chat.ApiProfiles
import dev.sherry.wcs.features.items.chat.ApiProtocol
import dev.sherry.wcs.features.items.chat.CAP_LLM
import dev.sherry.wcs.features.items.chat.CAP_TTS
import dev.sherry.wcs.features.items.chat.TTS_PROVIDER_BREEZE
import dev.sherry.wcs.features.items.chat.TTS_PROVIDER_DOUBAO
import dev.sherry.wcs.features.items.chat.TTS_PROVIDER_MOFANG
import dev.sherry.wcs.features.items.chat.TextToSpeech
import dev.sherry.wcs.features.items.chat.TtsProfiles
import dev.sherry.wcs.features.items.chat.asConfig
import dev.sherry.wcs.features.items.chat.localizedChatString
import dev.sherry.wcs.features.items.chat.pathSuffixMismatch
import dev.sherry.wcs.features.items.chat.requestEndpoint
import dev.sherry.wcs.features.items.chat.showApiModelPicker
import dev.sherry.wcs.features.items.chat.showBreezeModelPicker
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
import dev.sherry.wcs.utils.BreezeTtsClient
import dev.sherry.wcs.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「娱乐 → API配置」：统一管理 AI 功能共用的接口配置。
 *
 * 配置档是资产，功能是消费者，分两类能力：
 * - **对话模型**：地址 / 路径 / 协议 / Key / 默认模型
 * - **语音合成**：服务商 / 凭据（Breeze、魔方是 Key，豆包是 Cookie）/ 模型
 *
 * 语音合成这边**只管连接**：凭据与模型放这里，音色、语气指令、签到仍留在文字转语音里。
 * 签到不算配置（它要的是网页登录 Cookie，和合成用的 Key 是两套东西），所以这里没有签到。
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
        // 老设备第一次打开：把「文字转语音」里三套老凭据搬成语音合成档，列表里当场能看到
        remember { TtsProfiles.ensureMigrated() }
        var profiles by remember { mutableStateOf(ApiProfiles.all()) }
        var editingId by remember { mutableStateOf<String?>(null) }
        var creating by remember { mutableStateOf(false) }

        val editing = editingId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (editing != null) {
            ProfileEditor(
                profile = editing,
                onChanged = { profiles = ApiProfiles.all() },
                onBack = { editingId = null },
            )
            return
        }
        if (creating) {
            NewProfilePicker(
                onCreated = { created ->
                    profiles = ApiProfiles.all()
                    creating = false
                    editingId = created.id
                },
                onBack = { creating = false },
            )
            return
        }

        val llmProfiles = profiles.filter { !it.isTts }
        val ttsProfiles = profiles.filter { it.isTts }

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
                    }
                    if (llmProfiles.isNotEmpty()) {
                        SegmentedColumn(title = stringResource(R.string.api_profile_capability_llm)) {
                            llmProfiles.forEach { profile ->
                                item { ProfileRow(profile) { editingId = profile.id } }
                            }
                        }
                    }
                    if (ttsProfiles.isNotEmpty()) {
                        SegmentedColumn(title = stringResource(R.string.api_profile_capability_tts)) {
                            ttsProfiles.forEach { profile ->
                                item { ProfileRow(profile) { editingId = profile.id } }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { creating = true }) {
                    Text(stringResource(R.string.api_profile_new))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }

    @Composable
    private fun ProfileRow(profile: ApiProfile, onClick: () -> Unit) {
        BaseWidget(
            iconPlaceholder = false,
            title = profile.name,
            description = if (profile.isTts) {
                stringResource(TtsProfiles.providerLabelRes(profile.provider)) + " · " +
                    stringResource(
                        if (profile.hasCredential) R.string.api_profile_cred_ready
                        else R.string.api_profile_cred_missing,
                    )
            } else {
                buildString {
                    append(profile.protocol.label)
                    if (profile.baseUrl.isNotBlank()) {
                        append(" · ")
                        append(profile.baseUrl)
                    }
                }
            },
            onClick = onClick,
            trailingContent = {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                )
            },
        )
    }

    /** 新建时先问一句「哪一类」：两类字段完全不同，不能建完再猜。 */
    @Composable
    private fun ShowComposeDialogScope.NewProfilePicker(
        onCreated: (ApiProfile) -> Unit,
        onBack: () -> Unit,
    ) {
        AlertDialogContent(
            title = { Text(stringResource(R.string.api_profile_new_capability)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    SegmentedColumn {
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.api_profile_capability_llm),
                                description = stringResource(R.string.api_profile_capability_llm_hint),
                                onClick = { onCreated(ApiProfiles.create(capability = CAP_LLM)) },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.api_profile_capability_tts),
                                description = stringResource(R.string.api_profile_capability_tts_hint),
                                onClick = {
                                    val name = nextTtsName(TTS_PROVIDER_BREEZE)
                                    onCreated(
                                        ApiProfiles.create(
                                            name = name,
                                            capability = CAP_TTS,
                                            provider = TTS_PROVIDER_BREEZE,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = null,
            dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }

    /** 「Breeze」「Breeze 2」……同服务商建第二条时不重名，列表里才好认。 */
    private fun nextTtsName(provider: String): String {
        val base = when (provider) {
            TTS_PROVIDER_DOUBAO -> "豆包"
            TTS_PROVIDER_MOFANG -> "魔方"
            else -> "Breeze"
        }
        val existing = ApiProfiles.ofCapability(CAP_TTS).map { it.name }.toSet()
        if (!existing.contains(base)) return base
        var index = 2
        while (existing.contains("$base $index")) index++
        return "$base $index"
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
                    }

                    if (draft.isTts) TtsFields(draft) { persist(it) } else LlmFields(draft) { persist(it) }

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
                            val result = if (draft.isTts) {
                                testTtsConnection(draft)
                            } else {
                                AiModelConnection.testConnection(draft.asConfig())
                            }
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

    // ---- 对话模型 ----

    @Composable
    private fun ShowComposeDialogScope.LlmFields(draft: ApiProfile, persist: (ApiProfile) -> Unit) {
        SegmentedColumn {
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
    }

    // ---- 语音合成 ----

    /**
     * 语音合成档只有三样东西：服务商、一组凭据、模型（Breeze 才有）。
     * 地址由协议固定，只读展示；音色不在这里，它跟语言、情绪绑，属于功能侧。
     */
    @Composable
    private fun ShowComposeDialogScope.TtsFields(draft: ApiProfile, persist: (ApiProfile) -> Unit) {
        SegmentedColumn {
            item {
                DropDownMenuWidget(
                    title = stringResource(R.string.api_profile_provider),
                    description = null,
                    value = draft.provider.ifBlank { TTS_PROVIDER_BREEZE },
                    options = listOf(
                        DropdownOption(TTS_PROVIDER_BREEZE, stringResource(R.string.tts_backend_breeze)),
                        DropdownOption(TTS_PROVIDER_DOUBAO, stringResource(R.string.tts_backend_doubao)),
                        DropdownOption(TTS_PROVIDER_MOFANG, stringResource(R.string.tts_backend_mofa)),
                    ),
                    onValueChange = { picked ->
                        // 换服务商就把另一家的凭据清掉：豆包的 Cookie 拿到 Breeze 只会被当无效 Key
                        persist(
                            draft.copy(
                                provider = picked,
                                apiKey = if (picked == TTS_PROVIDER_DOUBAO) "" else draft.apiKey,
                                cookie = if (picked == TTS_PROVIDER_DOUBAO) draft.cookie else "",
                                defaultModelId = if (picked == TTS_PROVIDER_BREEZE) {
                                    draft.defaultModelId
                                } else {
                                    ""
                                },
                            ),
                        )
                    },
                )
            }
            if (draft.provider == TTS_PROVIDER_DOUBAO) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.tts_cfg_cookie),
                        value = draft.cookie,
                        onValueChange = { persist(draft.copy(cookie = it.trim())) },
                        dialogTitle = stringResource(R.string.tts_dlg_set_doubao_cookie),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        valueHint = stringResource(R.string.tts_doubao_cookie_hint),
                        password = true,
                    )
                }
            } else {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.tts_cfg_api_key),
                        value = draft.apiKey,
                        onValueChange = { persist(draft.copy(apiKey = it.trim())) },
                        dialogTitle = stringResource(
                            if (draft.provider == TTS_PROVIDER_MOFANG) R.string.tts_cfg_api_key
                            else R.string.tts_dlg_set_breeze_key,
                        ),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        valueHint = stringResource(
                            if (draft.provider == TTS_PROVIDER_MOFANG) R.string.tts_mofang_key_hint
                            else R.string.tts_breeze_key_hint,
                        ),
                        password = true,
                    )
                }
            }
            // 模型只对 Breeze 有意义（魔方的音色自己就是模型，豆包没有模型可选）
            if (draft.provider == TTS_PROVIDER_BREEZE) {
                item {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.tts_cfg_model),
                        description = draft.defaultModelId.ifBlank {
                            stringResource(R.string.api_profile_tts_model_hint, BreezeTtsClient.DEFAULT_MODEL)
                        },
                        onClick = {
                            showBreezeModelPicker(
                                context = context,
                                key = draft.apiKey,
                                current = draft.defaultModelId,
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
            }
        }

        Text(
            text = stringResource(
                R.string.api_profile_tts_endpoint,
                TtsProfiles.endpointOf(draft.provider.ifBlank { TTS_PROVIDER_BREEZE }),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.api_profile_tts_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /**
     * 语音合成的连通性检查：Breeze 拉模型列表、魔方拉音色列表，能拿到东西就说明 Key 对。
     * 豆包的鉴权在 WebSocket 握手里，本地只能查 Cookie 填没填，判为通过并说明原因。
     */
    private suspend fun testTtsConnection(profile: ApiProfile): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (profile.provider) {
                TTS_PROVIDER_DOUBAO -> {
                    check(profile.cookie.isNotBlank()) { localizedChatString(R.string.tts_toast_need_doubao_cookie) }
                }
                TTS_PROVIDER_MOFANG -> {
                    check(profile.apiKey.isNotBlank()) { localizedChatString(R.string.ai_reply_tts_missing_credentials) }
                    check(TextToSpeech.probeMofangKey(profile.apiKey)) {
                        localizedChatString(R.string.tts_err_no_response)
                    }
                }
                else -> {
                    check(profile.apiKey.isNotBlank()) { localizedChatString(R.string.ai_reply_tts_missing_credentials) }
                    val models = BreezeTtsClient.models(profile.apiKey)
                    check(models.isNotEmpty()) { localizedChatString(R.string.tts_err_no_response) }
                }
            }
            "ok"
        }
    }
}
