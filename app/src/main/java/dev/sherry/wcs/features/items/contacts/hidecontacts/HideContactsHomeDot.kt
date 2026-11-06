package dev.sherry.wcs.features.items.contacts.hidecontacts

import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.reflekt.reflekt
import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.preferences.WePrefs
import dev.sherry.wcs.ui.utils.findViewsWhich
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger

/**
 * 主页会话列表标题栏的红点：收到隐藏联系人的消息就亮起来，**逐个打开那些聊天**才会消失。
 *
 * ## 为什么是「待看名单」而不是一个布尔
 *
 * 用户要的是「多个隐藏联系人的消息必须全部点开才清掉」。一个布尔表达不了这件事：点开 A 不该把 B 的
 * 痕迹一起抹掉。所以存的是 `Set<String>`（MMKV `hidden_dot_pending`）：收到加、点开减、空了才灭。
 *
 * ## 为什么不需要通知通道
 *
 * 这就是标题栏里的一个 View，不是系统通知 —— 没有渠道、没有角标、没有点击行为（用户明确要求红点不可点，
 * 作用和「微信团队提醒」一样只用于提醒）。两个信号也都在主进程：消息入库走 `WeDatabaseListenerApi` 的
 * 插入监听，点开聊天走 `ChatFooter.setUserName`。所以红点是**当场出现**的，不用等列表刷新或回前台。
 *
 * ## 标题栏的真实形状（真机 8.0.76 `dumpsys activity top`，别再照猜的写）
 *
 *     LinearLayout (1018,64-1272,140)          ← 图标组，红点要插在它的第 0 位
 *       RelativeLayout                          ← 「搜索」那一格（第二格才是「+」）
 *         WeImageView  meb                      ← 可点击图标本体
 *         ImageView    med  (GONE)
 *     … RelativeLayout gp
 *       LinearLayout > LinearLayout
 *         TextView  android:id/text1            ← 标题「微信」，它往上第三四行才是标题栏
 *
 * 两版实现都栽在这里：`text1.parent` 是 LinearLayout 而不是 RelativeLayout（第一版因此每次都
 * `findTitleBar() == null` 静默返回），而图标又不是标题的兄弟、在另一个容器里。所以现在的找法是
 * 「从每个可见的 text1 往上爬，在同一条水平带里找最靠左的可点击图标」，红点插在图标组最左、
 * 「搜索」那一格之前。
 */
private const val TAG = "HideContactsHomeDot"

/** 待看的隐藏联系人 wxid 集合。跨重启保持 —— 收了消息但很久才点开是常态。 */
private const val KEY_PENDING = "hidden_dot_pending"

/** 认出自己画过的红点，避免每次刷新叠一层。 */
private const val DOT_TAG = "wcs_hidden_dot"

private val mainHandler = Handler(Looper.getMainLooper())

/** 「找不到锚点」的日志按时间节流（见 [logMissingBar]）。 */
private var lastMissingLogMs = 0L

/**
 * 装在 `HideContacts.onEnable` 里。钩子不在 `onDisable` 摘除（本功能惯例：直接挂钩子的卸载是尽力而为，
 * 见 `HideContactsBlock` 里同一条注释），所以钩子体自己判功能态与开关 —— 关掉之后它就是次空转早退。
 */
fun HideContacts.installHomeDot() {
    ChatFooter::class.reflekt().firstMethod {
        name = "setUserName"
    }.hookAfter {
        if (!isActive || !surfaceOn(HideSurface.HOME_DOT)) return@hookAfter
        val talker = args[0] as? String ?: return@hookAfter
        mainHandler.post {
            val current = pending()
            val rest = current - talker
            if (rest == current) return@post
            WeLogger.d(TAG, "chat opened, dot entries=${rest.size}")
            WePrefs.putStringSet(KEY_PENDING, rest)
            paint()
        }
    }

    installChatTransitionRepaint()

    // 名单是跨重启的，功能一装好就按它画一次，别等下一条消息。
    refreshHomeDot()
}

