package dev.sherry.wcs.features.items.system

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_box
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Code
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Extension
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Key
import com.composables.icons.materialsymbols.outlined.Language
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Newspaper
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Qr_code_scanner
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Shield
import com.composables.icons.materialsymbols.outlined.Smart_display
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
    private var rules by mutableStateOf(emptyList<QrCodeRecord.Rule>())

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
                        rules = rules,
                        onRefresh = ::refresh,
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
        refresh()
    }

    private fun refresh() {
        records = QrCodeRecord.recordsSnapshot()
        rules = QrCodeRecord.rulesSnapshot()
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
    rules: List<QrCodeRecord.Rule>,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var homeMenuEnabled by remember { mutableStateOf(QrCodeRecord.showInHomeMenu) }
    var viewMode by rememberSaveable { mutableIntStateOf(VIEW_ALL) }
    var query by rememberSaveable { mutableStateOf("") }

    val keyword = query.trim().lowercase()
    val visible = records.filter { record ->
        val category = QrCodeRecord.categoryOf(record.url)
        when {
            viewMode == VIEW_CATEGORY && category == null -> false
            viewMode == VIEW_FAVORITE && !record.favorite -> false
            keyword.isEmpty() -> true
            else -> record.url.lowercase().contains(keyword) ||
                category?.lowercase()?.contains(keyword) == true
        }
    }

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
                            shapes = MenuDefaults.itemShape(0, 2),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qr_code_record_rules_menu)) },
                            onClick = {
                                menuExpanded = false
                                showRulesDialog(context, onRefresh)
                            },
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

        item {
            SegmentedColumn {
                item {
                    BaseItemContainer {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = viewMode == VIEW_ALL,
                                    onClick = { viewMode = VIEW_ALL },
                                    label = { Text(stringResource(R.string.qr_code_record_view_all)) },
                                )
                                FilterChip(
                                    selected = viewMode == VIEW_CATEGORY,
                                    onClick = { viewMode = VIEW_CATEGORY },
                                    label = { Text(stringResource(R.string.qr_code_record_view_category)) },
                                )
                                FilterChip(
                                    selected = viewMode == VIEW_FAVORITE,
                                    onClick = { viewMode = VIEW_FAVORITE },
                                    label = { Text(stringResource(R.string.qr_code_record_view_favorite)) },
                                )
                            }
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.qr_code_record_search_hint)) },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Close,
                                                contentDescription = stringResource(R.string.dialog_cancel),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyCard(
                    viewMode = viewMode,
                    hasRecords = records.isNotEmpty(),
                    hasRules = rules.isNotEmpty(),
                    hasQuery = keyword.isNotEmpty(),
                    onAddRule = { showRulesDialog(context, onRefresh) },
                )
            }
        } else {
            items(visible, key = { "${it.time}:${it.url}" }) { record ->
                QrRecordCard(
                    record = record,
                    category = QrCodeRecord.categoryOf(record.url),
                    onRefresh = onRefresh,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun EmptyCard(
    viewMode: Int,
    hasRecords: Boolean,
    hasRules: Boolean,
    hasQuery: Boolean,
    onAddRule: () -> Unit,
) {
    val titleRes: Int
    val descriptionRes: Int
    when {
        !hasRecords -> {
            titleRes = R.string.system_qr_code_record_empty
            descriptionRes = R.string.qr_code_record_empty_description
        }

        viewMode == VIEW_CATEGORY && !hasRules -> {
            titleRes = R.string.qr_code_record_rules_empty
            descriptionRes = R.string.qr_code_record_rules_hint
        }

        else -> {
            titleRes = R.string.qr_code_record_no_match
            descriptionRes = R.string.qr_code_record_no_match_description
        }
    }

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
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(descriptionRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (viewMode == VIEW_CATEGORY && !hasRules) {
                        Button(onClick = onAddRule) {
                            Text(stringResource(R.string.qr_code_record_rule_add))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 按内容判类型：先具体协议、再域名特征、最后才落到网页/通用，顺序即优先级。
 * 码类型只认宿主解码器给的结果——微信自家码有专门的 codeType/codeName，
 * 比按域名猜可靠，也覆盖那些没有域名的加密串码。
 */
private fun classifyQrContent(record: QrCodeRecord.QrRecord): Pair<ImageVector, Int> {
    val url = record.url
    val uri = url.toUri()
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path?.lowercase().orEmpty()
    return when {
        // 宿主标记为微信自家码（WX_CODE）
        record.codeType == WX_CODE_TYPE || record.codeName.contains("WX") ->
            MaterialSymbols.Outlined.Extension to R.string.qr_code_record_type_miniprogram

        host == "servicewechat.com" ->
            MaterialSymbols.Outlined.Extension to R.string.qr_code_record_type_miniprogram

        host == "u.wechat.com" || host == "weixin.qq.com" && path.startsWith("/r/") ||
            url.startsWith("weixin://contacts/profile/") ->
            MaterialSymbols.Outlined.Person to R.string.qr_code_record_type_contact

        // wxp://f2f0… 是面对面收款码，不是小程序码
        url.startsWith("wxp://") || url.startsWith("weixin://wxpay") ||
            host == "wx.tenpay.com" || host == "payapp.weixin.qq.com" ->
            MaterialSymbols.Outlined.Payments to R.string.qr_code_record_type_payment

        host == "weixin.qq.com" && path.startsWith("/g/") ->
            MaterialSymbols.Outlined.Groups to R.string.qr_code_record_type_group

        host == "work.weixin.qq.com" || url.startsWith("wework://") ->
            MaterialSymbols.Outlined.Account_box to R.string.qr_code_record_type_work

        host == "open.weixin.qq.com" && path.contains("qrconnect") ||
            host == "login.weixin.qq.com" ->
            MaterialSymbols.Outlined.Key to R.string.qr_code_record_type_login

        // open 域下剩下的都是公众号场景码
        host == "open.weixin.qq.com" || host == "api.weixin.qq.com" ||
            host == "mp.weixin.qq.com" ->
            MaterialSymbols.Outlined.Newspaper to R.string.qr_code_record_type_official_account

        host == "channels.weixin.qq.com" || host == "finder.video.qq.com" ->
            MaterialSymbols.Outlined.Smart_display to R.string.qr_code_record_type_channels

        host == "weixin110.qq.com" || host == "support.weixin.qq.com" ->
            MaterialSymbols.Outlined.Shield to R.string.qr_code_record_type_security

        host == "game.weixin.qq.com" || host == "store.weixin.qq.com" ->
            MaterialSymbols.Outlined.Info to R.string.qr_code_record_type_service

        url.startsWith("weixin://") ->
            MaterialSymbols.Outlined.Code to R.string.qr_code_record_type_scheme

        url.startsWith("http://") || url.startsWith("https://") ->
            MaterialSymbols.Outlined.Language to R.string.qr_code_record_type_web

        else -> MaterialSymbols.Outlined.Qr_code_scanner to R.string.qr_code_record_type_other
    }
}

@Composable
private fun QrRecordCard(
    record: QrCodeRecord.QrRecord,
    category: String?,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    // CompositionLocal 只能在组合期读，点回调里再取会报「@Composable invocations…」
    val activity = LocalActivity.current!!
    var expanded by rememberSaveable(record.url, record.time) { mutableStateOf(false) }
    var truncated by remember(record.url) { mutableStateOf(false) }
    val uri = remember(record.url) { record.url.toUri() }
    // wxp:// 这类内部协议没有任何 Activity 能接 ACTION_VIEW，只能交给微信自己的识别流程；
    // 而识别流程要求认得出 handleCode 的参数形状，认不出来就只留复制。
    val isWebUrl = uri.scheme == "http" || uri.scheme == "https"
    val canOpenInWeChat = QrCodeRecord.replaySupported
    val (icon, typeRes) = classifyQrContent(record)

    SegmentedColumn {
        item {
            BaseWidget(
                icon = icon,
                iconColor = MaterialTheme.colorScheme.primary,
                title = stringResource(typeRes),
                description = formatEpoch(record.time, true) +
                    (category?.let { " · $it" } ?: ""),
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                QrCodeRecord.toggleFavorite(record.time)
                                onRefresh()
                            },
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Favorite,
                                contentDescription = stringResource(
                                    if (record.favorite) R.string.qr_code_record_unfavorite
                                    else R.string.qr_code_record_favorite,
                                ),
                                tint = if (record.favorite) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
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
                        if (canOpenInWeChat) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (!QrCodeRecord.openInWeChat(activity, record)) {
                                        showToast(
                                            context,
                                            context.localizedSystemString(
                                                R.string.qr_code_record_open_failed,
                                            ),
                                        )
                                    }
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

private fun showRulesDialog(context: Context, onSaved: () -> Unit) {
    showComposeDialog(context) {
        RulesDialogContent(onDismiss = onDismiss, onSaved = onSaved)
    }
}

@Composable
private fun RulesDialogContent(onDismiss: () -> Unit, onSaved: () -> Unit) {
    var rules by remember { mutableStateOf(QrCodeRecord.rulesSnapshot()) }
    var name by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }

    AlertDialogContent(
        title = { Text(stringResource(R.string.qr_code_record_rules_menu)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.qr_code_record_rules_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rules.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = rule.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = rule.prefix,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                QrCodeRecord.removeRule(rule.prefix)
                                rules = QrCodeRecord.rulesSnapshot()
                                onSaved()
                            },
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Delete_sweep,
                                contentDescription = stringResource(R.string.action_clear),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.qr_code_record_rule_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.qr_code_record_rule_prefix)) },
                    singleLine = true,
                )
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && prefix.isNotBlank(),
                onClick = {
                    QrCodeRecord.saveRule(name, prefix)
                    name = ""
                    prefix = ""
                    rules = QrCodeRecord.rulesSnapshot()
                    onSaved()
                },
            ) { Text(stringResource(R.string.qr_code_record_rule_add)) }
        },
    )
}

private const val VIEW_ALL = 0
private const val VIEW_CATEGORY = 1
private const val VIEW_FAVORITE = 2

/** 宿主解码器标给微信自家码的类型，普通二维码是 4 或 19 */
private const val WX_CODE_TYPE = 22
