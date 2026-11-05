package dev.sherry.wcs.features.items.contacts.hidecontacts

import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.reflection.BString
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList

private const val TAG = "HideContacts.Moments"

/** 分组字段探测结果只记一次，见 [installMomentsInlineEntryHook] 里的说明。 */
private var groupProbeLogged = false

/**
 * D0 入口探测只记一次：钩子装上了但整个测试期零命中时，分不出「这条渲染路径根本不经过 D0」和
 * 「钩子没挂上」。这句打在所有门禁之前，下一轮真机日志一看便知（2026-11-05 现场遗留）。
 */
private var d0ProbeLogged = false

/** Improve 路径入口探测只记一次，理由同 [d0ProbeLogged]。 */
private var improveProbeLogged = false

/** 详情页赞头像条（Y7）入口探测只记一次，理由同 [d0ProbeLogged]。 */
private var detailHeaderProbed = false

/** 详情页分组图标（W7）入口探测只记一次，理由同 [d0ProbeLogged]。 */
private var detailGroupIconProbed = false

/** feed 徽标谓词（X0）入口探测只记一次，理由同 [d0ProbeLogged]。 */
private var showGroupEnableProbed = false

/** Every Moments-side surface a hidden contact can leak through. */
fun HideContacts.installMomentsHooks() {
    installMomentsRedDotHook()
    installMomentsInlineEntryHook()
    installMomentsGroupBadgeHook()
}

/**
 * Keeps a hidden contact out of the 发现 tab's "N 位朋友的新动态" avatar + red dot.
 *
 * On 8.0.76 that entry is a *single* avatar, not an avatar strip, and the avatar it draws is driven
 * by one user-info KV pair: 68377 holds the wxid. "68377 is non-empty" is only *one of two* terms of
 * the dot predicate, though — `FindMoreFriendsUI.java:916-918` computes it as
 * `!(t8.K0(this.f226904x) && this.f226907y == 0)`, i.e. "68377 non-empty **OR** there are unread
 * likes/comments" (`f226907y` is `SnsCommentStorage.O0()`, `FindMoreFriendsUI.java:904`). The
 * bottom-tab dot in `com/tencent/mm/ui/ie.java:494` gates on 68377 alone. So suppressing 68377
 * removes the avatar and the 68377-attributable dot, but a hidden contact who likes or comments on a
 * post *you* can see still counts towards the unread-comment term — that surface is the inline
 * like/comment hook below, not this one.
 *
 * There is exactly one writer of that KV in the sync path — `NetSceneSnsSync.updateSyncDataCache`,
 * inlined by the decompiler into the synthetic accessor `static c3.H(c3, SnsObject)` at
 * `com/tencent/mm/plugin/sns/model/c3.java:103` (`c3.I` on 8.0.69), whose `:134` does
 * `j1.u().c().w(68377, snsObject.Username)`.
 *
 * So the fix is to cancel that whole cache write when the post's author is hidden, rather than to
 * patch the avatar view: it kills the avatar and the red dot in one place, and every other consumer
 * of KV 68377 inherits the fix for free — the chat-list banner
 * (`com/tencent/mm/ui/conversation/banner/z.java:44`) and the `+` menu's 朋友圈 entry
 * (`com/tencent/mm/ui/rg.java:371`) both read 68377 purely to decide whether to open Moments in
 * "there is something new" mode.
 *
 * Cancelling — as opposed to rewriting `Username` to `""` — is deliberate. `H` writes 68377 (author)
 * together with 68422/68418 (feed id), 68400 (create time) and 68421 (WeiShang feed type), and
 * `w2`'s `isNeedToUpdateRedDot` compares the *next* incoming post against all of them. Skipping the
 * method leaves those five fields internally consistent, still describing the last non-hidden post
 * that legitimately earned the red dot — which is exactly the state the user should keep seeing.
 * Blanking only the username would leave the other four fields pointing at a post nobody can see.
 *
 * One caveat to "consistent": `H` also *resets* the 68419/68420 counters, and cancelling it skips
 * those resets. 68420 is the "held the old red dot N times in a row" counter that
 * `isNeedToUpdateRedDot` increments on the branch where it does *not* call `H`
 * (`w2.java:191`), then compares against `clicfg_sns_red_dot_compare_times` (default 10). With `H`
 * permanently cancelled for hidden authors that counter only ever grows, so after ~10 suppressed
 * syncs WeChat starts biasing towards showing a dot for the next non-hidden post. That is benign —
 * it can only cause an *extra* dot for a post the user is allowed to see, never a leak — so it is
 * accepted rather than worked around.
 *
 * The method returns `void`, so cancellation is `result = null`, and it neither reads nor writes any
 * state we touch — there is no way for this hook to re-trigger itself.
 */
