package dev.sherry.wcs.features.items.contacts.hidecontacts

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.utils.WeLogger

private const val TAG = "HideContacts.Extra"

/**
 * The three hiding surfaces that neither SQL nor an adapter-input filter can reach.
 *
 * Everything in HideContactsSql.kt / HideContactsLists.kt cuts the contact out of a row WeChat reads
 * from `rcontact` / `rconversation` or out of a list an adapter is about to be built from. These three
 * are different in kind:
 *
 * - **最近转发** is a `List<String>` of talkers read straight off a local cache by
 *   `RecentForwardInfoStorage`; nothing about it is a query the rewriter recognises, and the entries
 *   are bare wxids rather than contact rows.
 * - **存储空间管理** is a `BaseAdapter` over an already-materialised row list; the list is never set
 *   through a method we can hook, so the earliest visible point is the moment the adapter is about to
 *   publish it.
 * - **状态** arrives as network protobuf and is turned into per-topic view models by one layout
 *   method; the members are decoded in Java afterwards, so there is no statement to rewrite.
 *
 * All three are opt-in (`defaultOn = false` in [HideSurface]): they are the surfaces users are least
 * likely to expect to change, so they ship off and stay off until asked for.
 *
 * 最近转发 rides two legs: the `List<String>` output filter, plus
 * [installSelectConvBlocklistHook] which writes WeChat's own per-picker blocklist
 * (`dk5.n0.q`) — that one covers every list in the contact picker that consults it, which the
 * output filter alone does not. An earlier third leg filtered `RConversationStorage` cursors; it
 * never fired on 8.0.76 and reached the homepage conversation list's table, so it was removed
 * (2026-11-06).
 */
fun HideContacts.installExtraSurfaceHooks() {
    installRecentForwardHook()
    installSelectConvBlocklistHook()
    installStorageManageHook()
    installStatusHook()
}

// ---------------------------------------------------------------------------------------------
// 转发选人面板（微信原生屏蔽集）
// ---------------------------------------------------------------------------------------------

/**
 * 往转发选人面板自己的屏蔽集里加隐藏联系人 —— 微信原生的「本次选人屏蔽谁」通道。
 *
 * `jk5.o4.e7()` 每次新建一个 `dk5.n0` 状态返回，它的 `q` 字段（HashSet）由 intent 的
 * `block_contact`/`Select_block_List` 和全局服务 `vh0.f3.qj()` 喂；面板里装配列表时逐个
 * `if (!hashSet.contains(username))` 才建行（`recent/o.invoke()` 实测就是这个形状）。所以写这一个集合
 * 覆盖面板所有认它的列表，不用再一个组件一个组件去堵名单/游标。
 *
 * 关键是作用域：state 对象是 `MvvmContactListUI` 这个 Activity 私有的、每次开面板新建，改它碰不到
 * 首页会话列表 —— 这也是不用 SQL 游标腿去够同一份 rconversation 的原因（那条会串面）。
 *
 * `dk5.n0` 上有 n/o/p/q 四个同型 HashSet，语义各不同（always_select / always_exclude /
 * already_selected / **block**），**只能按字段名 q 取，按类型会误写错集合**；因此把写入结果和候选
 * 字段名一起打日志，字段一漂移当场可见。
 */
