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

    fun temporaryDirectory(): File {
        val root = KnownPaths.moduleData.resolve("WCS_Cache").resolve("doubao_music").toFile()
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("Cannot create music cache")
        val zone = ZoneId.systemDefault()
        val cutoff = LocalDate.now(zone).minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
        root.listFiles()?.filter { it.isDirectory && ids.matches(it.name) }?.forEach { folder ->
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
