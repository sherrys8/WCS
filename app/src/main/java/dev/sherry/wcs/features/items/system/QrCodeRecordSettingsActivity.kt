package dev.sherry.wcs.features.items.system

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Person
import dev.sherry.wcs.R
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.WcSLocaleProvider
import dev.sherry.wcs.ui.agent.settings.AgentSettingsScaffold
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.Button
import dev.sherry.wcs.ui.content.IconButton
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseItemContainer
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.ui.utils.theme.ModuleTheme
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.copyToClipboard
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.formatEpoch
import dev.sherry.wcs.utils.openInSystem

@Keep
class QrCodeRecordSettingsActivity : ComponentActivity() {
    private var records by mutableStateOf(emptyList<QrCodeRecord.QrRecord>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WcSLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    QrCodeRecordSettingsScreen(
                        records = records,
                        onFinish = ::finish,
                        onClear = {
                            QrCodeRecord.clearAllRecords()
                            records = emptyList()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        records = QrCodeRecord.recordsSnapshot()
    }
}

@Composable
private fun QrCodeRecordSettingsScreen(
    records: List<QrCodeRecord.QrRecord>,
    onFinish: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var homeMenuEnabled by remember { mutableStateOf(QrCodeRecord.showInHomeMenu) }

    AgentSettingsScaffold(
        title = stringResource(R.string.feature_qr_code_record_name),
        onBack = onFinish,
        actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.More_vert,
                        contentDescription = stringResource(R.string.qr_code_record_menu),
                    )
                }
                DropdownMenuPopup(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                        SelectableDropdownMenuItem(
                            selected = homeMenuEnabled,
                            onClick = {
                                homeMenuEnabled = !homeMenuEnabled
                                QrCodeRecord.showInHomeMenu = homeMenuEnabled
                                menuExpanded = false
                            },
                            text = { Text(stringResource(R.string.qr_code_record_home_menu_enabled)) },
                            shapes = MenuDefaults.itemShape(0, 1),
                        )
                    }
                }
            }
        },
    ) {
        item {
            SegmentedColumn {
                item {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.History,
                        title = stringResource(R.string.qr_code_record_count, records.size),
                        description = stringResource(R.string.qr_code_record_native_open_description),
                        selected = true,
                        trailingContent = {
                            IconButton(
                                onClick = { showClearConfirm(context, onClear) },
                                enabled = records.isNotEmpty(),
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Delete_sweep,
                                    contentDescription = stringResource(R.string.action_clear),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
            }
        }

        if (records.isEmpty()) {
            item {
                SegmentedColumn {
                    item {
                        BaseItemContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.system_qr_code_record_empty),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = stringResource(R.string.qr_code_record_empty_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(records, key = { index, record -> "${record.time}:$index" }) { _, record ->
                QrRecordCard(record)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QrRecordCard(record: QrCodeRecord.QrRecord) {
    val context = LocalContext.current
    val activity = LocalActivity.current!!
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val openFailedMessage = stringResource(R.string.qr_code_record_open_failed)
    var expanded by rememberSaveable(record.url, record.time) { mutableStateOf(false) }
    var truncated by remember(record.url) { mutableStateOf(false) }
    val uri = remember(record.url) { record.url.toUri() }
    val (icon, typeRes) = when {
        uri.host.equals("u.wechat.com", ignoreCase = true) ->
            MaterialSymbols.Outlined.Person to R.string.qr_code_record_type_contact

        uri.host.equals("wx.tenpay.com", ignoreCase = true) ||
            record.url.startsWith("weixin://wxpay") ||
            record.url.startsWith("wxp://") ->
            MaterialSymbols.Outlined.Payments to R.string.qr_code_record_type_payment

        else -> MaterialSymbols.Outlined.Info to R.string.qr_code_record_type_other
    }

    SegmentedColumn {
        item {
            BaseWidget(
                icon = icon,
                iconColor = MaterialTheme.colorScheme.primary,
                title = stringResource(typeRes),
                description = formatEpoch(record.time, true),
                trailingContent = {
                    if (!uri.scheme.isNullOrEmpty()) {
                        IconButton(onClick = { uri.openInSystem(context, true) }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Open_in_new,
                                contentDescription = stringResource(R.string.system_qr_code_record_open_in_system),
                            )
                        }
                    }
                },
            )
        }
        item {
            BaseItemContainer {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = record.url,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
                        )
                    }
                    if (truncated || expanded) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                stringResource(
                                    if (expanded) R.string.qr_code_record_collapse
                                    else R.string.qr_code_record_expand,
                                ),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            onClick = {
                                runCatching { QrCodeRecord.openInWeChat(activity, record) }
                                    .onFailure {
                                        WeLogger.e("QrCodeRecord", "Failed to open native scan handler", it)
                                        showToast(context, openFailedMessage)
                                    }
                            },
                        ) { Text(stringResource(R.string.system_qr_code_record_open)) }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                copyToClipboard(context, record.url)
                                showToast(context, copiedMessage)
                            },
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Content_copy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.system_qr_code_record_copy))
                        }
                    }
                }
            }
        }
    }
}

private fun showClearConfirm(context: Context, onClear: () -> Unit) {
    showComposeDialog(context) {
        AlertDialogContent(
            title = { Text(stringResource(R.string.action_clear)) },
            text = { Text(stringResource(R.string.system_qr_code_record_clear_description)) },
            dismissButton = {
                TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClear()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.action_clear)) }
            },
        )
    }
}
