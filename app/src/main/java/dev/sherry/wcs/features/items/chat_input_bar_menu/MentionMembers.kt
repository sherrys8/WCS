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
import dev.sherry.wcs.ui.content.m3.DropDownMenuWidget
import dev.sherry.wcs.ui.content.m3.DropdownOption
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
 * 输入框文本原样发出、不附加 @昵称 前缀; 消息入库前往 msgsource 插一个
 * `<atuserlist><![CDATA[wxid1,wxid2,...]]></atuserlist>` 节点，让对面被@而气泡里没有 @ 文本。
 *
 * 注入点在 2026-10-23 定下来了: 发送途中 `field_lvbuffer` 还是 null，真正承载 msgsource 的是
 * 父类上一个 String 字段（真机 `com.tencent.mm.storage.e9` → 父类 `c8.G`，值形如
 * `<msgsource><pua>1</pua><eggSeed>…</eggSeed></msgsource>`），入库那 166 字节的 lvbuffer
 * 就是它序列化出来的。MsgSourceHelper 的合并方法写的也正是它，但那种方法是**单节点专用**的：
 * 把 `<atuserlist>` 喂给 sec_msg_node 键的方法，只会多写出一个**空的**
 * `<sec_msg_node></sec_msg_node>`（对照: 随后一条普通文本是 138 字节、不带这个节点），
 * 我们那份 atuserlist 整个被丢掉。所以改成按值找到那个字段直接写。
 * `git+1ed287f2` 实测：字段确实写进去了（`c8.G` 带上 atuserlist、hook 零异常），
 * 但发送仍然红色感叹号 —— 失败点从「写不进去」移到「微信不接受这份 msgsource」。
 * 剩下两个可能各自成一档：
 * - [STEALTH_WRITER_SETTER] 改走 MsgInfo 自己的 setter（recon 里唯一提到 atuserlist 的
 *   `(String) -> boolean` 方法），排掉「绕开 setter 少了脏标记」；
 * - [STEALTH_WRITER_NATIVE_NODES] 仍写字段，但按真机原生 @all 补齐 `eggIncluded`/`silence`/
 *   `membercount`/`notifystyleflag`，排掉「缺配套兄弟节点」。
 * 两档都不通，就是「atuserlist 必须与正文里的 @ 文本对应」这条一致性检查成立，隐蔽@判死
 * （裸 522 那轮已经给出同向证据：正文没有 @ 文本时对面连消息都收不到）。
 *
 * 已排掉、别再试的方向:
 * - 裸 CGI 522 发不出「正文不带 @ 文本」的@：可见@（正文拼 `@昵称 `）能送达，隐蔽正文与
 *   卡片正文都只被服务器接受、对面什么都没有；补齐原生 `silence`/`membercount`/
 *   `notifystyleflag` 三个字段不改变任何一条结果。
 * - alnode 键的合并方法: `z=false` 与 `z=true` 两次都直接让宿主发送失败（红色感叹号）。
 *
 * 跨版本锚点 (8.0.65–8.0.77 混淆名各不相同, 不使用类名/方法名):
 * - 消息入库方法: WeMessageApi.methodMsgInfoHandleApiInsertMessage;
 * - msgsource 载体字段: 不写死名字，在整条父类链上按值（含 `<msgsource>` 的 String 字段）认;
 * - msgsource setter: 也不写死名字，按签名认（单参 String、返回 boolean、提到 atuserlist）。
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

    /** 隐蔽@的两种写法: A=走宿主自己的 setter, B=按值写字段并把原生兄弟节点补齐 */
    private const val STEALTH_WRITER_SETTER = "setter"
    private const val STEALTH_WRITER_NATIVE_NODES = "native"

    private var stealthWriterMode by WePrefs.prefOption(
        "mention_members_stealth_writer",
        STEALTH_WRITER_SETTER,
    )

    // 点击菜单确认后置位 (talker、atuserlist CSV、含自己的成员数); 消息入库发生在
    // performSend 之后的异步流程里, 由入库钩子消费。发送失败残留的标记由 talker 不匹配兜底丢弃。
    @Volatile
    private var pendingStealthAt: Triple<String, String, Int>? = null

    // MsgInfo 的 msgsource setter: (String) -> boolean
    // —— 方法名各版本混淆不同，按签名认：recon 里唯一提到 atuserlist 的单参 boolean 方法
    private val methodSetMsgSource by dexMethod {
        matcher {
            paramCount(1)
            paramTypes(String::class.java)
            returnType("boolean")
            usingStrings("atuserlist")
        }
    }

    /**
     * 侦察: 不取用也不缓存，只把宿主里所有提到 `atuserlist` 的方法打进日志。
     * 其中 `com.tencent.mm.storage.<MsgInfo>::s2(String)Z` 是唯一提到 atuserlist 的
     * 单参方法，若按值写字段这条不通，它就是下一个要试的宿主自带 setter。
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
            var writerState by remember { mutableStateOf(stealthWriterMode) }
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
                            title = stringResource(R.string.mention_members_stealth_writer_label),
                            // 传 description 会顶掉当前选中项的显示，这一行要的就是看见选了哪种
                            description = null,
                            value = writerState,
                            options = listOf(
                                DropdownOption(
                                    STEALTH_WRITER_SETTER,
                                    stringResource(R.string.mention_members_stealth_writer_setter),
                                ),
                                DropdownOption(
                                    STEALTH_WRITER_NATIVE_NODES,
                                    stringResource(R.string.mention_members_stealth_writer_native),
                                ),
                            ),
                            onValueChange = {
                                writerState = it
                                stealthWriterMode = it
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
                                    // 原生发送输入框原文 (不加 @ 昵称前缀), atuserlist
                                    // 由入库钩子注入 msgsource
                                    val atUserIds = selectedContacts
                                        .map { it.wxId }
                                        .filter { it != "weixin" && it != "filehelper" }
                                        .take(MAX_AT_USERS)
                                    pendingStealthAt = Triple(
                                        currentConv,
                                        atUserIds.joinToString(","),
                                        allMembers.size + 1,
                                    )
                                    WeLogger.i(
                                        TAG,
                                        "stealth armed: mode=$stealthWriterMode talker=$currentConv" +
                                            " count=${atUserIds.size} first=${atUserIds.take(3)}",
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
            val atNode = "<atuserlist><![CDATA[${pending.second}]]></atuserlist>"
            // B: 按真机抓到的原生 @all 把兄弟节点补齐（signature 是服务端下发的，伪造不了）
            val extraNodes = if (stealthWriterMode == STEALTH_WRITER_NATIVE_NODES) {
                "<eggIncluded>1</eggIncluded><silence>0</silence>" +
                    "<membercount>${pending.third}</membercount><notifystyleflag>1</notifystyleflag>"
            } else {
                ""
            }
            // 字段名各版本都不同，按值认：整条父类链上唯一装着 <msgsource> 的 String 字段
            // 就是发送时的源头（真机 e9 → 父类 c8.G，入库那 166B lvbuffer 由它序列化出来）
            val carrier = args[0]!!.reflekt().fields {
                superclass()
                type = String::class
            }.firstOrNull { field -> (field.get() as? String)?.contains("<msgsource>") == true }
            val current = carrier?.get() as String? ?: "<msgsource></msgsource>"
            val payload = if (current.contains("<atuserlist>")) {
                current
            } else {
                "<msgsource>$atNode$extraNodes" + current.removePrefix("<msgsource>")
            }
            if (stealthWriterMode == STEALTH_WRITER_SETTER) {
                // A: 走微信自己的 setter —— 它认不认 atuserlist、会不会顺手改掉 G，
                //    都由紧跟的 scan after 作证
                methodSetMsgSource.method.invoke(args[0], payload)
            } else if (carrier != null) {
                carrier.set(payload)
            } else {
                WeLogger.w(
                    TAG,
                    "stealth skipped: no msgsource String field on ${args[0]!!.javaClass.name}",
                )
            }
            WeLogger.i(
                TAG,
                "stealth injected: mode=$stealthWriterMode talker=${msgInfo.talker}" +
                    " count=${pending.second.split(',').count { it.isNotEmpty() }} written=$payload",
            )
            dumpMsgSourceCandidates(args[0]!!, "after")
        }

        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
