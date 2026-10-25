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
import androidx.compose.ui.graphics.toArgb
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
import dev.sherry.wcs.ui.utils.theme.SeedResolver
import dev.sherry.wcs.ui.utils.theme.ThemeSettings
import dev.sherry.wcs.ui.utils.theme.darkScheme
import dev.sherry.wcs.ui.utils.theme.lightScheme
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

    /**
     * 行根底部再多留 7dp，让评论区与卡片底边的距离对齐顶部「昵称顶 → 卡片顶」那 6.86dp。
     *
     * 2026-10-25 真机实测（相对行顶）：昵称顶 10.86dp、互动块底 355.43dp、行底 358.86dp，
     * 卡片上下边各内缩 4dp → 卡片顶 4dp、卡片底 354.86dp。于是顶部有 6.86dp 呼吸，
     * 底部互动块反而比卡片底边**低 0.57dp**，视觉上就是两者重合。补 7dp 后底部间距 ≈ 6.43dp。
     * 这 7dp 落在卡片内部，卡片之间的 8dp 可见间隙不变（下一张卡片顶边跟着下移同样多）。
     */
    private const val BOTTOM_EXTRA_DP = 7
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

    /** 一趟取色的结果；[key] 会参与行的缓存键，所以换色种/换配色风格都会让旧卡片背景重建。 */
    private class Palette(
        val key: String,
        val surface: Int,
        val outline: Int,
        val ripple: Int,
    )

    private val rowStates = WeakHashMap<View, RowState>()
    private val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())
    private val lists: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    private var paletteKey: String? = null
    private var paletteSurface = 0
    private var paletteOutline = 0
    private var paletteRipple = 0

    override fun onEnable() {
        listOf(ImproveSnsTimelineUI::class.java, SnsUserUI::class.java).forEach { clazz ->
            clazz.reflekt().firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfter {
                scheduleAttach(thisObject as Activity)
            }
            // 从模块设置页返回时不重走 onCreate，也不会再有全局布局回调，所以在这里补一次；
            // 列表恢复时的重绑发生在 resume 之后，因此再往下一帧补一次。
            clazz.reflekt().firstMethod { name = "onResume" }.hookAfter {
                val root = (thisObject as Activity).rootView
                applyRemembered()
                root.post { applyRemembered() }
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
                                    applyRemembered()
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
        val palette = currentPalette(container.context)
        for (i in 0 until container.childCount) applyRow(container.getChildAt(i), palette)
    }

    /** 拨完描边或换完色种后立刻作用到当前可见条目，不必等下一次滚动或重进朋友圈。 */
    private fun applyRemembered() {
        lists.forEach { container ->
            val palette = currentPalette(container.context)
            for (i in 0 until container.childCount) applyRow(container.getChildAt(i), palette)
        }
    }

    private fun applyRow(row: View, palette: Palette) {
        if (classTimelineItem.isPlaceholder) return
        if (!classTimelineItem.clazz.isInstance(row)) return
        val group = row as ViewGroup
        val inset = HORIZONTAL_INSET_DP.dpToPx(row.context)

        val state = rowStates.getOrPut(row) {
            RowState(row.background, row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingBottom)
        }
        val key = "$strokeEnabled|${palette.key}"
        if (state.cardKey != key) {
            state.card = buildCard(row.context, strokeEnabled, palette)
            state.cardKey = key
        }

        val card = state.card!!
        val bottomExtra = BOTTOM_EXTRA_DP.dpToPx(row.context)
        if (row.background !== card || row.paddingLeft != state.baselinePaddingLeft + inset ||
            row.paddingBottom != state.baselinePaddingBottom + bottomExtra
        ) {
            row.background = card
            row.setPadding(
                state.baselinePaddingLeft + inset,
                state.baselinePaddingTop,
                state.baselinePaddingRight + inset,
                state.baselinePaddingBottom + bottomExtra,
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

    private fun buildCard(
        context: Context,
        stroke: Boolean,
        palette: Palette,
    ): Drawable {
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = RADIUS_DP.dpToPx(context).toFloat()
            setColor(palette.surface)
            if (stroke) {
                setStroke(1.dpToPx(context).coerceAtLeast(1), palette.outline)
            }
        }
        val horizontal = HORIZONTAL_INSET_DP.dpToPx(context)
        val vertical = VERTICAL_INSET_DP.dpToPx(context)
        return RippleDrawable(
            ColorStateList.valueOf(palette.ripple),
            InsetDrawable(surface, horizontal, vertical, horizontal, vertical),
            null,
        )
    }

    /**
     * 卡片底色、描边与按压波纹跟随莫奈取色，取法与主页侧栏完全一致：
     * 见 [dev.sherry.wcs.ui.utils.theme.InjectedUiTheme] —— 没开「把所选色种作用到微信」时用
     * [lightScheme]/[darkScheme] 这套固定方案，开了才按 [SeedResolver.injectedSeed] 现场生成。
     * 底色取 `surfaceContainerLow`（侧栏每张卡片用的就是它），不是 `surfaceContainer`。
     * 波纹沿用原来的透明度（浅 9%、深 16.5%），只把色换成主色，避免按压力度看起来变了。
     * 每趟布局只算一次（`injectedSeed` 在跟随壁纸时自己就要生成一套方案），并按输入缓存。
     */
    private fun currentPalette(context: Context): Palette {
        val dark = context.isDarkMode
        val applyCustom = ThemeSettings.applyToWechat
        val seed = SeedResolver.injectedSeed(context, dark)
        val key = "$dark|$applyCustom|$seed|${ThemeSettings.paletteStyle}|${ThemeSettings.effectiveColorSpec}"
        if (key == paletteKey) return Palette(key, paletteSurface, paletteOutline, paletteRipple)

        val scheme = if (applyCustom) SeedResolver.materialScheme(seed, dark)
        else if (dark) darkScheme else lightScheme
        val primary = scheme.primary.toArgb()
        val rippleAlpha = if (dark) 0x2A shl 24 else 0x18 shl 24
        paletteKey = key
        paletteSurface = scheme.surfaceContainerLow.toArgb()
        paletteOutline = scheme.outlineVariant.toArgb()
        paletteRipple = (primary and 0x00FFFFFF) or rippleAlpha
        return Palette(key, paletteSurface, paletteOutline, paletteRipple)
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
