package dev.sherry.wcs.features.items.system

import android.content.Context
import android.content.Intent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Shopping_cart
import com.tencent.mm.plugin.webview.ui.tools.WebViewUI
import dev.sherry.wcs.R
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.WcSLocaleProvider
import dev.sherry.wcs.ui.agent.settings.AgentSettingsScaffold
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.IconButton
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseItemContainer
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.ui.utils.theme.ModuleTheme
import dev.sherry.wcs.utils.android.copyToClipboard
import dev.sherry.wcs.utils.android.isDarkMode
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.formatEpoch
import dev.sherry.wcs.utils.openInSystem

@Keep
class QrCodeRecordActivity : ComponentActivity() {
    private var records by mutableStateOf(emptyList<QrCodeRecord.QrRecord>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = !isDarkMode
                isAppearanceLightNavigationBars = !isDarkMode
            }
        }
        setContent {
            WcSLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    val barColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                    SideEffect {
                        window.statusBarColor = barColor
                        window.navigationBarColor = barColor
                    }
                    QrCodeRecordScreen(
                        records = records,
                        onBack = ::finish,
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

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, QrCodeRecordActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun QrCodeRecordScreen(
    records: List<QrCodeRecord.QrRecord>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var homeMenuEnabled by remember { mutableStateOf(QrCodeRecord.showInHomeMenu) }

    AgentSettingsScaffold(
        title = stringResource(R.string.feature_qr_code_record_name),
        onBack = onBack,
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
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.qr_code_record_count, records.size),
                        description = stringResource(
                            R.string.qr_code_record_native_open_description,
                            QrCodeRecord.MAX_RECORDS,
                        ),
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
            items(records, key = { "${it.time}:${it.url}" }) { record ->
                QrRecordCard(record)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QrRecordCard(record: QrCodeRecord.QrRecord) {
    val context = LocalContext.current
    var expanded by rememberSaveable(record.url, record.time) { mutableStateOf(false) }
    var truncated by remember(record.url) { mutableStateOf(false) }
    val uri = remember(record.url) { record.url.toUri() }
    // wxp://、weixin:// 这类内部协议既没有能接 ACTION_VIEW 的 Activity，也不能安全回放进
    // 扫码流程（缺原始 Bundle 会让微信在小程序启动路径上 NPE），所以只给复制。
    val isWebUrl = uri.scheme == "http" || uri.scheme == "https"
    val (icon, typeRes) = when {
        uri.host.equals("u.wechat.com", ignoreCase = true) ->
            MaterialSymbols.Outlined.Person to R.string.qr_code_record_type_contact

        uri.host.equals("wx.tenpay.com", ignoreCase = true) || record.url.startsWith("weixin://wxpay") ->
            MaterialSymbols.Outlined.Shopping_cart to R.string.qr_code_record_type_payment

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
                    // 外链改道外部 App 时，「在微信中打开」本身就会跳出去，不必再给一个入口
                    if (isWebUrl && !LinkExternalAppJump.isEnabled) {
                        IconButton(onClick = { uri.openInSystem(context, true) }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Open_in_new,
                                contentDescription = stringResource(
                                    R.string.system_qr_code_record_open_in_system,
                                ),
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
                            onTextLayout = { result ->
                                if (!expanded) truncated = result.hasVisualOverflow
                            },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isWebUrl) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    context.startActivity(
                                        Intent(context, WebViewUI::class.java)
                                            .putExtra("rawUrl", record.url),
                                    )
                                },
                            ) { Text(stringResource(R.string.qr_code_record_open_in_wechat)) }
                        }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                copyToClipboard(context, record.url)
                                showToast(
                                    context,
                                    context.localizedSystemString(R.string.copied_to_clipboard),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Content_copy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(width = 8.dp, height = 0.dp))
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
