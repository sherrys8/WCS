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
    )

    private class Bucket(
        val account: String,
        val talker: String,
        val channel: Channel<Inbound>,
        var job: Job?,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val requestGate = Semaphore(1)

    @Volatile
    private var version = 0

    /** 供任务侧比对：任务领取时的版本与当前不一致就作废。 */
    val currentVersion: Int get() = version

    @Volatile
    private var closed = false

    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    fun submit(snapshot: Inbound) {
        if (closed) return
        val key = "${snapshot.account}|${snapshot.talker}"
        val bucket = buckets.computeIfAbsent(key) {
            Bucket(snapshot.account, snapshot.talker, Channel(Channel.UNLIMITED), null).also { created ->
                created.job = scope.launch { loop(created) }
            }
        }
        bucket.channel.trySend(snapshot)
    }

    /** 本人在该会话发言：作废在途任务，清掉缓冲，只影响这一个会话。 */
    fun onTakeover(talker: String, account: String) {
        version++
        val bucket = buckets.remove("$account|$talker") ?: return
        runCatching { bucket.channel.close() }
        bucket.job?.cancel()
    }

    fun markCooldown(talker: String, account: String) {
        val timing = timing()
        cooldownUntil["$account|$talker"] = System.currentTimeMillis() + timing.cooldownMs
    }

    fun shutdown() {
        closed = true
        version++
        buckets.values.forEach {
            runCatching { it.channel.close() }
            it.job?.cancel()
        }
        buckets.clear()
        cooldownUntil.clear()
    }

    private suspend fun loop(bucket: Bucket) {
        while (scope.isActive && !closed) {
            val first = bucket.channel.receiveCatching().getOrNull() ?: break
            val items = ArrayList<Inbound>(MAX_BATCH)
            items += first
            val timing = timing()
            var lastAdded = System.currentTimeMillis()
            val startedAt = lastAdded
            while (items.size < MAX_BATCH) {
                val debounceLeft = timing.debounceMs - (System.currentTimeMillis() - lastAdded)
                val maxLeft = timing.maxWaitMs - (System.currentTimeMillis() - startedAt)
                val wait = minOf(debounceLeft, maxLeft)
                if (wait <= 0) break
                val next = withTimeoutOrNull(wait) { bucket.channel.receiveCatching().getOrNull() }
                if (next == null) break
                items += next
                lastAdded = System.currentTimeMillis()
                if (items.size >= MAX_PENDING) break
            }

            val key = "${first.account}|${first.talker}"
            val cooldown = cooldownUntil[key] ?: 0L
            val now = System.currentTimeMillis()
            if (now < cooldown) delay(cooldown - now)

            val batch = Batch(first.account, first.talker, items, version)
            requestGate.withPermit {
                if (batch.version != version) return@withPermit
                if (closed) return@withPermit
                runCatching { handler(batch) }
                    .onFailure { WeLogger.w(TAG, "batch handler failed: ${it.message}") }
            }
        }
        buckets.remove("${bucket.account}|${bucket.talker}")
    }

    private companion object {
        const val TAG = "AutoReplyScheduler"
        const val MAX_BATCH = 20
        const val MAX_PENDING = 40
    }
}