private fun HideContacts.installSelectConvBlocklistHook() {
    if (methodSelectConvStateFactory.isPlaceholder) {
        WeLogger.w(TAG, "选人面板 state 工厂未解析; 只能用名单过滤兜底")
        return
    }
    WeLogger.i(
        TAG,
        "选人面板 state 工厂已解析: " +
            "${methodSelectConvStateFactory.method.declaringClass.name}.${methodSelectConvStateFactory.method.name}",
    )
    methodSelectConvStateFactory.hookAfter {
        if (isShowingAll) return@hookAfter
        if (!surfaceOn(HideSurface.RECENT_FORWARD)) return@hookAfter
        if (hiddenContacts.isEmpty()) return@hookAfter

        val state = result ?: return@hookAfter
        val blockField = runCatching {
            state.javaClass.reflekt().fields { name { it == "q" } }.map { it.self.makeAccessible() }.firstOrNull()
        }.getOrNull()
        if (blockField == null) {
            if (!pickerBlocksetProbed) {
                pickerBlocksetProbed = true
                WeLogger.w(
                    TAG,
                    "选人面板 state ${state.javaClass.name} 没有字段 'q'（名字漂了）; " +
                        "候选 HashSet 字段=" +
                        runCatching {
                            state.javaClass.reflekt().fields { type = HashSet::class.java }
                                .joinToString { it.self.name }
                        }.getOrNull(),
                )
            }
            return@hookAfter
        }
        val blockSet = blockField.get(state) as? MutableSet<Any> ?: return@hookAfter
        val before = blockSet.size
        blockSet.addAll(hiddenContacts)
        if (!pickerBlocksetProbed) {
            pickerBlocksetProbed = true
            WeLogger.i(
                TAG,
                "选人面板屏蔽集已写入 ${state.javaClass.name}.q $before->${blockSet.size} " +
                    "(hidden=${hiddenContacts.size})",
            )
        }
    }
}

/** 一次性确认字段 `q` 写对了没有；真机已验证，之后每次开面板再打就是纯噪音。 */
private var pickerBlocksetProbed = false

// ---------------------------------------------------------------------------------------------
// 最近转发（名单级）
// ---------------------------------------------------------------------------------------------

/**
 * 转发消息 -> 最近转发.
 *
 * `RecentForwardInfoStorage` keeps the frequent-forward targets and hands them out as `List<String>`
 * from the one `(int, int, int) -> List` method it declares; the entries are raw wxids, matched
 * against the hidden set directly. Cancelling here is a plain output filter: WeChat only displays
 * what it was handed, and it re-reads this list every time the forward picker opens, so the switch
 * takes effect on the next open.
 */
private fun HideContacts.installRecentForwardHook() {
    if (methodRecentForwardList.isPlaceholder) {
        WeLogger.w(TAG, "最近转发 query wasn't resolved; that list stays unfiltered")
        return
    }
    // 一次性确认解析落点：真机上「功能失效」既可能是过滤没命中，也可能是这条解析就没成功，
    // 光看有没有 filtered 日志分不出来（2026-11-05 现场结论：静态链路全部核实无误）。
    WeLogger.i(
        TAG,
        "最近转发 query resolved: " +
            "${methodRecentForwardList.method.declaringClass.name}.${methodRecentForwardList.method.name}",
    )

    methodRecentForwardList.hookAfter {
        if (isShowingAll) return@hookAfter
        if (!surfaceOn(HideSurface.RECENT_FORWARD)) return@hookAfter
        if (hiddenContacts.isEmpty()) return@hookAfter

        val targets = result as? List<*> ?: return@hookAfter
        val kept = targets.filterNot { it is String && isHiddenNow(it) }
        if (kept.size == targets.size) return@hookAfter

        val dropped = targets.size - kept.size
        val droppedIds = targets.filterIsInstance<String>().filter { isHiddenNow(it) }.take(5).joinToString()
        WeLogger.i(TAG, "filtered $dropped hidden target(s) from 最近转发 (ids=$droppedIds, kept=${kept.size})")
        result = ArrayList(kept)
    }
}

// ---------------------------------------------------------------------------------------------
// 存储空间管理
// ---------------------------------------------------------------------------------------------

/**
 * 我 -> 设置 -> 通用 -> 存储空间 -> 聊天记录管理.
 *
 * `CleanChattingAdapter` (an `android.widget.BaseAdapter`) holds its rows in a single `List` field and
 * never sets them through a method of its own — the list is handed over at construction and mutated
 * in place by the storage layer. The first point where the adapter's rows and the rows the list view
 * is about to render agree is therefore `BaseAdapter.notifyDataSetChanged`, so that is what this
 * hooks: filter the field immediately before the notify that publishes it.
 *
 * The rows are filtered by *content*, not by field name: the model class is obfuscated and its
 * username field has carried different names across versions, but no other row field ever holds a
 * wxid, so [mentionsAnyHidden] identifies the row without pinning a name.
 *
 * What this hook installs is the framework `BaseAdapter.notifyDataSetChanged`, which every adapter in
 * the process calls. That is acceptable because the body's first real check is an `isInstance` test
 * against this one adapter class — a single instanceof per notify for every other list — and it is
 * the only way to catch this adapter, which never overrides the method.
 */
