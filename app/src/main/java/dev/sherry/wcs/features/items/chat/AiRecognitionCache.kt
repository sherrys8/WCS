package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.api.core.models.WeMessage
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.fs.KnownPaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object AiRecognitionCache {
    private const val TAG = "AiRecognitionCache"
    private const val VERSION = 1
    private val locks = Array(64) { Mutex() }
    private val cleanupLock = Any()
    @Volatile private var lastCleanupDate: LocalDate? = null

    suspend fun getOrRecognize(
        kind: String,
        account: String,
        message: WeMessage,
        profile: ApiProfile,
        prompt: String,
        onCacheHit: () -> Unit = {},
        recognize: suspend () -> String?,
    ): String? = withContext(Dispatchers.IO) {
        fun ensureAccount() {
            check(account.isNotBlank() && WeApi.selfWxId == account) {
                localizedChatString(R.string.ai_reply_account_changed)
            }
        }
        ensureAccount()
        cleanupExpired()
        val identity = when {
            message.msgSvrId > 0L -> "server:${message.msgSvrId}"
            message.msgId > 0L -> "local:${message.msgId}"
            else -> null
        }
        if (identity == null || message.talker.isBlank()) {
            val text = recognize()
            currentCoroutineContext().ensureActive()
            ensureAccount()
            return@withContext text
        }
        val profileFingerprint = digest(JSONArray().apply {
            put(profile.id)
            put(profile.protocol.prefValue)
            put(profile.baseUrl)
            put(profile.apiPath)
            put(profile.apiKey)
            put(profile.defaultModelId)
            put(profile.capability)
            put(profile.provider)
            put(profile.cookie)
        }.toString())
        val key = digest(JSONArray().apply {
            put(VERSION)
            put(kind)
            put(message.talker)
            put(identity)
            put(message.typeCode)
            put(message.createTime)
            put(profileFingerprint)
            put(prompt)
        }.toString())
        val file = KnownPaths.moduleCache.resolve("ai-recognition")
            .resolve(digest(account)).resolve("$key.json").toFile()
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            ensureAccount()
            currentCoroutineContext().ensureActive()
            read(file)?.let { cached ->
                ensureAccount()
                onCacheHit()
                WeLogger.i(TAG, "cache hit: kind=$kind")
                return@withLock cached
            }
            val text = recognize()
            currentCoroutineContext().ensureActive()
            ensureAccount()
            if (!text.isNullOrBlank()) write(file, kind, text)
            text
        }
    }

    private fun expirationCutoffMillis(today: LocalDate, zone: ZoneId): Long =
        today.minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun cleanupExpired() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        if (lastCleanupDate == today) return
        synchronized(cleanupLock) {
            if (lastCleanupDate == today) return
            runCatching {
                val cutoff = expirationCutoffMillis(today, zone)
                val root = KnownPaths.moduleCache.resolve("ai-recognition").toFile()
                root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
                    directory.listFiles()?.forEach { file ->
                        val isCacheFile = file.extension == "json" ||
                            (file.name.startsWith("recognition-") && file.extension == "tmp")
                        if (file.isFile && isCacheFile && file.lastModified() < cutoff && !file.delete()) {
                            WeLogger.w(TAG, "failed to delete expired recognition cache entry")
                        }
                    }
                }
                lastCleanupDate = today
            }.onFailure { WeLogger.w(TAG, "failed to clean expired recognition cache", it) }
        }
    }

    private fun read(file: File): String? {
        val zone = ZoneId.systemDefault()
        if (!file.isFile || file.lastModified() < expirationCutoffMillis(LocalDate.now(zone), zone)) return null
        return try {
            val value = JSONObject(file.readText(Charsets.UTF_8))
            if (value.optInt("version") != VERSION) return null
            (value.opt("text") as? String)?.takeIf { it.isNotBlank() }
        } catch (e: IOException) {
            WeLogger.w(TAG, "cache read failed", e)
            null
        } catch (e: JSONException) {
            WeLogger.w(TAG, "invalid cache entry", e)
            null
        }
    }

    private fun write(file: File, kind: String, text: String) {
        var temporary: File? = null
        try {
            val directory = file.parentFile!!
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Cannot create recognition cache directory")
            }
            val pending = File.createTempFile("recognition-", ".tmp", directory)
            temporary = pending
            val value = JSONObject().put("version", VERSION).put("kind", kind)
                .put("createdAt", System.currentTimeMillis()).put("text", text)
            FileOutputStream(pending).use { output ->
                output.write(value.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!pending.renameTo(file)) throw IOException("Cannot publish recognition cache entry")
            WeLogger.i(TAG, "cache saved: kind=$kind, chars=${text.length}")
        } catch (e: IOException) {
            WeLogger.w(TAG, "cache write failed", e)
        } finally {
            temporary?.delete()
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
