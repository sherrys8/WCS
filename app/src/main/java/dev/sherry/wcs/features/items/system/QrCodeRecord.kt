package dev.sherry.wcs.features.items.system

import android.app.Activity
import android.os.Bundle
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

    /** 旧记录没存 codeName 时补这个，宿主自己扫出来的普通码就是它 */
    private const val DEFAULT_CODE_NAME = "QR_CODE"

    /** 直调 handleCode 会同步走回我们自己的 hook，这个窗口内的同内容不再记录 */
    private const val REPLAY_GUARD_MS = 3_000L

    @Serializable
    data class QrRecord(
        val url: String,
        val time: Long,
        val codeType: Int = 0,
        val codeVersion: Int = 0,
        val codeName: String = "",
        val source: Int = 0,
        val scene: Int = 0,
    )

    var showInHomeMenu by prefOption("qr_code_record_home_menu_enabled", false)
    private var prefRecords by prefOption(KEY_RECORDS, nul<String>())
    private val records = mutableListOf<QrRecord>()
    private var loaded = false
    private var lastRecordUrl = ""
    private var lastRecordAt = 0L
    private var replayShape: CodeArgShape? = null
    private var replayGuardUrl: String? = null
    private var replayGuardUntil = 0L

    /**
     * `handleCode` 的位置参数形状。新旧宿主在 source 前多一个 int，整段随之位移，
     * 所以按实际参数表判定，不写死下标。
     */
    private class CodeArgShape(
        val spareInt: Int,
        val source: Int,
        val scene: Int,
        val codeName: Int,
        val codeType: Int,
        val codeVersion: Int,
    ) {
        fun describe() = "spare=$spareInt source=$source scene=$scene codeName=$codeName" +
            " codeType=$codeType codeVersion=$codeVersion"
    }

    override fun onEnable() {
        val paramTypes = methodQBarString.method.parameterTypes
        val shape = shapeOf(paramTypes)
        replayShape = shape
        WeLogger.i(TAG, "handleCode arity=${paramTypes.size} shape=${shape?.describe() ?: "unknown"}")
        methodQBarString.hookBefore {
            val content = args[1] as String? ?: return@hookBefore
            if (shape == null) {
                handleUrl(content, 0, 0, "", 0, 0)
                return@hookBefore
            }
            handleUrl(
                content,
                args[shape.codeType] as Int,
                args[shape.codeVersion] as Int,
                args[shape.codeName] as String?,
                args[shape.source] as Int,
                args[shape.scene] as Int,
            )
        }
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    @Synchronized
    private fun handleUrl(
        url: String,
        codeType: Int,
        codeVersion: Int,
        codeName: String?,
        source: Int,
        scene: Int,
    ) {
        if (!loaded) {
            loadRecords()
            loaded = true
        }

        val now = System.currentTimeMillis()
        if (url == replayGuardUrl && now < replayGuardUntil) return
        if (url == lastRecordUrl && now - lastRecordAt < DUPE_WINDOW_MS) return
        lastRecordUrl = url
        lastRecordAt = now

        records.add(0, QrRecord(url, now, codeType, codeVersion, codeName.orEmpty(), source, scene))
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

    /** 参数形状认得出来才谈得上回放，认不出来就别给这个入口 */
    val replaySupported: Boolean
        get() = replayShape != null

    /**
     * 反射直调宿主自己的 `handleCode`：先按声明类造一个 handler（宿主带 tag 的构造优先，
     * 退无参构造），再按实际参数表把 source/scene/codeName/codeType/codeVersion 放回原位，
     * 尾巴上的 Bundle/boolean/int 按类型补默认值。
     *
     * 之前借道 `WebviewScanImageActivity` 会让跳小程序的码在 `appbrand.report` 里 NPE，
     * 因为那条路给不出 codeName 与 handler 上下文。
     */
    fun openInWeChat(activity: Activity, record: QrRecord): Boolean {
        val method = methodQBarString.method
        val shape = replayShape
        if (shape == null) {
            WeLogger.w(TAG, "replay skipped: arity=${method.parameterCount} 参数形状不认识")
            return false
        }

        val codeName = record.codeName.ifEmpty { DEFAULT_CODE_NAME }
        val source = normalizeSource(record.source)
        val args = arrayOfNulls<Any>(method.parameterCount)
        args[0] = activity
        args[1] = record.url
        if (shape.spareInt >= 0) args[shape.spareInt] = 0
        args[shape.source] = source
        args[shape.scene] = if (record.scene > 0) record.scene else replayScene(source)
        args[shape.codeName] = codeName
        args[shape.codeType] = record.codeType
        args[shape.codeVersion] = record.codeVersion
        fillTail(args, method.parameterTypes, shape.codeVersion + 1)

        return try {
            val handler = createHandler()
            replayGuardUrl = record.url
            replayGuardUntil = System.currentTimeMillis() + REPLAY_GUARD_MS
            WeLogger.i(TAG, "replay direct handler=${handler.javaClass.name} args: ${describeArgs(args)}")
            method.invoke(handler, args)
            WeLogger.i(TAG, "replay direct invoked ${record.url}")
            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "replay direct failed arity=${method.parameterCount} args: ${describeArgs(args)}", e)
            false
        }
    }

    /** 宿主的 handler 优先用带 tag 的构造，没有再退无参构造 */
    private fun createHandler(): Any {
        val clazz = methodQBarString.method.declaringClass
        return try {
            val tagged = clazz.getDeclaredConstructor(String::class.java)
            tagged.isAccessible = true
            tagged.newInstance(System.currentTimeMillis().toString())
        } catch (_: NoSuchMethodException) {
            val bare = clazz.getDeclaredConstructor()
            bare.isAccessible = true
            bare.newInstance()
        }
    }

    private fun shapeOf(paramTypes: Array<out Class<*>>): CodeArgShape? {
        fun intAt(i: Int) = i < paramTypes.size && isInt(paramTypes[i])

        if (intAt(2) && intAt(3) && paramTypes.size > 4 && paramTypes[4] == String::class.java &&
            intAt(5) && intAt(6)
        ) {
            return CodeArgShape(-1, 2, 3, 4, 5, 6)
        }
        if (intAt(2) && intAt(3) && intAt(4) && paramTypes.size > 5 && paramTypes[5] == String::class.java &&
            intAt(6) && intAt(7)
        ) {
            return CodeArgShape(2, 3, 4, 5, 6, 7)
        }
        return null
    }

    private fun fillTail(args: Array<Any?>, paramTypes: Array<out Class<*>>, from: Int) {
        var boolIndex = 0
        for (i in from until paramTypes.size) {
            args[i] = when {
                paramTypes[i] == Bundle::class.java -> Bundle().apply {
                    putString("stat_url", "scan_history")
                }

                isBoolean(paramTypes[i]) -> {
                    boolIndex += 1
                    boolIndex > 1
                }

                isInt(paramTypes[i]) -> -1
                paramTypes[i] == Long::class.javaPrimitiveType ||
                    paramTypes[i] == Long::class.javaObjectType -> 0L

                else -> null
            }
        }
    }

    private fun isInt(type: Class<*>) =
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType

    private fun isBoolean(type: Class<*>) =
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType

    private fun normalizeSource(source: Int): Int =
        if (source == 0 || source == 1 || source == 3) source else 0

    /** 宿主按来源决定走哪个场景，回放时要还回去 */
    private fun replayScene(source: Int): Int = when (source) {
        1 -> 34
        3 -> 42
        else -> 4
    }

    /** 检测用：内容只记长度，它已经出现在 added 那行里了 */
    private fun describeArgs(args: Array<Any?>): String = args.mapIndexed { index, arg ->
        when {
            arg == null -> "$index=null"
            index == 1 -> "1=String(len=${(arg as String).length})"
            arg is Int -> "$index=Int($arg)"
            arg is String -> "$index=$arg"
            arg is Bundle -> "$index=Bundle"
            else -> "$index=${arg.javaClass.simpleName}"
        }
    }.joinToString(" ")

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
