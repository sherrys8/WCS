package dev.sherry.wcs.features.items.contacts

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.TextView
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import dev.sherry.wcs.R
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.data
import dev.sherry.wcs.dexkit.dsl.dexClass
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.api.core.WeConversationApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeDatabaseListenerApi
import dev.sherry.wcs.features.api.ui.WeChatInputBarApi
import dev.sherry.wcs.features.api.ui.WeMainActivityBeautifyApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.items.contacts.hidecontacts.HideSurface
import dev.sherry.wcs.features.items.contacts.hidecontacts.installBlockHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.installExtraSurfaceHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.installListHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.installMomentsHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.installProfileHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.installSchedules
import dev.sherry.wcs.features.items.contacts.hidecontacts.installSearchHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.installSqlHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.installVoipHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.rewriteFtsSql
import dev.sherry.wcs.features.items.contacts.hidecontacts.rewriteMomentsFeedSql
import dev.sherry.wcs.features.items.contacts.hidecontacts.surfaceOn
import dev.sherry.wcs.features.items.contacts.hidecontacts.uninstallBlockHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.uninstallProfileHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.uninstallSchedules
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.getSystemService
import dev.sherry.wcs.utils.android.showToast
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import kotlin.math.sqrt


object HideContacts : ClickableFeature(), IResolveDex, WeChatInputBarApi.IInputBarListener,
    WeDatabaseListenerApi.IQueryListener {

    override val technicalId = "隐藏联系人"
    override val nameRes = R.string.feature_hide_contacts_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_hide_contacts_description

    private const val TAG = "HideContacts"

    private const val KEY_CONTACTS = "hidden_contacts"

    // One-time flag: older versions hid chats by writing parentRef='hidden_conv_parent'. Once we've
    // cleared that stale marker for the current hidden set (so #show / un-hide work again), we never
    // need to re-check. New hides rely purely on the query-time filter and never set the marker.
    private const val KEY_LEGACY_MIGRATED = "hidden_parentref_migrated"

    var hiddenContacts
        get() = WePrefs.getStringSetOrDef(KEY_CONTACTS, emptySet())
        set(value) {
            // Muting is a server-synced oplog (OpenImOpLogLogic), so only send it for contacts that
            // were just added — the previous version re-sent it for the entire set on every save.
            // NB: un-hiding deliberately does NOT restore the prior mute state; doing so would
            // overwrite a mute the user set themselves. See the design doc.
            val newlyHidden = value - WePrefs.getStringSetOrDef(KEY_CONTACTS, emptySet())
            WePrefs.putStringSet(KEY_CONTACTS, value)
            if (surfaceOn(HideSurface.MUTE)) {
                for (convId in newlyHidden) {
                    WeConversationApi.setDnd(convId, true)
                }
            }
            WeConversationApi.reloadConversations()
        }

    private object ScreenOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return

            val chattingUi = chattingUi?.get() ?: return
            val wxId = chattingUi.intent.getStringExtra("Chat_User")
            if (temporarilyShown || wxId !in hiddenContacts) return

            exitToMainActivity()
        }
    }

    private var chattingUi: WeakReference<ChattingUI>? = null

    // Registered against the application context, exactly once. It used to be registered on the
    // LauncherUI Activity inside doOnCreate — so every Activity recreation added another
    // registration — while onDisable unregistered against the application context, a different
    // Context, which throws and was being swallowed. Net effect: the receiver outlived the feature
    // and kept kicking the user out of hidden chats on screen-off after it was turned off.
    private var screenOffReceiverRegistered = false

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        // ACTION_USER_PRESENT used to be in this filter but onReceive never handled it.
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        HostInfo.application.registerReceiver(ScreenOffReceiver, filter)
        screenOffReceiverRegistered = true
        WeLogger.d(TAG, "registered screen off receiver")
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenOffReceiverRegistered) return
        screenOffReceiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(ScreenOffReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister screen off receiver", it) }
    }

    private object ShakeDetector : SensorEventListener {

        private var sensorManager: SensorManager? = null
        private var lastShakeTime: Long = 0
        private const val SHAKE_THRESHOLD = 4.5f // higher = harder shake required

        fun start(context: Context) {
            WeLogger.d(TAG, "starting shake detector")

            if (sensorManager != null) return

            sensorManager = context.getSystemService<SensorManager>()
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        fun stop() {
            WeLogger.d(TAG, "stopping shake detector")

            sensorManager?.unregisterListener(this)
            sensorManager = null
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH

            if (gForce > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (lastShakeTime + 1000 > now) return // 1-second debounce
                lastShakeTime = now

                exitToMainActivity()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // unused
        }
    }

    private fun exitToMainActivity() {
        WeLogger.d(TAG, "leaving conversation page")
        val ctx = HostInfo.application
        val intent = Intent(ctx, LauncherUI::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ctx.startActivity(intent)
    }

    override fun onEnable() {
        // --- home screen conversation list ---

        // Hide at query time: inject `username NOT IN (...)` into WeChat's list queries so hidden
        // contacts are filtered on every full read. Covers the homepage conversation list, the
        // contact selector / 群聊 / 标签 / 公众号 lists, and global search.
        installSqlHooks()

        // Block the per-row live-update notification that WeChat fires (type 3) when a new
        // message arrives. Without this the native ConversationStorage dispatcher pushes the
        // hidden contact's row directly to the list adapter — bypassing the SQL hook above —
        // and the contact reappears until the next full query. Cancelling the notification at
        // source means the adapter never sees the row, so there is no flash at all.
        hookNewMessageNotification()

        // Drop 拍一拍 messages sent by a hidden contact before they become a row.
        hookPatMessage()

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            migrateLegacyHiddenParentRef()

            val context = thisObject!!.reflekt()
                .firstField { type { it isSubclassOf Activity::class } }
                .get()!! as Activity

            registerScreenOffReceiver()

            installTitleGestures(context)
        }

        // --- shake to leave ---

        ChattingUI::class.reflekt().apply {
            firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as ChattingUI

                chattingUi = WeakReference(activity)

                val wxId = activity.intent.getStringExtra("Chat_User")
                if (temporarilyShown || wxId !in hiddenContacts) return@hookAfter

                ShakeDetector.start(activity)
            }

            firstMethod { name = "onPause" }.hookAfter {
                chattingUi?.clear()
                chattingUi = null
                ShakeDetector.stop()
            }
        }

        // --- adapter/list-level surfaces (通讯录, @成员选择器, 群成员列表, 收藏, 视频号点赞) ---
        //
        // Everything whose rows never pass through a query the SQL rewriter above can see. See
        // hidecontacts/HideContactsLists.kt.

        installListHooks()

        // --- global search results the SQL rewriter cannot reach ---
        //
        // 群聊内搜索成员 and 共同群聊好友建议. See hidecontacts/HideContactsSearch.kt.

        installSearchHooks()

        // NB: the 通讯录 -> 群聊 list (ChatroomContactAdapter) is NOT hooked at the adapter level.
        // Its cursor comes from ContactStorage.y(), whose SQL carries `from rcontact` + `pyInitial`
        // and is therefore already filtered by rewriteContactSelectorSql at the SQLite wrapper.
        //
        // A previous implementation additionally shifted adapter positions via a `hiddenPositions`
        // set. That was dead code (the set was always empty), and it was wrong in three ways: it
        // folded an ascending-only shift over an unordered MutableIntSet, it remapped getView but
        // not getItem/getItemId (so ChatroomContactUI's click listener would open the WRONG chat),
        // and it ignored temporarilyShown. Deleting it means a resolve failure degrades to "hidden
        // contact stays visible" instead of "tapping a row opens someone else's chat".

        // --- voip ---

        installVoipHooks()

        // --- moments inline likes/comments (mutual-friend posts) ---

        installMomentsHooks()

        // --- 最近转发, 存储空间管理, 状态 ---
        //
        // The three surfaces that are neither a rewriteable query nor an adapter input: a raw
        // `List<String>` cache, an adapter publishing a row list it never set, and a network-built
        // group list. See hidecontacts/HideContactsExtraSurfaces.kt.

        installExtraSurfaceHooks()

        // --- 禁止进入聊天 / 禁止查看资料 ---
        //
        // Gates the two Activities themselves rather than any list. See
        // hidecontacts/HideContactsBlock.kt.

        installBlockHooks()

        // --- 资料模拟非好友 / 拦截提交加好友 ---
        //
        // The profile page itself: friend-only rows and the add-friend commit. See
        // hidecontacts/HideContactsProfile.kt.

        installProfileHooks()

        // --- command ---

        WeChatInputBarApi.addListener(this)

        // --- moments feed ---

        WeDatabaseListenerApi.addListener(this)

        // --- notification ---
        //
        // Deliberately NOT installed here. WeChat raises new-message notifications from
        // CoreService, which the host manifest pins to :push — a process this feature never loads
        // in — so a dealNotify hook registered from here would never fire where it matters. Both
        // notification hooks now live in HideContactsNotifications, which loads in main + push and
        // reads this feature's persisted state out of WePrefs.

        // --- 定时显示/隐藏 ---
        //
        // Arms one AlarmManager alarm per enabled entry and applies whichever fire time most recently
        // passed while the process was down. See hidecontacts/HideContactsSchedule.kt — the catch-up
        // deliberately touches nothing but temporarilyShown, since no Activity exists at this point.

        installSchedules()

        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        uninstallSchedules()
        uninstallBlockHooks()
        uninstallProfileHooks()
        unregisterScreenOffReceiver()
        ShakeDetector.stop()
        chattingUi?.clear()
        chattingUi = null
        WeChatInputBarApi.removeListener(this)
        WeDatabaseListenerApi.removeListener(this)
        temporarilyShown = false
        WeConversationApi.reloadConversations()
    }

    /**
     * Toggles the temporary-show state. Mirrors the `#show` / `#hide` input-bar commands for
     * use by gesture-based triggers (e.g. triple-clicking the main-screen title).
     */
    fun toggleTemporarilyShown(context: Context) {
        if (temporarilyShown) {
            temporarilyShown = false
            showToast(context, context.localizedContactsString(R.string.contacts_hide_restored))
        } else {
            temporarilyShown = true
            showToast(context, context.localizedContactsString(R.string.contacts_hide_temporarily_shown))
        }
        WeConversationApi.reloadConversations()
    }

    /**
     * Writes the temporary-show state without any UI, for non-interactive callers — currently the
     * 定时显示/隐藏 scheduler, whose startup catch-up runs at process attach where no Activity (and
     * therefore no Toast) exists.
     *
     * Goes through the same refresh path as [toggleTemporarilyShown] and the `#show` / `#hide`
     * commands: `WeConversationApi.reloadConversations()` is what makes the query-time SQL filter
     * re-run, so skipping it would leave the list showing the previous state until the next full
     * re-query. It does **not** lock the flag — a manual toggle afterwards wins until the next
     * scheduled fire time.
     */
    fun setTemporarilyShown(shown: Boolean) {
        if (temporarilyShown == shown) return
        temporarilyShown = shown
        WeConversationApi.reloadConversations()
    }

    override fun onTextChanged(chatFooter: ChatFooter, text: String) {
        when (text) {
            "#show" -> {
                chatFooter.lastText = ""
                if (temporarilyShown) {
                    showToast(
                        chatFooter.context,
                        chatFooter.context.localizedContactsString(R.string.contacts_hide_already_shown),
                    )
                    return
                }
                temporarilyShown = true
                showToast(
                    chatFooter.context,
                    chatFooter.context.localizedContactsString(R.string.contacts_hide_shown_command_hint),
                )
                WeConversationApi.reloadConversations()
            }

            "#hide" -> {
                chatFooter.lastText = ""
                if (!temporarilyShown) {
                    showToast(
                        chatFooter.context,
                        chatFooter.context.localizedContactsString(R.string.contacts_hide_nothing_to_restore),
                    )
                    return
                }
                temporarilyShown = false
                showToast(
                    chatFooter.context,
                    chatFooter.context.localizedContactsString(R.string.contacts_hide_restored),
                )
                WeConversationApi.reloadConversations()
            }
        }
    }

    override fun onQuery(sql: String): String? = rewriteMomentsFeedSql(sql) ?: rewriteFtsSql(sql)

    // The parentRef marker older versions wrote via WeConversationApi.setConversationsVisibility to
    // hide a chat. WeChat's native list filter (m4.O) hides rows whose parentRef isn't null/empty.
    private const val LEGACY_HIDDEN_PARENT_REF = "hidden_conv_parent"

    // One-time cleanup for users upgrading from the parentRef-based hiding: clear the stale marker
    // for our currently-hidden chats. Without this, WeChat's own filter keeps hiding a chat (until
    // its next message resets parentRef) even after the user un-hides it, since un-hiding only drops
    // it from our set and never touched parentRef. Scoped to our hidden set so we don't disturb rows
    // hidden by 显隐全部对话 (ToggleAllConversationsVisibility), which shares the same marker.
    private fun migrateLegacyHiddenParentRef() {
        if (WePrefs.getBoolOrFalse(KEY_LEGACY_MIGRATED)) return

        val hidden = hiddenContacts
        if (hidden.isEmpty()) {
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            return
        }

        // DB not ready yet: leave the flag unset so we retry on the next launch.
        if (!WeDatabaseApi.isReady) return

        try {
            val inClause = hidden.joinToString(",") { "'${it.replace("'", "''")}'" }
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET parentRef = '' " +
                        "WHERE parentRef = '$LEGACY_HIDDEN_PARENT_REF' " +
                        "AND username IN ($inClause)"
            )
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            WeLogger.d(TAG, "cleared legacy hidden parentRef markers for ${hidden.size} chats")
        } catch (ex: Exception) {
            WeLogger.w(TAG, "failed to clear legacy hidden parentRef markers", ex)
        }
    }

    private var temporarilyShown = false

    /**
     * The predicate every hook should use: a contact counts as hidden only while the temporary-show
     * escape hatch (`#show` / triple-tap title) is off.
     */
    fun isHiddenNow(wxId: String): Boolean = !temporarilyShown && wxId in hiddenContacts

    /** For SQL rewriters, which bail wholesale rather than testing individual wxids. */
    val isTemporarilyShown: Boolean get() = temporarilyShown

    val autoRejectVoipEnabled: Boolean get() = autoRejectVoip

    var autoRejectVoip by prefOption("hide_auto_reject", false)

    /** Long-press the home-screen title for [longPressMs] to toggle the temporary reveal. */
    var longPressTitle by prefOption("hide_long_press_title", false)

    /** Minimum hold time, in milliseconds, that counts as a long press. */
    var longPressMs by prefOption("hide_long_press_ms", 500)

    var tripleClickTitle by prefOption("hide_triple_click_title", false)

    /**
     * Maximum gap between two taps, in milliseconds, for them to count as consecutive taps of the
     * triple-tap gesture. Set on the last tap of a triple (which is how WeChat's own double-tap
     * detection measures it), so a slower rhythm simply starts the count over.
     */
    var tripleTapMs by prefOption("hide_triple_tap_ms", 500)

    /**
     * Attaches the two 临时显示 gestures to the home-screen title.
     *
     * Both gestures share one touch listener on purpose — the title is a plain label with no other
     * behaviour, and a single listener is the only way to tell "held long enough to be a long press"
     * apart from "the first tap of a triple tap". The click listener is still installed, and is a
     * no-op: a View only receives ACTION_DOWN/UP events while it is clickable, so it is what keeps
     * the touch stream coming.
     *
     * Thresholds are read inside the listener rather than captured at install time, so changing
     * them in settings applies from the next touch without restarting WeChat.
     */
    private fun installTitleGestures(context: Activity) {
        val titleView = context.window?.decorView
            ?.findViewById<TextView>(android.R.id.text1)
        if (titleView == null) {
            WeLogger.w(TAG, "home-screen title not found; 长按/三击 gestures stay unavailable")
            return
        }

        var tapCount = 0
        var lastTapTime = 0L
        var downTime = 0L

        titleView.setOnClickListener { }
        titleView.setOnTouchListener { _, event ->
            // The listener outlives the feature: turning 隐藏联系人 off unhooks everything else but
            // leaves this View's listener in place, so it has to stay inert by itself.
            if (!isActive) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downTime = event.eventTime

                MotionEvent.ACTION_UP -> {
                    val longPress = longPressTitle && event.eventTime - downTime >= longPressMs
                    when {
                        longPress -> {
                            tapCount = 0
                            toggleTemporarilyShown(context)
                        }
                        tripleClickTitle -> {
                            tapCount = if (event.eventTime - lastTapTime > tripleTapMs) 1 else tapCount + 1
                            lastTapTime = event.eventTime
                            if (tapCount >= 3) {
                                tapCount = 0
                                toggleTemporarilyShown(context)
                            }
                        }
                        else -> tapCount = 0
                    }
                }

                MotionEvent.ACTION_CANCEL -> tapCount = 0
            }
            false
        }
    }

    // Hooks the ConversationStorage notify dispatcher to cancel per-row update events (type 3)
    // for hidden contacts before they reach list adapters. WeChat fires b(3, storage, talker)
    // synchronously after every new message, pin, or unread-state change; without this hook the
    // adapter sees the row immediately — before any SQL query runs — so the contact reappears
    // regardless of the query-rewrite filter. Cancelling the notification at source is
    // race-free: the hidden contact never reaches the adapter at all.
    //
    // Event type 5 (global reload) is not suppressed — that is the path reloadConversations() uses to
    // trigger a full re-query (which our SQL hook then filters correctly). The empty-talker check
    // additionally guards the "" sentinel used by reloadConversations().
    /**
     * Re-entrancy guard for [hookNewMessageNotification].
     *
     * `markAsRead` mutates the conversation via `ConversationStorage.updateUnreadByTalker`, and that
     * mutation makes the storage fire *this very notification again* for the same talker. Without a
     * guard the hook body re-enters itself with every condition still satisfied, recursing until the
     * thread's 4 MB stack is exhausted — which killed WeChat on startup (SIGSEGV in the guard page,
     * surfacing inside xlog's printf because ART only inserts stack-overflow checks in Java frames,
     * not in JNI) whenever a hidden contact had unread messages waiting to sync.
     *
     * Thread-local because WeChat dispatches this notification synchronously on the calling thread.
     */
    private val markingAsRead = ThreadLocal.withInitial { false }

    private fun hookNewMessageNotification() {
        val method = WeConversationApi.methodNotifyConversationChanged
        if (method.isPlaceholder) {
            WeLogger.w(TAG, "conversation notify method not resolved; new-message suppression unavailable")
            return
        }

        method.hookBefore {
            val eventType = args[0] as? Int ?: return@hookBefore
            if (eventType != 3) return@hookBefore
            if (temporarilyShown) return@hookBefore
            if (!surfaceOn(HideSurface.CONVERSATIONS)) return@hookBefore
            val talker = args[2] as? String ?: return@hookBefore
            if (talker.isEmpty()) return@hookBefore
            if (talker !in hiddenContacts) return@hookBefore

            // Already inside our own markAsRead: this is the storage echoing our write back at us.
            // Still cancel the event so the row never reaches an adapter, but do not write again.
            if (markingAsRead.get() == true) {
                result = null
                return@hookBefore
            }

            markingAsRead.set(true)
            try {
                WeConversationApi.markAsRead(talker)
            } finally {
                markingAsRead.set(false)
            }
            result = null
        }
    }

    /**
     * Suppresses 拍一拍 ("… 拍了拍 …") from a hidden contact.
     *
     * `PatMsgExtension.insertPatMsg` is the single writer of the pat system message: it either
     * creates a new `922746929` row or merges the pat into the existing one, and in both branches it
     * is what refreshes the conversation's digest and bumps it to the top of the homepage list.
     * Cancelling the call therefore removes the message row *and* the list disturbance in one go —
     * neither the conversation-list rewriter nor the per-row notification suppressor can help here,
     * because the row's talker is the *chat*, not the patter.
     *
     * The substituted return value is the method's own no-op result (`Pair.create(0L, 0L)`, taken
     * from its `t8.N0(...)` guard at `nq3/l.java:568` / `ti3/l.java:266`), so every caller sees a
     * shape it already handles: msgId 0 means "nothing was inserted".
     *
     * Only `fromUser` is tested. `talker` is the conversation the pat lands in, and a hidden
     * *conversation* is already handled by the query-time filter; `pattedUser` is frequently the
     * local user, whom we must never treat as hidden.
     *
     * NB this is the **only destructive** surface in 隐藏联系人: everywhere else a hidden contact's
     * content is merely not displayed and comes back verbatim once it is un-hidden, but here the row
     * is never written in the first place, so neither 临时显示 nor removing the contact from the
     * hidden list can recover it. It also means whether a given pat survives depends on the
     * [temporarilyShown] state *at the instant the message arrived*, not at the instant it is read.
     * Documented in the feature description; changing it would require buffering the pats instead.
     */
    private fun hookPatMessage() {
        if (methodPatMsgInsert.isPlaceholder) {
            WeLogger.w(TAG, "pat-message insert wasn't resolved; 拍一拍 stays visible")
            return
        }

        methodPatMsgInsert.hookBefore {
            if (!surfaceOn(HideSurface.PAT)) return@hookBefore
            val fromUser = args[1] as? String ?: return@hookBefore
            if (!isHiddenNow(fromUser)) return@hookBefore

            WeLogger.d(TAG, "suppressed a pat message from a hidden contact")
            result = android.util.Pair.create(0L, 0L)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showSettingsDialog(context)
    }

    //    private val methodMainAdapterPerformSearch by dexMethod()

    // WeChat's SQLite wrapper query: d95.b0.f(String sql, String[] args, int) -> Cursor. The
    // homepage conversation-list cursor (com.tencent.mm.storage.m4.A/B) is built through this
    // wrapper, NOT the standard SQLiteDatabase.rawQuery path WeDatabaseListenerApi hooks, so we
    // intercept it directly — the same chokepoint ConversationGrouping/AggregateChats use.
    /**
     * `AddressLiveList.e(List snapshotList)` — the 通讯录 MvvmList preprocessor.
     * See hidecontacts/HideContactsLists.kt for why this is the right cut point.
     */
    val methodAddressMvvmListPreprocessList by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.AddressLiveList"
            usingEqStrings("snapshotList")
        }
    }

    /**
     * `AtSomeoneLiveList.e(List snapshotList)` — the @成员选择器's MvvmList preprocessor, structurally
     * identical to [methodAddressMvvmListPreprocessList] and filtered by the same shared helper.
     *
     * `AtSomeoneLiveList` declares only `<init>`, `c()` (the log tag) and `e(List)`, and `"snapshotList"`
     * is the `o.g(...)` null-check literal that only `e` carries — unambiguous on 8.0.76
     * (`ui/chatting/atsomeone/AtSomeoneLiveList.java:35-36`) and on 8.0.69 (same file, same lines).
     *
     * `allowFailure` because this is a single opt-in surface: if the class is renamed on some version
     * in 8.0.65–8.0.76 we want the @成员 list to stay unfiltered, not to fail dex resolution for the
     * whole feature and take every other hidden-contact surface down with it.
     */
    val methodAtSomeoneMvvmListPreprocessList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.chatting.atsomeone.AtSomeoneLiveList"
            usingEqStrings("snapshotList")
        }
    }

    /**
     * `cc.d(List usernames)` — `SeeRoomMemberUI`'s adapter rebuild (`tc.d` on 8.0.69).
     *
     * Among the classes that carry the `"MicroMsg.SeeRoomMemberUI"` tag (the Activity itself plus its
     * adapter and three listeners) exactly one declares a `void (List)` method: the adapter, at
     * `chatroom/ui/cc.java:96` on 8.0.76 and `chatroom/ui/tc.java:95` on 8.0.69.
     */
    val methodSeeRoomMemberSetMemberList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.SeeRoomMemberUI")
            }
            paramTypes("java.util.List")
            returnType("void")
        }
    }

    /**
     * `SelectMemberUI.V6()` — the member-username list the @全体成员 / 删除成员 / 邀请 adapters load
     * from (`j7()` on 8.0.69).
     *
     * The class name is real, and it declares exactly one no-argument `List`-returning method on both
     * trees (`chatroom/ui/SelectMemberUI.java:115` on 8.0.76, `:178` on 8.0.69), so class + arity +
     * return type is unambiguous without needing the obfuscated method name.
     */
    val methodSelectMemberUiGetMemberList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.chatroom.ui.SelectMemberUI"
            paramCount = 0
            returnType("java.util.List")
        }
    }

    /**
     * `c.r(List)` — `FavoriteAdapter`'s single data-list setter (`c.t(List)` on 8.0.69).
     *
     * `"MicroMsg.FavoriteAdapter"` occurs in exactly one class app-wide
     * (`plugin/fav/ui/adapter/c.java` on both trees). That class declares two `void (List)` methods —
     * `d(List)` and `r(List)` — and only `r` uses the tag (in its catch block,
     * `c.java:1054` on 8.0.76 / `c.java:1051` on 8.0.69), so the tag + shape pair resolves to `r`
     * alone. `d(List)` uses no string constants at all.
     */
    val methodFavoriteAdapterSetDataList by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.FavoriteAdapter")
            paramTypes("java.util.List")
            returnType("void")
        }
    }

    /**
     * 搜索结果段的接活入口 —— 8.0.76 是 `plugin/fts/ui/a.o0(j23.v)`（`a.smali:719`）。
     *
     * 主页搜索每个结果段（联系人 / 聊天记录 / 公众号…）都继承这个基类，`o0` 把这一次搜索的
     * 结果对象交给段自己渲染。类和成员的名字全是混淆的，唯一稳定的是它打的那条日志字面量
     * `"search type %d | result %d"` —— 全 dex 只出现在这一个方法体里（已核），所以按"方法包含该
     * 字面量"来钉，比钉 `declaredClass` + 方法名都稳。
     */
    val methodFtsResultSectionReceive by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("search type %d | result %d")
            paramCount = 1
            returnType("void")
        }
    }

    /**
     * 主页搜索「联系人」那几行的适配器（`fm5/u`，tag 在 `fm5/u.smali:540`），以及同族的「会话」结果
     * 适配器（`fm5/e`，tag `MicroMsg.SearchConversationAdapter`）。
     *
     * 为什么必须换到这一层：三轮真机读数把上面那条 `a.o0` 的覆盖面钉住了 —— 它只管「结果从 FTS 查询里
     * 来的段」（聊天记录段 `e33.u` 实测少了一条），联系人那一整段一次都没经过它，而且整场搜索里连一条
     * 按关键词读 `rcontact` 的语句都没出现。`fm5/e`、`fm5/w` 才是调 `e01/jb.smali:154`（按关键词
     * LIKE 找人）的地方，`fm5/u` 就是那份结果的适配器 —— 行在这里bind 成 View，绕不过去。
     *
     * 类名混淆、tag 没混淆，所以按 tag 钉类。两者都 extends `com.tencent.mm.ui.z9`
     * （`smali_classes8/com/tencent/mm/ui/z9.smali:2`，`super android.widget.BaseAdapter`），
     * 它的 `getItem(int)`（`:177`）会按 `o(position)` 跳过 header 再从 map/cursor 取行，所以拿
     * `getItem` 定位「这一行是谁」是宿主自己的映射，比我们再数一遍位置可靠。
     */
    val classSearchResultAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.SearchResultAdapter")
        }
    }

    val classSearchConversationAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.SearchConversationAdapter")
        }
    }

    /**
     * The 视频号 like-drawer *refresh* callback — `jd.call(Object)` on 8.0.76
     * (`plugin/finder/feed/jd.java:50`), `zc.call(Object)` on 8.0.69.
     *
     * Two methods on each tree pair `"Finder.DrawerPresenter"` with `"[refreshData] Cost="`
     * (8.0.76: `feed/jd` + `feed/s5`; 8.0.69: `feed/zc` + `feed/t5`) — the like drawer and the
     * comment drawer. They are told apart by the builder whose retry-view they drive: the like
     * drawer carries `"…/FinderLikeDrawerBuilder"`, the comment drawer
     * `"…/FinderTimelineDrawerBuilder"`. All three strings together resolve to exactly one method on
     * each tree. Strings are preferred over a structural discriminator here because `allowFailure`
     * only guards the 0-hit case — a multi-hit would `error(...)` and take the whole feature down.
     */
    val methodFinderLikeDrawerRefresh by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "Finder.DrawerPresenter",
                "[refreshData] Cost=",
                "com/tencent/mm/plugin/finder/view/builder/FinderLikeDrawerBuilder"
            )
        }
    }

    /**
     * The 视频号 like-drawer *load-more* callback — `yc.call(Object)` on 8.0.76
     * (`plugin/finder/feed/yc.java:31`), `sc.call(Object)` on 8.0.69.
     *
     * `"Finder.DrawerPresenter"` + `"[loadMoreData] empty!"` matches two methods per tree (8.0.76:
     * `feed/yc` + `feed/v3`; 8.0.69: `feed/sc` + `feed/x3`). Unlike the refresh callback there is no
     * third string to separate them — the competing method carries *only* those two constants — so
     * the discriminator has to be structural: the like-drawer one calls
     * `FinderItem.getUnsignedId()` to stamp each entry with its feed id, the other never does.
     * `FinderItem` is an unobfuscated (kept) class, so that method name is stable across versions.
     */
    val methodFinderLikeDrawerLoadMore by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("Finder.DrawerPresenter", "[loadMoreData] empty!")
            invokeMethods {
                add { name = "getUnsignedId" }
                matchType = MatchType.Contains
            }
        }
    }

    /**
     * `fts.logic.q0.p(FTSResult)` — SearchChatroomMemberTask's body, the 群聊内搜索成员 task.
     *
     * `"SearchChatroomMemberTask"` is its `getName()` return value and occurs in exactly one class
     * app-wide on both trees (8.0.76 `plugin/fts/logic/q0.java:25`, 8.0.69 same path `:25`). That
     * class declares only `<init>(l, FTSRequest)`, `getName()` and `p(FTSResult)`, so class anchor +
     * one parameter + `void` resolves to `p` alone. See hidecontacts/HideContactsSearch.kt.
     */
    val methodFtsSearchChatroomMemberTask by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("SearchChatroomMemberTask")
            }
            paramCount = 1
            returnType("void")
        }
    }

    /**
     * `fts.logic.h.p(FTSResult)` — SearchCommonChatroomUserTask, the 共同群聊好友建议 task.
     *
     * Same shape argument as [methodFtsSearchChatroomMemberTask]:
     * `"SearchCommonChatroomUserTask"` occurs in one class only (8.0.76
     * `plugin/fts/logic/h.java:30`, 8.0.69 same path `:30`), which declares `<init>(k, FTSRequest)`,
     * `getName()` and `p(FTSResult)`. NB: the neighbouring tasks `g` and `s0` both report
     * `"SearchCommonChatroomTask"` — the `User` suffix is what makes this one unambiguous.
     */
    val methodFtsSearchCommonChatroomUserTask by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("SearchCommonChatroomUserTask")
            }
            paramCount = 1
            returnType("void")
        }
    }

    /**
     * `ContactStorage.getNormalContactCount(boolean includeBlack, String[], String...) -> int`
     * (`j4.O` on 8.0.76 `storage/j4.java:460`, `l3.O` on 8.0.69 `storage/l3.java:434`) — the
     * 通讯录 「N 位联系人」 footer.
     *
     * The log format string is unique to this method on both trees. Its result is adjusted rather
     * than its SQL rewritten: the statement ends in a bare `or username = 'weixin'`
     * (`j4.java:487` / `l3.java:461`), so an appended `AND ...` would bind to that OR's right
     * operand and silently do nothing. See hidecontacts/HideContactsLists.kt.
     */
    val methodNormalContactCount by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.ContactStorage",
                "getNormalContactCount, sql:%s, result:%d, includeBlack:%s, time:%d"
            )
        }
    }

    /**
     * `PatMsgExtension.insertPatMsg(String talker, String fromUser, String pattedUser, String
     * suffix, int createTime, long svrId) -> android.util.Pair` — 拍一拍
     * (`nq3/l.java:560` on 8.0.76, `ti3/l.java:260` on 8.0.69).
     *
     * `"insert pat msg %d %s %s"` appears in exactly one method per tree (`nq3/l.java:620`,
     * `ti3/l.java:320`); pairing it with the class tag keeps the match method-local.
     */
    val methodPatMsgInsert by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.PatMsgExtension", "insert pat msg %d %s %s")
        }
    }

    /**
     * `fb4.z0.D0(SnsInfo, SnsObject, Context, rs, boolean, d8, String, Map, Map, List)` —
     * `SnsUtil.snsInfoToSnsStruct`. Turns a post's raw `SnsObject` (attrBuf) into the UI-facing
     * struct for every Moments renderer. See hidecontacts/HideContactsMoments.kt for why this is
     * the right chokepoint for a hidden contact's inline likes/comments on someone else's post.
     */
    val methodSnsInfoToSnsStruct by dexMethod {
        matcher {
            usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")
        }
    }

    /**
     * `static void c3.H(c3 netSceneSnsSync, SnsObject snsObject)` (8.0.76; the same method is
     * `c3.I` on 8.0.69) — `NetSceneSnsSync`'s inlined `updateSyncDataCache`, the single writer of
     * the 发现 tab's "N 位朋友的新动态" state. See hidecontacts/HideContactsMoments.kt.
     *
     * Matched on the `SnsMethodCalculate.markStartTimeMs/markEndTimeMs` pair alone, deliberately.
     * The obvious extra anchors ("preRdUsername" / "isCoverPreRd" /
     * "updateSyncDataCache build previousRedDotInfo error") belong to a `previousRedDotInfo`
     * telemetry block that only exists from 8.0.76 onwards — including them would make this
     * delegate resolve to 0 hits on 8.0.65–8.0.75, and since it has no `allowFailure` that failure
     * marks the *whole* HideContacts feature as Failed, so `FeaturesLoader` would skip `startup()`
     * and every other hidden-contact surface would silently stop working.
     *
     * The narrower pair is still unambiguous: `"updateSyncDataCache"` occurs nowhere in the app
     * except inside this one method (8.0.76 `c3.java:106`/`:141`, 8.0.69 `c3.java:103`/`:111`), and
     * every user of it necessarily also carries the class-name constant from the same call. The
     * method shape — `static void (c3, SnsObject)` — is identical on both trees, so the hook body
     * needs no per-version branching.
     */
    val methodSnsSyncUpdateRedDotCache by dexMethod {
        matcher {
            usingEqStrings("updateSyncDataCache", "com.tencent.mm.plugin.sns.model.NetSceneSnsSync")
        }
    }

    // ── VoIPMP / ILink (the stack that actually runs on 8.0.7x) ──────────────────────────────
    // See hidecontacts/HideContactsVoip.kt for how these fit together.

    /** `ZIDL_ibmKH7hbMB.ZIDL_FBV(long, int, int, long, long, byte[] username, byte[][], boolean)` */
    val methodVoipMpLaunchIncomingCard by dexMethod {
        matcher {
            // 8.0.76 changed from "launchInComingCardAsync: " to "[volume report] launchInComingCardAsync: "
            usingStrings("MicroMsg.VoIPMP.CoreV2", "launchInComingCardAsync: ")
        }
    }

    /**
     * `mp5.q2.qa(Context, int, is4.r, long, long, String username, ArrayList, boolean)` — the
     * banner/notification/ringtone dispatcher. q2 declares exactly one 8-parameter method, so the
     * class anchor plus the parameter count is unambiguous.
     */
    val methodVoipMpLaunchBanner by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.VoIPMP.Launcher", "closeReceiverBanner")
            }
            paramCount = 8
            returnType("void")
        }
    }

    /** `mp5.q2.Qa()` — "rejectByShortCut", the entry WeChat's own quick-reject uses. */
    val methodVoipMpReject by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPMP.CoreV2", "rejectByShortCut")
            paramCount = 0
            returnType("void")
        }
    }

    /**
     * `nq5.e.a(String username, boolean videoCall, boolean outCall, long, boolean)` — the incoming
     * ringtone. NB: this is NOT the old `MicroMsg.RingPlayer` / "playSound, type: ..." match, which
     * resolved to the call-ENDED tone and therefore never silenced anything.
     */
    val methodVoipMpStartRing by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPRingtoneController", "startRing() called with: username = ")
        }
    }

    /** `xp5.b.d(String username, boolean, boolean, boolean)` — starts the VoIP foreground service. */
    val methodVoipMpStartFgs by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPVoIPNotificationHelper", "startFGS isBindVoIPForegroundService ")
        }
    }

    /** `mp5.q2.Ii(String toUser, ...)` — VoIPMP call-record insertion (未接听 / 已取消 / duration). */
    val methodVoipMpInsertMsg by dexMethod {
        matcher {
            paramTypes(
                "java.lang.String",
                "boolean",
                "int",
                "long",
                "long",
                "long",
                "int",
            )
            returnType("void")
            anyOf(
                MethodMatcher().apply {
                    usingEqStrings(
                        "MicroMsg.VoIPMP.Launcher",
                        "insertMsg() called with: toUser = ",
                    )
                },
                MethodMatcher().apply {
                    declaredClass {
                        usingEqStrings("MicroMsg.VoIPMP.Launcher", "closeReceiverBanner")
                    }
                },
            )
        }
    }

    // ── multitalk (群通话), used when the VoIPMP multitalk experiment is off ───────────────────

    /** `v0.G(MultiTalkGroup)` — MultiTalkManager.onInviteMultiTalk. */
    val methodMultiTalkOnInvite by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(SplitGroupCall.methodExitMultiTalk.data.declaredClassName)
            usingEqStrings(
                "MicroMsg.MT.MultiTalkManager",
                "onInviteMultiTalk All Var Value:\n isMute: %b isHandsFree: %b isCameraFace: %b multiTalkStatus: %s groupIsNull: %b",
            )
        }
    }

    /**
     * `v0.g(isReject, isMissCall, isPhoneCall, isNetworkError, boolean, boolean)` —
     * exitCurrentMultiTalk. Declared on the same `v0` (MultiTalkManager) as [methodMultiTalkOnInvite],
     * so the invite hook's `thisObject` is the receiver to invoke this on — no separate singleton
     * lookup needed.
     */
    // ── legacy v2protocal stack (only reached when the peer downgrades) ───────────────────────

    /** `nr4.y.x(...)` — the incoming float card. Shared by both stacks, so live on 8.0.76 as well. */
    val methodVoipShowFloatingCard by dexMethod {
        matcher {
            usingEqStrings(".ui.voip.VoipFloatView")
            paramCount = 8
        }
    }

    /**
     * `nr4.y.z(Context, String toUser)` — AnimatedVoipBaseFloatCardManager.showFinishCard, the
     * "已拒绝通话" banner shown *after* a rejection. Distinct from [methodVoipShowFloatingCard]
     * (the incoming card) even though both live on `nr4.y`, so suppressing the incoming card does
     * not cover it.
     *
     * The bare "showFinishCard" string constant occurs only in this method (the lambda classes
     * carry longer `...$showFinishCard$3$2$...` constants, which `usingEqStrings` will not match).
     */
    val methodVoipShowFinishCard by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("showFinishCard", "(Landroid/content/Context;Ljava/lang/String;)V")
            paramCount = 2
        }
    }
    val methodVoipAcceptIncomingCall by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "acceptIncomingCal, roomInfo:")
        }
    }
    val methodVoipStartAcceptVoip by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "startAcceptVoIP, roomInfo:")
        }
    }
    val methodVoipServiceExSetInviteContent by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to setInviteContent during calling, status =")
        }
    }
    val methodVoipServiceExReject by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to reject with calling, status =")
        }
    }

    /** `j0.j(String content, a65.j4 addMsg)` — server-pushed `<voipmsg>` bubble (msg type 50). */
    val methodVoipBubbleHandle by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPBubbleHelper", "handlerBubbleMsg: parse bubble info error")
        }
    }

    /**
     * `b2.d(String talker, String, int, int, String, boolean, k0, f16.l)` — legacy call-record
     * insertion.
     *
     * NB: do NOT match on "insertMsg() called with: voipInfo = " — those strings live in the
     * synthetic Runnable `b2$$a.run()`, which takes ZERO parameters, so the previous matcher made
     * `args[0]` throw on every legacy call record. The callagain URL is unique to b2 itself, and
     * `d` is the only 8-parameter method it declares.
     */
    val methodVoipLegacyInsertMsg by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.VoipPluginManager", "weixin://voip/callagain/?username=")
            }
            paramCount = 8
            returnType("void")
        }
    }