private fun HideContacts.installMomentsRedDotHook() {
    methodSnsSyncUpdateRedDotCache.hookBefore {
        if (!surfaceOn(HideSurface.DISCOVER)) return@hookBefore
        // Static two-arg method: args[0] is the NetSceneSnsSync instance the synthetic accessor was
        // handed, args[1] is the SnsObject. `Username` is a real (unobfuscated) protobuf field.
        val snsObject = args.getOrNull(1) as? SnsObject ?: return@hookBefore
        val username = snsObject.Username ?: return@hookBefore
        if (!isHiddenNow(username)) return@hookBefore
        WeLogger.i(TAG, "suppressing moments red-dot cache update for $username")
        result = null
    }
}

/**
 * 赞/评入口类（`a65.ha6` 族）的字段解析结果，feed 内联过滤与详情页过滤共用一份。
 * 入口类的字段名每版漂移，所以只在安装期解析一次、之后全部走这里持有的 Field 引用。
 */
private class MomentsEntryContext(
    val parseFrom: Method,
    val usernameField: Field,
    val replyUsernameField: Field,
)

private fun HideContacts.resolveMomentsEntryContext(): MomentsEntryContext? {
    // `a65.ha6`'s own field names are obfuscated and drift across versions, and the class name
    // itself isn't guaranteed either — so it is never referenced directly. Instead we borrow the
    // same trick FakeMomentsLikes already uses: ha6 is also the return type of
    // SnsCommentFooter.getCommentInfo(), and that method name is real (not obfuscated).
    val entryClass = runCatching {
        SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve the SNS like/comment entry class; moments hiding unavailable", it)
        return null
    }

    // Declared String fields, in source order, are [Username, Nickname, Content, ReplyUsername,
    // ...] — verified against a65/ha6.java (f9152d/f9153e/f9156h/f9160o) and cross-checked in
    // com/tencent/mm/plugin/sns/ui/widget/t2.java, which reads the same four fields by their real
    // names. Selecting by declared type + ordinal (mirrors SplitGroupCall's ILinkMember lookup and
    // HideContactsVoip.readMultiTalkInvite) survives a field rename; hardcoding "f9152d" would not.
    val stringFields = entryClass.reflekt().fields { type = BString }.map { it.self.makeAccessible() }
    val usernameField = stringFields.getOrNull(0)
    val replyUsernameField = stringFields.getOrNull(3)
    if (usernameField == null || replyUsernameField == null) {
        WeLogger.w(TAG, "failed to resolve username/replyUsername fields on the SNS entry class; moments hiding unavailable")
        return null
    }

    // SnsObject (com.tencent.mm.protobuf.f) has no clone(); the only reliable way to duplicate one
    // is a full serialize + reparse round trip through the protobuf codec it already implements.
    // parseFrom() isn't declared on the SnsObject stub (only toByteArray()/the LikeUserList family
    // are), so it is resolved reflectively once here, exactly as FakeMomentsLikes already does.
    val parseFromMethod = runCatching {
        SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve SnsObject.parseFrom; moments hiding unavailable", it)
        return null
    }

    WeLogger.i(
        TAG,
        "moments entry context resolved: entry=${entryClass.name}, " +
            "username=${usernameField.name}, replyUsername=${replyUsernameField.name}",
    )
    return MomentsEntryContext(parseFromMethod, usernameField, replyUsernameField)
}

