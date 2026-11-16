package dev.sherry.wcs.features.items.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.github.penfeizhou.animation.apng.decode.APNGDecoder
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
import com.github.penfeizhou.animation.loader.FileLoader
import com.github.penfeizhou.animation.webp.decode.WebPDecoder
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
import dev.sherry.wcs.features.api.core.models.MessageType
import dev.sherry.wcs.features.api.ui.WeChatMessageContextMenuApi
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature
import dev.sherry.wcs.i18n.LocalWcSLocalizedContext
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.utils.ReplyIcon
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.fs.KnownPaths
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object AiImageRecognition : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {
    override val technicalId = "AI识图"
    override val nameRes = R.string.ai_image_name
    override val descriptionRes = R.string.ai_image_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val defaultEnabled = false

    private const val PROFILE_KEY = "ai_image_profile_id"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems() = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = 777032,
            text = localizedChatString(R.string.ai_image_name),
            drawable = ReplyIcon,
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = { it.type == MessageType.IMAGE || it.type?.isSticker == true },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { _, chattingContext, message ->
            showComposeDialog(chattingContext.activity) {
                RecognitionDialog(message)
            }
        },
    )

    @Composable
    private fun ShowComposeDialogScope.RecognitionDialog(message: MessageInfo) {
        val talker = message.talker
        val platformContext = LocalContext.current
        val localizedContext = LocalWcSLocalizedContext.current
        val scope = rememberCoroutineScope()
        var profileId by remember { mutableStateOf(WePrefs.getStringOrDef(PROFILE_KEY, "")) }
        var busy by remember { mutableStateOf(false) }
        var job by remember { mutableStateOf<Job?>(null) }
        var result by remember { mutableStateOf("") }
        var feedback by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        val profile = ApiProfiles.get(profileId)
        val prompt = stringResource(R.string.ai_image_prompt)
        val needConfig = stringResource(R.string.ai_image_need_config)
        val emptyResult = stringResource(R.string.ai_image_empty)
        val animationPrompt = stringResource(R.string.ai_image_animation_prompt)
        val unsupportedAnimation = stringResource(R.string.ai_image_animation_unsupported)

        AlertDialogContent(
            title = { Text(stringResource(R.string.ai_image_name)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SegmentedColumn {
                        item {
                            BaseWidget(
                                title = stringResource(R.string.ai_image_profile),
                                description = profile?.name ?: needConfig,
                                enabled = !busy,
                                onClick = {
                                    showApiProfilePicker(platformContext, profileId, CAP_VISION) { picked ->
                                        profileId = picked.id
                                        WePrefs.putString(PROFILE_KEY, picked.id)
                                        result = ""
                                        feedback = ""
                                    }
                                },
                            )
                        }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = {
                            val snapshot = ApiProfiles.get(profileId)
                            if (snapshot == null || snapshot.capability != CAP_VISION ||
                                snapshot.apiKey.isBlank() || snapshot.defaultModelId.isBlank()
                            ) {
                                failed = true
                                feedback = needConfig
                            } else {
                                busy = true
                                failed = false
                                feedback = ""
                                result = ""
                                job = scope.launch {
                                    try {
                                        result = recognize(
                                            message, snapshot, prompt, emptyResult,
                                            animationPrompt, unsupportedAnimation,
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failed = true
                                        feedback = localizedContext.getString(
                                            R.string.ai_image_failed,
                                            e.message.orEmpty().take(500),
                                        )
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.ai_image_start)) }
                    if (busy) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.ai_image_loading))
                    }
                    if (result.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                result,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                onClick = {
                                    val clipboard = platformContext.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText(
                                        localizedContext.getString(R.string.ai_image_name), result,
                                    ))
                                    failed = false
                                    feedback = localizedContext.getString(R.string.ai_image_copied)
                                },
                            ) { Text(stringResource(android.R.string.copy)) }
                            TextButton(
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                onClick = {
                                    val text = result
                                    busy = true
                                    feedback = ""
                                    job = scope.launch {
                                        try {
                                            val sent = runInterruptible(Dispatchers.IO) {
                                                WeMessageApi.sendText(talker, text)
                                            }
                                            failed = !sent
                                            if (!sent) {
                                                feedback = localizedContext.getString(R.string.ai_image_send_failed)
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            failed = true
                                            feedback = localizedContext.getString(R.string.ai_image_send_failed)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.ai_reply_send)) }
                        }
                    }
                    if (feedback.isNotBlank()) {
                        Text(
                            feedback,
                            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                            color = if (failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    job?.cancel()
                    onDismiss()
                }) {
                    Text(stringResource(if (busy) R.string.dialog_cancel else R.string.dialog_close))
                }
            },
        )
    }

    private suspend fun recognize(
        message: MessageInfo,
        profile: ApiProfile,
        prompt: String,
        emptyResult: String,
        animationPrompt: String,
        unsupportedAnimation: String,
    ): String = withContext(Dispatchers.IO) {
        val cacheDirectory = (KnownPaths.moduleCache / "ai-image-recognition").createDirectories()
        val temporary = cacheDirectory / "media-${UUID.randomUUID()}.tmp"
        try {
            val media = runInterruptible {
                val deadline = SystemClock.elapsedRealtime() + 120_000L
                if (message.type?.isSticker == true) {
                    val md5 = message.stickerMd5 ?: throw IOException(emptyResult)
                    WeMessageApi.materializeNotificationSticker(md5, temporary, deadline, wait = true)
                } else {
                    WeMessageApi.materializeNotificationLargeImage(message.serverId, temporary, deadline)
                }
            } ?: throw IOException(emptyResult)
            currentCoroutineContext().ensureActive()
            val animation = encodeAnimation(media.path, emptyResult, unsupportedAnimation)
            val dataUri = animation ?: encodeImage(media.path, emptyResult)
            currentCoroutineContext().ensureActive()
            val instruction = if (animation == null) prompt else "$prompt\n$animationPrompt"
            requestRecognition(profile, instruction, dataUri, emptyResult)
        } finally {
            temporary.toFile().delete()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun encodeAnimation(path: Path, emptyResult: String, unsupported: String): String? {
        val header = ByteArray(12)
        val size = path.toFile().inputStream().use { it.read(header) }
        val signature = if (size >= 6) String(header, 0, 6, Charsets.US_ASCII) else ""
        val gif = signature == "GIF87a" || signature == "GIF89a"
        val png = size >= 8 && header.take(8) ==
            listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte(),
                0x0d.toByte(), 0x0a.toByte(), 0x1a.toByte(), 0x0a.toByte())
        val webp = size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
        if (!gif && !png && !webp) return null
        if (path.fileSize() > 20L * 1024 * 1024) throw IOException(unsupported)
        val bytes = path.readBytes()
        var animated = false
        val frameEnds = mutableListOf<Long>()
        var animationDuration = 0L
        if (!gif) {
            var offset = if (png) 8 else 12
            while (offset + 8 <= bytes.size) {
                currentCoroutineContext().ensureActive()
                val typeOffset = if (png) offset + 4 else offset
                val type = String(bytes, typeOffset, 4, Charsets.US_ASCII)
                if (type == "acTL" || type == "ANIM" || type == "ANMF") animated = true
                val length = if (png) {
                    (0..3).fold(0L) { value, index ->
                        (value shl 8) or (bytes[offset + index].toLong() and 255)
                    }
                } else {
                    (0..3).fold(0L) { value, index ->
                        value or ((bytes[offset + 4 + index].toLong() and 255) shl (index * 8))
                    }
                }
                val next = offset.toLong() + 8 + length + if (png) 4 else length % 2
                if (next > bytes.size || next <= offset) throw IOException(emptyResult)
                val duration = when {
                    png && type == "fcTL" -> {
                        if (length != 26L) throw IOException(emptyResult)
                        val numerator = ((bytes[offset + 28].toInt() and 255) shl 8) or
                            (bytes[offset + 29].toInt() and 255)
                        val denominator = (((bytes[offset + 30].toInt() and 255) shl 8) or
                            (bytes[offset + 31].toInt() and 255)).let { if (it == 0) 100 else it }
                        (numerator * 1000L / denominator).coerceAtLeast(10L)
                    }
                    webp && type == "ANMF" -> {
                        if (length < 16L) throw IOException(emptyResult)
                        (0..2).fold(0L) { value, index ->
                            value or ((bytes[offset + 20 + index].toLong() and 255) shl (index * 8))
                        }.coerceAtLeast(10L)
                    }
                    else -> null
                }
                if (duration != null) {
                    animationDuration += duration
                    if (frameEnds.size >= 1000 || animationDuration > 600_000L) {
                        throw IOException(unsupported)
                    }
                    frameEnds.add(animationDuration)
                }
                offset = next.toInt()
            }
            if (!animated) return null
            if (frameEnds.isEmpty()) throw IOException(emptyResult)
            return encodeFrameSequenceAnimation(path, png, frameEnds, emptyResult, unsupported)
        }
        val timeline = gifFrameTimes(bytes, emptyResult)
        if (timeline.isEmpty()) throw IOException(emptyResult)
        if (timeline.size == 1) return null
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException(unsupported)
        if (movie.width() <= 0 || movie.height() <= 0 || movie.duration() <= 0) {
            throw IOException(unsupported)
        }
        val total = timeline.last().second
        val indices = if (timeline.size <= 6) timeline.indices.toList() else {
            (0 until 6).map { sample ->
                val time = (total - 1) * sample / 5
                timeline.indexOfFirst { time < it.second }
            }.distinct()
        }
        val scale = minOf(1.0, 960.0 / maxOf(movie.width(), movie.height()))
        val width = (movie.width() * scale).toInt().coerceAtLeast(1)
        val height = (movie.height() * scale).toInt().coerceAtLeast(1)
        val frames = mutableListOf<Bitmap>()
        try {
            for (index in indices) {
                currentCoroutineContext().ensureActive()
                val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(frame)
                    canvas.drawColor(Color.WHITE)
                    canvas.scale(scale.toFloat(), scale.toFloat())
                    val midpoint = (timeline[index].first + timeline[index].second) / 2
                    val time = midpoint * movie.duration() / total
                    movie.setTime(time.toInt())
                    movie.draw(canvas, 0f, 0f)
                    if (frames.any { it.sameAs(frame) }) frame.recycle() else frames.add(frame)
                } catch (error: Throwable) {
                    frame.recycle()
                    throw error
                }
            }
            return encodeStoryboard(frames, emptyResult)
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    private fun encodeStoryboard(frames: List<Bitmap>, emptyResult: String): String {
        if (frames.isEmpty()) throw IOException(emptyResult)
        val columns = minOf(2, frames.size)
        val sheet = Bitmap.createBitmap(
            columns * 480, ((frames.size + 1) / 2) * 512, Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = Color.BLACK
                textSize = 24f
            }
            frames.forEachIndexed { index, frame ->
                val x = index % 2 * 480
                val y = index / 2 * 512
                canvas.drawText("${index + 1}", x + 12f, y + 26f, paint)
                val ratio = minOf(464.0 / frame.width, 464.0 / frame.height)
                val w = (frame.width * ratio).toInt().coerceAtLeast(1)
                val h = (frame.height * ratio).toInt().coerceAtLeast(1)
                val left = x + (480 - w) / 2
                val top = y + 32 + (480 - h) / 2
                canvas.drawBitmap(frame, null, Rect(left, top, left + w, top + h), paint)
            }
            return encodeBitmap(sheet, emptyResult)
        } finally {
            sheet.recycle()
        }
    }

    private suspend fun encodeFrameSequenceAnimation(
        path: Path,
        png: Boolean,
        ends: List<Long>,
        emptyResult: String,
        unsupported: String,
    ): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > 4_000_000L
        ) throw IOException(unsupported)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960 &&
            minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1
        ) sample *= 2
        fun createDecoder(): FrameSeqDecoder<*, *> = if (png) {
            object : APNGDecoder(FileLoader(path.toString()), null) {
                init { sampleSize = sample }
            }
        } else {
            object : WebPDecoder(FileLoader(path.toString()), null) {
                init { sampleSize = sample }
            }
        }
        val frames = mutableListOf<Bitmap>()
        try {
            val count = ends.size
            val total = ends.last()
            val indices = if (count <= 6) (0 until count).toList() else {
                (0 until 6).map { sample ->
                    val time = (total - 1) * sample / 5
                    ends.indexOfFirst { time < it }
                }.distinct()
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            for (index in indices) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) throw IOException(unsupported)
                val decoder = createDecoder()
                val decoded = try {
                    decoder.getFrameBitmap(index) ?: throw IOException(emptyResult)
                } finally {
                    decoder.stop()
                }
                try {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException(unsupported)
                    val scale = minOf(1.0, 960.0 / maxOf(decoded.width, decoded.height))
                    val frame = Bitmap.createBitmap(
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        val canvas = Canvas(frame)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(decoded, null, Rect(0, 0, frame.width, frame.height),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        if (frames.any { it.sameAs(frame) }) frame.recycle() else frames.add(frame)
                    } catch (error: Throwable) {
                        frame.recycle()
                        throw error
                    }
                } finally {
                    decoded.recycle()
                }
            }
            return encodeStoryboard(frames, emptyResult)
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    private fun gifFrameTimes(bytes: ByteArray, emptyResult: String): List<Pair<Long, Long>> {
        var offset = 6
        fun readByte(): Int {
            if (offset >= bytes.size) throw IOException(emptyResult)
            return bytes[offset++].toInt() and 255
        }
        fun readShort(): Int = readByte() or (readByte() shl 8)
        fun skip(length: Int) {
            if (length < 0 || length > bytes.size - offset) throw IOException(emptyResult)
            offset += length
        }
        fun skipBlocks() {
            while (true) {
                val length = readByte()
                if (length == 0) return
                skip(length)
            }
        }
        val width = readShort()
        val height = readShort()
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000L) {
            throw IOException(emptyResult)
        }
        val packed = readByte()
        skip(2)
        if (packed and 128 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        val frames = mutableListOf<Pair<Long, Long>>()
        var time = 0L
        var delay = 100L
        while (true) {
            when (readByte()) {
                0x3b -> return frames
                0x21 -> {
                    if (readByte() == 0xf9) {
                        if (readByte() != 4) throw IOException(emptyResult)
                        readByte()
                        val ticks = readShort()
                        delay = if (ticks < 2) 100L else ticks * 10L
                        readByte()
                        if (readByte() != 0) throw IOException(emptyResult)
                    } else skipBlocks()
                }
                0x2c -> {
                    skip(8)
                    val flags = readByte()
                    if (flags and 128 != 0) skip(3 * (1 shl ((flags and 7) + 1)))
                    readByte()
                    skipBlocks()
                    if (frames.size >= 10_000 || time + delay > Int.MAX_VALUE) {
                        throw IOException(emptyResult)
                    }
                    frames.add(time to time + delay)
                    time += delay
                    delay = 100L
                }
                else -> throw IOException(emptyResult)
            }
        }
    }

    private fun encodeBitmap(bitmap: Bitmap, emptyResult: String): String =
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw IOException(emptyResult)
            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }

    private fun encodeImage(path: Path, emptyResult: String): String {
        val filename = path.toString()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filename, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException(emptyResult)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 3200) {
                inSampleSize *= 2
            }
        }
        val bitmap = BitmapFactory.decodeFile(filename, options) ?: throw IOException(emptyResult)
        var scaled: Bitmap? = null
        try {
            val scale = minOf(1.0, 1600.0 / maxOf(bitmap.width, bitmap.height))
            val image = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true,
                ).also { scaled = it }
            } else bitmap
            val opaque = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(opaque)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(image, 0f, 0f, null)
                return encodeBitmap(opaque, emptyResult)
            } finally {
                opaque.recycle()
            }
        } finally {
            scaled?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
        }
    }

    private suspend fun requestRecognition(
        profile: ApiProfile,
        prompt: String,
        dataUri: String,
        emptyResult: String,
    ): String = coroutineScope {
        val connection = URL(profile.asConfig().requestEndpoint()).openConnection() as HttpURLConnection
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 120_000
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${profile.apiKey}")
                    setRequestProperty("Content-Type", "application/json")
                }
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUri)))
                val body = JSONObject()
                    .put("model", profile.defaultModelId)
                    .put("stream", false)
                    .put("messages", JSONArray().put(JSONObject()
                        .put("role", "user").put("content", content)))
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val buffer = CharArray(500)
                        var length = 0
                        while (length < buffer.size) {
                            val count = reader.read(buffer, length, buffer.size - length)
                            if (count < 0) break
                            length += count
                        }
                        String(buffer, 0, length)
                    }.orEmpty()
                    throw IOException("HTTP $status: $detail")
                }
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val value = JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.opt("content")
                if (value !is String || value.isBlank()) throw IOException(emptyResult)
                value.trim()
            }
        } finally {
            cancellation.cancel()
            connection.disconnect()
        }
    }
}
