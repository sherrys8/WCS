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
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.api.core.WeApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeMessageApi
import dev.sherry.wcs.features.api.core.models.MessageInfo
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
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.runOnUiThread
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.strings.isGroupChatWxId

/**
 * @所有人（含隐蔽@与卡片两种形态）。
 *
 * 可见@与卡片@共用同一个裸 CGI `/cgi-bin/micromsg-bin/newsendmsg` (522)，
 * atuserlist 写在请求自带的 msgsource 里（`NewSendMsgItemProto` 第 6 字段）：
 * - 默认：正文 = `@昵称 ` 拼接 + 原文（微信原生那种可见@）。真机能送达。
 * - 卡片：正文 = 链接卡片 appmsg XML（`<type>57</type>`，标题取原文），wire type 发 1。
 *   `<type>19</type>` + wire 49 也试过；两种形态真机都是服务器接受、对面什么都没有，
 *   同一段 XML 走宿主 AppMsgLogic 却能正常渲染，但宿主通路 (WeAppMsgApi) 没有 msgSource 参数。
 * - 隐蔽：改走原生发送 (`WeChatInputBarMenuApi.performSend`)，正文就是输入框原文，
 *   atuserlist 在入库前用 MsgSourceHelper 的合并方法注入内存 MsgInfo；写法由
 *   [stealthMentionMode] 选，三种都没在真机跑通过：
 *   alnode 键 + z=false（上一版，宿主发送直接失败、红色感叹号）、
 *   alnode 键 + z=true、sec_msg_node 键 + z=false（`SendSecMsg` 用同一个入库 hook，
 *   已验证节点能随出网带到对端）。
 *   裸 522 的隐蔽（正文不拼 @昵称）真机连 send 带 notify@all 与单 wxid 两种都送不到对面，
 *   判据是正文里有没有 @ 文本，所以放弃那条。
 *
 * msgsource 的字段集照真机抓到的原生 @all 补齐（见 BlockAtAllNotifications 的采集日志）：
 * 原生没有 <atall> 节点，也没有 <alnode>，但带 silence/membercount/notifystyleflag
 * 与一个我们无法伪造的服务端 signature。
 *
 * 服务器对 atuserlist 的人数上限见 [MAX_AT_USERS]。
 */
object MentionMembers : SwitchFeature(), IResolveDex {

    override val technicalId = "@所有人"
    override val nameRes = R.string.feature_mention_members_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_mention_members_description

    /** 微信服务器对 atuserlist 人数的上限, 超出部分静默截断 */
    private const val MAX_AT_USERS = 200

    private const val TAG = "MentionMembers"

    /** 隐蔽@的注入写法：三种都还没在真机验过，摆出来逐个试 */
    private const val STEALTH_MODE_ALNODE_ASYNC = "alnode"
    private const val STEALTH_MODE_ALNODE_DIRTY = "alnode_dirty"
    private const val STEALTH_MODE_SEC_NODE_WRITER = "secnode"

    private var stealthMentionAll by WePrefs.prefOption("mention_members_stealth_all", false)

    private var cardMentionAll by WePrefs.prefOption("mention_members_card_all", false)

    private var stealthMentionMode by WePrefs.prefOption(
        "mention_members_stealth_mode",
        STEALTH_MODE_ALNODE_ASYNC,
    )

    // 点击菜单确认后置位 (talker 到 atuserlist CSV); 消息入库发生在 performSend 之后的
    // 异步流程里, 由入库钩子消费。发送失败残留的标记由 talker 不匹配兜底丢弃。
    @Volatile
    private var pendingStealthAt: Pair<String, String>? = null

    // MsgSourceHelper 的 alnode 合并方法: static (msgInfo, String nodeXml, boolean) -> void
    private val methodMergeAlnodeNode by dexMethod {
        matcher {
            usingEqStrings("(?s)<alnode[^>]*>.*?</alnode>")
            paramCount(3)
            returnType("void")
        }
    }

    // sec_msg_node 合并方法, 同签名 —— SendSecMsg 用它注入过节点并真机生效
    private val methodMergeSecNode by dexMethod {
        matcher {
            usingEqStrings("(?s)<sec_msg_node[^>]*>.*?</sec_msg_node>")
            paramCount(3)
            returnType("void")
        }
    }

