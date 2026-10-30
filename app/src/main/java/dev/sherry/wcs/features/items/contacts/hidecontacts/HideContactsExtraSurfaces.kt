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
 */
fun HideContacts.installExtraSurfaceHooks() {
    installRecentForwardHook()
    installStorageManageHook()
    installStatusHook()
}

// ---------------------------------------------------------------------------------------------
// 最近转发
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

    methodRecentForwardList.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        if (!surfaceOn(HideSurface.RECENT_FORWARD)) return@hookAfter
        if (hiddenContacts.isEmpty()) return@hookAfter

        val targets = result as? List<*> ?: return@hookAfter
        val kept = targets.filterNot { it is String && isHiddenNow(it) }
        if (kept.size == targets.size) return@hookAfter

        WeLogger.d(TAG, "filtered ${targets.size - kept.size} hidden target(s) from 最近转发")
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
        if (isTemporarilyShown) return@hookBefore
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
        if (isTemporarilyShown) return@hookBefore
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
