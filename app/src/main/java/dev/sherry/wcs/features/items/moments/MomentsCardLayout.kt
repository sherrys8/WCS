package dev.sherry.wcs.features.items.moments

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.reflekt.reflekt
import dev.sherry.wcs.R
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexClass
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.dpToPx
import dev.sherry.wcs.ui.utils.findViewWhich
import dev.sherry.wcs.ui.utils.rootView
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.isDarkMode
import java.util.Collections
import java.util.WeakHashMap

/**
 * 朋友圈卡片布局：把时间线里每一条动态画成独立的圆角卡片。
 *
 * 画法沿用「美化对话列表」（GradientDrawable + InsetDrawable + RippleDrawable），数值来自
 * 2026-10-25 真机 dump：行根通栏且没有横向 padding、头像左边缘 12.6dp、正文顶距行顶 5.4dp。
 * 因此圆角 20dp 必须连内容一起右移（给行根补左右 padding），否则头像左上角会露在弧线外。
 *
 * 走 `ImproveSnsTimelineUI` 的全局布局回调而不是宿主绑定钩子：时间线列表在本仓 stub 里只
 * 继承 `ViewGroup`，拿不到 RecyclerView 的子 View 挂载监听；布局回调每次滚动都会重跑，
 * 顺带覆盖了宿主重绑时可能发生的背景重置。
 */
object MomentsCardLayout : ClickableFeature(), IResolveDex {

    override val technicalId = "朋友圈卡片布局"
    override val nameRes = R.string.feature_moments_card_layout_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS, FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_moments_card_layout_description

    private const val TAG = "MomentsCardLayout"

    private const val RADIUS_DP = 20
    private const val HORIZONTAL_INSET_DP = 10

    /** 相邻两行各内缩一次，所以卡片之间的可见间距是它的两倍（8dp）。 */
    private const val VERTICAL_INSET_DP = 4
    private const val MAX_DIVIDER_HEIGHT_PX = 3

    private var strokeEnabled by prefOption("moments_card_stroke", true)

    /** 朋友圈条目根 View；真机上类名是混淆的，按微信自己留在类里的那串字面量认。 */
    private val classTimelineItem by dexClass {
        matcher {
            usingEqStrings("${PackageNames.WECHAT}.plugin.sns.ui.improve.item.click.ImproveTimelineItemClick")
        }
    }

    private class RowState(
        val baselineBackground: Drawable?,
        val baselinePaddingLeft: Int,
        val baselinePaddingTop: Int,
        val baselinePaddingRight: Int,
        val baselinePaddingBottom: Int,
    ) {
        var card: Drawable? = null
        var cardKey: String? = null
        var divider: View? = null
    }

    private val rowStates = WeakHashMap<View, RowState>()
    private val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())
    private val lists: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    override fun onEnable() {
        listOf(ImproveSnsTimelineUI::class.java, SnsUserUI::class.java).forEach { clazz ->
            clazz.reflekt().firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfter {
                scheduleAttach(thisObject as Activity)
            }
        }
    }

    override fun onDisable() {
        lists.forEach { container ->
            for (i in 0 until container.childCount) restoreRow(container.getChildAt(i))
        }
        rowStates.clear()
        attachedRoots.clear()
        lists.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_card_title)) },
                text = {
                    SegmentedColumn {
                        item(key = "stroke") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moments_card_stroke),
                                description = stringResource(R.string.moments_card_stroke_description),
                                checked = strokeEnabled,
                                onCheckedChange = {
                                    strokeEnabled = it
                                    refresh()
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2000).forEach { delay ->
            root.postDelayed({
                runCatching { attachToList(root) }
                    .onFailure { WeLogger.w(TAG, "attach failed, delay=${delay}ms", it) }
            }, delay.toLong())
        }
    }

    private fun attachToList(root: ViewGroup) {
        val container = root.findViewWhich { it is WxRecyclerView } as? WxRecyclerView ?: return
        synchronized(attachedRoots) {
            if (!attachedRoots.add(root)) return
        }
        container.viewTreeObserver.addOnGlobalLayoutListener { applyVisible(container) }
    }

    private fun applyVisible(container: ViewGroup) {
        lists.add(container)
        for (i in 0 until container.childCount) applyRow(container.getChildAt(i))
    }

    /** 拨完描边立刻作用到当前可见条目，不必等下一次滚动。 */
    private fun refresh() {
        lists.forEach { container ->
            for (i in 0 until container.childCount) applyRow(container.getChildAt(i))
        }
    }

    private fun applyRow(row: View) {
        if (classTimelineItem.isPlaceholder) return
        if (!classTimelineItem.clazz.isInstance(row)) return
        val group = row as ViewGroup
        val inset = HORIZONTAL_INSET_DP.dpToPx(row.context)

        val state = rowStates.getOrPut(row) {
            RowState(row.background, row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingBottom)
        }
        val key = "$strokeEnabled|${row.context.isDarkMode}"
        if (state.cardKey != key) {
            state.card = buildCard(row.context, strokeEnabled, row.context.isDarkMode)
            state.cardKey = key
        }

        val card = state.card!!
        if (row.background !== card || row.paddingLeft != state.baselinePaddingLeft + inset) {
            row.background = card
            row.setPadding(
                state.baselinePaddingLeft + inset,
                state.baselinePaddingTop,
                state.baselinePaddingRight + inset,
                state.baselinePaddingBottom,
            )
        }
        hideDivider(group, state)
    }

    private fun restoreRow(row: View) {
        val state = rowStates[row] ?: return
        row.background = state.baselineBackground
        row.setPadding(
            state.baselinePaddingLeft,
            state.baselinePaddingTop,
            state.baselinePaddingRight,
            state.baselinePaddingBottom,
        )
        state.divider?.let { divider ->
            if (divider.visibility != View.VISIBLE) divider.visibility = View.VISIBLE
        }
        rowStates.remove(row)
    }

    private fun buildCard(context: Context, stroke: Boolean, dark: Boolean): Drawable {
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = RADIUS_DP.dpToPx(context).toFloat()
            setColor(if (dark) 0xFF252827.toInt() else 0xFFF7FAF9.toInt())
            if (stroke) {
                setStroke(1.dpToPx(context).coerceAtLeast(1), if (dark) 0x22FFFFFF else 0x16161D1C)
            }
        }
        val horizontal = HORIZONTAL_INSET_DP.dpToPx(context)
        val vertical = VERTICAL_INSET_DP.dpToPx(context)
        return RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x2AFFFFFF else 0x18006A62),
            InsetDrawable(surface, horizontal, vertical, horizontal, vertical),
            null,
        )
    }

    /**
     * 分隔线是行根的最后一个子 View，真机实测 1272x1px 且通栏；卡片已经左右内缩，
     * 通栏的线会露在卡片外面，所以按「最后一个 + 极薄 + 裸 View」三个条件认它。
     */
    private fun hideDivider(group: ViewGroup, state: RowState) {
        if (group.childCount == 0) return
        val last = group.getChildAt(group.childCount - 1)
        if (last.javaClass != View::class.java || last.height > MAX_DIVIDER_HEIGHT_PX) return
        state.divider = last
        if (last.visibility != View.GONE) last.visibility = View.GONE
    }
}