private fun HideContacts.isEntryHidden(ctx: MomentsEntryContext, entry: Any): Boolean {
    val username = ctx.usernameField.get(entry) as? String
    if (username != null && isHiddenNow(username)) return true
    // A reply that quotes a hidden contact's own comment ("回复 张三: ...") still names them,
    // even when the reply's own author isn't hidden — so it must be stripped too.
    val replyUsername = ctx.replyUsernameField.get(entry) as? String
    return replyUsername != null && isHiddenNow(replyUsername)
}

/**
 * 从 [list] 里剔除隐藏联系人条目，返回（过滤后的 LinkedList 副本, 剔除数）；
 * 没有可剔除内容时返回 null（调用方保持原值不动）。
 */
private fun HideContacts.filteredEntryCopy(list: List<*>, ctx: MomentsEntryContext): Pair<LinkedList<Any>, Int>? {
    var dropped = 0
    val kept = LinkedList<Any>()
    for (entry in list) {
        if (entry == null) continue
        if (isEntryHidden(ctx, entry)) {
            dropped++
            continue
        }
        kept.add(entry)
    }
    if (dropped == 0) return null
    return kept to dropped
}

/**
 * Hides a hidden contact's likes/comments that surface inline under a *mutual friend's* Moments
 * post.
 *
 * These never touch the `SnsComment` table, so the "moments-comments" SQL rule in
 * HideContactsSql.kt (WRAPPER_RULES) — which filters rows selected `from SnsComment` — never sees
 * them. WeChat instead serializes them straight into the post's own protobuf blob:
 * `SnsInfo.attrBuf` -> `SnsObject.LikeUserList` / `CommentUserList` (`a65.ha6` entries).
 *
 * `fb4.z0.D0` ("SnsUtil.snsInfoToSnsStruct") 是经典渲染链把原始 `SnsObject` 转成 UI 结构 `dt`
 * 的唯一汇点。但 2026-11-05 真机探测显示这条链在用户的机器上零命中 —— 实际渲染走的是 Improve
 * 链：`f2.getItem -> ImproveDataUIC.d7 -> ce4.b.n()` 拿到 `ImproveSnsInfo`，各渲染组件（赞/评
 * 的 `ImproveInteractionLayout` 等）经 `ImproveSnsInfo.d1()`（"getSnsObject"）直接读缓存的
 * `SnsObject`。所以两条链都要堵：D0 在入口前换参，d1 在出口后换返回值，共用下面的
 * [stripSnsObject]。
 *
 * d1 的 6 个调用方全部是 UI 读侧（ae4/b、ae4/d、ImproveRecyclerView、wd4 的 measure/build 等），
 * 没有任何写路径经过它 —— 替换返回值不会污染缓存原对象，也不会影响之后的点赞/评论写回。
 */