private fun HideContacts.installStorageManageHook() {
    if (methodStorageAdapterGetView.isPlaceholder) {
        WeLogger.w(TAG, "存储空间 adapter wasn't resolved; that list stays unfiltered")
        return
    }

    val adapterClass = methodStorageAdapterGetView.method.declaringClass
    val rowsField = adapterClass.reflekt()
        .fields { type = List::class }
        .singleOrNull()
        ?.self
        ?.makeAccessible()
    if (rowsField == null) {
        WeLogger.w(TAG, "存储空间 adapter has no single row list; that list stays unfiltered")
        return
    }

    adapterClass.reflekt().firstMethod {
        name = "notifyDataSetChanged"
        parameterCount = 0
        superclass()
    }.hookBefore {
        // Cheapest check first: this hook sits on the framework method every adapter in the process
        // calls, so the branch that does not apply to us must not touch MMKV.
        if (!adapterClass.isInstance(thisObject)) return@hookBefore
        if (isShowingAll) return@hookBefore
        if (!surfaceOn(HideSurface.STORAGE_MANAGE)) return@hookBefore
        if (hiddenContacts.isEmpty()) return@hookBefore

        val rows = rowsField.get(thisObject) as? List<*> ?: return@hookBefore
        val kept = rows.filterNot { it != null && mentionsAnyHidden(it) }
        if (kept.size == rows.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${rows.size - kept.size} hidden row(s) from 存储空间管理")
        // Substituted, not mutated: the list may be a snapshot the storage layer still holds.
        rowsField.set(thisObject, ArrayList(kept))
    }
}

// ---------------------------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------------------------

/**
 * 发现 -> 状态 -> a topic's "N 位朋友" list.
 *
 * The list is assembled from network protobuf by one method that takes
 * `(Context, List<topic group>) -> List<group layout>`, so filtering its **input** keeps the avatars,
 * the member count and the per-row ordering the layout computes internally in agreement — the same
 * rule the MvvmList hooks follow.
 *
 * A group is an obfuscated model holding one "topic info" object; the info holds the topic id
 * (`String`) and the member list (`List`). Nothing here is addressed by name: the info is found as
 * the group's only field whose type declares a `List`, and rebuilt with its own
 * `(String, List)` / `(info)` constructors, which is how WeChat itself creates these objects. A
 * group whose every member is hidden is dropped instead of being rebuilt empty, so the layout never
 * sees an empty avatar stack.
 *
 * Unlike the other two, this one needs no refresh plumbing of its own: the layout method re-runs
 * every time the page is opened, and a partial-page toggle is an edge case not worth re-driving the
 * host UI for.
 */
private fun HideContacts.installStatusHook() {
    if (methodStatusGroupLayout.isPlaceholder) {
        WeLogger.w(TAG, "状态分组 layout wasn't resolved; that list stays unfiltered")
        return
    }

    methodStatusGroupLayout.hookBefore {
        if (isShowingAll) return@hookBefore
        if (!surfaceOn(HideSurface.STATUS)) return@hookBefore
        if (hiddenContacts.isEmpty()) return@hookBefore

        val groups = args[1] as? List<*> ?: return@hookBefore
        if (groups.isEmpty()) return@hookBefore

        val filtered = filterStatusGroups(groups) ?: return@hookBefore
        WeLogger.d(TAG, "filtered hidden contacts out of 状态 (${groups.size} -> ${filtered.size} group(s))")
        args[1] = filtered
    }
}

/** One 状态 group, as reached from the model the layout method is handed. */
private class StatusGroupShape(
    val group: Any,
    val info: Any,
    val items: List<*>,
)

/** Returns the groups to lay out, or null when nothing changed. */
private fun HideContacts.filterStatusGroups(groups: List<*>): List<Any>? {
    var changed = false
    val result = ArrayList<Any>(groups.size)

    for (group in groups) {
        val shape = readStatusGroup(group ?: continue)
        if (shape == null) {
            result.add(group!!)
            continue
        }

        val kept = shape.items.filterNot { it != null && mentionsAnyHidden(it) }
        if (kept.size == shape.items.size) {
            result.add(shape.group)
            continue
        }

        changed = true
        if (kept.isEmpty()) continue

        result.add(rebuildStatusGroup(shape, kept))
    }

    return if (changed) result else null
}

/**
 * Locates (topic info, member list) inside one group, or null when the shape is not what we expect.
 *
 * The info type is recognised by declaring both a `String` (the topic id) and a `List` (the members),
 * which is exactly what [rebuildStatusGroup] needs to reconstruct it.
 */
private fun readStatusGroup(group: Any): StatusGroupShape? {
    val infoField = group.javaClass.reflekt()
        .fields { superclass() }
        .firstOrNull { field ->
            if (!field.type.name.startsWith(PackageNames.WECHAT)) return@firstOrNull false
            val infoFields = field.type.reflekt().fields { superclass() }
            infoFields.any { it.type == String::class.java } && infoFields.any { it.type == List::class.java }
        }
        ?.self
        ?.makeAccessible() ?: return null

    val info = infoField.get(group) ?: return null
    val itemsField = info.javaClass.reflekt()
        .firstFieldOrNull {
            type = List::class
            superclass()
        }
        ?.self ?: return null
    val items = itemsField.makeAccessible().get(info) as? List<*> ?: return null

    return StatusGroupShape(group, info, items)
}

/**
 * Rebuilds a group around [kept] with WeChat's own constructors — the same pair the layout builds its
 * normal output with: the info type takes `(topic id, members)` and the group takes `(info)`.
 */
private fun rebuildStatusGroup(shape: StatusGroupShape, kept: List<Any?>): Any {
    val infoClass = shape.info.javaClass
    val topicId = infoClass.reflekt()
        .firstField { type = String::class; superclass() }
        .self.makeAccessible()
        .get(shape.info) as String
    val infoCtor = infoClass.getConstructor(String::class.java, List::class.java)
    val groupCtor = shape.group.javaClass.getConstructor(infoClass)
    return groupCtor.newInstance(infoCtor.newInstance(topicId, ArrayList(kept)))
}

/**
 * True when [value] — a 存储空间 row or a 状态 member — carries a hidden wxid.
 *
 * Neither the row classes nor the protobuf fields they wrap can be addressed by name: both are
 * obfuscated and their field order changes between versions. Matching on *content* is safe here
 * because the only way to produce a false positive is a display string that is character-for-character
 * a hidden contact's wxid.
 *
 * The scan goes one level into [PackageNames.WECHAT] model fields, because that is how deep the
 * identifier sits: the storage row holds a `...Model` wrapper, which holds the generated protobuf
 * object. It stops there on purpose — this runs for every row of a list, and recursing further would
 * start calling arbitrary host getters.
 */
private fun HideContacts.mentionsAnyHidden(value: Any): Boolean =
    stringFieldsOf(value).any { isHiddenNow(it) } ||
            nestedModelsOf(value).any { nested -> stringFieldsOf(nested).any { isHiddenNow(it) } }

private fun stringFieldsOf(value: Any): List<String> = value.javaClass.reflekt()
    .fields {
        type = String::class
        superclass()
    }
    .mapNotNull { it.self.makeAccessible().get(value) as? String }
    .filter { it.isNotEmpty() }

private fun nestedModelsOf(value: Any): List<Any> = value.javaClass.reflekt()
    .fields { superclass() }
    .mapNotNull { field ->
        if (!field.type.name.startsWith(PackageNames.WECHAT)) return@mapNotNull null
        field.self.makeAccessible().get(value)
    }
