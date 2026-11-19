package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 每会话一个 actor：缓冲、防抖、串行、全局闸。
 *
 * `submit` 只在宿主 DB 线程做一次 `trySend`，绝不做查询或 IO；真正的活都在各自的协程里。
 */
internal class AutoReplyScheduler(
    private val scope: CoroutineScope,
    private val timing: () -> Timing,
    private val handler: suspend (Batch) -> Unit,
) {

    data class Timing(
        val debounceMs: Long,
        val maxWaitMs: Long,
        val cooldownMs: Long,
        /** 一批最多处理多少条，也是积压上限：超了丢最旧的，只留最新的。 */
        val maxBatch: Int = 10,
    )

    data class Inbound(
        val account: String,
        val talker: String,
        val msgId: Long,
        val msgSvrId: Long,
        val createTime: Long,
        val typeCode: Int,
        val content: String,
        val imagePath: String?,
        val senderWxId: String,
        val senderLabel: String,
        val isAtMe: Boolean,
        val isAnnounceAll: Boolean,
    )

    data class Batch(
        val account: String,
        val talker: String,
        val items: List<Inbound>,
        val version: Int,
        /** 为了给这批腾位置而丢掉的旧消息条数，只用于日志。 */
        val dropped: Int = 0,
    )

    /**
     * 待处理消息放在有界队列里，[signal] 只当「有新消息」的唤醒用、不携带内容。
     * 原来用无界 Channel 装消息，刷屏 200 条就常驻 200 条，而且只能按先进先出
     * 慢慢消化 —— 攒批时想丢掉旧的根本拿不到它们。
     */
    private class Bucket(
        val account: String,
        val talker: String,
    ) {
        val queue = ArrayDeque<Inbound>()
        val signal = Channel<Unit>(Channel.CONFLATED)
        var job: Job? = null
        var dropped = 0
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val requestGate = Semaphore(1)

    /** 每会话一个版本号：本人接管只作废这一个会话，不能牵连别的会话。 */
    private val versions = ConcurrentHashMap<String, Int>()

    fun versionFor(account: String, talker: String): Int = versions["$account|$talker"] ?: 0

    @Volatile
    private var closed = false

    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    /**
     * 最近由本模块发出、还没被数据库回调认领的正文。
     *
     * `sendText` 不返回 msgId，入站回调只能看到 `isSend=1`，分不出「本人手动发的」和
     * 「AI 刚发的」。不区分的话，AI 每发一条都会触发一次接管，把同一会话里刚排队
     * 的下一批作废，表现就是「回了一句之后就不回了」。
     */
    private class Echo(val text: String, val at: Long, val voice: Boolean)

    private val echoes = ConcurrentHashMap<String, MutableList<Echo>>()

    /**
     * 发送成功后登记。语音消息入库时 content 是一段 XML，没法用正文比对，
     * 只能靠「类型 + 时间窗」认领，所以单独标一个 voice。
     */
    fun markSelfSent(
        account: String,
        talker: String,
        text: String,
        voice: Boolean = false,
    ) {
        val key = "$account|$talker"
        val list = echoes.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list += Echo(normalizeEcho(text), System.currentTimeMillis(), voice)
            while (list.size > MAX_ECHOES) list.removeAt(0)
        }
    }

    /**
     * 认领一条回声。命中即消费，避免同一行被匹配两次；超出窗口的条目顺手清掉。
     * 群聊里自己发的正文入库时是 `wxid_x:\n正文`，所以除了全等还允许后缀匹配。
     */
    fun consumeSelfEcho(
        account: String,
        talker: String,
        content: String,
        typeCode: Int,
    ): Boolean {
        val list = echoes["$account|$talker"] ?: return false
        val isVoice = typeCode == 34
        val target = normalizeEcho(content)
        val now = System.currentTimeMillis()
        synchronized(list) {
            val iterator = list.iterator()
            while (iterator.hasNext()) {
                val echo = iterator.next()
                if (now - echo.at > ECHO_WINDOW_MS) {
                    iterator.remove()
                    continue
                }
                val hit = if (isVoice) {
                    echo.voice && now - echo.at <= VOICE_ECHO_WINDOW_MS
                } else {
                    target.isNotBlank() && (target == echo.text || target.endsWith(echo.text))
                }
                if (hit) {
                    iterator.remove()
                    return true
                }
            }
        }
        return false
    }

    private fun normalizeEcho(raw: String): String = raw.replace('\u2005', ' ').trim()

    fun submit(snapshot: Inbound) {
        if (closed) return
        val key = "${snapshot.account}|${snapshot.talker}"
        val cap = timing().maxBatch.coerceAtLeast(1)
        val bucket = buckets.computeIfAbsent(key) {
            Bucket(snapshot.account, snapshot.talker).also { created ->
                created.job = scope.launch { loop(created) }
            }
        }
        synchronized(bucket.queue) {
            bucket.queue.addLast(snapshot)
            // 刷屏：只保留最新的 cap 条。丢的是队头，也就是最早的那些。
            while (bucket.queue.size > cap) {
                bucket.queue.removeFirst()
                bucket.dropped++
            }
        }
        bucket.signal.trySend(Unit)
    }

    /** 本人在该会话发言：作废在途任务，清掉缓冲，只影响这一个会话。 */
    fun onTakeover(talker: String, account: String) {
        val key = "$account|$talker"
        versions[key] = (versions[key] ?: 0) + 1
        val bucket = buckets.remove(key) ?: return
        synchronized(bucket.queue) { bucket.queue.clear() }
        runCatching { bucket.signal.close() }
        bucket.job?.cancel()
    }

    fun markCooldown(talker: String, account: String) {
        val timing = timing()
        cooldownUntil["$account|$talker"] = System.currentTimeMillis() + timing.cooldownMs
    }

    fun shutdown() {
        closed = true
        versions.keys.forEach { versions[it] = (versions[it] ?: 0) + 1 }
        buckets.values.forEach {
            synchronized(it.queue) { it.queue.clear() }
            runCatching { it.signal.close() }
            it.job?.cancel()
        }
        buckets.clear()
        cooldownUntil.clear()
    }

    private fun poll(bucket: Bucket): Inbound? = synchronized(bucket.queue) {
        bucket.queue.removeFirstOrNull()
    }

    /** 队列超过 cap 就丢最旧的，返回本次丢掉的条数（含 submit 时已丢的）。 */
    private fun drainDropped(bucket: Bucket, cap: Int): Int = synchronized(bucket.queue) {
        while (bucket.queue.size > cap) {
            bucket.queue.removeFirst()
            bucket.dropped++
        }
        val total = bucket.dropped
        bucket.dropped = 0
        total
    }

    /**
     * 循环体里任何一处抛出未捕获的异常（`timing()`、`poll`、`delay` 都可能），
     * 都会直接跳出 while。原来 remove 写在 while 之后，异常退出就执行不到，
     * bucket 会留在映射里：job 已经死了、没人收 signal，后续 submit 复用它，
     * 这个会话就永久静默到进程重启。所以摘除动作放进 finally。
     */
    private suspend fun loop(bucket: Bucket): Unit = try {
        while (scope.isActive && !closed) {
            // 第一条：先拿队列里现成的，没有才等唤醒信号
            var head = poll(bucket)
            if (head == null) {
                if (bucket.signal.receiveCatching().getOrNull() == null) break
                head = poll(bucket)
            }
            val first = head ?: continue
            // 版本在收到第一条时就定下：冷却等待期间被接管，这批也就该作废。
            val batchVersion = versionFor(first.account, first.talker)
            val timing = timing()
            val cap = timing.maxBatch.coerceAtLeast(1)
            val items = ArrayList<Inbound>(cap)
            items += first
            var lastAdded = System.currentTimeMillis()
            val startedAt = lastAdded
            while (items.size < cap) {
                val debounceLeft = timing.debounceMs - (System.currentTimeMillis() - lastAdded)
                val maxLeft = timing.maxWaitMs - (System.currentTimeMillis() - startedAt)
                val wait = minOf(debounceLeft, maxLeft)
                if (wait <= 0) break
                val got = withTimeoutOrNull(wait) { bucket.signal.receiveCatching().getOrNull() }
                if (got == null) break
                val next = poll(bucket) ?: continue
                items += next
                lastAdded = System.currentTimeMillis()
            }

            val key = "${first.account}|${first.talker}"
            val cooldown = cooldownUntil[key] ?: 0L
            val now = System.currentTimeMillis()
            if (now < cooldown) delay(cooldown - now)

            // 生成期间进来的消息在 submit 里已经被截到 cap；这里再兜一次，
            // 顺便把丢掉的条数报给日志。
            val dropped = drainDropped(bucket, cap)
            val batch = Batch(first.account, first.talker, items, batchVersion, dropped)
            requestGate.withPermit {
                if (batch.version != versionFor(batch.account, batch.talker)) return@withPermit
                if (closed) return@withPermit
                runCatching { handler(batch) }
                    .onFailure { WeLogger.w(TAG, "batch handler failed: ${it.message}") }
            }
        }
    } finally {
        // 只删自己这一个：接管之后可能已经有新 bucket 顶上来了，
        // 无脑 remove(key) 会把新的那个也踢出映射，留下一个没人管的 actor。
        buckets.remove("${bucket.account}|${bucket.talker}", bucket)
    }

    private companion object {
        const val TAG = "AutoReplyScheduler"
        const val MAX_ECHOES = 8
        const val ECHO_WINDOW_MS = 120_000L
        const val VOICE_ECHO_WINDOW_MS = 15_000L
    }
}
