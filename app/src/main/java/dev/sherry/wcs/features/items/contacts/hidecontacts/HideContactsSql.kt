package dev.sherry.wcs.features.items.contacts.hidecontacts

import dev.sherry.wcs.features.api.core.WeDatabaseApi

import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.utils.WeLogger

private const val TAG = "HideContacts.Sql"

/**
 * Query-time hiding.
 *
 * Almost everything WeChat displays comes out of SQLite, and almost every read funnels through one
 * wrapper — `ka5.b0.g(String sql, String[] args, int) -> Cursor` (`ka5/b0.java:1009`; `b0.B(sql,
 * args)` is just `g(sql, args, 0)`). Every `com.tencent.mm.storage.*` storage class goes through it,
 * so hiding a contact from a new surface is usually a matter of recognising one more query shape
 * rather than finding a new hook. That is what [WRAPPER_RULES] is for.
 *
 * One path bypasses the wrapper: the Moments feed and FTS (全局搜索) are both rewritten from
 * [WeDatabaseListenerApi]'s query listener — see [rewriteMomentsFeedSql] and [rewriteFtsSql].
 *
 * Bind arguments are always passed separately from the SQL text, so injecting literal
 * `NOT IN ('...')` predicates is safe everywhere here.
 */
fun HideContacts.installSqlHooks() {
    installWrapperHook()
    // 装好时记一次开关状态：下面每条判读日志都以 surfaceOn(SEARCH) 为前提交件，开关没开时现场会
    // 一片安静，那和「钩子根本没装上」在日志里长得一模一样，分不清。
    WeLogger.i(TAG, "query hooks installed; search surface on = ${surfaceOn(HideSurface.SEARCH)}")
}

// ── the wrapper chokepoint ───────────────────────────────────────────────────────────────────

/**
 * One recognisable query shape and the predicate to add to it.
 *
 * [surface] is the switch this shape belongs to. A rule whose surface is off does not fall through
 * to a later rule that would happen to match the same statement: the two contact-list rules below
 * overlap by design (a 标签 member query is also a contact-selector query), so "first shape wins,
 * then its own switch decides" is the only way the 标签页 switch can mean anything.
 *
 * [matches] is handed the SQL already lowercased, so predicates must be written in lower case.
 * [condition] receives the hidden-contact set and returns a single boolean expression; it is
 * inserted by [injectCondition], which handles joining onto an existing `WHERE` and staying ahead
 * of any `ORDER BY` / `GROUP BY` / `LIMIT` tail.
 */
private class SqlRule(
    val name: String,
    val surface: HideSurface,
    val matches: (lowerSql: String) -> Boolean,
    val condition: (hidden: Set<String>) -> String,
)

