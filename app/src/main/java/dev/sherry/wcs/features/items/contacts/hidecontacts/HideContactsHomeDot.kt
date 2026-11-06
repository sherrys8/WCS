package dev.sherry.wcs.features.items.contacts.hidecontacts

import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import dev.sherry.wcs.ui.utils.findViewWhich
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

/** 找不到锚点只报一次：这个刷新点很密，刷屏会把有用行冲掉。 */
private var missingLogged = false

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

    // 名单是跨重启的，功能一装好就按它画一次，别等下一条消息。
    refreshHomeDot()
}

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
 * 必须有一个「回到主页」的刷新点 —— 标题栏是宿主自己的视图树，切 tab 或从聊天页返回时会被换掉，
 * 我们那个红点跟着旧树一起没；只有消息到达/点开聊天两个事件的话，红点就再也不回来了。
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

/** 按当前名单把红点画出来或撤掉。 */
private fun paint() {
    // 已被移出隐藏名单的人顺手剔除，否则红点永远灭不掉。这一步只看名单，不能被开关带着走 ——
    // 关掉那一面应当只是不画，不该把攒下来的名单清空，否则再打开就什么都没有了。
    val waiting = pending().filter { it in HideContacts.hiddenContacts }.toSet()
    if (waiting.size != pending().size) WePrefs.putStringSet(KEY_PENDING, waiting)

    val show = waiting.isNotEmpty() && HideContacts.surfaceOn(HideSurface.HOME_DOT)
    val bar = findBar()
    if (bar == null) {
        if (show && !missingLogged) {
            missingLogged = true
            WeLogger.w(TAG, "title bar icon group not found; dot cannot be placed")
        }
        return
    }

    val dot = bar.container.findViewWhich { it.tag == DOT_TAG }
    if (dot != null) {
        dot.visibility = if (show) View.VISIBLE else View.GONE
        return
    }
    if (!show) return

    attachDot(bar)
}

private fun attachDot(bar: Bar) {
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
    WeLogger.d(TAG, "dot attached in ${bar.container.javaClass.simpleName} at index ${bar.index}")
}

/**
 * 找到标题栏里的图标组：从每个「可见、有宽度」的 `android.R.id.text1` 往上爬（最多到 decor），
 * 在它某个祖先的子树里找**与标题同一水平带、且位于标题右侧**的最靠左可点击图标；找到后按
 * 「当前容器只剩一个可见子 View 就继续往上并」爬出图标组容器，把红点插在图标那一格之前。
 *
 * 候选标题按窗口内 y 坐标升序取第一个成立的 —— 主页头部在窗口顶端，其它带 text1 的工具条（聊天页
 * 那条）y 更大或当下根本没显示。
 */
private fun findBar(): Bar? {
    val decor = LauncherUI.getInstance()?.window?.decorView ?: return null
    val titles = decor.findViewsWhich { it is TextView && it.id == android.R.id.text1 && it.isShown && it.width > 0 }
        .toList()
        .sortedBy { title -> IntArray(2).also { title.getLocationInWindow(it) }[1] }

    for (title in titles) {
        val box = IntArray(2).also { title.getLocationInWindow(it) }
        val titleLeft = box[0]
        val titleBottom = box[1] + title.height
        val bar = barFor(title, titleLeft, titleBottom) ?: continue
        return bar
    }
    return null
}

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