private fun HideContacts.installMomentsInlineEntryHook() {
    val ctx = resolveMomentsEntryContext() ?: return
    WeLogger.i(
        TAG,
        "moments inline hook installed: struct=${methodSnsInfoToSnsStruct.method.declaringClass.name}." +
            "${methodSnsInfoToSnsStruct.method.name}",
    )

    methodSnsInfoToSnsStruct.hookBefore {
        if (!d0ProbeLogged) {
            d0ProbeLogged = true
            WeLogger.i(
                TAG,
                "snsInfoToSnsStruct first call, args1=${args.getOrNull(1)?.javaClass?.name ?: "null"}",
            )
        }
        if (isShowingAll) return@hookBefore
        val inlineOn = surfaceOn(HideSurface.MOMENTS_INLINE) && hiddenContacts.isNotEmpty()
        val iconOn = surfaceOn(HideSurface.GROUP_ICON)
        if (!inlineOn && !iconOn) return@hookBefore

        val original = args.getOrNull(1) as? SnsObject ?: return@hookBefore
        val stripped = stripSnsObject(original, ctx, inlineOn, iconOn) ?: return@hookBefore
        args[1] = stripped
    }

    // Improve 渲染链（8.0.76 实测主路径）：ImproveSnsInfo.d1() 是 UI 读侧拿 SnsObject 的汇点，
    // 在返回值上换过滤后的克隆，门禁与 D0 钩子完全一致。
    if (methodImproveSnsInfoGetSnsObject.isPlaceholder) {
        WeLogger.w(TAG, "improve getSnsObject wasn't resolved; improve feed stays unfiltered")
        return
    }
    WeLogger.i(
        TAG,
        "improve getSnsObject resolved: ${methodImproveSnsInfoGetSnsObject.method.declaringClass.name}." +
            "${methodImproveSnsInfoGetSnsObject.method.name}",
    )
    methodImproveSnsInfoGetSnsObject.hookAfter {
        if (!improveProbeLogged) {
            improveProbeLogged = true
            WeLogger.i(TAG, "improve getSnsObject first call, result=${result?.javaClass?.name ?: "null"}")
        }
        if (isShowingAll) return@hookAfter
        val inlineOn = surfaceOn(HideSurface.MOMENTS_INLINE) && hiddenContacts.isNotEmpty()
        val iconOn = surfaceOn(HideSurface.GROUP_ICON)
        if (!inlineOn && !iconOn) return@hookAfter

        val original = result as? SnsObject ?: return@hookAfter
        val stripped = stripSnsObject(original, ctx, inlineOn, iconOn) ?: return@hookAfter
        result = stripped
    }

    // 详情页：数据不经过 D0/d1，initView 直接把 SnsObject 的 LikeUserList/CommentUserList 活引用
    // 塞进 Y7（赞头像条）和 CommentListAdapter（评论/赞列表）。在这两个入口把参数换成过滤后的
    // 副本即可，不动 SnsObject 本身 —— s5.f 的缓存原对象保持完整，q2 写回安全。
    installMomentsDetailHook(ctx)
}

/**
 * D0 钩子与 Improve d1 钩子共用的过滤体：把 [original] 过滤成一份干净的克隆，
 * 没有可过滤内容时返回 null（调用方保持原值不动）。
 *
 * 赞/评过滤由 [inlineOn] 门控，分组标记由 [iconOn] 门控；[ctx] 持有已解析好的入口字段。
 */