private val WRAPPER_RULES = listOf(
    SqlRule("conversation-list", HideSurface.CONVERSATIONS, ::looksLikeConversationListQuery) {
        "rconversation.username NOT IN (${it.toSqlList()})"
    },
    // 通讯录 -> 标签 -> 成员列表. A contact-selector query narrowed to label membership, matched first
    // so the 标签页 switch can drop just this surface. The matcher is a strict subset of the
    // contact-selector one below, so a host version that scopes labels differently simply falls
    // through to the 通讯录 rule instead of leaving label members unfiltered.
    SqlRule("contact-label", HideSurface.CONTACT_LABEL, ::looksLikeContactLabelQuery) {
        "rcontact.username NOT IN (${it.toSqlList()})"
    },
    // Qualify as rcontact.username: several of these queries are joins (e.g. "from rcontact,
    // bizinfo" for the 公众号 list, or the OpenIM left join) where a bare `username` would be an
    // ambiguous column reference.
    SqlRule("contact-list", HideSurface.CONTACTS, ::looksLikeContactSelectorQuery) {
        "rcontact.username NOT IN (${it.toSqlList()})"
    },
    // 主页搜索里那一条读联系人的语句。真机 8.0.76 抓到的原文是
    // `SELECT rowid, * FROM rcontact WHERE rcontact.username LIKE '<词>'` —— 它既没有
    // `pyinitial`/`quanpin` 也没有 ` order by `，上面的 contact-list 规则按形状认不出它，
    // 于是「主页搜索隐藏」开着、联系人照样能被搜出来（日志里那两行
    // `search on, no rule matched` 就是这么来的）。放在 contact-list **之后**：
    // 选择器/通讯录那条查询仍然归「通讯录隐藏」管，只有落空的这种搜索形状才归「主页搜索隐藏」。
    SqlRule("contact-search", HideSurface.SEARCH, ::looksLikeContactSearchQuery) {
        "rcontact.username NOT IN (${it.toSqlList()})"
    },
    // 通讯录 -> 新的朋友. Friend requests live in their own table (FMessageConversationStorage),
    // keyed by `talker`, so none of the rcontact rules above reach them. One rule covers the list,
    // the 4-avatar header strip, the total count AND the red-dot count, because all four run through
    // this same wrapper:
    //   select * from fmessage_conversation  ORDER BY lastModifiedTime DESC          (list)
    //   select * from fmessage_conversation  where isNew = 1 ORDER BY ... limit 4    (avatars)
    //   select count(*) from fmessage_conversation                                  (total)
    //   select count(*) from fmessage_conversation where isNew = 1 and fmsgIsSend < 2 (red dot)
    SqlRule("new-friends", HideSurface.NEW_FRIENDS, ::looksLikeNewFriendsQuery) {
        "talker NOT IN (${it.toSqlList()})"
    },
    // Unread totals: the launcher-icon badge and the 微信 bottom-tab count both come from
    // ConversationLogic's `unReadCount > 0` reads, which a hidden contact's messages would otherwise
    // still inflate. Covers the aggregation, the per-username incremental refresh and the
    // per-contact shortcut-badge join in one rule.
    SqlRule("unread-count", HideSurface.UNREAD, ::looksLikeUnreadCountQuery) {
        "rconversation.username NOT IN (${it.toSqlList()})"
    },
    // 朋友圈 -> 消息列表: likes and comments a hidden contact left, on our posts or on mutual
    // friends'. `talker` is the actor. The count(*) variants must be rewritten too or the 发现-tab
    // red dot and the list's paging counters go out of sync with the rows actually returned.
    //
    // NB: SnsComment DELETEs run through a different (exec) entry point and contain no "select", so
    // they can never match here — narrowing this rule would otherwise risk silently skipping the
    // deletion of a hidden contact's comments.
    SqlRule("moments-comments", HideSurface.MOMENTS, { it.contains("from snscomment") }) {
        "talker NOT IN (${it.toSqlList()})"
    },
    // 通讯录 -> 群聊 的底部「N 个群聊」计数 (ContactCountView with contactType == 2). The statement is
    // built inline in `ui/contact/f1.run()` (8.0.76 `f1.java:28`, 8.0.69 `e1.java:28`).
    //
    // The prefix alone does NOT identify it: ContactStorage.O — the 通讯录 contact count — emits the
    // exact same leading text whenever `includeBlack` is false (8.0.76 `storage/j4.java:462-466`,
    // 8.0.69 `storage/l3.java:436-440` append "type & 8 =0 and " between the two halves), which is
    // precisely how ContactCountView calls it for contactType == 1. That query's WHERE ends in a bare
    // `or username = 'weixin'`, so injectCondition must never touch it (see its KDoc), and task 6A
    // already corrects that count in a hookAfter — matching it here would compound the two.
    // looksLikeGroupCountQuery therefore also requires the bare-OR tail to be absent; see there.
    //
    // Everything e2.c appends after the prefix is parenthesised or a plain `and`, and there is no
    // ORDER BY / LIMIT tail, so appending is safe.
    SqlRule("group-count", HideSurface.CONTACTS, ::looksLikeGroupCountQuery) {
        "rcontact.username NOT IN (${it.toSqlList()})"
    },
    // 微信运动排行榜. `ExdeviceRankInfoStg` reads the ranking with
    // `select *, rowid from HardDeviceRankInfo where rankID = ? order by score desc`
    // (8.0.76 `f42/c.java:40`, 8.0.69 `oy1/c.java:40`); the table carries a real `username` column.
    // Ranks are renumbered by the UI afterwards, which is the intended result.
    //
    // Deliberately narrow: `select COUNT(*) from HardDeviceRankInfo where rankID = ?`
    // (ExdeviceRankInfoUI) decides insert-vs-update when the server pushes new rank data, so
    // filtering *that* could make WeChat insert duplicate rows.
    SqlRule("exdevice-rank", HideSurface.EXDEVICE, ::looksLikeExdeviceRankQuery) {
        "username NOT IN (${it.toSqlList()})"
    },
)

/**
 * Prefix of the 群聊 count statement, lowercased. `ui/contact/f1.run()` seeds its StringBuilder with
 * exactly this literal (8.0.76 `f1.java:28`, 8.0.69 `e1.java:28`, byte-identical).
 */
