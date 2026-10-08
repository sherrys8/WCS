package dev.sherry.wcs.features.items.system

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import com.tencent.mm.ui.LauncherUI
import dev.sherry.wcs.R
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.api.ui.WeHomeScreenPopupMenuApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.items.home_screen_menu.localizedHomeMenuString
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.utils.QrCodeIcon
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.HookParam
import dev.sherry.wcs.utils.nul
import dev.sherry.wcs.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge

object QrCodeRecord : ClickableFeature(), IResolveDex, WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "二维码扫描记录"
    override val nameRes = R.string.feature_qr_code_record_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_qr_code_record_description

    private const val KEY_RECORDS = "qr_code_records"
    private const val HOME_MENU_ITEM_ID = 777031

    private const val EXTRA_REPLAY = "dev.sherry.wcs.qr_code_record_replay"

    @Serializable
    data class QrRecord(
        val url: String,
        val time: Long,
        // 早期版本只存 url/time，19 是微信普通的 QR_CODE 类型
        val codeType: Int = 19,
        val codeVersion: Int = 0,
    )

    var showInHomeMenu by prefOption("qr_code_record_home_menu_enabled", false)
    private var prefRecords by prefOption(KEY_RECORDS, nul<String>())
    private val records = mutableListOf<QrRecord>()
    private var loaded = false

    override fun onEnable() {
        val codeTypeIndex = if (methodQBarString.method.parameterCount == 16) 6 else 5
        methodQBarString.hookBefore {
            // 宿主识别 Activity 会把自身原样透传给这个 handler
            if ((args[0] as Activity).intent.getBooleanExtra(EXTRA_REPLAY, false)) return@hookBefore
            val content = args[1] as String? ?: return@hookBefore
            record(content, args[codeTypeIndex] as Int, args[codeTypeIndex + 1] as Int)
        }
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    @Synchronized
    private fun record(content: String, codeType: Int, codeVersion: Int) {
        if (content.isEmpty()) return
        loadRecords()
        records.add(0, QrRecord(content, System.currentTimeMillis(), codeType, codeVersion))
        prefRecords = DefaultJson.encodeToString(records.toList())
    }

    override fun onClick(context: ComponentActivity) {
        context.startActivity(Intent(context, QrCodeRecordSettingsActivity::class.java))
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        if (!showInHomeMenu) return emptyList()
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                HOME_MENU_ITEM_ID,
                localizedHomeMenuString(R.string.qr_code_record_home_menu_title),
                QrCodeIcon,
            ) {
                val activity = LauncherUI.getInstance()!!
                activity.startActivity(Intent(activity, QrCodeRecordSettingsActivity::class.java))
            },
        )
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

    @Synchronized
    fun recordsSnapshot(): List<QrRecord> {
        loadRecords()
        return records.toList()
    }

    @Synchronized
    fun clearAllRecords() {
        records.clear()
        loaded = true
        WePrefs.remove(KEY_RECORDS)
    }

    private fun loadRecords() {
        if (loaded) return
        prefRecords
            ?.let { runCatching { DefaultJson.decodeFromString<List<QrRecord>>(it) }.getOrNull() }
            ?.let { records.addAll(it) }
        loaded = true
    }

    val methodQBarString by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.QBarStringHandler", "key_offline_scan_show_tips")
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
    }
}
