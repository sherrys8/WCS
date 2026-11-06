package dev.sherry.wcs.features.items.contacts

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.sherry.wcs.R
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.data
import dev.sherry.wcs.dexkit.dsl.dexClass
import dev.sherry.wcs.dexkit.dsl.dexConstructor
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.api.core.WeConversationApi
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.core.WeDatabaseListenerApi
import dev.sherry.wcs.features.api.ui.WeChatInputBarApi
import dev.sherry.wcs.features.api.ui.WeCurrentConversationApi
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
import dev.sherry.wcs.features.items.contacts.hidecontacts.rewriteMomentsCommentsSql
import dev.sherry.wcs.features.items.contacts.hidecontacts.rewriteMomentsFeedSql
import dev.sherry.wcs.features.items.contacts.hidecontacts.surfaceOn
import dev.sherry.wcs.features.items.contacts.hidecontacts.uninstallBlockHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.uninstallProfileHooks
import dev.sherry.wcs.features.items.contacts.hidecontacts.uninstallSchedules
import dev.sherry.wcs.features.items.system.agent.WeChatForegroundTracker
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.getSystemService
import dev.sherry.wcs.utils.android.getTopMostActivity
import dev.sherry.wcs.utils.android.showToast
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.sqrt


object HideContacts : ClickableFeature(), IResolveDex, WeChatInputBarApi.IInputBarListener,
    WeDatabaseListenerApi.IQueryListener {

    override val technicalId = "隐藏联系人"
    override val nameRes = R.string.feature_hide_contacts_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_hide_contacts_description

    private const val TAG = "HideContacts"

    /** 聊天 fragment 的包名前缀：当前页面属于它才算「此刻停在聊天里」（见 [finishHiddenChats]）。 */
    private const val CHATTING_UI_PACKAGE = "${PackageNames.WECHAT}.ui.chatting."

    private const val KEY_CONTACTS = "hidden_contacts"

    /** 输入栏指令的默认值：设置里这条被清空时按它恢复（见 [showCommand]）。 */
    const val DEFAULT_SHOW_COMMAND = "#show"

    /** 同 [DEFAULT_SHOW_COMMAND]，「恢复隐藏」那条。 */
    const val DEFAULT_HIDE_COMMAND = "#hide"

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
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (hideOnLock) enterAwayHidden()

                    // 退出隐藏聊天是这功能原有的行为，不受新开关影响；临时显示开着时照旧不动。
                    if (!temporarilyShown) finishHiddenChats()
                }
            }
        }
    }

    /**
     * 当前还活着的聊天页，**包含已经 onPause 的那些**。
     *
     * 这里原来是一个 `WeakReference<ChattingUI>` 单槽，并且在 `onPause` 里清空 —— 可息屏和按 Home 的
     * 次序正是「先暂停顶层 Activity，再广播 `ACTION_SCREEN_OFF` / 再判离开」，两者都在主线程，所以等
     * 触发点来取的时候它必然已经是 null，退出聊天这件事一次都没真正执行过。改成只在 `onDestroy` 摘除。
     *
     * `WeakHashMap` 的键是弱引用、且 Activity 没覆写 equals/hashCode（按身份比），既不拦 GC 也不会误合并。
     */
    private val openChats: MutableSet<ChattingUI> =
        Collections.newSetFromMap(WeakHashMap<ChattingUI, Boolean>())

    // Registered against the application context, exactly once. It used to be registered on the
    // LauncherUI Activity inside doOnCreate — so every Activity recreation added another
    // registration — while onDisable unregistered against the application context, a different
    // Context, which throws and was being swallowed. Net effect: the receiver outlived the feature
    // and kept kicking the user out of hidden chats on screen-off after it was turned off.
    private var screenOffReceiverRegistered = false

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        // 息屏是受保护的系统广播，运行时注册不用 RECEIVER_EXPORTED/NOT_EXPORTED 标记。
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

    private val awayHandler = Handler(Looper.getMainLooper())

    /**
     * 「按一次 Home 就隐藏」：[WeChatForegroundTracker] 判离开的口径是 onStart/onStop 计数归零，
     * 而 `onStop` 要等退出动画放完才来，所以体感是「得回到桌面才藏」。这里接上更早的那半个信号：
     * 任何页面 `onPause` 后等 [awaySettleMs] 再复检一次，其间只要有页面重新 resumed 就当没离开。
     *
     * 复检有两层，任一层成立就不触发：`onActivityResumed` 直接摘掉挂起的检查；检查本身再问一次
     * `getTopMostActivity(allowPaused = false)` —— 内部跳转的恢复偶尔落在窗口外，这层仍然认得出
     * 「还有个页面没 paused」。这里不能用 `isForeground`：按 Home 后 350ms 时页面还处在 paused 但
     * 尚未 stopped，按 started 计数的口径仍然算「在前台」，正好会把这次触发吃掉。
     *
     * 开关写在体内（和其余门控一样，改开关下一次生效），旧的 onStop 通路保留不动 —— 它现在是这套
     * 更快通路的兜底，重复调用 [enterAwayHidden] 不会二次重绘。
     */
    private val awayLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: Activity) {
            if (!hideOnLeave) return
            awayHandler.removeCallbacks(awaySettleCheck)
            awayHandler.postDelayed(awaySettleCheck, awaySettleMs.toLong())
        }

        override fun onActivityResumed(activity: Activity) {
            awayHandler.removeCallbacks(awaySettleCheck)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private val awaySettleCheck = Runnable {
        if (getTopMostActivity(allowPaused = false) != null) return@Runnable
        WeLogger.d(TAG, "away watchdog fired after ${awaySettleMs}ms settle")
        enterAwayHidden()
    }

    private var awayWatchdogRegistered = false

    private fun registerAwayWatchdog() {
        if (awayWatchdogRegistered) return
        HostInfo.application.registerActivityLifecycleCallbacks(awayLifecycleCallbacks)
        awayWatchdogRegistered = true
        WeLogger.d(TAG, "registered away watchdog")
    }

    private fun unregisterAwayWatchdog() {
        if (!awayWatchdogRegistered) return
        awayWatchdogRegistered = false
        awayHandler.removeCallbacks(awaySettleCheck)
        runCatching { HostInfo.application.unregisterActivityLifecycleCallbacks(awayLifecycleCallbacks) }
            .onFailure { WeLogger.w(TAG, "failed to unregister away watchdog", it) }
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

    /**
     * 触发隐藏就一定离开隐藏联系人的聊天页。8.0.76 的聊天页有两种承载，**常态是 fragment**，只处理
     * Activity 那条等于什么都没做（真机 2026-11-06 就是这么错的：看门狗触发 9 次，一次都没关成页面）：
     *
     *  - **LauncherUI 里的聊天 fragment**：主页之上压一个 `com.tencent.mm.ui.chatting.*` fragment，
     *    根本不是 Activity —— `HomeSidePanel` 判「在不在聊天里」用的就是 `currentFragmet != null`
     *    （`HomeSidePanel.kt:1138`）。所以 finish Activity、`startActivity(LauncherUI)` 都动不了它，
     *    只能调微信自己的 `MMFragment.finish()`，它内部走 `getSupportFragmentManager().popBackStack()`，
     *    正好落到会话列表。
     *    聊天对象取 `WeCurrentConversationApi.value`（由 `ChatFooter.setUserName` 维护）。它在离开聊天
     *    时**不会清空**，所以必须先确认当前 fragment 确实是聊天页再采信，否则会把用户从别的页面踢出来。
     *  - **`ChattingUI` Activity**：独立窗口和通知直达会话那两条路才会出现，`finish()` 掉就行。
     *
     * 一律不用 `startActivity`：从后台启动 Activity 属 Android 10+ 的 BAL 限制范围，锁屏那一刻随时会被
     * 静默丢弃；`finish()` / `popBackStack()` 不受这个限制。
     *
     * 每次触发都打一行状态：这一条曾经因为「静默 return」而让人完全看不出断在哪一环。
     */
    private fun finishHiddenChats() {
        val talker = WeCurrentConversationApi.value
        val hiddenTalker = talker.isNotEmpty() && talker in hiddenContacts
        val fragment = LauncherUI.getInstance()?.currentFragmet
        val fragmentClass = fragment?.javaClass?.name.orEmpty()
        WeLogger.d(
            TAG,
            "close hidden chats: hiddenTalker=$hiddenTalker fragment=$fragmentClass activityChats=${openChats.size}",
        )
        if (hiddenTalker && fragmentClass.startsWith(CHATTING_UI_PACKAGE)) {
            fragment?.finish()
        }

        // 先快照：finish() 会让微信走 onDestroy，直接在集合上迭代会撞上并发修改。
        for (activity in openChats.toList()) {
            val wxId = activity.intent.getStringExtra("Chat_User") ?: continue
            if (wxId !in hiddenContacts) continue
            WeLogger.d(TAG, "finishing hidden ChattingUI activity")
            activity.finish()
        }
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

        registerScreenOffReceiver()

        // 离开微信检测走 ActivityLifecycleCallbacks：比钩 onUserLeaveHint 稳（微信内部页面跳转
        // 时 startedCount 不会归零，不会误判成离开），ensureRegistered 与 addOnChanged 都幂等。
        // 这套口径要到 onStop 才成立，也就是「回到桌面才藏」，所以下面另有一套 onPause + 延时复检
        // 的看门狗把触发点提前到按 Home 的瞬间（见 [awayLifecycleCallbacks]）。
        //
        // 必须在 onEnable 里注册，不能只靠下面的 doOnCreate 钩子：运行中关了再开本功能时
        // LauncherUI 已存在、onCreate 不会重跑，而 onDisable 已把接收器和监听摘掉 —— 不在这里
        // 补注册，离开/息屏触发就全哑了（真机 2026-11-05 复现）。
        WeChatForegroundTracker.ensureRegistered()
        WeChatForegroundTracker.addOnChanged(foregroundListener)
        registerAwayWatchdog()

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            migrateLegacyHiddenParentRef()

            val context = thisObject!!.reflekt()
                .firstField { type { it isSubclassOf Activity::class } }
                .get()!! as Activity

            installTitleGestures(context)
        }

        // --- 聊天页跟踪：摇一摇退出的宿主，也是「触发隐藏时必须离开隐藏聊天」的数据源 ---

        ChattingUI::class.reflekt().apply {
            firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as ChattingUI

                openChats += activity

                val wxId = activity.intent.getStringExtra("Chat_User")
                if (temporarilyShown || wxId !in hiddenContacts) return@hookAfter

                ShakeDetector.start(activity)
            }

            // onPause 只停摇一摇，不再摘引用：离开/息屏的判定就发生在这之后，那时还得认得出停在哪个聊天。
            firstMethod { name = "onPause" }.hookAfter {
                ShakeDetector.stop()
            }

            firstMethod { name = "onDestroy" }.hookAfter {
                openChats -= thisObject as ChattingUI
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
        unregisterAwayWatchdog()
        ShakeDetector.stop()
        openChats.clear()
        WeChatInputBarApi.removeListener(this)
        WeDatabaseListenerApi.removeListener(this)
        WeChatForegroundTracker.removeOnChanged(foregroundListener)
        temporarilyShown = false
        awayHidden = false
        WeConversationApi.reloadConversations()
    }

    /**
     * Enters the persistent hidden state — the trigger side of away-hiding, and the only way
     * hiding starts.
     *
     * 隐藏一经触发就一直保持：回到前台、解锁都不恢复，只有长按/三击标题、显示指令、定时显示或
     * 关闭功能才解除。所以进隐藏时要先清掉上一次手动显示的覆盖，否则显示指令之后再离开微信，
     * 这一次隐藏会静默失效。
     *
     * Repaints through `WeConversationApi.reloadConversations()` rather than just flipping the flag:
     * the recents card and the lock-screen view are captured right around this callback, so the list
     * has to be rebuilt before the system takes that picture.
     *
     * 也是在这里收尾「离开隐藏联系人的聊天页」：停在谁的聊天里、从哪条路触发的隐藏都归一到这个入口，
     * 所以退出写在这一处，而不是按 Home 看门狗 / onStop / 息屏 三条路各写一遍（用户定：触发隐藏就必须
     * 回到主页会话列表，不挂任何表面开关）。
     */
    fun enterAwayHidden() {
        val wasHidden = awayHidden
        val wasShown = temporarilyShown
        temporarilyShown = false
        awayHidden = true
        // 已经处于隐藏态但覆盖还开着时也要重绘：状态位没翻，可列表此刻正显示着覆盖前的内容。
        if (!wasHidden || wasShown) WeConversationApi.reloadConversations()
        finishHiddenChats()
    }

    /**
     * The 手动恢复 entry shared by the title gestures (long press / triple tap) and by nothing else —
     * it reports with the same 显示提示文案 as the show command (see [showHintText]). Only meaningful
     * while away-hiding is active; afterwards the next leave / screen-off clears the override again
     * (see [enterAwayHidden]).
     */
    fun showTemporarily(context: Context) {
        if (temporarilyShown) {
            showToast(
                context,
                context.localizedContactsString(R.string.contacts_hide_already_shown),
            )
            return
        }
        temporarilyShown = true
        showToast(
            context,
            hintFor(
                context,
                showHintText,
                R.string.contacts_hide_shown_command_hint,
                hideCommand.trim().ifEmpty { DEFAULT_HIDE_COMMAND },
            ),
        )
        WeConversationApi.reloadConversations()
    }

    /**
     * Writes the temporary-show state without any UI, for non-interactive callers — currently the
     * 定时显示/隐藏 scheduler, whose startup catch-up runs at process attach where no Activity (and
     * therefore no Toast) exists.
     *
     * Goes through the same refresh path as the `#show` / `#hide` commands:
     * `WeConversationApi.reloadConversations()` is what makes the query-time SQL filter
     * re-run, so skipping it would leave the list showing the previous state until the next full
     * re-query. It does **not** lock the flag — a manual toggle afterwards wins until the next
     * scheduled fire time.
     */
    fun setTemporarilyShown(shown: Boolean) {
        if (temporarilyShown == shown) return
        temporarilyShown = shown
        WeConversationApi.reloadConversations()
    }

    /**
     * 输入栏指令派发：见 [showCommand] / [hideCommand]。
     *
     * 仍然是整串相等（不是前缀匹配）—— 这两条指令的语义是「切换显示状态」，误触发一次就把用户
     * 正在打的字清掉了。改成 trim 后比较，是因为设置里手输很容易带进首尾空格，那样指令会静默失效。
     *
     * 留空不等于关闭：按 [DEFAULT_SHOW_COMMAND] / [DEFAULT_HIDE_COMMAND] 生效，所以有效指令永远非
     * 空，不会和「刚被清空的输入框」相等而每轮自触发。
     */
    override fun onTextChanged(chatFooter: ChatFooter, text: String) {
        val typed = text.trim()
        val show = showCommand.trim().ifEmpty { DEFAULT_SHOW_COMMAND }
        val hide = hideCommand.trim().ifEmpty { DEFAULT_HIDE_COMMAND }
        // 两条指令被配成同一个字符串时按当前状态来回切：只按字面顺序匹配的话同串只会命中一条腿，
        // 用户切过去就切不回来，只能靠手势或改设置收拾。
        val wantHide = typed == hide && (typed != show || temporarilyShown)
        when {
            wantHide -> reHide(chatFooter)
            typed == show -> temporarilyShow(chatFooter)
        }
    }

    /**
     * 提示文案取词：[custom]（[showHintText] / [hideHintText]）留空就用内置文案，内置文案里的
     * `%1$s` 交给 Android 填成 [command]；自定义文案原样显示，只把 `{command}` 换成那条指令 —— 不
     * 走 String.format，免得用户写的百分号被当成格式符崩掉。
     */
    private fun hintFor(context: Context, custom: String, defaultRes: Int, command: String): String =
        if (custom.isBlank()) context.localizedContactsString(defaultRes, command)
        else custom.replace("{command}", command)

    private fun temporarilyShow(chatFooter: ChatFooter) {
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
            hintFor(
                chatFooter.context,
                showHintText,
                R.string.contacts_hide_shown_command_hint,
                hideCommand.trim().ifEmpty { DEFAULT_HIDE_COMMAND },
            ),
        )
        WeConversationApi.reloadConversations()
    }

    private fun reHide(chatFooter: ChatFooter) {
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
            hintFor(
                chatFooter.context,
                hideHintText,
                R.string.contacts_hide_restored,
                showCommand.trim().ifEmpty { DEFAULT_SHOW_COMMAND },
            ),
        )
        WeConversationApi.reloadConversations()
    }

    override fun onQuery(sql: String): String? =
        rewriteMomentsFeedSql(sql) ?: rewriteMomentsCommentsSql(sql) ?: rewriteFtsSql(sql)

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

    /** 离开微信或息屏时置位（[enterAwayHidden]），此后一直保持到手动恢复或功能关闭。 */
    @Volatile
    private var awayHidden = false

    /**
     * The predicate every hook should use: a contact counts as hidden only while the app is in the
     * away-hidden state (entered by leaving WeChat / screen off) and the `#show` / scheduled reveal
     * is off.
     */
    val isHidingActive: Boolean get() = awayHidden && !temporarilyShown

    fun isHiddenNow(wxId: String): Boolean = isHidingActive && wxId in hiddenContacts

    /** 隐藏没生效时所有闸口整体放行，SQL 改写器也按这个整条跳过。 */
    val isShowingAll: Boolean get() = !isHidingActive

    val autoRejectVoipEnabled: Boolean get() = autoRejectVoip

    var autoRejectVoip by prefOption("hide_auto_reject", false)

    /** 切到后台（按 HOME、切到别的应用）时进入隐藏态，回前台不恢复。 */
    var hideOnLeave by prefOption("hide_on_leave", false)

    /** 息屏时进入隐藏态，解锁不恢复。 */
    var hideOnLock by prefOption("hide_on_lock", false)

    /** 长按主页标题恢复显示的总闸；时长由 [longPressMs] 控制。 */
    var gestureLongPressEnabled by prefOption("hide_gesture_long_press", false)

    /** 三击主页标题恢复显示的总闸；间隔由 [tripleTapMs] 控制。 */
    var gestureTripleTapEnabled by prefOption("hide_gesture_triple_tap", false)

    /** 长按主页标题恢复显示：按住达到该时长（毫秒）即触发。 */
    var longPressMs by prefOption("hide_long_press_ms", 500)

    /**
     * 三击判定间隔（毫秒）：在最后一次点击时度量与前一次的间距，超过则重新从一计数。
     */
    var tripleTapMs by prefOption("hide_triple_tap_ms", 500)

    /**
     * 输入栏里的「临时显示」指令，默认 [DEFAULT_SHOW_COMMAND]。
     *
     * 可改是为了让用户避开自己聊天内容里会出现的文本：`onTextChanged` 由 `afterTextChanged`
     * 每次击键驱动，所以指令**输完即触发、不需要按发送**，靠清空输入框避免它真的发出去。代价是
     * 指令若与常用词重合，打字打到一半就会触发 —— 这两条偏好存在的理由。
     *
     * 空串不表示关闭，表示「用默认值」（设置侧写入时也会把空值改回默认，见 [onTextChanged]）。
     */
    var showCommand by prefOption("hide_show_command", DEFAULT_SHOW_COMMAND)

    /** 输入栏里的「恢复隐藏」指令，默认 [DEFAULT_HIDE_COMMAND]；与 [showCommand] 同规则。 */
    var hideCommand by prefOption("hide_hide_command", DEFAULT_HIDE_COMMAND)

    /**
     * 「临时显示」成功后提示的自定义文案；空 = 用内置文案。自定义文案里的 `{command}` 会换成
     * [hideCommand]，长按/三击手势也走这份文案（见 [showTemporarily]）。
     */
    var showHintText by prefOption("hide_show_hint_text", "")

    /** 「恢复隐藏」成功后提示的自定义文案；空 = 用内置文案，`{command}` 换成 [showCommand]。 */
    var hideHintText by prefOption("hide_hide_hint_text", "")

    /**
     * 按 Home 后隔多久复检「是不是真的离开了微信」（毫秒），见 [awayLifecycleCallbacks]。
     * 默认 350ms：短了会赶上微信内部页面还没 resume 的间隙而误触发，长了就退回原来的迟钝。
     */
    var awaySettleMs by prefOption("hide_away_settle_ms", 350)

    /**
     * 前后台翻转只管进隐藏这一半：离开前台且开了「离开微信隐藏」就进入持久隐藏态；
     * 回前台什么都不做 —— 恢复是手势、`#show`、定时或关功能的事。
     */
    private val foregroundListener: (Boolean) -> Unit = { foreground ->
        if (!foreground && hideOnLeave) enterAwayHidden()
    }

    /**
     * Attaches the two 手动恢复 gestures to the home-screen title: long press or triple tap calls
     * [showTemporarily]. Neither gesture ever hides — hiding is exclusively leave / screen-off.
     *
     * Both gestures share one touch listener on purpose — the title is a plain label with no other
     * behaviour, and a single listener is the only way to tell "held long enough to be a long press"
     * apart from "the first tap of a triple tap". The click listener is still installed, and is a
     * no-op: a View only receives ACTION_DOWN/UP events while it is clickable, so it is what keeps
     * the touch stream coming.
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
                    // Each gesture has its own switch (they can be on together or alone); both the
                    // switches and the durations are read here rather than captured at install
                    // time, so changing them applies from the next touch without restarting WeChat.
                    val heldMs = event.eventTime - downTime
                    when {
                        heldMs >= longPressMs -> {
                            tapCount = 0
                            if (gestureLongPressEnabled) showTemporarily(context)
                        }
                        !gestureTripleTapEnabled -> {}
                        else -> {
                            tapCount = if (event.eventTime - lastTapTime > tripleTapMs) 1 else tapCount + 1
                            lastTapTime = event.eventTime
                            if (tapCount >= 3) {
                                tapCount = 0
                                showTemporarily(context)
                            }
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> tapCount = 0
            }
            false
        }
    }

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

    /**
     * 隐藏联系人收到新消息时的两处处置，都挂在同一个宿主事件上（`ConversationStorage` 的通知派发，
     * type 3；微信在每条新消息、置顶、未读变化之后同步派发一次）。两件事分属两个面：
     *
     * - **摘掉事件**，闸 [HideSurface.CONVERSATIONS]。不摘的话适配器在任何 SQL 查询之前就看见这一行，
     *   查询改写白做；在源头取消没有竞态问题 —— 隐藏联系人根本到不了适配器。
     * - **清掉未读**，闸 [HideSurface.UNREAD]。`updateUnreadByTalker` 会把该会话未读归零并把消息标成
     *   已读，这是**往真库里写、且不可逆**：被清掉的那条消息，之后即使恢复显示也没有任何「未读」痕迹。
     *   所以它只由「未读角标」那一面承诺（它的文案写的就是「计算未读数时不计入」），不再跟着「隐藏会话
     *   列表」默认发生 —— 用户 2026-11-06 定：收到隐藏联系人的消息时，那条消息要留着。
     *
     * type 5（全局重载）不摘：那是 [WeConversationApi.reloadConversations] 用来触发全量重查的通路；
     * 空 talker 检查顺带挡掉它用的 `""` 哨兵。
     */
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
            // 两处处置分属两个面，见函数注释：摘事件跟着「隐藏会话列表」，写库清未读跟着「未读角标」。
            val rowsHidden = surfaceOn(HideSurface.CONVERSATIONS)
            val unreadSuppressed = surfaceOn(HideSurface.UNREAD)
            if (!rowsHidden && !unreadSuppressed) return@hookBefore
            val talker = args[2] as? String ?: return@hookBefore
            if (talker.isEmpty()) return@hookBefore
            if (talker !in hiddenContacts) return@hookBefore

            // Already inside our own markAsRead: this is the storage echoing our write back at us.
            // Still cancel the event so the row never reaches an adapter, but do not write again.
            if (markingAsRead.get() == true) {
                if (rowsHidden) result = null
                return@hookBefore
            }

            if (unreadSuppressed) {
                markingAsRead.set(true)
                try {
                    WeConversationApi.markAsRead(talker)
                } finally {
                    markingAsRead.set(false)
                }
            }
            if (rowsHidden) result = null
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
     * `ContactLabelStorage.getUserNameListById(String)` —— 一个标签的成员用户名列表。8.0.76 是
     * `storage/g4.smali:3114` 的 `s2(String): ArrayList`，方法体里那条日志字面量
     * `getUserNameListById() called with: id = [` 是它的身份（类名与方法名都混淆，日志串没混淆）。
     *
     * 标签这一面的所有消费方都汇到这一个方法（`ba3/{q5,j3,m1,s1,b3,k0}`、`fa3/{e2,n1}` 共 8 处在调），
     * 所以筛这一处就够，不用一页一页追；为什么不能筛 SQL、也不能筛上一版那个 UI 控件，
     * 见 `HideContactsLists.installLabelMemberHooks` 的读数。
     */
    val methodLabelUserNamesById by dexMethod(allowFailure = true) {
        matcher {
            usingStrings("getUserNameListById() called with")
            paramTypes("java.lang.String")
            returnType = "java.util.ArrayList"
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
     * LIKE 找人）的地方，`fm5/u` 就是那份结果的适配器 —— 行在这里 bind 成 View，绕不过去。
     *
     * 类名混淆、tag 没混淆，但**光靠 tag 钉不住**：真机 8.0.76 上 `usingEqStrings` 这两个字面量各自都
     * 命中两个类（`fm5.p` 与 `fm5.u`、`fm5.b` 与 `fm5.e` —— 前两个是回调/监听类，也打了同一个 tag），
     * DexKit 报 `Multiple classes found for key: classSearchResultAdapter, count: 2, classes: fm5.p,fm5.u`。
     * 所以要再加一条结构约束：这个类必须**自己声明** `getView(int, View, ViewGroup)`。方法名是覆写
     * Android SDK 的，混淆改不掉；而 `getView` 恰好只有适配器有、监听类没有（`fm5/p:1` 是
     * `super Object + implements ic5.e`，一个 getView 都不声明）—— 这是唯一能把两者分开的判据。
     *
     * 两者都 extends `com.tencent.mm.ui.z9`（`smali_classes8/com/tencent/mm/ui/z9.smali:2`，
     * `super android.widget.BaseAdapter`），它的 `getItem(int)`（`:177`）会按 `o(position)` 跳过 header
     * 再从 map/cursor 取行，所以拿 `getItem` 定位「这一行是谁」是宿主自己的映射，比我们再数一遍位置可靠。
     */
    val classSearchResultAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.SearchResultAdapter")
            methods {
                add {
                    name = "getView"
                    paramTypes("int", "android.view.View", "android.view.ViewGroup")
                    returnType = "android.view.View"
                }
            }
        }
    }

    val classSearchConversationAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.SearchConversationAdapter")
            methods {
                add {
                    name = "getView"
                    paramTypes("int", "android.view.View", "android.view.ViewGroup")
                    returnType = "android.view.View"
                }
            }
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
     * `ae4.p.d1()` — `ImproveSnsInfo.getSnsObject`（8.0.76 上的实名 `d1`，懒加载包装）。
     *
     * 8.0.76 的 Improve 朋友圈渲染链不走 [methodSnsInfoToSnsStruct]（真机探测零命中），而是
     * `f2.getItem -> ImproveDataUIC.d7 -> ce4.b.n()` 取出 `ImproveSnsInfo` 后，各渲染组件经
     * 它的 `d1()` 直接读缓存的 `SnsObject`（`SnsInfoStorageLogic.getMergeSnsObject` 合并结果）。
     * 该方法只有 6 个调用方、全部是 UI 读侧，没有写路径经过它 —— 在这里替换成过滤后的克隆，
     * 与 D0 钩子的「只改本机渲染、不动服务端」是同一个安全边界。见 hidecontacts/HideContactsMoments.kt。
     *
     * 匹配：两个 mark 字符串的交集只有这一个方法（`s5.e` 只有 getMergeSnsObject 没有
     * getSnsObject），再加返回类型与零参兜底。`allowFailure = true`：这是新上的 opt-in 面，
     * 解析失败只该让这一面静默缺失，不该拖垮整个特性。
     */
    val methodImproveSnsInfoGetSnsObject by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "getSnsObject",
                "getMergeSnsObject",
                "com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo",
            )
            paramCount = 0
            returnType("com.tencent.mm.protocal.protobuf.SnsObject")
        }
    }

    /**
     * `SnsCommentDetailUI.Y7(List, boolean) boolean` — 朋友圈详情页「赞头像条」的装配入口
     * （字符串锚 "setLikedHeader"）。详情页的数据不经过 D0/d1（feed 链），initView 直接从
     * s5.f 新解析的 SnsObject 上把 `LikeUserList` 活引用传进来；全部 5 个调用点首参都是
     * LikeUserList（SnsCommentDetailUI.smali:1254/23175/25548/30518 与 $$j.smali:290），
     * 所以在参数上换成过滤后的副本即可覆盖整个详情页头部。
     *
     * 不能钩 s5.f/s5.e 的返回值：q2 会把 s5.f 的结果改写后持久化（q2.smali:3389），全局
     * 换返回值会把过滤固件化、污染真实点赞/评论数据。见 hidecontacts/HideContactsMoments.kt。
     */
    val methodSnsDetailSetLikedHeader by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("setLikedHeader", "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI")
            paramCount = 2
            returnType("boolean")
        }
    }

    /**
     * `SnsCommentDetailUI$CommentListAdapter.<init>(UI, LinkedList comments, LinkedList likes,
     * Activity, String)` — 详情页评论/赞列表的唯一装配点：全 dex 只有 initView 一处调用
     * （SnsCommentDetailUI.smali:23202），args[1] = CommentUserList、args[2] = LikeUserList，
     * 都是活引用。类锚用 adapter 各方法自带的 mark 字符串。
     */
    val ctorSnsDetailCommentAdapter by dexConstructor(throwOnFailure = false) {
        matcher {
            declaredClass {
                usingEqStrings("com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI\$CommentListAdapter")
            }
            paramCount(5)
        }
    }

    /**
     * `SnsCommentDetailUI.onSceneEnd(int, int, String, m1)` — 详情页数据就绪回调，网络/本地读取
     * 完成后把 `s5.f(...)` 新解析的 `SnsObject.CommentUserList`/`LikeUserList` 重新赋给 adapter 的
     * `f197164d`/`f197165e`（mark 串 "setListInfo"）。构造器只在一处 initView 调用，这层赋值会
     * **覆盖**构造器入口的过滤 —— 这就是详情页评论过滤命中却仍可见的真因。在它之后补一层过滤，
     * 与构造器入口共用同一个过滤体，两处赋值点都被堵住。
     *
     * 匹配：`setListInfo` + `onScene objectDetail type:%s, isRequestDetail:%s` 两个 mark 串的
     * 交集全 dex 只落在 onSceneEnd。allowFailure = true：详情页评论是 opt-in 面，解析失败只让
     * 这一层缺失、构造器那层兜底仍在。
     */
    val methodSnsDetailOnSceneEnd by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("setListInfo", "onScene objectDetail type:%s, isRequestDetail:%s")
        }
    }

    /**
     * `SnsCommentDetailUI.W7(SnsObject, SnsInfo) void`（反射名 "setGroupIcon"）— 详情页分组
     * 图标的唯一可见性入口：内部用 fb4.e1.e/c/g("checkEnableShowGroupIconBySnsObject" 家族)
     * 判定后直接 setVisibility。与 feed 侧的 X0 不同，这里取消整个方法就是干净的视图层隐藏
     * （图标默认 GONE），不涉及其它状态写入。
     */
    val methodSnsDetailSetGroupIcon by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("checkEnableShowGroupIconBySnsObject", "com/tencent/mm/plugin/sns/ui/SnsCommentDetailUI")
        }
    }

    /**
     * `ae4.p.X0()` — `ImproveSnsInfo.getShowGroupEnable`，feed 徽标（分组可见图标）的判定谓词。
     *
     * 只对本人发出的动态生效，门控在 ExtFlag 位组合上（内部走 fb4.e1 "checkEnableShowGroup"
     * 家族，读 ExtFlag&0x4/0x200 加 GroupUser/GroupContactTagIdList 非空）。stripSnsObject 只
     * 清分组列表不清 ExtFlag —— 所以 8.0.76 真机上「列表清了徽标还在」。在此把 true 压成 false，
     * 与视图层方案相比不碰渲染、只动一个布尔谓词。
     */
    val methodImproveSnsInfoShowGroupEnable by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("getShowGroupEnable", "com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo")
            paramCount = 0
            returnType("java.lang.Boolean")
        }
    }

    /**
     * `jk5.o4.e7()` — 转发选人面板（`MvvmContactListUI`）的**状态工厂**，每次新建一个 `dk5.n0`
     * 初始状态返回。它上面的 `q` 字段（HashSet）是微信自己的「本次选人屏蔽这些联系人」集合，来源是
     * intent 的 `block_contact` / `Select_block_List`，外加 `vh0.f3.qj()` 这个全局屏蔽服务；
     * `recent/o.invoke()` 建行前逐个 `if (!hashSet.contains(str))` 查的就是它。
     *
     * 所以往里加隐藏联系人是**一处覆盖整个面板所有列表**的做法，比按组件逐个过滤名单/游标靠谱；
     * 而且这个 state 对象是本 Activity 私有的（`new dk5.n0()`），改它结构上碰不到首页会话列表。
     *
     * 匹配：三个 intent 键名字符串的交集只落在这个方法里。`allowFailure = true`：这是 opt-in 面，
     * 解析失败时原有的名单过滤（`methodRecentForwardList`）仍在兜底。
     */
    val methodSelectConvStateFactory by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("always_exclude_select_contact", "ForwardParams_ForwardByUIC", "Select_block_List")
            paramCount = 0
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