private const val GROUP_COUNT_PREFIX =
    "select count(username) from rcontact where type & 1 !=0 and type & 32 =0 and type & 8 =0 and verifyflag & 8 = 0"

/**
 * The tail that only `ContactStorage.O` (「N 位联系人」) ever produces — an unconditional
 * `sb6.append(" or username = 'weixin'")` at the very end of the WHERE (8.0.76 `storage/j4.java:487`,
 * 8.0.69 `storage/l3.java:461`).
 *
 * `O` assembles its statement as `"…type & 32 =0 and " + ("type & 8 =0 and " unless includeBlack) +
 * "verifyFlag & 8 = 0 "`, so with `includeBlack = false` — the only way ContactCountView ever calls
 * it (8.0.76 `ui/contact/f1.java:22`, 8.0.69 `ui/contact/e1.java:22`) — the text it emits starts with
 * [GROUP_COUNT_PREFIX] verbatim. The two statements first diverge in what `e01.e2` appends next, and
 * that differs again between the plain and the `usernameFlag` feature-flag path (`e2.b`/`e2.c` fork
 * on `com.tencent.mm.contact.d.a()` into `m`/`o` on 8.0.76, `k`/`m` on 8.0.69), so no substring of
 * that suffix is a stable discriminator.
 *
 * This tail is: it is emitted on every branch of `O` on both trees, and the 群聊 statement can never
 * contain it — `f1`/`e1` only ever append ` and rcontact.username != '…'` terms after `e2.c`.
 * Excluding it is also exactly the safety property [injectCondition] needs, since it is that bare OR
 * that makes an appended `AND` bind to the wrong operand.
 */
private const val CONTACT_COUNT_BARE_OR_TAIL = "or username = 'weixin'"

private fun looksLikeGroupCountQuery(lower: String): Boolean =
    lower.startsWith(GROUP_COUNT_PREFIX) && !lower.contains(CONTACT_COUNT_BARE_OR_TAIL)

private fun looksLikeExdeviceRankQuery(lower: String): Boolean =
    lower.startsWith("select *, rowid from harddevicerankinfo") && lower.contains("order by score")

private fun looksLikeNewFriendsQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("from fmessage_conversation")) return false
    // getByEncryptTalker is a single-row lookup, not a display list — filtering it would make a
    // hidden contact's own friend-request row unreadable to the rest of WeChat.
    return !lower.contains("encrypttalker=")
}

private fun looksLikeUnreadCountQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("rconversation")) return false
    // The literal predicate, not just the column: the homepage list query also selects unReadCount
    // but never filters on it, and it is already handled by looksLikeConversationListQuery.
    return lower.contains("unreadcount > 0")
}

private fun HideContacts.installWrapperHook() {
    if (WeDatabaseApi.methodSqliteWrapperRawQuery.isPlaceholder) {
        WeLogger.w(TAG, "SQLite wrapper query method not resolved; query-time hiding disabled")
        return
    }
    WeDatabaseApi.methodSqliteWrapperRawQuery.hookBefore {
        val sql = args.firstOrNull() as? String ?: return@hookBefore
        val rewritten = rewriteWrapperSql(sql)
        if (rewritten == null) {
            logUnmatchedContactQuery(sql)
            return@hookBefore
        }
        args[0] = rewritten
    }
}

/**
 * 全局搜索里那条读联系人的语句到底长什么样，只能从现场读出来。
 *
 * 联系人名下的形状很多，而 [WRAPPER_RULES] 刻意放过 `rowid=`/`encryptusername=` 这类单行读取；
 * 「开了搜索隐藏却什么都没挡」时，缺的就是这一行读数。只打前 80 个字符，够认表名和 where 的
 * 形状，不会把用户输入的关键词抄进日志。
 */
private fun logUnmatchedContactQuery(sql: String) {
    if (!HideContacts.surfaceOn(HideSurface.SEARCH)) return
    val lower = sql.lowercase()
    if (!lower.contains("rcontact")) return
    if (!lower.contains("like") && !lower.contains("match")) return
    WeLogger.d(TAG, "search on, no rule matched: ${sql.take(80)}")
}