private fun HideContacts.stripSnsObject(
    original: SnsObject,
    ctx: MomentsEntryContext,
    inlineOn: Boolean,
    iconOn: Boolean,
): SnsObject? {
    val likeList = original.LikeUserList as? List<*>
    val commentList = original.CommentUserList as? List<*>
    val hasHiddenLike = inlineOn && likeList?.any { it != null && isEntryHidden(ctx, it) } == true
    val hasHiddenComment = inlineOn && commentList?.any { it != null && isEntryHidden(ctx, it) } == true

    // 分组可见标记。SnsObject 的分组字段每版重命名，所以按「名字里含 group」探测，不钉名字。
    val groupFields = if (iconOn) {
        original.reflekt().fields { name { it.contains("group", ignoreCase = true) } }
            .map { it.self.makeAccessible() }
    } else {
        emptyList()
    }
    // 探测结果只报一次：这一句跑在每条动态的渲染上，但「一个字段都没搜到」和「搜到了却没标记」
    // 是两种完全不同的修法，光看有没有 stripped 日志分不出来。
    if (iconOn && !groupProbeLogged) {
        groupProbeLogged = true
        WeLogger.i(
            TAG,
            "group field probe on ${original.javaClass.name}: " +
                (if (groupFields.isEmpty()) "none" else groupFields.joinToString { "${it.name}:${it.type.simpleName}" }),
        )
    }
    val hasGroupMark = groupFields.any { field ->
        when (val value = runCatching { field.get(original) }.getOrNull()) {
            null -> false
            is String -> value.isNotEmpty()
            is Number -> value.toInt() != 0
            // GroupList/GroupUser/GroupContactTagIdList 是 LinkedList：非空即有分组标记，
            // 上一轮只查了 String/Int，列表型字段全部漏判 —— 这是分组图标一直显示的原因之一。
            is Collection<*> -> value.isNotEmpty()
            else -> false
        }
    }
    if (!hasHiddenLike && !hasHiddenComment && !hasGroupMark) return null

    // MUST operate on a clone, never on `original` directly: SnsInfoStorageLogic.e (s5.e in
    // the decompile) caches the parsed SnsObject keyed by a content hash and hands back that
    // SAME instance on every later render of this post. Stripping entries in place would
    // permanently truncate the cached object; the next time anyone (dis)likes or comments and
    // this object is re-serialized back into SnsInfo.attrBuf, the entries we removed here would
    // be gone from what gets persisted, not just from what gets displayed.
    val clone = SnsObject().also { ctx.parseFrom.invoke(it, original.toByteArray()) }

    if (hasHiddenLike) {
        val filtered = LinkedList((clone.LikeUserList as List<*>).filterNotNull().filterNot { isEntryHidden(ctx, it) })
        clone.LikeUserList = filtered
        clone.LikeUserListCount = filtered.size
        clone.LikeCount = filtered.size
    }
    if (hasHiddenComment) {
        val filtered = LinkedList((clone.CommentUserList as List<*>).filterNotNull().filterNot { isEntryHidden(ctx, it) })
        clone.CommentUserList = filtered
        clone.CommentUserListCount = filtered.size
        clone.CommentCount = filtered.size
    }
    if (hasHiddenLike || hasHiddenComment) {
        WeLogger.d(
            TAG,
            "hid hidden-contact entries under a moments post (likes ${likeList?.size ?: 0}->${clone.LikeUserList?.size ?: 0}, " +
                "comments ${commentList?.size ?: 0}->${clone.CommentUserList?.size ?: 0})",
        )
    }
    if (hasGroupMark) {
        // 只抹本机渲染出来的这一份克隆：服务端的可见范围完全不动，刷新/别人看还是原样。
        groupFields.forEach { field ->
            when (field.type) {
                BString -> field.set(clone, "")
                Int::class.javaPrimitiveType, Int::class.java -> field.setInt(clone, 0)
                // LinkedList 等集合型分组字段：清空列表即可抹掉标记。
                else -> (field.get(clone) as? MutableCollection<*>)?.clear()
            }
        }
        WeLogger.d(TAG, "stripped a moments post's group-visibility marker")
    }
    return clone
}

/**
 * Feed 侧的分组图标谓词：`ae4.p.X0()`（`ImproveSnsInfo.getShowGroupEnable`）。
 *
 * 8.0.76 真机显示：stripSnsObject 已经把 GroupList/GroupUser/GroupContactTagIdList 全部清空，
 * 但徽标仍在 —— 因为 X0 真正的判定走的是 ExtFlag 位组合（0x3/0x5/0x401/0x201/0x1001），
 * 列表清了 ExtFlag 没清。stripSnsObject 不能动 ExtFlag（否则会影响其它消费者），所以直接在
 * 谓词出口把 true 压成 false，视图层不碰、谓词层拦截，与浮云那套 OnGlobalLayoutListener 方案
 * 相比更干净。
 */
