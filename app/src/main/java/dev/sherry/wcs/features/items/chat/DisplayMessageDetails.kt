package dev.sherry.wcs.features.items.chat

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Info
import dev.sherry.wcs.R
import dev.sherry.wcs.i18n.LocalWcSLocalizedContext
import dev.sherry.wcs.features.api.ui.WeChatMessageContextMenuApi
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.Button
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.lazySegmentedItems
import dev.sherry.wcs.ui.utils.ChatInfoIcon
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.android.copyToClipboard
import dev.sherry.wcs.utils.android.showToast

object DisplayMessageDetails : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "显示消息详情"
    override val nameRes = R.string.feature_display_message_details_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_display_message_details_description

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777005, localizedChatString(R.string.chat_message_details_menu),
                ChatInfoIcon, MaterialSymbols.Outlined.Info, { _ -> true },
                // per-message detail dialog; has no meaning for a batch selection
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            )
            { view, _, msgInfo ->
                val displayItems = mutableListOf<Pair<String, String>>()
                displayItems += view.context.localizedChatString(R.string.chat_message_details_type) to
                    msgInfo.typeCode.toString()
                displayItems += "ID" to msgInfo.id.toString()
                displayItems += view.context.localizedChatString(R.string.chat_message_details_talker_id) to
                    msgInfo.talker
                displayItems += view.context.localizedChatString(R.string.chat_message_details_sender_id) to
                    msgInfo.sender
                displayItems += view.context.localizedChatString(R.string.chat_message_details_content) to
                    msgInfo.content

                showComposeDialog(view.context) {
                    val localizedContext = LocalWcSLocalizedContext.current
                    AlertDialogContent(
                        title = { Text(stringResource(R.string.chat_message_details_title)) },
                        text = {
                            LazyColumn {
                                lazySegmentedItems(displayItems, key = { it.first }) { (key, value) ->
                                    BaseWidget(
                                        title = key,
                                        description = value,
                                        onClick = {
                                            copyToClipboard(value)
                                            showToast(localizedContext.getString(R.string.chat_message_details_copied))
                                        },
                                    )
                                }
                            }
                        },
                        confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
                    )
                }
            }
        )
    }
}