/** Returns the rewritten SQL, or null to leave the query untouched. */
private fun rewriteWrapperSql(sql: String): String? {
    if (HideContacts.isTemporarilyShown) return null
    val hidden = HideContacts.hiddenContacts
    if (hidden.isEmpty()) return null

    val lower = sql.lowercase()
    val rule = WRAPPER_RULES.firstOrNull { it.matches(lower) } ?: return null
    if (!HideContacts.surfaceOn(rule.surface)) return null
    // wrapper 通路原本一句改写日志都没有，「到底有没有命中规则」只能靠猜；搜索这一条刚加了新规则，
    // 先只给它打点（其它规则每次通讯录渲染都会跑，打了会淹掉日志）。
    if (rule.surface == HideSurface.SEARCH) {
        WeLogger.d(TAG, "rule ${rule.name} matched: ${sql.take(80)}")
    }
    return injectCondition(sql, rule.condition(hidden))
}

private fun looksLikeConversationListQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("from rconversation")) return false
    // Match only the homepage list query, which spells out per-conversation display columns.
    // Folder-container / single-row lookups use `select *` (no such columns) and aggregate/count
    // reads lack them too, so they're skipped and left untouched. NB: we deliberately do NOT bail
    // on the substring "wcs_folder_" — when AggregateChats is enabled it appends its own
    // `NOT LIKE 'wcs_folder_%'` clause to this very query, and bailing on it would skip hiding.
    return lower.contains("conversationtime") &&
            lower.contains("unreadcount") &&
            lower.contains("digestuser")
}

/**
 * Recognises the 标签 member list: a contact-selector query that also filters on label membership.
 *
 * This is deliberately a strict subset of [looksLikeContactSelectorQuery] rather than an
 * independent shape: the rule sits in front of the 通讯录 one, so it can only ever claim statements
 * the 通讯录 switch would otherwise have handled. Anything it fails to recognise — a host version
 * that scopes labels some other way — then falls through to 通讯录 instead of leaving label members
 * unfiltered. Requiring a `labelIDList` *predicate* (not merely the column in the projection, which
 * the generic list query may also carry) is what keeps a plain 通讯录 query from being mistaken for
 * a label one.
 */
private val LABEL_MEMBERSHIP_PREDICATE = Regex("""labelidlist\s*(?:like|in|=)""")

private fun looksLikeContactLabelQuery(lower: String): Boolean =
    looksLikeContactSelectorQuery(lower) && LABEL_MEMBERSHIP_PREDICATE.containsMatchIn(lower)

/**
 * Recognises full contact-list queries (contact selector, 群聊 list, 标签 members, OpenIM, 公众号).
 * The table must be rcontact and the query must select pyinitial / quanpin.
 *
 * Those columns alone are NOT enough: WeChat uses one identical column list for list queries and
 * for single-row getters, so keying on them also matched `ContactStorage.p(rowid)`
 * ("... where rowid=N") and `ContactStorage.e0/v` ("... where username=X or encryptUsername=X").
 * Appending `AND rcontact.username NOT IN (...)` there made getContactByRowId return null for a
 * hidden contact, and — since AND binds tighter than OR — silently broke lookup by encryptUsername.
 * Both are lookups the rest of WeChat relies on, not display lists.
 *
 * Every real display list (`ContactStorage.x/y/z`-sorted, `U` for labels, `R` for OpenIM, the
 * BrandService join) ends in `order by showHead asc, ...`, while none of the single-row getters has
 * an ORDER BY at all — so requiring one separates them cleanly. The explicit bails are
 * belt-and-braces in case a future list query shape shows up without a sort.
 */
/**
 * 主页搜索读联系人的形状（真机 8.0.76）：`SELECT rowid, * FROM rcontact WHERE rcontact.username LIKE ?`。
 *
 * 与 [looksLikeContactSelectorQuery] 的关键区别是它既不带拼音列也不带 ` order by `，所以那条规则认不出它。
 * 这里反过来只认「按 LIKE 找联系人」，并且放过 `username =` 这类单行读 —— 那是宿主自己按名字取联系人，
 * 加一条 NOT IN 会让它读不到人（contact-list 规则因为同样的原因放过 `rowid=`/`encryptusername=`）。
 */
private fun looksLikeContactSearchQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("from rcontact")) return false
    if (!lower.contains("like")) return false
    if (lower.contains("where rowid=") || lower.contains("encryptusername=")) return false
    return !lower.contains("username =")
}

private fun looksLikeContactSelectorQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("from rcontact")) return false
    if (!lower.contains("pyinitial") && !lower.contains("quanpin")) return false
    if (lower.contains("where rowid=") || lower.contains("encryptusername=")) return false
    return lower.contains(" order by ")
}

