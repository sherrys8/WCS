package dev.sherry.wcs.features.items.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.fs.KnownPaths
import dev.sherry.wcs.utils.fs.createDirsSafe
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream

sealed interface CustomAvatarImportResult {
    data class Success(val assetId: String) : CustomAvatarImportResult
    data object TooLarge : CustomAvatarImportResult
    data object TooManyPixels : CustomAvatarImportResult
    data object UnsupportedAspectRatio : CustomAvatarImportResult
    data object InvalidImage : CustomAvatarImportResult
    data class Failure(val error: Throwable) : CustomAvatarImportResult
}

/** Copies a picked image into module assets as a bounded, square, re-encoded bitmap. */
object CustomAvatarAssets {

    private const val MAX_DIMENSION = 1024
    private const val MAX_IMAGE_BYTES = 50L * 1024L * 1024L
    private const val MAX_IMAGE_PIXELS = 50_000_000L
    private const val MAX_ASPECT_RATIO = 100
    private const val JPEG_QUALITY = 95

    private val root: Path by lazy {
        (KnownPaths.moduleAssets / "custom_friend_avatars").createDirsSafe()
    }

    private class Rejected(val result: CustomAvatarImportResult) : Exception()

    /** Legacy values are kept loadable: an asset id resolves to its file, anything else passes through. */
    fun resolve(value: String): Any = file(value) ?: value

    fun file(value: String): File? {
        if (!isAsset(value)) return null
        return (root / value).toFile().takeIf(File::isFile)
    }

    fun delete(value: String) {
        file(value)?.delete()
    }

    fun isAsset(value: String): Boolean {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return false
        return parsed.toString() == value
    }

    fun cleanupStaleTempFiles() {
        root.toFile().listFiles { file -> file.isFile && file.name.startsWith(".") }?.forEach { it.delete() }
    }

    fun import(uri: Uri): CustomAvatarImportResult {
        val assetId = UUID.randomUUID().toString()
        val raw = root / ".$assetId.raw"
        val partial = root / ".$assetId.part"
        val destination = root / assetId
        return runCatching {
            copyRaw(uri, raw)
            val file = raw.toFile()
            val orientation = orientationOf(file)
            val bounds = boundsOf(file)
            val size = if (orientation in SWAPPED_ORIENTATIONS) bounds.second to bounds.first else bounds
            if (size.first.toLong() * size.second.toLong() > MAX_IMAGE_PIXELS) {
                throw Rejected(CustomAvatarImportResult.TooManyPixels)
            }
            if (maxOf(size.first, size.second) > MAX_ASPECT_RATIO * minOf(size.first, size.second)) {
                throw Rejected(CustomAvatarImportResult.UnsupportedAspectRatio)
            }

            val square = squareBitmap(decodeBounded(file, size, orientation))
                ?: throw Rejected(CustomAvatarImportResult.InvalidImage)
            try {
                compress(square, partial)
            } finally {
                square.recycle()
            }
            if (partial.toFile().length() <= 0L) {
                throw Rejected(CustomAvatarImportResult.InvalidImage)
            }
            partial.moveTo(destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assetId
        }.fold(
            onSuccess = { CustomAvatarImportResult.Success(it) },
            onFailure = { failure ->
                partial.deleteIfExists()
                when (failure) {
                    is Rejected -> failure.result
                    else -> CustomAvatarImportResult.Failure(failure)
                }
            },
        ).also { raw.deleteIfExists() }
    }

    private fun copyRaw(uri: Uri, raw: Path) {
        val input = HostInfo.application.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Failed to open selected image")
        input.use { source ->
            raw.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) throw Rejected(CustomAvatarImportResult.TooLarge)
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun orientationOf(file: File): Int = runCatching {
        ExifInterface(file.toString()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun boundsOf(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw Rejected(CustomAvatarImportResult.InvalidImage)
        }
        return options.outWidth to options.outHeight
    }

    private fun decodeBounded(file: File, size: Pair<Int, Int>, orientation: Int): Bitmap? {
        var sample = 1
        while (size.first / sample > MAX_DIMENSION || size.second / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val matrix = orientationMatrix(orientation) ?: return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    private fun squareBitmap(decoded: Bitmap?): Bitmap? {
        if (decoded == null) return null
        val edge = minOf(decoded.width, decoded.height)
        if (decoded.width == edge && decoded.height == edge) return decoded
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - edge) / 2,
            (decoded.height - edge) / 2,
            edge,
            edge,
        )
        if (square !== decoded) decoded.recycle()
        return square
    }

    private fun compress(bitmap: Bitmap, partial: Path) {
        FileOutputStream(partial.toFile()).use { output ->
            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            if (!bitmap.compress(format, JPEG_QUALITY, output)) {
                throw IllegalStateException("Avatar encoding failed")
            }
            output.fd.sync()
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
            setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
            setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
        else -> null
    }

    private val SWAPPED_ORIENTATIONS = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
    )
}
