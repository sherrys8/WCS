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
import dev.sherry.wcs.ui.utils.QrCodeScannerIcon
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.HookParam
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.nul
import dev.sherry.wcs.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge

object QrCodeRecord : ClickableFeature(), IResolveDex, WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "二维码扫描记录"
    override val nameRes = R.string.feature_qr_code_record_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_qr_code_record_description

    private const val TAG = "QrCodeRecord"
    private const val KEY_RECORDS = "qr_code_records"
    private const val HOME_MENU_ITEM_ID = 777026

    /** 我们自己回放给扫码流程时挂的标记，hook 见到它就跳过，避免把回放记成一次新扫码 */
    private const val EXTRA_REPLAY = "dev.sherry.wcs.qr_code_record_replay"

    /** 记录整串 JSON 存在一个偏好键里，不设上限会越来越肥 */
    const val MAX_RECORDS = 200

    /** 微信识别流程常对同一串连续回调多次，这个窗口内的重复内容只记一条 */
    private const val DUPE_WINDOW_MS = 1_500L

    /**
     * `codeType = 0` 表示这条记录是旧版本存的，不知道微信自己的码类型。
     * 拿默认值去回放会让微信把载荷投错分支（收款码曾被送进小程序启动路径后 NPE），
     * 所以只有记到真实 codeType 的记录才允许「在微信中打开」。
     */
    @Serializable
    data class QrRecord(
        val url: String,
        val time: Long,
        val codeType: Int = 0,
        val codeVersion: Int = 0,
    )

    var showInHomeMenu by prefOption("qr_code_record_home_menu_enabled", false)
    private var prefRecords by prefOption(KEY_RECORDS, nul<String>())
    private val records = mutableListOf<QrRecord>()
    private var loaded = false
    private var lastRecordUrl = ""
    private var lastRecordAt = 0L

    override fun onEnable() {
        // 新旧宿主的 handleCode 参数个数不同，codeType/codeVersion 位置随之位移
        val codeTypeIndex = if (methodQBarString.method.parameterCount == 16) 6 else 5
        methodQBarString.hookBefore {
            if ((args[0] as Activity).intent.getBooleanExtra(EXTRA_REPLAY, false)) return@hookBefore
            val content = args[1] as String? ?: return@hookBefore
            handleUrl(content, args[codeTypeIndex] as Int, args[codeTypeIndex + 1] as Int)
        }
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    @Synchronized
    private fun handleUrl(url: String, codeType: Int, codeVersion: Int) {
        if (!loaded) {
            loadRecords()
            loaded = true
        }

        val now = System.currentTimeMillis()
        if (url == lastRecordUrl && now - lastRecordAt < DUPE_WINDOW_MS) return
        lastRecordUrl = url
        lastRecordAt = now

        records.add(0, QrRecord(url, now, codeType, codeVersion))
        if (records.size > MAX_RECORDS) {
            records.subList(MAX_RECORDS, records.size).clear()
        }
        WeLogger.i(TAG, "added $url")
        saveRecords()
    }

    override fun onClick(context: ComponentActivity) {
        QrCodeRecordActivity.launch(context)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        if (!showInHomeMenu) return emptyList()
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                HOME_MENU_ITEM_ID,
                localizedHomeMenuString(R.string.qr_code_record_home_menu_title),
                QrCodeScannerIcon,
            ) {
                QrCodeRecordActivity.launch(LauncherUI.getInstance()!!)
            },
        )
    }

    /** 只有记到真实 codeType 的记录才回放；旧记录交给调用方隐藏入口 */
    fun canReplayInWeChat(record: QrRecord): Boolean = record.codeType != 0

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
        if (!loaded) {
            loadRecords()
            loaded = true
        }
        return records.toList()
    }

    @Synchronized
    fun clearAllRecords() {
        records.clear()
        loaded = true
        lastRecordUrl = ""
        lastRecordAt = 0L
        WePrefs.remove(KEY_RECORDS)
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

    val methodQBarString by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.QBarStringHandler", "key_offline_scan_show_tips")
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
    }
}