//    private val classVoipService by dexClass()
//    private val classVoipManager by dexClass()
//    private val classIncomingVoipInvite by dexClass()
//    private val classIncomingVoipILinkInvite by dexClass()
//    private val classMultiTalkInvite by dexClass()
//    private val classVoipFloatCard by dexClass()
//    private val classRecentForwardInfoHelperV3 by dexClass()
//    private val classContactRecommendHelperV3 by dexClass()

    // ── surfaces with neither a query we can rewrite nor an adapter input we can filter ────────
    // See hidecontacts/HideContactsExtraSurfaces.kt for how each one is filtered.

    /**
     * `RecentForwardInfoStorage`'s `(int, int, int) -> List` read — 转发 -> 最近转发.
     *
     * The storage class is obfuscated, the two constants are not: `"MicroMsg.RecentForwardInfoStorage"`
     * is its tag and `"start query frequentCount:"` is emitted by this one method only, and no other
     * method on that class pairs the tag with a three-int signature returning a list.
     *
     * `allowFailure`: this is a new opt-in surface whose anchors have not been verified against our
     * supported trees; a miss must leave 最近转发 unfiltered rather than fail resolution for the whole
     * feature, which would take every other hidden-contact surface down with it.
     */
    val methodRecentForwardList by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.RecentForwardInfoStorage", "start query frequentCount:")
            returnType("java.util.List")
            paramTypes("int", "int", "int")
        }
    }

    /**
     * `CleanChattingAdapter.getView(int, View, ViewGroup) -> View` — 存储空间 -> 聊天记录管理.
     *
     * Only resolved to name the adapter class: its rows are never handed over through a setter, so the
     * hook itself lands on `BaseAdapter.notifyDataSetChanged` with an `isInstance` guard. See the
     * extra-surfaces file.
     */
    val methodStorageAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.CleanChattingAdapter", "get item is null. [%d]")
            returnType("android.view.View")
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
        }
    }

    /**
     * 状态's `(Context, List<group>) -> List<group layout>` — turns the topic groups into the per-topic
     * avatar rows. `"arrow@name"` is a layout constant carried by this method alone.
     */
    val methodStatusGroupLayout by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("arrow@name")
            returnType("java.util.List")
            paramTypes("android.content.Context", "java.util.List")
        }
    }

    // ── 资料模拟非好友 / 拦截提交加好友 ─────────────────────────────────────────────────────────
    // See hidecontacts/HideContactsProfile.kt for how the two are used.

    /**
     * The 发送 button on WeChat's 验证 page: `SayHiWithSnsPermissionUI3$5.onClick(View)`.
     *
     * The listener is a synthetic class, so its own name is the only stable anchor a string query can
     * use; the log line next to it belongs to this method alone.
     */
    val methodVerifySubmit by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                $$"com/tencent/mm/plugin/profile/ui/SayHiWithSnsPermissionUI3$5",
                "mCommitBtn onClick() called needVerify:%s userName:%s",
            )
            returnType("void")
            paramTypes("android.view.View")
        }
    }
}