private fun HideContacts.installMomentsGroupBadgeHook() {
    if (methodImproveSnsInfoShowGroupEnable.isPlaceholder) {
        WeLogger.w(TAG, "improve showGroupEnable wasn't resolved; feed group badge stays unfiltered")
        return
    }
    WeLogger.i(
        TAG,
        "improve showGroupEnable resolved: ${methodImproveSnsInfoShowGroupEnable.method.declaringClass.name}." +
            "${methodImproveSnsInfoShowGroupEnable.method.name}",
    )
    methodImproveSnsInfoShowGroupEnable.hookAfter {
        if (!showGroupEnableProbed) {
            showGroupEnableProbed = true
            WeLogger.i(TAG, "showGroupEnable first call, result=${result?.javaClass?.name ?: "null"}")
        }
        if (isShowingAll) return@hookAfter
        if (!surfaceOn(HideSurface.GROUP_ICON)) return@hookAfter
        if (result != true) return@hookAfter
        result = false
    }

    // 详情页的分组图标：W7（"setGroupIcon"）内部判定「当前用户是作者 && 分组可见」才
    // setVisibility(0)+setOnClickListener，否则 setVisibility(8)。取消整个调用会让图标停在
    // 布局默认态（可见），且没注册点击 —— 用户看到的就是「图标可见但点不动」。所以这里改成
    // 把 args[0]（SnsObject）换成 Username 清空的克隆，让 W7 自己走到 else 分支 setVisibility(8)，
    // 图标真正 GONE，且不动 s5.f 的缓存原对象。
    if (methodSnsDetailSetGroupIcon.isPlaceholder) {
        WeLogger.w(TAG, "detail setGroupIcon wasn't resolved; detail group badge stays unfiltered")
        return
    }
    WeLogger.i(
        TAG,
        "detail setGroupIcon resolved: ${methodSnsDetailSetGroupIcon.method.declaringClass.name}." +
            "${methodSnsDetailSetGroupIcon.method.name}",
    )
    val groupCtx = resolveMomentsEntryContext()
    methodSnsDetailSetGroupIcon.hookBefore {
        if (!detailGroupIconProbed) {
            detailGroupIconProbed = true
            WeLogger.i(TAG, "detail setGroupIcon first call")
        }
        if (isShowingAll) return@hookBefore
        if (!surfaceOn(HideSurface.GROUP_ICON)) return@hookBefore
        val sns = args.getOrNull(0) as? SnsObject ?: return@hookBefore
        if (sns.Username == null) return@hookBefore
        val parseFrom = groupCtx?.parseFrom ?: return@hookBefore
        val clone = runCatching { SnsObject().also { c -> parseFrom.invoke(c, sns.toByteArray()) } }.getOrNull()
            ?: return@hookBefore
        clone.Username = null
        args[0] = clone
    }
}

/**
 * 详情页赞/评过滤：Y7（赞头像条）+ CommentListAdapter（评论/赞列表）。
 *
 * 详情页的数据不经过 D0/d1，initView 直接把 s5.f 新解析的 SnsObject 上的 LikeUserList/
 * CommentUserList 活引用传进来。Y7 的 5 个调用点首参都是 LikeUserList；adapter 构造器
 * args[1]=comments、args[2]=likes。在这两个入口把参数换成过滤后的副本即可，不动 SnsObject
 * 本身 —— s5.f 的缓存原对象保持完整，q2 写回安全。
 */
