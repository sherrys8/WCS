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
 * ## 为什么不需要通知通道，也不需要 :push
 *
 * 这就是标题栏里的一个 View，不是系统通知 —— 没有渠道、没有角标、没有点击行为（用户明确要求红点不可点，
 * 作用和「微信团队提醒」一样只是提醒）。两个信号也都在主进程：消息入库走 `WeDatabaseListenerApi` 的
 * 插入监听，点开聊天走 `ChatFooter.setUserName`。所以红点是**当场出现**的，不用等列表刷新或回前台。
 *
 * ## 为什么挂在「搜索按钮左边」
 *
 * 主页标题栏是那个装着 `android.R.id.text1` 标题的 RelativeLayout（`HomeSidePanel` 认标题栏用的就是
 * 同一个 TextView）。红点插在标题右侧**最靠左**的那个可点击图标左边，那就是搜索按钮，「+」在它右边。
 * 这条判据只用运行期的 `view.id` 与 `left`，不碰任何混淆的 `R.id` 名字。
 */
private const val TAG = "HideContactsHomeDot"

/** 待看的隐藏联系人 wxid 集合。跨重启保持 —— 收了消息但很久才点开是常态。 */
private const val KEY_PENDING = "hidden_dot_pending"

/** 认出自己画过的红点，避免每次刷新叠一层。 */
private const val DOT_TAG = "wcs_hidden_dot"

private val mainHandler = Handler(Looper.getMainLooper())

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
 * 由 `HideContacts` 的 `doOnCreate` 钩子和 `installHomeDot` 调用：主页刚建好（或重建）的那一刻按当前
 * 名单补画一次。消息到达与点开聊天这两处本来就会刷，所以这里只负责「名单是旧的、视图是新的」这一种。
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

/** 按当前名单把红点画出来或撤掉。 */
private fun paint() {
    // 已经被移出隐藏名单的人顺手剔掉，否则红点会永远灭不掉。注意这一步只看名单，不能被下面的开关
    // 判断带着走 —— 关掉那一面应当只是不画，不该把攒下来的名单清空，否则再打开就什么都没有了。
    val waiting = pending().filter { it in HideContacts.hiddenContacts }.toSet()
    if (waiting.size != pending().size) WePrefs.putStringSet(KEY_PENDING, waiting)

    val show = waiting.isNotEmpty() && HideContacts.surfaceOn(HideSurface.HOME_DOT)
    val bar = findTitleBar() ?: return
    val dot = bar.findViewWhich { it.tag == DOT_TAG }
    when {
        !show -> dot?.visibility = View.GONE
        dot != null -> dot.visibility = View.VISIBLE
        else -> attachDot(bar, allowRetry = true)
    }
}

private fun findTitleBar(): RelativeLayout? {
    val activity = LauncherUI.getInstance() ?: return null
    if (activity.isFinishing) return null
    val title = activity.window.decorView.findViewWhich { it is TextView && it.id == android.R.id.text1 }
        ?: return null
    return title.parent as? RelativeLayout
}

/** 标题右侧最靠左的可点击图标 = 搜索按钮。宽度为 0 说明这一版还没布局完，不算找到。 */
private fun findSearchAnchor(bar: ViewGroup, title: View): View? =
    (0 until bar.childCount)
        .map { bar.getChildAt(it) }
        .filter { it !== title && it.tag != DOT_TAG && it.visibility == View.VISIBLE && it.isClickable }
        .filter { it.left >= title.right && it.width > 0 }
        .minByOrNull { it.left }

private fun attachDot(bar: RelativeLayout, allowRetry: Boolean) {
    val title = bar.findViewWhich { it is TextView && it.id == android.R.id.text1 } ?: return
    val anchor = findSearchAnchor(bar, title)
    if (anchor == null) {
        if (!allowRetry) {
            WeLogger.w(TAG, "still no clickable icon right of the title; children=${bar.childCount}")
            return
        }
        // 回前台/标题栏刚重建的那一刻还没测量完，布局稳定后补一次（只补一次，避免和布局风暴对循环）。
        bar.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                bar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (pending().isNotEmpty() && bar.findViewWhich { it.tag == DOT_TAG } == null) {
                    attachDot(bar, allowRetry = false)
                }
            }
        })
        return
    }

    val density = HostInfo.application.resources.displayMetrics.density
    val size = (8 * density).toInt()
    val dot = View(bar.context).apply {
        tag = DOT_TAG
        isClickable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FA5151"))
        }
    }

    when (val container = anchor.parent) {
        // 图标直接挂在标题栏那个 RelativeLayout 上：LEFT_OF 贴到搜索按钮左边。这里用的是运行期
        // anchor.id，不是混淆的 R.id 名字，所以改版重命名不影响。
        bar -> {
            val params = RelativeLayout.LayoutParams(size, size)
                .addRule(RelativeLayout.CENTER_VERTICAL)
                .addRule(RelativeLayout.LEFT_OF, anchor.id)
            params.rightMargin = (4 * density).toInt()
            bar.addView(dot, params)
        }
        // 图标被包了一层容器：在同一容器里插到搜索按钮之前，视觉位置同样是「搜索左边」。
        is LinearLayout -> {
            val params = LinearLayout.LayoutParams(size, size)
            params.gravity = Gravity.CENTER_VERTICAL
            params.marginEnd = (4 * density).toInt()
            container.addView(dot, container.indexOfChild(anchor), params)
        }

        else -> {
            WeLogger.w(TAG, "search icon sits in an unsupported container: ${container.javaClass.name}")
            return
        }
    }
    WeLogger.d(
        TAG,
        "dot attached left of ${anchor.javaClass.simpleName}(id=${anchor.id}) " +
                "in ${anchor.parent.javaClass.simpleName}",
    )
}
