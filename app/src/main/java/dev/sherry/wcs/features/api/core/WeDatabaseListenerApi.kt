package dev.sherry.wcs.features.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.CancellationSignal
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sherry.wcs.R
import dev.sherry.wcs.constants.Preferences
import dev.sherry.wcs.features.core.ApiFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
object WeDatabaseListenerApi : ApiFeature() {

    override val technicalId = "数据库监听服务"
    override val nameRes = R.string.feature_we_database_listener_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_database_listener_api_description

    fun interface IInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    fun interface IUpdateListener {
        fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int)
    }

    fun interface IQueryListener {
        /**
         * 只有 SQL 文本：绑定参数里可能藏着这条读的主角（FTS 的 `… WHERE aux_index=?`），
         * 需要按人否决一类查询的面上别在这里想办法，改到装配点去做 —— 见
         * `hidecontacts/HideContactsSql.kt` 里 rewriteFtsSql 的那段现场结论。
         */
        fun onQuery(sql: String): String?
    }

    private const val TAG = "WeDatabaseListenerApi"

    private val insertListeners = CopyOnWriteArrayList<IInsertListener>()
    private val updateListeners = CopyOnWriteArrayList<IUpdateListener>()
    private val queryListeners = CopyOnWriteArrayList<IQueryListener>()

    fun addListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.add(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.add(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.add(listener)
        }
    }

    fun removeListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.remove(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.remove(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.remove(listener)
        }
    }

    override fun onEnable() {
        hookDatabaseInsert()
        hookDatabaseUpdate()
        hookDatabaseQuery()
    }

    override fun onDisable() {
        insertListeners.clear()
        updateListeners.clear()
        queryListeners.clear()
    }

    // ==================== 私有辅助方法 ====================

    private fun formatArgs(args: Array<out Any?>): String {
        return args.mapIndexed { index, arg ->
            "arg[$index](${arg?.javaClass?.simpleName ?: "null"})=$arg"
        }.joinToString(", ")
    }

    private fun logWithStack(
        methodName: String,
        table: String,
        args: Array<out Any?>,
        result: Any? = null
    ) {
        if (!Preferences.verboseLog) return

        val argsInfo = formatArgs(args)
        val resultStr = if (result != null) ", result=$result" else ""
        val stackStr = ", stack=${WeLogger.currentStackTrace}"

        WeLogger.logChunkedD(TAG, "[$methodName] table=$table$resultStr, args=[$argsInfo]$stackStr")
    }

    // ==================== Insert Hook ====================

    private fun hookDatabaseInsert() {
        SQLiteDatabase::class.reflekt()
            .firstMethod {
                name = "insertWithOnConflict"
                parameters(String::class, String::class, ContentValues::class, Int::class)
            }.hookAfter {
                try {
                    if (insertListeners.isEmpty()) return@hookAfter

                    val table = args[0] as String
                    val values = args[2] as ContentValues

                    logWithStack("Insert", table, args, result)
                    insertListeners.forEach { it.onInsert(table, values) }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Insert dispatch failed", e)
                }
            }
    }

    // ==================== Update Hook ====================

    private fun hookDatabaseUpdate() {
        listOf(
            "com.tencent.wcdb.compat.SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase"
        ).forEach { className ->
            className.toClass().reflekt()
                .firstMethod {
                    name = "updateWithOnConflict"
                    parameters(
                        String::class,
                        ContentValues::class,
                        String::class,
                        Array<String>::class,
                        Int::class
                    )
                }
                .hookBefore {
                    try {
                        if (updateListeners.isEmpty()) return@hookBefore

                        val table = args[0] as String
                        val values = args[1] as ContentValues
                        val whereClause = args[2] as String?

                        @Suppress("UNCHECKED_CAST")
                        val whereArgs = args[3] as Array<String>?
                        val conflictAlgorithm = args[4] as Int

                        logWithStack("Update", table, args)

                        updateListeners.forEach { it.onUpdate(table, values, whereClause, whereArgs, conflictAlgorithm) }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "update dispatch failed", e)
                    }
                }
        }
    }

    // ==================== Query Hook ====================

    /**
     * 两条读语句的通路都要装，而不是按版本二选一。
     *
     * 宿主有两套 WCDB 入口，各自内部只有一个汇聚点：`wcdb.compat.SQLiteDatabase` 的 2 参 `rawQuery`
     * 直接转调 3 参重载（真机 8.0.76 `com/tencent/wcdb/compat/SQLiteDatabase.smali:1221` → `:1234`），
     * 3 参再走 `core.Handle`；`wcdb.database.SQLiteDatabase` 的 2/3 参 `rawQuery`、4 参
     * `rawQueryWithFactory` 以及 `query()`/`queryWithFactory()` 全部转调 5 参
     * `rawQueryWithFactory`（`com/tencent/wcdb/database/SQLiteDatabase.smali:3647`、`:3670`、`:3693`、
     * `:3585` → `:3627`）。两套互不转调，所以同一条语句不会被分发两次。
     *
     * 为什么必须两套都装：要改写的语句并不都出自同一套。朋友圈 `SnsInfo` 走 compat（真机日志里
     * `hid N contacts from moments feed` 一直是这条路），而全局搜索读的是
     * `MicroMsg.FTS.FTSIndexDB` —— 它的查询方法调的是 database 类的 5 参
     * `rawQueryWithFactory`（`com/tencent/mm/plugin/fts/o.smali:1683`）。早先这里按 `isNewVersion`
     * 只挑一条装，8.0.76 挑中的是 compat，于是搜索语句根本没进过 `onQuery`：不抛异常、不打日志，
     * 表现就是「改写规则写好了却像没生效」。Update 那侧本来就两套都装（见 hookDatabaseUpdate），
     * 这里之前是不对称的。
     */
    private fun hookDatabaseQuery() {
        hookCompatQuery()
        hookCoreQuery()
    }

    /** 钉 3 参重载：2 参那条转调进来，所以一条就覆盖 compat 的全部读语句。 */
    private fun hookCompatQuery() {
        com.tencent.wcdb.compat.SQLiteDatabase::class.reflekt()
            .firstMethod {
                name = "rawQuery"
                parameters(String::class, Array<Any>::class, CancellationSignal::class)
            }
            .hookBefore {
                try {
                    if (queryListeners.isEmpty()) return@hookBefore

                    val sql = args[0] as? String ?: return@hookBefore
                    var currentSql = sql

                    logWithStack("rawQuery", "N/A", args)

                    queryListeners.forEach { listener ->
                        listener.onQuery(currentSql)?.let { currentSql = it }
                    }

                    if (currentSql != sql) {
                        args[0] = currentSql
                        if (Preferences.verboseLog)
                            WeLogger.d(
                                TAG,
                                "[rawQuery] SQL modified: $sql -> $currentSql, stack=${WeLogger.currentStackTrace}"
                            )
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "compat query dispatch failed", e)
                }
            }
    }

    /** 钉 5 参重载：database 类的 rawQuery/query/queryWithFactory 全都转调它。 */
    private fun hookCoreQuery() {
        SQLiteDatabase::class.reflekt().firstMethod {
            name = "rawQueryWithFactory"
            parameterCount = 5
        }.hookBefore {
            try {
                if (queryListeners.isEmpty()) return@hookBefore

                val sql = args[1] as? String ?: return@hookBefore
                var currentSql = sql

                logWithStack(
                    "rawQueryWithFactory",
                    args[3] as? String ?: "N/A",
                    args
                )

                queryListeners.forEach { listener ->
                    listener.onQuery(currentSql)?.let { currentSql = it }
                }

                if (currentSql != sql) {
                    args[1] = currentSql
                    if (Preferences.verboseLog)
                        WeLogger.d(
                            TAG,
                            "[rawQueryWithFactory] SQL modified: $sql -> $currentSql, stack=${WeLogger.currentStackTrace}"
                        )
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Old version query dispatch failed", e)
            }
        }
    }
}
