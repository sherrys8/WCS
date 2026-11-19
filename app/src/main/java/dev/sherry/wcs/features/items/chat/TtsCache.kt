package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.fs.KnownPaths
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

object TtsCache {
    val generatedDir: File
        get() = prepareDirectory("wcs_tts")

    val previewDir: File
        get() = prepareDirectory("breeze_previews")

    fun expirationCutoffMillis(): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun prepareDirectory(name: String): File {
        val directory = File(KnownPaths.moduleCache.toFile(), name).apply { mkdirs() }
        runCatching {
            val cutoff = expirationCutoffMillis()
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff && !file.delete()) {
                    WeLogger.w("TtsCache", "failed to delete expired audio cache file")
                }
            }
        }.onFailure { WeLogger.w("TtsCache", "failed to clean expired audio cache", it) }
        return directory
    }
}