    private fun showSettingsDialog(context: Context) {
        showComposeDialog(context) {
            var stealthState by remember { mutableStateOf(stealthMentionAll) }
            var cardState by remember { mutableStateOf(cardMentionAll) }
            var stealthModeState by remember { mutableStateOf(stealthMentionMode) }
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
                        DropDownMenuWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.mention_members_stealth_mode_label),
                            // 传 description 会顶掉当前选中项的显示，这一行要的就是看见选了哪种
                            description = null,
                            value = stealthModeState,
                            options = listOf(
                                DropdownOption(
                                    STEALTH_MODE_ALNODE_ASYNC,
                                    stringResource(R.string.mention_members_stealth_mode_alnode),
                                ),
                                DropdownOption(
                                    STEALTH_MODE_ALNODE_DIRTY,
                                    stringResource(R.string.mention_members_stealth_mode_alnode_dirty),
                                ),
                                DropdownOption(
                                    STEALTH_MODE_SEC_NODE_WRITER,
                                    stringResource(R.string.mention_members_stealth_mode_secnode),
                                ),
                            ),
                            onValueChange = {
                                stealthModeState = it
                                stealthMentionMode = it
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

                                if (stealthMentionAll) {
                                    // 隐蔽: 原生发送输入框原文（正文不带 @昵称），atuserlist 由入库钩子注入
                                    val atUserIds = selectedContacts
                                        .map { it.wxId }
                                        .filter { it != "weixin" && it != "filehelper" }
                                        .take(MAX_AT_USERS)
                                    pendingStealthAt = currentConv to atUserIds.joinToString(",")
                                    WeLogger.i(
                                        TAG,
                                        "stealth armed: mode=$stealthMentionMode talker=$currentConv" +
                                            " count=${atUserIds.size}",
                                    )
                                    WeChatInputBarMenuApi.performSend(chatFooter)
                                    return@ContactsSelector
                                }

                                val content = chatFooter.lastText
                                val isAllSelected = selectedContacts.size == allMembers.size
                                val mentionedContacts = selectedContacts.take(MAX_AT_USERS)
                                val atWxIds = if (isAllSelected) {
                                    "notify@all"
                                } else {
                                    mentionedContacts.joinToString(",") { it.wxId }
                                }

                                // 真机抓到的原生 @all msgsource 带的三个字段，我们原先一个都没有；
                                // notifystyleflag 是「有人@你」最可疑的开关（原先猜的 <atall> 节点不存在）。
                                val msgSource = "<msgsource>" +
                                    "<atuserlist><![CDATA[$atWxIds]]></atuserlist>" +
                                    "<pua>1</pua>" +
                                    "<alnode><cf>5</cf><inlenlist>73</inlenlist></alnode>" +
                                    "<eggIncluded>1</eggIncluded>" +
                                    "<silence>0</silence>" +
                                    "<membercount>${allMembers.size + 1}</membercount>" +
                                    "<notifystyleflag>1</notifystyleflag>" +
                                    "</msgsource>"

                                val item = NewSendMsgItemProto(
                                    toUser = UserNameProto(currentConv),
                                    content = if (cardMentionAll) {
                                        buildCardContent(content)
                                    } else {
                                        mentionedContacts.joinToString("") { "@${it.nickname} " } + content
                                    },
                                    // 卡片那条的形态由正文 appmsg 自己的 <type> 决定，wire type 一起发 1
                                    type = 1,
                                    msgSource = msgSource
                                )
                                val reqProto = NewSendMsgReqProto(
                                    count = 1,
                                    items = listOf(item)
                                )
                                val reqBytes = WeProto.encodeWithDefaults(reqProto)
                                WeLogger.i(
                                    TAG,
                                    "cgi 522 send: card=$cardMentionAll" +
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

    /**
     * 卡片@所有人: 输入框文本进 `<title>`，正文是链接卡片(appmsg `<type>57</type>`)形态。
     * 不带 `<?xml ...?>` 声明——微信自己读消息时会把它剥掉（`MessageInfo.kt:317`）。
     */
    private fun buildCardContent(text: String): String {
        val title = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<msg><appmsg><title>$title</title><action>view</action><type>57</type><finderLiveProductShare><isPriceBeginShow>false</isPriceBeginShow></finderLiveProductShare><gameshare><appbrandext><priority>-1</priority></appbrandext></gameshare><appattach /></appmsg><fromusername>${WeApi.selfWxId}</fromusername><scene>0</scene><appinfo><version>1</version><appname /></appinfo><commenturl /></msg>"""
    }

    override fun onEnable() {
        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookBefore {
            val pending = pendingStealthAt ?: return@hookBefore
            val msgInfo = MessageInfo(args[0]!!)

            // 只处理自己发送的文本消息 (isSend=1, type=1)
            if (msgInfo.isSend != 1 || msgInfo.typeCode != 1) return@hookBefore

            // 消费标记; talker 不匹配 (发送失败残留、用户先在别的会话发言) 时丢弃,
            // 避免把 atuserlist 误注入无关消息
            pendingStealthAt = null
            if (msgInfo.talker != pending.first) return@hookBefore

            val mergeMethod = when (stealthMentionMode) {
                STEALTH_MODE_SEC_NODE_WRITER -> methodMergeSecNode.method
                else -> methodMergeAlnodeNode.method
            }
            mergeMethod.invoke(
                null,
                args[0],
                "<atuserlist><![CDATA[${pending.second}]]></atuserlist>",
                // 只有 ALNODE_DIRTY 这一档试 z=true: 让合并方法立刻落库，而不是随入库方法写
                stealthMentionMode == STEALTH_MODE_ALNODE_DIRTY,
            )
            WeLogger.i(
                TAG,
                "stealth injected: mode=$stealthMentionMode talker=${msgInfo.talker}" +
                    " count=${pending.second.split(',').count { it.isNotEmpty() }}",
            )
        }

        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        pendingStealthAt = null
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