/**
 * 从聊天页回到会话列表时补画两次。
 *
 * **这一条是「红点亮过一次就再也不出现」的真因**：标题栏跟着聊天 fragment 一起被重建，我们原先唯一的
 * 重绘点是 `LauncherUI.onResume` —— 而打开聊天是同一个 Activity 里 push 一个 fragment，Activity 从没
 * 暂停过，onResume 压根不触发，于是红点随旧树一起没了，之后没有任何时机再画。
 *
 * `closeChatting` 是 `HomeSidePanel` 早就在用的同款信号（它那边也靠 startChatting/closeChatting 重算
 * 视图树）。这里延时两次是因为 `closeChatting` 那一刻宿主的树还没重建完；[paint] 是幂等的，多跑一次
 * 只是早退。
 *
 * 接收者必须是 `HideContacts`：`hookAfter` 是 `BaseFeature` 的**成员扩展**（`BaseFeature.kt:161`），
 * 只有隐式接收者在作用域里时才解析得到 —— 写成顶层函数会直接 `Unresolved reference 'hookAfter'`。
 */
private fun HideContacts.installChatTransitionRepaint() {
    LauncherUI::class.reflekt().firstMethod {
        name = "closeChatting"
        parameters(Boolean::class)
    }.hookAfter {
        mainHandler.postDelayed({ paint() }, REPAINT_DELAY_MS)
        mainHandler.postDelayed({ paint() }, REPAINT_DELAY_MS * 3)
    }
}

private const val REPAINT_DELAY_MS = 300L

/**
 * 消息入库侧的入口（由 `HideContacts.onInsert` 转过来）。只认**收到的**消息，自己发出去的不算提醒。
 *
 * 只在隐藏真正生效时才记：列表正显示着的时候用户本来就能看见这条会话，再叠个红点是重复提醒。
 */
fun HideContacts.onHiddenMessageArrived(values: ContentValues) {
    if (values.getAsInteger("isSend") == 1) return
    val talker = values.getAsString("talker") ?: return
    if (!isHiddenNow(talker)) return
    if (!surfaceOn(HideSurface.HOME_DOT)) return

    mainHandler.post {
        val current = pending()
        if (talker in current) return@post
        WeLogger.d(TAG, "hidden contact message arrived, dot entries=${current.size + 1}")
        WePrefs.putStringSet(KEY_PENDING, current + talker)
        paint()
    }
}

/**
 * 由 `doOnCreate` 与 `onActivityResumed` 调用：主页回到前台/重建时按当前名单补画一次。
 *
 * 必须有人在「主页重新出现在屏幕上」这一刻重画 —— 标题栏是宿主自己的视图树，聊天 fragment 弹栈时会被
 * 换掉，我们那个红点跟着旧树一起没。`onActivityResumed` 只覆盖"整个 Activity 重新可见"（切回来、解锁），
 * **从聊天页返回列表不走它**，那一路由 [installChatTransitionRepaint] 补。
 */
fun HideContacts.refreshHomeDot() {
    if (!isActive || !surfaceOn(HideSurface.HOME_DOT)) return
    mainHandler.post { paint() }
}

/** 关掉功能或那一面时调用：名单与红点一起清掉。 */
fun HideContacts.clearHomeDot() {
    mainHandler.post {
        WePrefs.putStringSet(KEY_PENDING, emptySet())
        paint()
    }
}

private fun pending(): Set<String> = WePrefs.getStringSetOrDef(KEY_PENDING, emptySet())

/** 标题栏宿主 + 要插在哪个容器、哪一位之前（anchorId 供 RelativeLayout 那一路 LEFT_OF 用）。 */
private class Bar(val container: ViewGroup, val index: Int, val anchorId: Int)

/**
 * 按当前名单把红点画出来或撤掉。
 *
 * **已有红点一律在整棵 decor 里找**，不是只在「这一次算出来的那个容器」里找。标题栏会被重建、也会同时
 * 存在好几条（主页与聊天页各一条 `text1`），按容器找会同时犯两种错：旧点永远灭不掉、新点永远亮不起来，
 * 而这两种在界面上看起来都是「红点不出现」。
 */
