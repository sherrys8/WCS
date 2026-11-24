package dev.sherry.wcs.features.items.entertain

import dev.sherry.wcs.features.items.chat.ApiProfile
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.fs.KnownPaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

object DoubaoMusicStore {
    private val ids = Regex("[a-fA-F0-9-]{36}")
    private val owners = Regex("[a-f0-9]{64}")

    /** 临时目录闲置多久才允许回收。要大于一次下载的最长耗时，见 [temporaryDirectory]。 */
    private const val TEMPORARY_IDLE_MS = 10L * 60 * 1000

    fun owner(profile: ApiProfile): String = MessageDigest.getInstance("SHA-256")
        .digest((profile.id + "\n" + DoubaoMusicClient.cookieValue(profile.cookie, "sessionid")).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun directory(track: DoubaoMusicTrack): File {
        require(ids.matches(track.id) && owners.matches(track.owner))
        return KnownPaths.moduleData.resolve("doubao_music").resolve("library")
            .resolve(track.owner).resolve(track.id).toFile()
    }

    fun audio(track: DoubaoMusicTrack): File = File(directory(track), "audio.m4a")
    fun cover(track: DoubaoMusicTrack): File = File(directory(track), "cover.jpg")

    /**
     * 每次下载音频、写元数据都领一个一次性目录。
     *
     * 顺手回收旧临时目录时**只能删「又空又闲置」的**：从拿到目录到真正写出第一个文件之间
     * 有一段窗口，此时目录是空的；另一个协程（封面补图、[save]）进来清理，就会把它当垃圾删掉，
     * 写入端随后 `outputStream()` 直接抛 FileNotFoundException——表现是「豆包已经生成，
     * 模块却下载失败」。清理频率远低于领取频率，用闲置阈值把窗口挡掉即可。
     */
    fun temporaryDirectory(): File {
        val root = KnownPaths.moduleData.resolve("WCS_Cache").resolve("doubao_music").toFile()
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("Cannot create music cache")
        val zone = ZoneId.systemDefault()
        val staleBefore = System.currentTimeMillis() - TEMPORARY_IDLE_MS
        val cutoff = LocalDate.now(zone).minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
        root.listFiles()?.forEach { folder ->
            if (!folder.isDirectory || !ids.matches(folder.name)) return@forEach
            if (folder.lastModified() >= staleBefore) return@forEach
            folder.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
            if (folder.listFiles()?.isEmpty() == true) folder.delete()
        }
        return File(root, UUID.randomUUID().toString()).apply {
            if (!mkdirs()) throw IOException("Cannot create music download directory")
        }
    }

    fun publish(source: File, destination: File) {
        val parent = destination.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create music library")
        if (!source.renameTo(destination)) throw IOException("Cannot publish music file")
    }

    fun save(track: DoubaoMusicTrack) {
        val folder = directory(track)
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) throw IOException("Cannot create music library")
        val json = JSONObject().put("version", 1).put("id", track.id).put("owner", track.owner)
            .put("title", track.title).put("lyrics", track.lyrics).put("audioUrl", track.audioUrl)
            .put("backupUrl", track.backupUrl).put("coverUrl", track.coverUrl).put("videoId", track.videoId)
            .put("taskId", track.taskId).put("conversationId", track.conversationId)
            .put("duration", track.duration).put("createdAt", track.createdAt)
        val temporary = temporaryDirectory()
        val pending = File(temporary, "track.part")
        try {
            pending.outputStream().use { output -> output.write(json.toString().toByteArray(Charsets.UTF_8)) }
            publish(pending, File(folder, "track.json"))
        } finally { pending.delete(); temporary.delete() }
    }

    fun all(profile: ApiProfile): List<DoubaoMusicTrack> {
        val owner = owner(profile)
        val root = KnownPaths.moduleData.resolve("doubao_music").resolve("library").resolve(owner).toFile()
        return root.listFiles()?.filter { it.isDirectory && ids.matches(it.name) }?.mapNotNull { folder ->
            runCatching {
                val value = JSONObject(File(folder, "track.json").readText(Charsets.UTF_8))
                if (value.optInt("version") != 1 || value.text("id") != folder.name || value.text("owner") != owner) return@runCatching null
                DoubaoMusicTrack(
                    id = folder.name, owner = owner, title = value.text("title"), lyrics = value.text("lyrics"),
                    audioUrl = value.text("audioUrl"), backupUrl = value.text("backupUrl"), coverUrl = value.text("coverUrl"),
                    videoId = value.text("videoId"), taskId = value.text("taskId"), conversationId = value.text("conversationId"),
                    duration = value.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    createdAt = value.optLong("createdAt"),
                )
            }.onFailure { WeLogger.w("DoubaoMusic", "cannot read music metadata") }.getOrNull()
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    fun delete(track: DoubaoMusicTrack) {
        val folder = directory(track)
        folder.listFiles()?.filter { it.isFile }?.forEach { if (!it.delete()) throw IOException("Cannot delete music file") }
        if (folder.exists() && !folder.delete()) throw IOException("Cannot delete music directory")
    }

    private fun JSONObject.text(key: String): String = opt(key) as? String ?: ""
}
