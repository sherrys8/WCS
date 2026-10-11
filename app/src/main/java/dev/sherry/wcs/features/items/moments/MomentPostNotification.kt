package dev.sherry.wcs.features.items.moments

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeDatabaseListenerApi
import dev.sherry.wcs.features.api.core.models.IWeContact
import dev.sherry.wcs.features.api.ui.WeMomentsApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.LocalizedContextFactory
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.ContactsSelector
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseSupportingWidget
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.ShowComposeDialogScope
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import java.util.concurrent.Executors

/**
 * 指定好友发布朋友圈时提醒。
 *
 * 触发通路用 WcS 自己的数据库监听（朋友圈入库就是 `SnsInfo` 的一次写），
 * 因此不需要 Hchat 那套「本地伪造 snsId 注册表」——WcS 不会凭空造朋友圈记录。
 */
object MomentPostNotification : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "朋友圈发布通知"
    override val nameRes = R.string.feature_moment_post_notification_name
    override val descriptionRes = R.string.feature_moment_post_notification_description
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)

    private const val TAG = "MomentPostNotification"
    private const val TBL_SNS_INFO = "SnsInfo"
    private const val CHANNEL_ID = "wcs_moment_post_v1"
    private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"
    private const val TIMELINE_UI = "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"
    private const val MAX_NOTIFIED_RECORDS = 2000
    private const val MAX_TEXT_CHARS = 500

    private const val TEMPLATE_VARIABLES = "%sender% %wxid% %type% %content% %snsid%"

    private var targets by WePrefs.prefOption("moment_post_notify_targets", emptySet())
    private var systemNotification by WePrefs.prefOption("moment_post_notify_system", true)
    private var toastAlert by WePrefs.prefOption("moment_post_notify_toast", true)
    private var titleTemplate by WePrefs.prefOption("moment_post_notify_title_template", "")
    private var bodyTemplate by WePrefs.prefOption("moment_post_notify_body_template", "")
    private var toastTemplate by WePrefs.prefOption("moment_post_notify_toast_template", "")
    private var enabledAtSeconds by WePrefs.prefOption("moment_post_notify_enabled_at", 0L)
    private var notifiedKeys by WePrefs.prefOption("moment_post_notify_ids", emptySet())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WcS-MomentPostNotify").apply { isDaemon = true }
    }

    override fun onEnable() {
        if (enabledAtSeconds <= 0L) enabledAtSeconds = nowSeconds()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        enabledAtSeconds = 0L
        notifiedKeys = emptySet()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) { SettingsContent(context) }
    }

    // ==================== 触发 ====================

    override fun onInsert(table: String, values: ContentValues) {
        runCatching { scheduleFromValues(table, values) }
            .onFailure { WeLogger.w(TAG, "failed to handle a Moments insert", it) }
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        runCatching { scheduleFromValues(table, values) }
            .onFailure { WeLogger.w(TAG, "failed to handle a Moments update", it) }
    }

    private fun scheduleFromValues(table: String, values: ContentValues) {
        if (table != TBL_SNS_INFO) return
        if (!systemNotification && !toastAlert) return

        val snsId = values.getAsLong("snsId") ?: return
        if (snsId == 0L) return

        val owner = values.getAsString("userName")?.trim().orEmpty()
        if (owner.isBlank() || owner == WeApi.selfWxId) return
        if (owner !in targets) return

        // 新建行的 sourceType 常常还没写，所以只排广告位，不要求「活跃来源」位
        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType and WeMomentsApi.AD_SOURCE_FLAG != 0) return

        val key = java.lang.Long.toUnsignedString(snsId)
        if (key in notifiedKeys) return

        runCatching { worker.execute { notifyAbout(snsId, owner, key) } }
            .onFailure { WeLogger.w(TAG, "failed to schedule Moments post alert", it) }
    }

    private fun notifyAbout(snsId: Long, owner: String, key: String) {
        if (!_isEnabled) return
        val post = runCatching { resolvePost(snsId, owner) }
            .onFailure { WeLogger.w(TAG, "failed to read Moments post $key", it) }
            .getOrNull() ?: return
        if (!markNotified(key)) return

        val sender = displayNameOf(post.owner)
        val type = localized(MomentsContentType.fromId(post.type)?.nameRes ?: R.string.message_type_unknown)
        val title = renderTemplate(titleTemplate, sender, post, key, type)
            .ifBlank { localized(R.string.moment_post_notify_default_title) }
        val body = renderTemplate(bodyTemplate, sender, post, key, type)
            .ifBlank { defaultBody(sender, type, post.text) }

        if (systemNotification) {
            runCatching { sendNotification(title, body, snsId) }
                .onFailure { WeLogger.w(TAG, "failed to notify Moments post $key", it) }
        }
        if (toastAlert) {
            val toast = renderTemplate(toastTemplate, sender, post, key, type)
                .ifBlank { localized(R.string.moment_post_notify_toast_default, sender, type) }
            mainHandler.post { showToast(toast) }
        }
        WeLogger.i(TAG, "Moments alert sent, owner=${post.owner}, sns=$key, type=${post.type}")
    }

    private class PostSnapshot(val owner: String, val type: Int, val text: String)

    private fun resolvePost(snsId: Long, owner: String): PostSnapshot? {
        val enabledAt = enabledAtSeconds
        if (enabledAt <= 0L) return null
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return null
        if (WeMomentsApi.isDeleted(snsInfo)) return null
        val content = WeMomentsApi.getMomentContent(snsInfo) ?: return null
        // 只提醒开启功能之后发布的帖子，否则每次开启都会被历史刷屏
        if (content.createTime <= 0 || content.createTime.toLong() < enabledAt) return null
        return PostSnapshot(owner, content.type, content.contentText.trim().take(MAX_TEXT_CHARS))
    }

    // ==================== 去重 ====================

    private fun markNotified(key: String): Boolean = synchronized(this) {
        if (key in notifiedKeys) return@synchronized false
        val updated = LinkedHashSet(notifiedKeys)
        updated.add(key)
        while (updated.size > MAX_NOTIFIED_RECORDS) {
            val iterator = updated.iterator()
            iterator.next()
            iterator.remove()
        }
        notifiedKeys = updated
        true
    }

    // ==================== 提醒呈现 ====================

    private fun sendNotification(title: String, body: String, snsId: Long) {
        val context = HostInfo.application
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body).setBigContentTitle(title))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openTimelineIntent(context, snsId))
        manager.notify(("wcs_moments_" + java.lang.Long.toUnsignedString(snsId)).hashCode(), builder.build())
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                localized(R.string.moment_post_notify_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    private fun openTimelineIntent(context: Context, snsId: Long): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val home = Intent().apply {
            component = ComponentName(context.packageName, LAUNCHER_UI)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val timeline = Intent().apply {
            component = ComponentName(context.packageName, TIMELINE_UI)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivities(context, snsId.toInt(), arrayOf(home, timeline), flags)
    }

    private fun defaultBody(sender: String, type: String, text: String): String =
        if (text.isBlank()) {
            localized(R.string.moment_post_notify_body_default, sender, type)
        } else {
            localized(R.string.moment_post_notify_body_with_text, sender, type, text)
        }

    private fun renderTemplate(
        template: String,
        sender: String,
        post: PostSnapshot,
        key: String,
        type: String,
    ): String = template
        .replace("%sender%", sender)
        .replace("%wxid%", post.owner)
        .replace("%type%", type)
        .replace("%content%", post.text)
        .replace("%snsid%", key)
        .trim()

    private fun displayNameOf(wxId: String): String {
        val name = runCatching {
            WeDatabaseApi.getFriends().firstOrNull { it.wxId == wxId }?.displayName.orEmpty()
        }.getOrDefault("")
        return name.ifBlank { wxId }
    }

    private fun localized(resourceId: Int, vararg formatArgs: Any): String = LocalizedContextFactory.create(
        HostInfo.application,
        WcSLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(resourceId, *formatArgs)

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    // ==================== 设置界面 ====================

    @Composable
    private fun ShowComposeDialogScope.SettingsContent(context: Context) {
        var targetsState by remember { mutableStateOf(targets) }
        var systemState by remember { mutableStateOf(systemNotification) }
        var toastState by remember { mutableStateOf(toastAlert) }
        var titleState by remember { mutableStateOf(titleTemplate) }
        var bodyState by remember { mutableStateOf(bodyTemplate) }
        var toastTextState by remember { mutableStateOf(toastTemplate) }
        val variables = stringResource(R.string.moment_post_notify_template_hint, TEMPLATE_VARIABLES)
        val senderSample = stringResource(R.string.moment_post_notify_sample_sender)
        val contentSample = stringResource(R.string.moment_post_notify_sample_content)
        val imageType = stringResource(MomentsContentType.IMG.nameRes)
        val titleHint = stringResource(
            R.string.moment_post_notify_default_hint,
            stringResource(R.string.moment_post_notify_default_title),
        )
        val bodyHint = stringResource(
            R.string.moment_post_notify_default_hint,
            stringResource(R.string.moment_post_notify_body_with_text, senderSample, imageType, contentSample),
        )
        val toastHint = stringResource(
            R.string.moment_post_notify_default_hint,
            stringResource(R.string.moment_post_notify_toast_default, senderSample, imageType),
        )

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.moment_post_notify_settings_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SegmentedColumn(title = stringResource(R.string.moment_post_notify_targets_group)) {
                        item(key = "targets") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moment_post_notify_targets),
                                description = if (targetsState.isEmpty()) {
                                    stringResource(R.string.moment_post_notify_targets_none)
                                } else {
                                    stringResource(R.string.moment_post_notify_targets_selected, targetsState.size)
                                },
                                onClick = {
                                    showTargetPicker(context) { picked -> targetsState = picked }
                                },
                            )
                        }
                    }

                    SegmentedColumn(title = stringResource(R.string.moment_post_notify_method_group)) {
                        item(key = "system") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moment_post_notify_system),
                                description = stringResource(R.string.moment_post_notify_system_summary),
                                checked = systemState,
                                onCheckedChange = {
                                    systemState = it
                                    systemNotification = it
                                },
                            )
                        }
                        item(key = "toast") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moment_post_notify_toast),
                                description = stringResource(R.string.moment_post_notify_toast_summary),
                                checked = toastState,
                                onCheckedChange = {
                                    toastState = it
                                    toastAlert = it
                                },
                            )
                        }
                    }

                    SegmentedColumn(title = stringResource(R.string.moment_post_notify_templates_group)) {
                        item(key = "title_template") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.moment_post_notify_title_template),
                                value = titleState,
                                onValueChange = {
                                    val trimmed = it.trim()
                                    titleState = trimmed
                                    titleTemplate = trimmed
                                },
                                dialogTitle = stringResource(R.string.moment_post_notify_title_template),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = titleHint,
                            )
                        }
                        item(key = "body_template") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.moment_post_notify_body_template),
                                value = bodyState,
                                onValueChange = {
                                    val trimmed = it.trim()
                                    bodyState = trimmed
                                    bodyTemplate = trimmed
                                },
                                dialogTitle = stringResource(R.string.moment_post_notify_body_template),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = bodyHint,
                            )
                        }
                        item(key = "toast_template") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.moment_post_notify_toast_template),
                                value = toastTextState,
                                onValueChange = {
                                    val trimmed = it.trim()
                                    toastTextState = trimmed
                                    toastTemplate = trimmed
                                },
                                dialogTitle = stringResource(R.string.moment_post_notify_toast_template),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = toastHint,
                            )
                        }
                        item(key = "template_variables") {
                            BaseSupportingWidget(title = variables) {
                                Text(
                                    text = TEMPLATE_VARIABLES,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
        )
    }

    private fun showTargetPicker(context: Context, onPicked: (Set<String>) -> Unit) {
        showComposeDialog(context) {
            val contacts = remember { loadFriends() }
            ContactsSelector(
                title = stringResource(R.string.moment_post_notify_picker_title),
                contacts = contacts,
                initialSelectedWxIds = targets,
                onDismiss = onDismiss,
                onConfirm = { picked ->
                    targets = picked
                    onPicked(picked)
                    onDismiss()
                },
            )
        }
    }

    private fun loadFriends(): List<IWeContact> = runCatching {
        WeDatabaseApi.getFriends().distinctBy { it.wxId }
    }.onFailure { WeLogger.w(TAG, "failed to load friends for the Moments alert picker", it) }
        .getOrDefault(emptyList())
}