private fun paint() {
    // 已被移出隐藏名单的人顺手剔除，否则红点永远灭不掉。这一步只看名单，不能被开关带着走 ——
    // 关掉那一面应当只是不画，不该把攒下来的名单清空，否则再打开就什么都没有了。
    val waiting = pending().filter { it in HideContacts.hiddenContacts }.toSet()
    if (waiting.size != pending().size) WePrefs.putStringSet(KEY_PENDING, waiting)

    val show = waiting.isNotEmpty() && HideContacts.surfaceOn(HideSurface.HOME_DOT)
    val launcher = LauncherUI.getInstance()
    val decor = launcher?.window?.decorView
    if (decor == null) {
        logMissingBar(show, "no LauncherUI decor")
        return
    }
    val dots = decor.findViewsWhich { it.tag == DOT_TAG }.toList()

    if (!show) {
        dots.forEach { it.visibility = View.GONE }
        WeLogger.d(TAG, "paint: off, waiting=${waiting.size} dots=${dots.size}")
        return
    }

    // 聊天页顶栏也有一条 text1 加一组可点击图标 —— 判据用 LauncherUI 自己的 `currentFragmet`
    // （非空=正在看聊天页，`HomeSidePanel.isChattingVisible` 同款）。这时候只把手上已有的点按名单摆好，
    // 新画留给 closeChatting 之后的补画，否则红点会画到聊天页的工具栏上去。
    val fragment = launcher?.currentFragmet?.javaClass?.name.orEmpty()
    if (fragment.isNotEmpty()) {
        dots.forEach { it.visibility = View.VISIBLE }
        WeLogger.d(TAG, "paint: on a chat page, dots=${dots.size} fragment=$fragment")
        return
    }

    val titles = titlesOf(decor)
    val bar = barOf(titles)
    if (bar == null) {
        dots.forEach { it.visibility = View.VISIBLE }
        logMissingBar(show, "titles=${titles.size}")
        return
    }

    val mine = dots.filter { it.parent === bar.container }
    dots.filterNot { it.parent === bar.container }.forEach { it.visibility = View.GONE }
    if (mine.isEmpty()) {
        attachDot(bar, waiting.size)
    } else {
        mine.forEach { it.visibility = View.VISIBLE }
        WeLogger.d(TAG, "paint: already in place, waiting=${waiting.size} onScreen=${mine.count { it.isShown }}")
    }
}

/**
 * 「找不到锚点」按时间节流，而不是一次性 —— 真机 2026-11-07 那次，进程启动期必然失败的一次把唯一
 * 那条日志消耗掉了，之后每一次真失败全是静默，于是「画不上」和「画了但看不见」在日志里长得一模一样。
 */
private fun logMissingBar(show: Boolean, detail: String) {
    if (!show) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastMissingLogMs < 5_000L) return
    lastMissingLogMs = now
    WeLogger.w(TAG, "title bar icon group not found; dot cannot be placed ($detail)")
}

private fun attachDot(bar: Bar, waiting: Int) {
    val density = HostInfo.application.resources.displayMetrics.density
    val size = (8 * density).toInt()
    val margin = (4 * density).toInt()
    val dot = View(bar.container.context).apply {
        tag = DOT_TAG
        isClickable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FA5151"))
        }
    }

    // `addRule` 返回 void，不能链式连写（CI 实测 Unresolved reference）。
    when (bar.container) {
        is RelativeLayout -> {
            val params = RelativeLayout.LayoutParams(size, size)
            params.addRule(RelativeLayout.CENTER_VERTICAL)
            // 有可用 id 才 LEFT_OF（真机那一格的容器是 LinearLayout，走下面那条；这条留给改版）。
            if (bar.anchorId > 0) params.addRule(RelativeLayout.LEFT_OF, bar.anchorId)
            params.marginEnd = margin
            bar.container.addView(dot, bar.index, params)
        }

        is LinearLayout -> {
            val params = LinearLayout.LayoutParams(size, size)
            params.gravity = Gravity.CENTER_VERTICAL
            params.marginEnd = margin
            bar.container.addView(dot, bar.index, params)
        }

        else -> {
            WeLogger.w(TAG, "icon group container type unsupported: ${bar.container.javaClass.name}")
            return
        }
    }
    WeLogger.d(TAG, "dot attached in ${bar.container.javaClass.simpleName} at index ${bar.index}, waiting=$waiting")

    // addView 之后才知道它到底有没有真的出现在屏幕上：布局参数不合（0 宽、被挤到容器外、父级不可见）
    // 在日志里和"画上了"完全一样。等一轮布局再读一次，界面上"没红点"就能当场分诊。
    mainHandler.postDelayed({
        val box = IntArray(2).also { dot.getLocationInWindow(it) }
        WeLogger.i(
            TAG,
            "dot probe: shown=${dot.isShown} size=${dot.width}x${dot.height} " +
                "at=${box[0]},${box[1]} parent=${dot.parent?.javaClass?.simpleName}",
        )
    }, REPAINT_DELAY_MS)
}

