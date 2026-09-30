package dev.sherry.wcs.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
import dev.sherry.wcs.features.api.ui.WeChatMessageContextMenuApi
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature
import dev.sherry.wcs.ui.utils.DownloadIcon
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SaveStickersToLocalStorage : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "贴纸保存到本地"
    override val nameRes = R.string.feature_save_stickers_to_local_storage_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_save_stickers_to_local_storage_description

    private const val TAG = "SaveStickersToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            @Suppress("UNCHECKED_CAST")
            WeChatMessageContextMenuApi.MenuItem(
                777001,
                localizedChatString(R.string.chat_action_save_locally),
                DownloadIcon,
                MaterialSymbols.Outlined.Download,
                { msgInfo -> msgInfo.type?.isSticker ?: false },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgs ->
                        msgs.isNotEmpty() && msgs.all { it.type?.isSticker ?: false }
                    },
                    onClick = { view, _, msgs ->
                        CoroutineScope(Dispatchers.IO).launch {
                            var succeeded = 0
                            msgs.forEach { if (saveSticker(it) != null) succeeded++ }
                            showToastSuspend(
                                view.context,
                                view.context.localizedChatQuantity(
                                    R.plurals.chat_stickers_saved_locally,
                                    msgs.size,
                                    succeeded,
                                    msgs.size,
                                ),
                            )
                        }
                    },
                )
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val path = saveSticker(msgInfo)
                    showToastSuspend(
                        view.context,
                        path?.let { view.context.localizedChatString(R.string.chat_sticker_saved_to, it) }
                            ?: view.context.localizedChatString(R.string.chat_sticker_save_failed),
                    )
                }
            }
        )
    }

    private suspend fun saveSticker(msgInfo: MessageInfo): String? {
        val md5 = msgInfo.imagePath ?: run {
            WeLogger.e(TAG, "sticker imagePath is null")
            return null
        }
        return WeMessageApi.saveStickerByMd5(md5) ?: run {
            WeLogger.e(TAG, "failed to save sticker md5=$md5")
            null
        }
    }
}
