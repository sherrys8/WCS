package dev.sherry.wcs.features.items.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeMessageApi
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
import kotlin.io.path.div
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
            isSupported = { it.type == MessageType.IMAGE },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { _, chattingContext, message ->
            showComposeDialog(chattingContext.activity) {
                RecognitionDialog(message.serverId, message.talker)
            }
        },
    )

    @Composable
    private fun ShowComposeDialogScope.RecognitionDialog(serverId: Long, talker: String) {
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
                                        result = recognize(serverId, snapshot, prompt, emptyResult)
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
        serverId: Long,
        profile: ApiProfile,
        prompt: String,
        emptyResult: String,
    ): String = withContext(Dispatchers.IO) {
        val temporary = KnownPaths.moduleCache / "ai-image-${UUID.randomUUID()}.tmp"
        try {
            val media = runInterruptible {
                WeMessageApi.materializeNotificationLargeImage(
                    serverId, temporary, SystemClock.elapsedRealtime() + 120_000L,
                )
            } ?: throw IOException(emptyResult)
            currentCoroutineContext().ensureActive()
            val dataUri = encodeImage(media.path, emptyResult)
            currentCoroutineContext().ensureActive()
            requestRecognition(profile, prompt, dataUri, emptyResult)
        } finally {
            temporary.toFile().delete()
        }
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
            return ByteArrayOutputStream().use { output ->
                if (!image.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw IOException(emptyResult)
                "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
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
