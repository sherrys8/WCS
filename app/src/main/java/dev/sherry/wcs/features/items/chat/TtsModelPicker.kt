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
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.BreezeTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MODEL_ROWS_VISIBLE = 6
private const val LIST_ITEM_MIN_HEIGHT_DP = 56

/**
 * 选 Breeze 的合成模型。
 *
 * 与对话模型选择器同构：点进来直接拉一次（Key 就在本页填好了，不该再点一次按钮），
 * 点一条 → 右下角原位变「确定」。拉不到时留「重新获取」，手填模型名走页面上的另一行。
 */
fun showBreezeModelPicker(
    context: Context,
    key: String,
    current: String,
    onPicked: (String) -> Unit,
) {
    showComposeDialog(context) { BreezeModelPickerContent(key, current, onPicked) }
}

@Composable
private fun ShowComposeDialogScope.BreezeModelPickerContent(
    key: String,
    current: String,
    onPicked: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf("") }

    fun fetch() {
        if (fetching) return
        if (key.isBlank()) {
            fetchError = localizedChatString(R.string.ai_reply_tts_missing_credentials)
            return
        }
        fetching = true
        fetchError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BreezeTtsClient.models(key) }
            }
            fetching = false
            result.fold(
                onSuccess = { list -> models = list.map { it.id to it.name.ifBlank { it.id } } },
                onFailure = { failure -> fetchError = failure.message },
            )
        }
    }

    LaunchedEffect(Unit) { fetch() }

    AlertDialogContent(
        title = {
            Text(
                text = stringResource(R.string.tts_cfg_model),
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
                                stringResource(R.string.tts_model_fetch_hint)
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
                        models.forEach { (id, name) ->
                            BaseWidget(
                                iconPlaceholder = false,
                                title = name,
                                description = if (id == name) null else id,
                                selected = id == picked || (picked.isEmpty() && id == current),
                                onClick = { picked = id },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (picked.isEmpty()) {
                Button(onClick = { fetch() }, enabled = !fetching) {
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
