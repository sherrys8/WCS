package dev.sherry.wcs.features.items.system

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Globe
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Shopping_cart
import dev.sherry.wcs.R
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.IconButton
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.copyToClipboard
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.formatEpoch
import dev.sherry.wcs.utils.nul
import dev.sherry.wcs.utils.openInSystem
import dev.sherry.wcs.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge

object QrCodeRecord : ClickableFeature(), IResolveDex {

    override val technicalId = "二维码扫描记录"
    override val nameRes = R.string.feature_qr_code_record_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_qr_code_record_description

    private const val TAG = "QrCodeRecord"
    private const val KEY_RECORDS = "qr_code_records"

    /** 我们自己回放给扫码流程时挂的标记，hook 见到它就跳过，避免把回放记成一次新扫码 */
    private const val EXTRA_REPLAY = "dev.sherry.wcs.qr_code_record_replay"

    @Serializable
    data class QrRecord(
        val url: String,
        val time: Long,
        // 老记录只有 url/time；19 是微信普通 QR_CODE 类型
        val codeType: Int = 19,
        val codeVersion: Int = 0,
    )

    private val records = mutableListOf<QrRecord>()
    private var prefRecords by prefOption(KEY_RECORDS, nul<String>())
    private var loaded = false

    override fun onEnable() {
        // 新旧宿主的 handleCode 参数个数不同，codeType/codeVersion 位置随之位移
        val codeTypeIndex = if (methodQBarString.method.parameterCount == 16) 6 else 5
        methodQBarString.hookBefore {
            if ((args[0] as Activity).intent.getBooleanExtra(EXTRA_REPLAY, false)) return@hookBefore
            val content = args[1] as String? ?: return@hookBefore
            handleUrl(content, args[codeTypeIndex] as Int, args[codeTypeIndex + 1] as Int)
        }
    }

    private fun handleUrl(url: String, codeType: Int, codeVersion: Int) {
        if (!loaded) {
            loadRecords()
            loaded = true
        }

        records.add(0, QrRecord(url, System.currentTimeMillis(), codeType, codeVersion))
        WeLogger.i(TAG, "added $url")
        saveRecords()
    }

    /**
     * 走微信自己的识别流程：这个宿主 Activity 会发布 DealQBarStrEvent 并管理结果/取消，
     * 好友码、群码、支付码、小程序码都能落到对应功能；直接丢给 WebViewUI 只会开网页。
     */
    fun openInWeChat(activity: Activity, record: QrRecord) {
        activity.startActivity(
            Intent().setClassName(
                HostInfo.packageName,
                "com.tencent.mm.plugin.webview.stub.WebviewScanImageActivity",
            )
                .putExtra("key_string_for_scan", record.url)
                .putExtra("key_codetype_for_scan", record.codeType)
                .putExtra("key_codeversion_for_scan", record.codeVersion)
                .putExtra(EXTRA_REPLAY, true),
        )
    }

    override fun onClick(context: ComponentActivity) {
        if (!loaded) {
            loadRecords()
            loaded = true
        }

        showComposeDialog(context) {
            var list by remember { mutableStateOf(records.toList()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_qr_code_record_name)) },
                text = {
                    if (list.isEmpty()) {
                        Text(
                            text = stringResource(R.string.system_qr_code_record_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(list, key = { "${it.url}${it.time}" }) { record ->
                                val (icon, tint) = getQrTypeConfig(record.url)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                ) {
                                    // Header row: icon badge + timestamp & url
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp, end = 12.dp)
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(tint.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = tint,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = formatEpoch(record.time, true),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                text = record.url,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // Action buttons, end-aligned below content
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        IconButton({
                                            copyToClipboard(context, record.url)
                                            showToast(
                                                context,
                                                context.localizedSystemString(R.string.copied_to_clipboard)
                                            )
                                        }) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Content_copy,
                                                contentDescription = stringResource(
                                                    R.string.system_qr_code_record_copy
                                                ),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton({
                                            openInWeChat(context, record)
                                        }) {
                                            Icon(
                                                imageVector =
                                                    if (LinkExternalAppJump.isEnabled) MaterialSymbols.Outlined.Open_in_new
                                                    else MaterialSymbols.Outlined.Globe,
                                                contentDescription = stringResource(
                                                    R.string.system_qr_code_record_open
                                                ),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (!LinkExternalAppJump.isEnabled) {
                                            IconButton({
                                                record.url.toUri().openInSystem(context, true)
                                            }) {
                                                Icon(
                                                    imageVector = MaterialSymbols.Outlined.Open_in_new,
                                                    contentDescription = stringResource(
                                                        R.string.system_qr_code_record_open_in_system
                                                    ),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton({
                        records.clear()
                        list = emptyList()
                        clearRecords()
                    }) { Text(stringResource(R.string.action_clear)) }
                },
                confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } }
            )
        }
    }

    /**
     * Determines the icon presentation and branding color based on URL targets.
     */
    @Composable
    private fun getQrTypeConfig(url: String): Pair<ImageVector, Color> {
        return when {
            url.startsWith("https://u.wechat.com") -> {
                MaterialSymbols.Outlined.Person to Color(0xFF07C160)
            }

            url.startsWith("https://wx.tenpay.com") || url.startsWith("weixin://wxpay") -> {
                MaterialSymbols.Outlined.Shopping_cart to Color(0xFFFDAE17)
            }

            else -> {
                MaterialSymbols.Outlined.Info to MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    }

    private fun saveRecords() {
        prefRecords = DefaultJson.encodeToString(records.toList())
    }

    private fun loadRecords() {
        records.clear()
        prefRecords
            ?.let { runCatching { DefaultJson.decodeFromString<List<QrRecord>>(it) }.getOrNull() }
            ?.let { records.addAll(it) }
    }

    private fun clearRecords() {
        WePrefs.remove(KEY_RECORDS)
    }

    val methodQBarString by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.QBarStringHandler", "key_offline_scan_show_tips")
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
    }
}