/**
 * Inserts an extra WHERE predicate ahead of any ORDER BY / GROUP BY / LIMIT tail, joining onto an
 * existing WHERE when there is one. Mirrors ConversationGrouping.injectCondition.
 *
 * The trailing `AND` is safe against WeChat's WHERE builders because they parenthesise their OR
 * groups (e.g. ConversationStorage.O returns `((parentRef is null) or (parentRef in (...)))`).
 * Callers must not use this on a query whose WHERE ends in a bare OR — see the 通讯录 contact-count
 * query, which ends in `or username = 'weixin'`.
 */
fun injectCondition(sql: String, condition: String): String {
    val insertionPoint = listOf(" order by ", " group by ", " limit ")
        .map { sql.indexOf(it, ignoreCase = true) }
        .filter { it >= 0 }
        .minOrNull() ?: sql.length
    val head = sql.substring(0, insertionPoint)
    val tail = sql.substring(insertionPoint)
    val connector = if (head.contains(" where ", ignoreCase = true)) " AND " else " WHERE "
    return "$head$connector$condition$tail"
}

/** Renders a hidden-contact set as a single-quoted SQL value list with `''` escaping. */
fun Set<String>.toSqlList(): String =
    joinToString(",") { "'${it.replace("'", "''")}'" }

// ── global search (FTS) ──────────────────────────────────────────────────────────────────────

private const val SQL_SELECT_MESSAGE =
    "SELECT type, subtype, entity_id, aux_index, MAX(timestamp) as maxTime, count(aux_index) as msgCount, talker FROM FTS5MetaMessage"

private const val SQL_SELECT_MESSAGES_BY_KEYWORD =
    "SELECT FTS5MetaMessage.docid, type, subtype, entity_id, aux_index, timestamp, talker FROM FTS5MetaMessage"

/**
 * 服务通知搜索 (`FTS5ServiceNotifyStorage.queryNotifyMessage`, 8.0.76 `q23/j.java:112-135`,
 * 8.0.69 `uv2/j.java:107-130`).
 *
 * Shaped like the [FTS_SQL_REGEX] queries but its `aux_index` is pinned to the literal
 * `'notifymessage'` for every row, so an `aux_index NOT IN (...)` wrapper would filter nothing.
 * `FTS5MetaServiceNotify` is the one meta table with an extra `talker TEXT` column
 * (`q23/j.java:155`), and that is where the contact actually lives — hence its own branch.
 */
private const val SQL_SELECT_SERVICE_NOTIFY =
    "SELECT FTS5MetaServiceNotify.docid, type, subtype, entity_id, aux_index,"

/**
 * SearchRelatedChatroomTask / SearchCommonChatroomTask (`fts.logic.i` and `fts.logic.g`, `:41` /
 * `:39` on both trees). They select the chatroom name straight out of the ChatroomMember index, so
 * the plain `aux_index` wrapper hides a hidden *group* from 相关的群聊 / 共同群聊.
 */
private const val SQL_SELECT_CHATROOM_BY_INDEX = "SELECT aux_index FROM FTS5IndexChatroomMember"

/**
 * FTS meta tables whose `aux_index` column holds the contact/chatroom id.
 *
 * Table names are `"FTS5Meta" + storage.getTableSuffix()` (`i23/a.java:403`). The four added here
 * are ChatroomMember (`q23/a.java:48`), WeShop (`k15/m.java:38`), AIHistory (`wv4/h.java:40`) and
 * AIHistoryChat (`wv4/b.java:38`); ServiceNotify needs its own branch, see
 * [SQL_SELECT_SERVICE_NOTIFY]. AIHistoryChat only exists from 8.0.76 —
 * listing a table that a given version never creates simply never matches, which is harmless.
 * 朋友圈 has no FTS table at all on these versions, and `FTS5MetaSOSHistory` (搜一搜 query history,
 * `q23/i.java`) has neither an `aux_index` nor a `talker` column, so both are out of scope.
 */
private val FTS_SQL_REGEX =
    Regex("^SELECT (FTS5MetaContact|FTS5MetaTopHits|FTS5MetaKefuContact|FTS5MetaFeature|FTS5MetaWeApp|FTS5MetaFinderFollow|FTS5MetaFavorite|FTS5MetaChatroomMember|FTS5MetaWeShop|FTS5MetaAIHistory|FTS5MetaAIHistoryChat)\\.docid, type, subtype, entity_id, aux_index,.*")

/**
 * A query that already pins `aux_index` to one value — either a bind placeholder
 * (`i23/a.java:197`) or an inlined literal (`q23/h.java:133`, 在当前聊天里搜索聊天记录).
 *
 * Such a query is already scoped to a single chat, so adding `aux_index NOT IN (...)` can only ever
 * turn it into zero rows. That is exactly what used to happen when a hidden chat was opened (via
 * 临时显示 off) and the user searched inside it: the in-chat search silently returned nothing.
 */
private val AUX_INDEX_PINNED_REGEX = Regex("aux_index\\s*=\\s*[?']", RegexOption.IGNORE_CASE)

/**
 * The `chatroom TEXT, member TEXT` mapping table (`q23/b.java:47`) behind 共同群聊 / 群聊计数 /
 * 群成员建议. The two join shapes WeChat builds over it:
 * - `... JOIN FTS5ChatRoomMembers ON (aux_index = chatroom) WHERE member=? ...` —
 *   SearchChatroomByMemberTask (`k0.java:29`), SearchChatroomInMemberTask (`n0.java:36`),
 *   SearchChatroomCountTask (`m0.java:29`);
 * - `FROM FTS5ChatRoomMembers, FTS5MetaContact WHERE member IN (...) AND chatroom = aux_index` —
 *   SearchCommonChatroomTask (`s0.java:42`).
 */
private const val CHATROOM_MEMBERS_JOIN = "FTS5ChatRoomMembers ON (aux_index = chatroom)"
private const val CHATROOM_MEMBERS_CROSS_JOIN = "FROM FTS5ChatRoomMembers, "

/**
 * FTS (全局搜索) rewriting, called from [HideContacts.onQuery].
 *
 * It rides the same `WeDatabaseListenerApi` query listener the Moments feed rewrite uses rather than a
 * hook of its own: that API now covers **both** WCDB read entry points. They coexist on 8.0.76 and each
 * has one internal chokepoint — `compat.SQLiteDatabase`'s 3-arg `rawQuery` and `database.SQLiteDatabase`'s
 * 5-arg `rawQueryWithFactory` — and the two never call each other, so hooking only one leaves whole
 * features unreadable: 朋友圈 `SnsInfo` came out of compat while 全局搜索 is served by
 * `MicroMsg.FTS.FTSIndexDB` (`com/tencent/mm/plugin/fts/o.smali:1683`) on the database class. With the
 * old either-or install the search statements never even reached this function.
 *
 * [bindArgs] matters for the same reason: the FTS search tasks resolve a person into docids with
 * `… WHERE aux_index=? …`, so the identity is only in the bind array, never in the SQL text.
 */
fun rewriteFtsSql(sql: String, bindArgs: Array<out Any?>?): String? {
    if (HideContacts.isTemporarilyShown) return null

    // An empty set would render `aux_index NOT IN ()` — a SQLite syntax error that breaks ALL
    // global search while the feature is enabled but nothing is hidden yet.
    val hidden = HideContacts.hiddenContacts
    if (hidden.isEmpty()) return null

    // 标签成员表与「把人解析成 docid」那两条，归「标签内隐藏」和「主页搜索隐藏」共管，所以放在
    // SEARCH 那道总闸之前 —— 只开标签面时也必须生效。
    rewriteFtsMemberSql(sql, bindArgs, hidden)?.let { return it }

    if (!HideContacts.surfaceOn(HideSurface.SEARCH)) return null

    val rewritten = rewriteFtsStatement(sql, hidden)
    if (rewritten == null) {
        // Which FTS statement 全局搜索 actually runs is only knowable from the device, and every shape
        // below is a pattern match. 80 characters is enough to recognise the table and the where
        // clause without copying the user's search term into the log.
        if (sql.contains("FTS5")) WeLogger.d(TAG, "fts query left alone: ${sql.take(80)}")
        return null
    }
    WeLogger.d(TAG, "filtered hidden contacts out of an fts query: ${sql.take(80)}")
    return rewritten
}

/**
 * 标签的成员列表：`SELECT user FROM FTS5ContactLabels WHERE label_id=?;`
 * (`com/tencent/mm/plugin/fts/logic/h0.smali:109`，把 label_id 绑成十进制字符串后逐行读 `user`)。
 *
 * 「标签内隐藏」以前一直没生效就是因为这条读根本不走 `ka5.b0` wrapper，也不带 `labelIDList`
 * —— 真机 2026-10-31 的日志里 `labelidlist` 出现 0 次，而这条语句在用户翻标签页时打了 5 次。
 * `user` 就在投影里，外层直接筛掉即可。
 */
private const val SQL_LABEL_MEMBERS = "SELECT user FROM FTS5ContactLabels WHERE label_id=?"

/**
 * 搜索任务把「人」换成 docid 的那类读：`… WHERE aux_index=? …`，绑进去的第一个值就是被查的人
 * （`i23/a.smali:2379` 的 `w(int[], String, int)` 与 `fts/logic/n3.smali:640`；调用方全在
 * `plugin/fts/logic` 的搜索任务里 —— e0/g0/h0/y/n3，只读，不参与索引写入）。
 *
 * 命中时让这条语句返回零行：那个人就解析不出 docid，自然出不了结果。
 *
 * 只认占位符形式 `aux_index=?`：会话内搜索写的是 `aux_index='wxid_x'` 字面量
 * （见 [AUX_INDEX_PINNED_REGEX] 的说明），那种语句的 bindArgs 里装的是关键词，误判会把会话内
 * 搜索整个打死。同样只认 SELECT —— `DELETE FROM … WHERE rowid IN (SELECT docid FROM … WHERE
 * aux_index=? AND timestamp <= ?)`（`plugin/fts/o.smali:1172`）嵌着同形状的 subquery，
 * 给 DELETE 加条件只会让索引删不掉旧行，那是纯粹的副作用。
 */
private val AUX_INDEX_PLACEHOLDER = Regex("aux_index\\s*=\\s*\\?", RegexOption.IGNORE_CASE)

/** Returns the rewritten statement, or null when this read is none of the two member-shaped surfaces. */
private fun rewriteFtsMemberSql(sql: String, bindArgs: Array<out Any?>?, hidden: Set<String>): String? {
    if (HideContacts.surfaceOn(HideSurface.CONTACT_LABEL) && sql.startsWith(SQL_LABEL_MEMBERS)) {
        WeLogger.d(TAG, "filtered hidden contacts out of a label member list")
        return wrapWithNotIn(sql, "user", hidden)
    }

    if (!HideContacts.surfaceOn(HideSurface.SEARCH) && !HideContacts.surfaceOn(HideSurface.CONTACT_LABEL)) {
        return null
    }
    if (!sql.startsWith("SELECT", true)) return null
    if (!AUX_INDEX_PLACEHOLDER.containsMatchIn(sql)) return null
    val target = bindArgs?.firstOrNull() as? String ?: return null
    if (!HideContacts.isHiddenNow(target)) return null

    // 宿主把这些语句拼出来时都以字面 `;` 收尾（`i23/a.w` 里最后 append 的就是 ";"），条件要插到它
    // 之前；万一没有分号，原样追加也一样合法。
    val body = sql.trimEnd().removeSuffix(";")
    WeLogger.d(TAG, "vetoed an fts docid lookup for $target: ${sql.take(80)}")
    return "$body AND aux_index NOT IN (${hidden.toSqlList()});"
}

/** Returns the rewritten FTS query, or null to leave it untouched. */
private fun rewriteFtsStatement(sql: String, hidden: Set<String>): String? {
    // Checked first: its SQL also carries `aux_index = 'notifymessage'`, which the pinned-aux_index
    // bail below would otherwise (wrongly) treat as a chat-scoped search.
    if (sql.startsWith(SQL_SELECT_SERVICE_NOTIFY)) return wrapWithNotIn(sql, "talker", hidden)

    rewriteChatroomMembersSql(sql, hidden)?.let { return it }

    val matchesAuxIndexShape = FTS_SQL_REGEX.containsMatchIn(sql) ||
            sql.startsWith(SQL_SELECT_MESSAGE) ||
            sql.startsWith(SQL_SELECT_MESSAGES_BY_KEYWORD) ||
            sql.startsWith(SQL_SELECT_CHATROOM_BY_INDEX)
    if (!matchesAuxIndexShape) return null
    if (AUX_INDEX_PINNED_REGEX.containsMatchIn(sql)) return null

    return wrapWithNotIn(sql, "aux_index", hidden)
}

/**
 * Filters the `FTS5ChatRoomMembers` join that feeds 共同群聊, the 群聊数量 counter and the group-member
 * suggestions, so neither a hidden group (`chatroom`) nor a hidden contact (`member`) contributes.
 *
 * The predicate is pushed into the join's own `ON` clause rather than appended with
 * [injectCondition]. SearchChatroomInMemberTask wraps the join in a derived table that only projects
 * `docid, aux_index, timestamp`, so an appended `AND chatroom NOT IN (...)` would land in the outer
 * WHERE where neither column is in scope — a hard SQL error that would take global search down.
 *
 * Any other statement touching the table (the `SELECT DISTINCT chatroom FROM FTS5ChatRoomMembers;`
 * enumerations and the 标签 statistics query) is deliberately left alone: they end in a `;`, which
 * [injectCondition] would append past.
 */
private fun rewriteChatroomMembersSql(sql: String, hidden: Set<String>): String? {
    if (!sql.contains("FTS5ChatRoomMembers")) return null

    val list = hidden.toSqlList()
    val filter = "chatroom NOT IN ($list) AND member NOT IN ($list)"

    if (sql.contains(CHATROOM_MEMBERS_JOIN)) {
        return sql.replace(
            CHATROOM_MEMBERS_JOIN,
            "FTS5ChatRoomMembers ON (aux_index = chatroom AND $filter)"
        )
    }
    if (sql.contains(CHATROOM_MEMBERS_CROSS_JOIN) && !sql.contains(';')) {
        return injectCondition(sql, filter)
    }
    return null
}

/** Wraps a finished FTS query in a filtering outer SELECT on one of its projected columns. */
private fun wrapWithNotIn(sql: String, column: String, hidden: Set<String>): String =
    "SELECT * FROM (${sql.removeSuffix(";")}) AS a WHERE $column NOT IN (${hidden.toSqlList()});"

// ── moments feed ─────────────────────────────────────────────────────────────────────────────

// 在朋友圈信息流中隐藏被隐藏联系人发布的朋友圈; EnhanceQuery 会把信息流标记替换为 (1=1)
private const val FEED_MARKER_RAW = "(sourceType & 2 != 0 )"
private const val FEED_MARKER_ENHANCED = "(1=1)"

/** 信息流语句里已有的作者过滤子句（本功能或别的实现注入的），用来把名单并进去而不是跳过。 */
private val ALREADY_INJECTED_SNS =
    Regex("SnsInfo\\.userName\\s+not\\s+in\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)

/** Called from `HideContacts.onQuery`; returns null to leave the query untouched. */
fun rewriteMomentsFeedSql(sql: String): String? {
    if (HideContacts.isTemporarilyShown) return null
    if (!HideContacts.surfaceOn(HideSurface.MOMENTS)) return null

    val hidden = HideContacts.hiddenContacts
    if (hidden.isEmpty()) return null

    // 只处理主信息流查询: 排除个人主页 (userName=)
    if (!sql.contains("from SnsInfo", false)) return null
    if (sql.contains("SnsInfo.userName=", false)) return null

    // 已经注入过（本功能早前的重写，或别的实现注入的）：把名单并进去，别直接放过。
    // 早先这里是 `return null` —— 名单一变（新加一个密友）那条语句就永远不再更新。
    val injected = ALREADY_INJECTED_SNS.find(sql)
    if (injected != null) {
        val merged = injected.groupValues[1].split(',')
            .map { it.trim().trim('\'') }
            .filter { it.isNotEmpty() }
            .toSet() + hidden
        val mergedList = merged.joinToString(",") { "'${it.replace("'", "''")}'" }
        WeLogger.i(TAG, "merged moments feed filter (${merged.size}): ${sql.take(80)}")
        return sql.replaceRange(injected.range, "SnsInfo.userName NOT IN ($mergedList)")
    }

    val filter = " AND SnsInfo.userName NOT IN (${hidden.toSqlList()}) "

    val rewritten = when {
        sql.contains(FEED_MARKER_RAW) ->
            sql.replaceFirst(FEED_MARKER_RAW, FEED_MARKER_RAW + filter)

        // EnhanceQuery 先执行时, 信息流标记已变为 (1=1); 个人主页不会出现该精确形式
        sql.contains(FEED_MARKER_ENHANCED) ->
            sql.replaceFirst(FEED_MARKER_ENHANCED, FEED_MARKER_ENHANCED + filter)

        else -> return null
    }

    // SQL 原文前 80 字符一起打：这条改写命中过几十次却仍然显示动态，说明"命中的是不是出列表
    // 那条语句"从来没被证实过，光有次数判不了。
    WeLogger.i(TAG, "hid ${hidden.size} contacts from moments feed: ${sql.take(80)}")
    return rewritten
}
