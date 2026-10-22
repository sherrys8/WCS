package dev.sherry.wcs.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Alternate_email
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.net.WePacketHelper
import dev.sherry.wcs.features.api.net.models.protobuf.NewSendMsgItemProto
import dev.sherry.wcs.features.api.net.models.protobuf.NewSendMsgReqProto
import dev.sherry.wcs.features.api.net.models.protobuf.UserNameProto
import dev.sherry.wcs.features.api.net.models.protobuf.WeProto
import dev.sherry.wcs.features.api.ui.WeChatInputBarMenuApi
import dev.sherry.wcs.features.api.ui.WeCurrentConversationApi
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature
import dev.sherry.wcs.i18n.LocalWcSLocalizedContext
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.ContactsSelector
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.runOnUiThread
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.strings.isGroupChatWxId

/**
 * @所有人（含隐蔽@与卡片两种正文形态）。
 *
 * 三条通路共用同一个裸 CGI `/cgi-bin/micromsg-bin/newsendmsg` (522)，
 * atuserlist 一律写在请求自带的 msgsource 里（`NewSendMsgItemProto` 第 6 字段），
 * 因此提醒随出网请求确定送出；差别只在正文：
 * - 默认：正文 = `@昵称 ` 拼接 + 原文（微信原生那种可见@）
 * - 隐蔽：正文 = 输入框原文，不拼 @昵称 —— 气泡里看不到@痕迹。
 *   曾在入库方法 (methodMsgInfoHandleApiInsertMessage) 前用 MsgSourceHelper
 *   合并节点注入 atuserlist，真机证明改过的内存 MsgInfo 会让宿主发送直接失败
 *   （微信显示红色感叹号），故废弃。
 * - 卡片：正文 = 一段 appmsg XML（type=19，标题取原文），消息类型换成 49。
 *   真机两次（自建壳与用户提供的 type=19 模板）都是服务器接受、两端不渲染，
 *   同一段 XML 走宿主 AppMsgLogic 却能正常显示，故怀疑是 522 组装差异，未结案。
 *
 * 服务器对 atuserlist 的人数上限见 [MAX_AT_USERS]。
 */
object MentionMembers : SwitchFeature() {

    override val technicalId = "@所有人"
    override val nameRes = R.string.feature_mention_members_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_mention_members_description

    /** 微信服务器对 atuserlist 人数的上限, 超出部分静默截断 */
    private const val MAX_AT_USERS = 200

    private const val TAG = "MentionMembers"

    private var stealthMentionAll by WePrefs.prefOption("mention_members_stealth_all", false)

    private var cardMentionAll by WePrefs.prefOption("mention_members_card_all", false)

