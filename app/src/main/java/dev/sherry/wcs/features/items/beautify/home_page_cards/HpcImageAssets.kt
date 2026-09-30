package dev.sherry.wcs.features.items.beautify.home_page_cards

import android.net.Uri
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.fs.KnownPaths
import dev.sherry.wcs.utils.fs.createDirsSafe
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.div
import kotlin.io.path.outputStream

internal object HpcImageAssets {

    private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024

    private const val UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    private val root by lazy {
        (KnownPaths.moduleAssets / "home_page_cards" / "images").createDirsSafe()
    }

    /** Copies [uri] content into module assets; returns stored file name (UUID) or null on failure. */
    fun importFromUri(uri: Uri): String? {
        val name = UUID.randomUUID().toString()
        val partial = root / ".$name.part"
        val target = root / name
        return try {
            val input = HostInfo.application.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) throw IOException("image too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
            name
        } catch (e: Exception) {
            partial.toFile().delete()
            null
        }
    }

    /** Resolves a stored asset file by [name]; returns null when [name] is not a valid stored asset. */
    fun assetFile(name: String?): File? {
        if (name.isNullOrEmpty()) return null
        if (!name.matches(Regex(UUID_PATTERN))) return null
        val file = (root / name).toFile()
        return file.takeIf { it.isFile }
    }

    fun deleteAsset(name: String?) {
        assetFile(name)?.delete()
    }
}