/**
 * 候选标题：所有「可见、有宽度」的 `android.R.id.text1`，按窗口内 y 升序。
 *
 * 主页头部在窗口顶端，其它带 text1 的工具条要么 y 更大、要么当下根本没显示（`isShown` 要求整条祖先链
 * 都可见，所以被聊天页压在下面的那条主页标题不会进来）。
 */
private fun titlesOf(decor: View): List<View> =
    decor.findViewsWhich { it is TextView && it.id == android.R.id.text1 && it.isShown && it.width > 0 }
        .toList()
        .sortedBy { windowY(it) }

/** 逐个标题往上找图标组，第一个成立的就是要插红点的那条工具栏。 */
private fun barOf(titles: List<View>): Bar? = titles.firstNotNullOfOrNull { title ->
    val box = IntArray(2).also { title.getLocationInWindow(it) }
    barFor(title, box[0], box[1] + title.height)
}

private fun windowY(view: View): Int = IntArray(2).also { view.getLocationInWindow(it) }[1]

private fun barFor(title: View, titleLeft: Int, titleBottom: Int): Bar? {
    var ancestor: View = title
    while (ancestor.parent is View) {
        ancestor = ancestor.parent as View
        val icons = ancestor.findViewsWhich { candidate ->
            candidate !== title && candidate.isClickable && candidate.isShown && candidate.width > 0 &&
                    candidate.tag != DOT_TAG && runsInTitleStrip(candidate, titleLeft, titleBottom)
        }.toList()
        val icon = icons.minByOrNull { windowX(it) } ?: continue
        return climbToIconGroup(icon)
    }
    return null
}

/** 图标必须排在标题右边、且和标题同一条水平带（顶部工具条 vs 内容区的同名 id 就靠这个分开）。 */
private fun runsInTitleStrip(view: View, titleLeft: Int, titleBottom: Int): Boolean {
    val box = IntArray(2).also { view.getLocationInWindow(it) }
    return box[0] >= titleLeft && box[1] <= titleBottom
}

/** 比较左右必须用窗口坐标：候选图标可能来自不同父容器，`left` 是各自容器内的相对值。 */
private fun windowX(view: View): Int = IntArray(2).also { view.getLocationInWindow(it) }[0]

/**
 * 图标自己那一格通常还挂着一个 GONE 的小红点 View，所以「只有一个可见子 View 的容器」要一路并到
 * 真正的图标组（搜索和「+」都在里面），红点插在那里才不会挤进图标格内。
 */
private fun climbToIconGroup(icon: View): Bar? {
    var cell: View = icon
    var container = icon.parent as? ViewGroup ?: return null
    while (visibleChildCount(container) == 1 && container.parent is ViewGroup) {
        cell = container
        container = container.parent as ViewGroup
    }
    val index = container.indexOfChild(cell)
    if (index < 0) return null
    return Bar(container, index, cell.id)
}

private fun visibleChildCount(container: ViewGroup): Int =
    (0 until container.childCount).count { container.getChildAt(it).visibility == View.VISIBLE }