    private fun showSettingsDialog(context: Context) {
        showComposeDialog(context) {
            var stealthState by remember { mutableStateOf(stealthMentionAll) }
            var cardState by remember { mutableStateOf(cardMentionAll) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.mention_members_settings_title)) },
                text = {
                    Column {
                        SwitchWidget(
                            title = stringResource(R.string.mention_members_stealth_label),
                            description = stringResource(R.string.mention_members_stealth_description),
                            checked = stealthState,
                            onCheckedChange = {
                                stealthState = it
                                stealthMentionAll = it
                            },
                        )
                        SwitchWidget(
                            title = stringResource(R.string.mention_members_card_label),
                            description = stringResource(R.string.mention_members_card_description),
                            checked = cardState,
                            onCheckedChange = {
                                cardState = it
                                cardMentionAll = it
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "mention_members",
                icon = MaterialSymbols.Outlined.Alternate_email,
                label = localizedChatInputString(R.string.mention_members_action_label),
                isSupported = { _, _ ->
                    WeCurrentConversationApi.value.isGroupChatWxId
                },
                onClick = { context, chatFooter ->
                    val currentConv = WeCurrentConversationApi.value
                    if (!currentConv.isGroupChatWxId) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.mention_members_group_only),
                        )
                        return@ActionItem
                    }

                    if (stealthMentionAll && chatFooter.lastText.isEmpty()) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.mention_members_input_empty),
                        )
                        return@ActionItem
                    }

                    val allMembers = WeDatabaseApi
                        .getGroupMembers(currentConv)
                        .filter { c -> c.wxId != WeApi.selfWxId }

                    if (allMembers.isEmpty()) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.mention_members_empty_group),
                        )
                        return@ActionItem
                    }

                    showComposeDialog(context) {
                        val dialogContext = LocalContext.current
                        val localizedContext = LocalWcSLocalizedContext.current
                        ContactsSelector(
                            title = stringResource(R.string.feature_mention_members_name),
                            contacts = allMembers,
                            initialSelectedWxIds = allMembers.map { it.wxId }.toSet(),
                            onDismiss = onDismiss,
                            onConfirm = { selectedWxIds ->
                                if (selectedWxIds.isEmpty()) {
                                    showToast(
                                        dialogContext,
                                        localizedContext.localizedChatInputString(
                                            R.string.mention_members_select_one,
                                        ),
                                    )
                                    return@ContactsSelector
                                }

                                onDismiss()

                                val selectedContacts = allMembers.filter { it.wxId in selectedWxIds }

                                val content = chatFooter.lastText
                                val isAllSelected = selectedContacts.size == allMembers.size
                                val mentionedContacts = selectedContacts.take(MAX_AT_USERS)
                                val atWxIds = if (isAllSelected) {
                                    "notify@all"
                                } else {
                                    mentionedContacts.joinToString(",") { it.wxId }
                                }

                                val item = NewSendMsgItemProto(
                                    toUser = UserNameProto(currentConv),
                                    content = when {
                                        cardMentionAll -> buildCardContent(content)
                                        // 隐蔽: 正文只发原文，不拼 @昵称，@ 全靠请求里的 atuserlist
                                        stealthMentionAll -> content
                                        else -> mentionedContacts.joinToString("") { "@${it.nickname} " } + content
                                    },
                                    type = if (cardMentionAll) 49 else 1,
                                    msgSource = """<msgsource><atuserlist><![CDATA[$atWxIds]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>"""
                                )
                                val reqProto = NewSendMsgReqProto(
                                    count = 1,
                                    items = listOf(item)
                                )
                                val reqBytes = WeProto.encodeWithDefaults(reqProto)
                                WeLogger.i(
                                    TAG,
                                    "cgi 522 send: card=$cardMentionAll stealth=$stealthMentionAll" +
                                        " allSelected=$isAllSelected targets=${mentionedContacts.size}",
                                )

                                WePacketHelper.sendCgi(
                                    "/cgi-bin/micromsg-bin/newsendmsg",
                                    522,
                                    0,
                                    0,
                                    reqBytes = reqBytes
                                ) {
                                    onSuccess { _ ->
                                        showToast(
                                            context,
                                            context.localizedChatInputString(R.string.mention_members_sent_unseen),
                                        )
                                        val now = System.currentTimeMillis()
                                        WeMessageApi.createSimpleMsgInfoAndInsert(
                                            10000,
                                            currentConv,
                                            context.localizedChatInputQuantity(
                                                R.plurals.mention_members_message_count,
                                                selectedContacts.size,
                                                selectedContacts.size,
                                            ),
                                            now
                                        )
                                        chatFooter.lastText = ""
                                    }
                                    onFailure { errType, errCode, errMsg ->
                                        WeLogger.w(
                                            TAG,
                                            "cgi 522 failed: card=$cardMentionAll errType=$errType" +
                                                " errCode=$errCode msg=$errMsg",
                                        )
                                    }
                                }
                            }
                        )
                    }
                },
                onLongClick = { context, _ ->
                    runOnUiThread {
                        showSettingsDialog(context)
                    }
                }
            )
        )
    }

    /** 卡片@所有人: 输入框文本进 <title>，其余字段沿用模板原样；atuserlist 仍随出网请求带出。 */
    private fun buildCardContent(text: String): String {
        val title = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<msg><appmsg><title>$title</title><des>这里可以写补充说明。</des><action>view</action><type>19</type><statextstr>GhQKEnd4MjgxYTcwYTNkMzkwYmRmMg==</statextstr><recorditem><![CDATA[<recordinfo></recordinfo>]]></recorditem><finderLiveProductShare><isPriceBeginShow>false</isPriceBeginShow></finderLiveProductShare><gameshare><appbrandext><priority>-1</priority></appbrandext></gameshare></appmsg><fromusername>${WeApi.selfWxId}</fromusername><appinfo><version>1</version><appname>好友已设置小可爱可见</appname></appinfo><extcommoninfo><media_expire_at>1793772892</media_expire_at></extcommoninfo></msg>"""
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
