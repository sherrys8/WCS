package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.fs.KnownPaths
import dev.sherry.wcs.utils.fs.moveReplacing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.io.path.div

/**
 * 去重账本。
 *
 * 两件事：内存里「这条消息已被领取」的原子标记，磁盘上一份任务状态（重启后不重复回旧消息）。
 * 内存表只负责一次运行期内不重入；磁盘账本负责跨启动。两者都只降概率，做不到 exactly-once。
 */
internal object AutoReplyLedger {

    private const val TAG = "AutoReplyLedger"
    private const val MEMORY_CAPACITY = 2000
    private const val DISK_TASKS = 500
    private const val REBUILD_WINDOW_MS = 24L * 3600 * 1000

    /** 插入序淘汰：`claim` 先查后插在同一把锁里，等价于原子 `putIfAbsent`。 */
    private val claimed = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MEMORY_CAPACITY
    }

    private val lock = Any()
    private val tasks = ArrayList<Task>()
    private var loaded = false

    private val stateDir: File get() = (KnownPaths.moduleData / "auto_reply").toFile()
    private val stateFile: File get() = File(stateDir, "state.json")

    data class Task(
        val id: String,
        val account: String,
        val talker: String,
        val batch: List<String>,
        val state: String,
        val at: Long,
        val chars: Int,
        val model: String,
        val note: String,
    )

    /** 返回 true 表示本进程第一次领取这条消息。 */
    fun claim(key: String, at: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (claimed.containsKey(key)) return false
        if (claimed.size >= MEMORY_CAPACITY) {
            val iterator = claimed.keys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        claimed[key] = at
        true
    }

    /** 客户端 id 作为别名一起写入：补齐服务端 id 后不会再处理一次。 */
    fun alias(key: String, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        claimed.putIfAbsent(key, at)
        Unit
    }

    fun isClaimed(key: String): Boolean = synchronized(lock) { claimed.containsKey(key) }

    fun ensureLoaded() = synchronized(lock) {
        if (loaded) return
        loaded = true
        if (!stateFile.isFile) return
        runCatching {
            val root = JSONObject(stateFile.readText())
            val array = root.optJSONArray("tasks") ?: JSONArray()
            val cutoff = System.currentTimeMillis() - REBUILD_WINDOW_MS
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val task = Task(
                    id = obj.optString("id"),
                    account = obj.optString("account"),
                    talker = obj.optString("talker"),
                    batch = buildList {
                        val list = obj.optJSONArray("batch") ?: JSONArray()
                        for (j in 0 until list.length()) add(list.optString(j))
                    },
                    state = obj.optString("state"),
                    at = obj.optLong("at"),
                    chars = obj.optInt("chars"),
                    model = obj.optString("model"),
                    note = obj.optString("note"),
                )
                tasks += task
                if (task.at >= cutoff) {
                    for (key in task.batch) {
                        claimed.putIfAbsent("${task.account}|${task.talker}|$key", task.at)
                    }
                }
            }
        }.onFailure { WeLogger.w(TAG, "failed to read state.json", it) }
    }

    fun record(task: Task) = synchronized(lock) {
        tasks += task
        while (tasks.size > DISK_TASKS) tasks.removeAt(0)
        writeLocked()
    }

    fun recent(limit: Int, talker: String? = null): List<Task> = synchronized(lock) {
        tasks.asReversed().asSequence()
            .filter { talker == null || it.talker == talker }
            .take(limit)
            .toList()
    }

    fun clear() = synchronized(lock) {
        tasks.clear()
        claimed.clear()
        runCatching { stateFile.delete() }
        Unit
    }

    /** 临时文件 + rename：写到一半被杀不会留下半份 JSON。 */
    private fun writeLocked() {
        runCatching {
            stateDir.mkdirs()
            val array = JSONArray()
            for (task in tasks) {
                array.put(
                    JSONObject()
                        .put("id", task.id)
                        .put("account", task.account)
                        .put("talker", task.talker)
                        .put("batch", JSONArray(task.batch))
                        .put("state", task.state)
                        .put("at", task.at)
                        .put("chars", task.chars)
                        .put("model", task.model)
                        .put("note", task.note),
                )
            }
            val payload = JSONObject().put("version", 1).put("tasks", array).toString()
            val temp = File(stateDir, "state.json.tmp")
            temp.writeText(payload)
            temp.toPath().moveReplacing(stateFile.toPath())
        }.onFailure { WeLogger.w(TAG, "failed to write state.json", it) }
    }
}
