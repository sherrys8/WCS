package dev.sherry.wcs.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sherry.wcs.R
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import kotlinx.coroutines.launch

/** 模型列表最多露出的行数，其余上下滑动。 */
private const val MODEL_ROWS_VISIBLE = 8

/** 列表项最小高度，用于估算滚动区域高度。 */
private const val LIST_ITEM_MIN_HEIGHT_DP = 56

/**
 * 功能侧选配置档。
 *
 * 这里只能「选」，不能改：地址、密钥这些属于配置档本身，改一处会同时影响所有引用方，
 * 所以编辑入口只放在「娱乐 → API配置」的管理页里。
 *
 * [capability] 传了就只列这一类（语音合成不该在对话模型的列表里出现）。
 */
fun showApiProfilePicker(
    context: Context,
    selectedId: String,
    capability: String? = null,
    onPick: (ApiProfile) -> Unit,
) {
    showComposeDialog(context) { ApiProfilePickerContent(selectedId, capability, onPick) }
}

/**
 * 选模型。AI 回复、群聊总结、API配置编辑页共用这一套：点模型行弹出，
 * 「获取模型」把列表拉下来，点中一条后右下角原位变成「确定」。
 *
 * 传 [config] 而不是各功能自己的偏好对象，是为了让所有入口走完全相同的请求路径
 * —— 换配置档后重开弹窗，拉的就是新配置档的模型列表。
 *
 * [autoFetch] 给「点进来就该开始拉」的场景用（配置页的默认模型行）；功能侧保持手动，
 * 避免每次点开设置都打一次网络。
 */
fun showApiModelPicker(
    context: Context,
    config: AiApiConfig,
    current: String,
    autoFetch: Boolean = false,
    onPicked: (String) -> Unit,
) {
    showComposeDialog(context) { ApiModelPickerContent(config, current, autoFetch, onPicked) }
}

@Composable
private fun ShowComposeDialogScope.ApiProfilePickerContent(
    selectedId: String,
    capability: String?,
    onPick: (ApiProfile) -> Unit,
) {
    val profiles = remember(capability) {
        if (capability == null) ApiProfiles.all() else ApiProfiles.ofCapability(capability)
    }

    AlertDialogContent(
        title = {
            Text(
                text = stringResource(R.string.ui_group_ai_settings_title),
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
                        text = stringResource(R.string.api_profile_empty_picker),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SegmentedColumn {
                        profiles.forEach { profile ->
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = profile.name,
                                    description = if (profile.isTts) {
                                        stringResource(R.string.api_profile_capability_tts) + " · " +
                                            stringResource(TtsProfiles.providerLabelRes(profile.provider))
                                    } else {
                                        "${profile.protocol.label} · ${profile.baseUrl}"
                                    },
                                    selected = profile.id == selectedId,
                                    onClick = {
                                        onPick(profile)
                                        onDismiss()
                                    },
                                    trailingContent = {
                                        if (profile.id == selectedId) Text("✓")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = null,
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
        },
    )
}

/**
 * 模型选择弹窗：右下角先只有「获取模型」，取回列表并点选一条后原位变成「确定」。
 * 列表最多露出 [MODEL_ROWS_VISIBLE] 行，其余上下滑动。
 */
@Composable
private fun ShowComposeDialogScope.ApiModelPickerContent(
    config: AiApiConfig,
    current: String,
    autoFetch: Boolean,
    onPicked: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf("") }

    fun fetch() {
        if (fetching) return
        fetching = true
        fetchError = null
        scope.launch {
            val result = AiModelConnection.fetchModels(config)
            fetching = false
            result.fold(
                onSuccess = { models = it },
                onFailure = { failure -> fetchError = failure.message },
            )
        }
    }

    // 配置页的默认模型行：点进来就直接开始拉，省掉再点一次「获取模型」
    LaunchedEffect(Unit) { if (autoFetch) fetch() }

    AlertDialogContent(
        title = {
            Text(
                text = stringResource(R.string.ui_group_ai_model_picker_title),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (models.isEmpty()) {
                    if (fetching) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(4.dp),
                                strokeWidth = 3.dp,
                            )
                            Text(
                                text = stringResource(R.string.ui_group_ai_settings_fetching),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        val error = fetchError
                        Text(
                            text = if (error == null) {
                                stringResource(R.string.ai_reply_model_fetch_hint)
                            } else {
                                stringResource(R.string.ui_group_ai_settings_fetch_failed_toast, error)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (error == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.ui_group_ai_model_picker_summary, models.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = (LIST_ITEM_MIN_HEIGHT_DP * MODEL_ROWS_VISIBLE).dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        models.forEach { name ->
                            BaseWidget(
                                iconPlaceholder = false,
                                title = name,
                                selected = name == picked || (picked.isEmpty() && name == current),
                                onClick = { picked = name },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (picked.isEmpty()) {
                Button(
                    onClick = { fetch() },
                    enabled = !fetching,
                ) {
                    Text(
                        stringResource(
                            if (fetching) R.string.ui_group_ai_settings_fetching
                            else R.string.ui_group_ai_settings_fetch_models,
                        ),
                    )
                }
            } else {
                Button(
                    onClick = {
                        onPicked(picked)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.dialog_confirm)) }
            }
        },
    )
}