private fun HideContacts.installMomentsDetailHook(ctx: MomentsEntryContext) {
    if (methodSnsDetailSetLikedHeader.isPlaceholder) {
        WeLogger.w(TAG, "detail setLikedHeader wasn't resolved; detail likes header stays unfiltered")
    } else {
        WeLogger.i(
            TAG,
            "detail setLikedHeader resolved: ${methodSnsDetailSetLikedHeader.method.declaringClass.name}." +
                "${methodSnsDetailSetLikedHeader.method.name}",
        )
        methodSnsDetailSetLikedHeader.hookBefore {
            if (!detailHeaderProbed) {
                detailHeaderProbed = true
                WeLogger.i(TAG, "detail setLikedHeader first call, args0=${args.getOrNull(0)?.javaClass?.name ?: "null"}")
            }
            if (isShowingAll) return@hookBefore
            if (!surfaceOn(HideSurface.MOMENTS_INLINE) || hiddenContacts.isEmpty()) return@hookBefore
            val likes = args.getOrNull(0) as? List<*> ?: return@hookBefore
            val (copy, dropped) = filteredEntryCopy(likes, ctx) ?: return@hookBefore
            WeLogger.i(TAG, "hid $dropped hidden like(s) in the moments detail header")
            args[0] = copy
        }
    }

    if (ctorSnsDetailCommentAdapter.isPlaceholder) {
        WeLogger.w(TAG, "detail comment adapter ctor wasn't resolved; detail list stays unfiltered")
        return
    }
    WeLogger.i(
        TAG,
        "detail comment adapter ctor resolved: ${ctorSnsDetailCommentAdapter.constructor.declaringClass.name}." +
            "${ctorSnsDetailCommentAdapter.constructor.name}",
    )
    ctorSnsDetailCommentAdapter.hookBefore {
        if (isShowingAll) return@hookBefore
        if (!surfaceOn(HideSurface.MOMENTS_INLINE) || hiddenContacts.isEmpty()) return@hookBefore
        val comments = args.getOrNull(1) as? List<*>
        if (comments != null) {
            filteredEntryCopy(comments, ctx)?.let { (copy, dropped) ->
                WeLogger.i(TAG, "hid $dropped hidden comment(s) in the moments detail list")
                args[1] = copy
            }
        }
        val likes = args.getOrNull(2) as? List<*>
        if (likes != null) {
            filteredEntryCopy(likes, ctx)?.let { (copy, dropped) ->
                WeLogger.i(TAG, "hid $dropped hidden like(s) in the moments detail list")
                args[2] = copy
            }
        }
    }

    // 详情页数据就绪回调 onSceneEnd 会在构造器之后，把 s5.f 新解析 SnsObject 的 CommentUserList/
    // LikeUserList 重新赋给 adapter 的 f197164d/f197165e（mark "setListInfo"），覆盖构造器入口的
    // 过滤 —— 这是评论过滤命中却仍可见的真因。在这里 hookAfter 再补一层：把 adapter 的两个列表
    // 字段换成过滤后的副本（只换引用、不动 s5.f 缓存原列表，q2 写回安全）。
    if (methodSnsDetailOnSceneEnd.isPlaceholder) {
        WeLogger.w(TAG, "detail onSceneEnd wasn't resolved; comment list re-filter unavailable (ctor filter still applies)")
        return
    }
    WeLogger.i(
        TAG,
        "detail onSceneEnd resolved: ${methodSnsDetailOnSceneEnd.method.declaringClass.name}." +
            "${methodSnsDetailOnSceneEnd.method.name}",
    )
    methodSnsDetailOnSceneEnd.hookAfter {
        if (isShowingAll) return@hookAfter
        if (!surfaceOn(HideSurface.MOMENTS_INLINE) || hiddenContacts.isEmpty()) return@hookAfter
        val ui = thisObject ?: return@hookAfter
        // SnsCommentDetailUI.f196992v 是 CommentListAdapter 实例；字段名混淆每版漂，按「值类型名
        // 含 CommentListAdapter」认字段（该内部类名在 plugin.sns.ui 包是保留的、不混淆），不钉名字。
        // adapter 上的两个 LinkedList 字段按声明序是 [comments, likes]（构造器
        // <init>(UI, comments, likes, Activity, String) 的顺序）。
        val adapter = runCatching {
            ui.javaClass.reflekt().fields { type { it.name.contains("CommentListAdapter") } }
                .map { it.self.makeAccessible() }
                .firstOrNull()?.get(ui)
        }.getOrNull() ?: return@hookAfter
        val listFields = runCatching {
            adapter.javaClass.reflekt().fields { type = LinkedList::class.java }
                .map { it.self.makeAccessible() }
        }.getOrNull() ?: return@hookAfter
        val commentsField = listFields.getOrNull(0) ?: return@hookAfter
        val likesField = listFields.getOrNull(1)
        val comments = runCatching { commentsField.get(adapter) as? List<*> }.getOrNull()
        if (comments != null) {
            filteredEntryCopy(comments, ctx)?.let { (copy, dropped) ->
                WeLogger.i(TAG, "hid $dropped hidden comment(s) in the moments detail list (onSceneEnd)")
                commentsField.set(adapter, copy)
            }
        }
        if (likesField != null) {
            val likes = runCatching { likesField.get(adapter) as? List<*> }.getOrNull()
            if (likes != null) {
                filteredEntryCopy(likes, ctx)?.let { (copy, dropped) ->
                    WeLogger.i(TAG, "hid $dropped hidden like(s) in the moments detail list (onSceneEnd)")
                    likesField.set(adapter, copy)
                }
            }
        }
    }
}
