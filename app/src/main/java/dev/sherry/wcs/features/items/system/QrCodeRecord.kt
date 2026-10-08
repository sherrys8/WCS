package dev.sherry.wcs.features.items.system

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
import dev.sherry.wcs.ui.utils.LinkIcon
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

    /** 记录整串 JSON 存在一个偏好键里，不设上限会越来越肥 */
    const val MAX_RECORDS = 200

    /** 微信识别流程常对同一串连续回调多次，这个窗口内的重复内容只记一条 */
    private const val DUPE_WINDOW_MS = 1_500L

    @Serializable
    data class QrRecord(val url: String, val time: Long)

    var showInHomeMenu by prefOption("qr_code_record_home_menu_enabled", false)
    private var prefRecords by prefOption(KEY_RECORDS, nul<String>())
    private val records = mutableListOf<QrRecord>()
    private var loaded = false
    private var lastRecordUrl = ""
    private var lastRecordAt = 0L

    override fun onEnable() {
        methodQBarString.hookBefore {
            val content = args[1] as String? ?: return@hookBefore
            handleUrl(content)
        }
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    @Synchronized
    private fun handleUrl(url: String) {
        if (!loaded) {
            loadRecords()
            loaded = true
        }

        val now = System.currentTimeMillis()
        if (url == lastRecordUrl && now - lastRecordAt < DUPE_WINDOW_MS) return
        lastRecordUrl = url
        lastRecordAt = now

        records.add(0, QrRecord(url, now))
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
                LinkIcon,
            ) {
                QrCodeRecordActivity.launch(LauncherUI.getInstance()!!)
            },
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
