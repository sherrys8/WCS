package dev.sherry.wcs.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Alternate_email
import dev.ujhhgtg.reflekt.reflekt
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
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.runOnUiThread
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.strings.isGroupChatWxId
import org.luckypray.dexkit.DexKitBridge

/**
 * @所有人 (含隐蔽@模式)。
 *
 * 隐蔽@模式: 确认成员选择后走原生发送路径 (保留引用等全部原生能力),
 * 输入框文本原样发出、不附加 @昵称 前缀; 消息入库前把
 * `<atuserlist><![CDATA[wxid1,wxid2,...]]></atuserlist>` 注入 msgsource。
 *
 * 真机量到的事实（2026-10-22，别再往这些方向猜）：
 * - 裸 CGI 522 发不出「正文不带 @ 文本」的@：可见@（正文拼 `@昵称 `）能送达，隐蔽正文
 *   与卡片正文都只被服务器接受、对面什么都没有；补齐原生 `silence`/`membercount`/
 *   `notifystyleflag` 三个字段不改变任何一条结果。
 * - 入库注入用的 MsgSourceHelper 合并方法是**单节点专用**的：把 `<atuserlist>` 喂给
 *   sec_msg_node 键的方法，入库后 166 字节的 msgsource 里只多出一个**空的**
 *   `<sec_msg_node></sec_msg_node>`（对照：随后一条普通文本是 138 字节、不带这个节点），
 *   我们那份 atuserlist 整个被丢掉；alnode 键那个方法两种 z 都直接让宿主发送失败
 *   （红色感叹号）。
 * - 发送途中 `field_lvbuffer` 是 null，msgsource 是微信在插入过程中才组装出来的。
 *
 * 所以这一版只带两个侦察，不改发送行为：
 * - [resolveDex] 把宿主里所有提到 `atuserlist` 的方法打进日志，找微信自己@人时用的写入器；
 * - [dumpMsgSourceCandidates] 在注入前后把 MsgInfo **整条父类链**上所有 String 字段连值
 *   打出来（上一版只扫了叶子类自身的 12 个混淆字段，而 `field_*` 全在父类，等于没扫到）。
 *
 * 跨版本锚点 (8.0.65–8.0.77 混淆名各不相同, 不使用类名/方法名):
 * - 消息入库方法: WeMessageApi.methodMsgInfoHandleApiInsertMessage;
 * - msgsource 合并方法: MsgSourceHelper 里 "(?s)<sec_msg_node[^>]*>.*?</sec_msg_node>"
 *   正则字面量, static (msgInfo, String nodeXml, boolean) -> void —— 与 SendSecMsg 同一个，
 *   它是这条注入通路上唯一被真机证明能写出节点的写入器。
 */
object MentionMembers : SwitchFeature(), IResolveDex {

    override val technicalId = "@所有人"
    override val nameRes = R.string.feature_mention_members_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_mention_members_description

    /** 微信服务器对 atuserlist 人数的上限, 超出部分静默截断 */
    private const val MAX_AT_USERS = 200

    private const val TAG = "MentionMembers"

    private var stealthMentionAll by WePrefs.prefOption("mention_members_stealth_all", false)

    // 点击菜单确认后置位 (talker 到 atuserlist CSV); 消息入库发生在 performSend
    // 之后的异步流程里, 由入库钩子消费。发送失败残留的标记由 talker 不匹配兜底丢弃。
    @Volatile
    private var pendingStealthAt: Pair<String, String>? = null

    // MsgSourceHelper 的 sec_msg_node 合并方法: static (msgInfo, String nodeXml, boolean) -> void
    // —— SendSecMsg 用的同一个，是这条通路上唯一被真机证明不会打断发送的写入器；
    //    换成 alnode 键那个方法，两种 z 都会让宿主发送直接失败（红色感叹号）。
    private val methodMergeMsgSourceNode by dexMethod {
        matcher {
            usingEqStrings("(?s)<sec_msg_node[^>]*>.*?</sec_msg_node>")
            paramCount(3)
            returnType("void")
        }
    }

    /**
     * 侦察: 不取用也不缓存，只把宿主里所有提到 `atuserlist` 的方法打进日志。
     * 找到微信自己@人时写 atuserlist 的那个方法，隐蔽@才有合法写入器。
     */
    override fun resolveDex(dexKit: DexKitBridge) {
        val candidates = dexKit.findMethod {
            matcher {
                usingStrings("atuserlist")
            }
        }
        // 我们要的写入器长这样: static (msgInfo, String nodeXml, boolean) -> void
        val writerShape = "Ljava/lang/String;Z)V"
        val shaped = candidates.filter { candidate -> candidate.methodSign.endsWith(writerShape) }
        val listed = if (shaped.isEmpty()) candidates else shaped
        WeLogger.logChunkedD(
            TAG,
            "atuserlist recon: total=${candidates.size} writerShaped=${shaped.size}" +
                listed.take(60).joinToString(prefix = " | ") {
                    "${it.className}::${it.methodName}${it.methodSign}"
                },
        )
    }

    /**
     * 侦察: 打印整条父类链上的 String 字段。`MessageInfo` 读 `field_*` 走的是
     * `getField(name, superclass = true)`，只扫叶子类等于什么都没扫。
     */
    private fun dumpMsgSourceCandidates(instance: Any, label: String) {
        val strings = instance.reflekt().fields {
            superclass()
            type = String::class
        }.mapNotNull { field ->
            val value = field.get() as? String ?: return@mapNotNull null
            "${field.declaringClass.simpleName}.${field.name}=${value.take(120)}"
        }
        val carriers = strings.filter { it.contains("msgsource") }.ifEmpty { listOf("<none>") }
            .joinToString(" | ")
        WeLogger.logChunkedD(
            TAG,
            "stealth scan $label: class=${instance.javaClass.name} carriers=$carriers" +
                " strings=${strings.joinToString(" | ")}",
        )
    }

    private fun showSettingsDialog(context: Context) {
        showComposeDialog(context) {
            var stealthState by remember { mutableStateOf(stealthMentionAll) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.mention_members_settings_title)) },
                text = {
                    SwitchWidget(
                        title = stringResource(R.string.mention_members_stealth_label),
                        description = stringResource(R.string.mention_members_stealth_description),
                        checked = stealthState,
                        onCheckedChange = {
                            stealthState = it
                            stealthMentionAll = it
                        },
                    )
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
                                    // 原生发送输入框原文 (不加 @ 昵称前缀), atuserlist
                                    // 由入库钩子注入 msgsource
                                    val atUserIds = selectedContacts
                                        .map { it.wxId }
                                        .filter { it != "weixin" && it != "filehelper" }
                                        .take(MAX_AT_USERS)
                                    pendingStealthAt = currentConv to atUserIds.joinToString(",")
                                    WeLogger.i(
                                        TAG,
                                        "stealth armed: talker=$currentConv count=${atUserIds.size}" +
                                            " first=${atUserIds.take(3)}",
                                    )
                                    WeChatInputBarMenuApi.performSend(chatFooter)
                                    return@ContactsSelector
                                }

                                val content = chatFooter.lastText
                                val atNicknames = selectedContacts.joinToString("") { "@${it.nickname} " }
                                val isAllSelected = selectedContacts.size == allMembers.size
                                val atWxIds = if (isAllSelected) {
                                    "notify@all"
                                } else {
                                    selectedContacts.joinToString(",") { it.wxId }
                                }

                                val item = NewSendMsgItemProto(
                                    toUser = UserNameProto(currentConv),
                                    content = atNicknames + content,
                                    type = 1,
                                    msgSource = """<msgsource><atuserlist><![CDATA[$atWxIds]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>"""
                                )
                                val reqProto = NewSendMsgReqProto(
                                    count = 1,
                                    items = listOf(item)
                                )
                                val reqBytes = WeProto.encodeWithDefaults(reqProto)

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

    override fun onEnable() {
        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookBefore {
            val msgInfo = MessageInfo(args[0]!!)

            // 只处理自己发送的文本消息 (isSend=1, type=1)
            if (msgInfo.isSend != 1 || msgInfo.typeCode != 1) return@hookBefore

            val pending = pendingStealthAt ?: return@hookBefore

            // 消费标记; talker 不匹配 (发送失败残留、用户先在别的会话发言) 时丢弃,
            // 避免把 atuserlist 误注入无关消息
            pendingStealthAt = null
            if (msgInfo.talker != pending.first) {
                WeLogger.w(
                    TAG,
                    "stealth dropped: pending talker=${pending.first} incoming=${msgInfo.talker}" +
                        " content=${msgInfo.content.take(20)}",
                )
                return@hookBefore
            }

            dumpMsgSourceCandidates(args[0]!!, "before")
            // z=false: 只改内存 msgsource, 随入库方法写入
            methodMergeMsgSourceNode.method.invoke(
                null,
                args[0],
                "<atuserlist><![CDATA[${pending.second}]]></atuserlist>",
                false,
            )
            dumpMsgSourceCandidates(args[0]!!, "after")
            val buffer = msgInfo.lvBuffer
            val hasAtList = buffer?.toString(Charsets.ISO_8859_1)?.contains("atuserlist") == true
            WeLogger.i(
                TAG,
                "stealth injected: talker=${msgInfo.talker} content=${msgInfo.content.take(20)}" +
                    " count=${pending.second.split(',').count { it.isNotEmpty() }}" +
                    " lvbuffer=${buffer?.size ?: -1}B atuserlist=$hasAtList" +
                    " msgsource=${msgInfo.msgSource}",
            )
        }

        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
