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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.sherry.wcs.features.api.ui.WeMomentsApi
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
 * 2026-10-25 真机 dump：行根通栏且没有横向 padding、头像左边缘 12.6dp、昵称顶 10.86dp、
 * 正文底与行底齐平。所以圆角 20dp 必须连内容一起右移（给行根补左右 padding），并且行根底部
 * 要多留 7dp，否则评论区会比卡片底边还低、看着两者重合。
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
     * 真机实测（相对行顶）：昵称顶 10.86dp、互动块底 355.43dp、行底 358.86dp，卡片上下各内缩
     * 4dp → 卡片底边在 354.86dp，评论区其实比它**低 0.57dp**。补 7dp 后底部间距 ≈ 6.43dp。
     * 这 7dp 落在卡片内部，卡片之间的 8dp 可见间隙不变。成组时只给组末那条留。
     */
    private const val BOTTOM_EXTRA_DP = 7
    private const val MAX_DIVIDER_HEIGHT_PX = 3

    /** 从互动区控件往下找底色节点的最大层数；再深就是正文与图片了。 */
    private const val NESTED_SEARCH_DEPTH = 2

    /** 评论区圆角，取自已批准的稿子（微信自己那块就是很小的圆角）。 */
    private const val NESTED_RADIUS_DP = 4

    private var strokeEnabled by prefOption("moments_card_stroke", true)
    private var nestedStrokeEnabled by prefOption("moments_card_stroke_nested", true)
    private var groupEnabled by prefOption("moments_card_group", false)

    /** 朋友圈条目根 View；真机上类名是混淆的，按微信自己留在类里的那串字面量认。 */
    private val classTimelineItem by dexClass {
        matcher {
            usingEqStrings("${PackageNames.WECHAT}.plugin.sns.ui.improve.item.click.ImproveTimelineItemClick")
        }
    }

    private enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }

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
        val nested = HashMap<View, NestedPaint>()
    }

    /**
     * 评论区一块底色的记账。
     *
     * [applied] 是判定「这块还是不是我们涂的」的依据，不能改成只按 [styleKey] 短路：列表重新绑定会把
     * 宿主的 Drawable 设回去，那一刻配置没变、节点也早登记过，短路一生效这块底色就再也补不回来了。
     * [baseline] 跟着一起刷新，因为行 View 复用后宿主设回来的那张图未必还是第一次登记的那张。
     */
    private class NestedPaint(
        var baseline: Drawable?,
        var applied: Drawable?,
        var styleKey: String?,
    )

    /** 一趟取色的结果；[key] 会参与行的缓存键，所以换色种/换配色风格都会让旧卡片背景重建。 */
    private class Palette(
        val key: String,
        val surface: Int,
        val outline: Int,
        val ripple: Int,
        val nested: Int,
    )

    private val rowStates = WeakHashMap<View, RowState>()
    private val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())
    private val lists: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    private var paletteKey: String? = null
    private var paletteSurface = 0
    private var paletteOutline = 0
    private var paletteRipple = 0
    private var paletteNested = 0

    override fun onEnable() {
        listOf(ImproveSnsTimelineUI::class.java, SnsUserUI::class.java).forEach { clazz ->
            clazz.reflekt().firstMethodOrNull {
                name = "onCreate"
                parameters(Bundle::class)
            }?.hookAfter {
                scheduleAttach(thisObject as Activity)
            }
        }

        // onResume 照「首页三卡」那套写：钩 android.app.Activity 基类，再按类名过滤。朋友圈这两个
        // 时间线类自己没有声明 onResume，从它们自己身上找永远找不到 —— 上一版就是这么让描边开关
        // 从头到尾没装上钩子。
        Activity::class.java.reflekt().firstMethodOrNull { name = "onResume" }?.hookAfter {
            val activity = thisObject as Activity
            val name = activity.javaClass.name
            if (name != ImproveSnsTimelineUI::class.java.name && name != SnsUserUI::class.java.name) {
                return@hookAfter
            }
            applyRemembered()
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
            // 开关的显示必须由 Compose State 驱动（同「首页三卡」）：直接把 MMKV 委托喂给
            // checked 不是 State，写完偏好也不会重组，滑块看着就是按不动。
            var stroke by remember { mutableStateOf(strokeEnabled) }
            var nestedStroke by remember { mutableStateOf(nestedStrokeEnabled) }
            var grouped by remember { mutableStateOf(groupEnabled) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_card_title)) },
                text = {
                    SegmentedColumn {
                        item(key = "stroke") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moments_card_stroke),
                                description = stringResource(R.string.moments_card_stroke_description),
                                checked = stroke,
                                onCheckedChange = {
                                    stroke = it
                                    strokeEnabled = it
                                    applyRemembered()
                                },
                            )
                        }
                        item(key = "stroke_nested") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moments_card_stroke_nested),
                                description = stringResource(R.string.moments_card_stroke_nested_description),
                                checked = nestedStroke,
                                onCheckedChange = {
                                    nestedStroke = it
                                    nestedStrokeEnabled = it
                                    applyRemembered()
                                },
                            )
                        }
                        item(key = "group") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moments_card_group),
                                description = stringResource(R.string.moments_card_group_description),
                                checked = grouped,
                                onCheckedChange = {
                                    grouped = it
                                    groupEnabled = it
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
        container.viewTreeObserver.addOnGlobalLayoutListener { applyPass(container) }
    }

    private fun applyPass(container: ViewGroup) {
        lists.add(container)
        paintAll(container, currentPalette(container.context))
    }

    /**
     * 拨完开关或换完色种后立刻生效。做法照「美化对话列表」那套（`WeConversationListViewApi.refresh`
     * → `notifyAdapterChanged`）：先当场重涂，再让适配器整体重绑一次 —— 重绑会把行背景换回宿主
     * 自己的，而重绑必然伴随布局，全局布局回调随后按新配置再涂一遍，所以最终显示的一定是当前开关
     * 的状态，不需要自己排延时。
     */
    private fun applyRemembered() {
        val containers = lists.toList()
        if (containers.isEmpty()) return
        containers.forEach { container ->
            paintAll(container, currentPalette(container.context))
            notifyAdapterChanged(container)
        }
    }

    private fun notifyAdapterChanged(container: ViewGroup) {
        val adapter = container.reflekt().firstMethodOrNull {
            name = "getAdapter"
            parameterCount = 0
            superclass()
        }?.invoke() ?: return
        adapter.reflekt().firstMethodOrNull {
            name = "notifyDataSetChanged"
            parameterCount = 0
            superclass()
        }?.invoke()
    }

    /** 一趟只重涂当前可见的条目；成组要比较相邻两条的作者，所以先把行收集成有序列表。 */
    private fun paintAll(container: ViewGroup, palette: Palette) {
        val rows = ArrayList<View>(container.childCount)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (isTimelineRow(child)) rows.add(child)
        }
        if (rows.isEmpty()) return

        val authors = if (groupEnabled) rows.map { authorOf(it) } else emptyList()
        for (index in rows.indices) {
            applyRow(rows[index], palette, positionOf(authors, index))
        }
    }

    /**
     * 成组的判据是「相邻两条是同一作者」。只能看到当前挂载的行，所以屏幕最上面那条永远
     * 被判成组首 —— 它上面那条已经回收了，这是布局回调通路的固有代价。
     */
    private fun positionOf(authors: List<String?>, index: Int): GroupPosition {
        if (!groupEnabled || authors.isEmpty()) return GroupPosition.SINGLE
        val current = authors[index] ?: return GroupPosition.SINGLE
        val samePrev = index > 0 && authors[index - 1] == current
        val sameNext = index < authors.lastIndex && authors[index + 1] == current
        return when {
            samePrev && sameNext -> GroupPosition.MIDDLE
            samePrev -> GroupPosition.LAST
            sameNext -> GroupPosition.FIRST
            else -> GroupPosition.SINGLE
        }
    }

    private fun isTimelineRow(child: View): Boolean =
        !classTimelineItem.isPlaceholder && classTimelineItem.clazz.isInstance(child)

    /** 动态作者 wxid：从行里的互动区拿到它绑定的 ImproveSnsInfo，再读 userName。 */
    private fun authorOf(row: View): String? {
        if (WeMomentsApi.classImproveInteractionLayout.isPlaceholder) return null
        if (WeMomentsApi.fieldInteractionSnsInfo.isPlaceholder) return null
        val interaction = row.findViewWhich {
            WeMomentsApi.classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return null
        val snsInfo = runCatching {
            WeMomentsApi.fieldInteractionSnsInfo.field.get(interaction)
        }.getOrNull() ?: return null
        return runCatching { WeMomentsApi.getOwnerWxId(snsInfo) }.getOrNull()
    }

    private fun applyRow(row: View, palette: Palette, position: GroupPosition) {
        val group = row as ViewGroup
        val inset = HORIZONTAL_INSET_DP.dpToPx(row.context)
        val bottomExtra = if (position == GroupPosition.FIRST || position == GroupPosition.MIDDLE) 0
        else BOTTOM_EXTRA_DP.dpToPx(row.context)

        val state = rowStates.getOrPut(row) {
            RowState(row.background, row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingBottom)
        }
        val key = "$strokeEnabled|$groupEnabled|$position|${palette.key}"
        if (state.cardKey != key) {
            state.card = buildCard(row.context, palette, position)
            state.cardKey = key
        }

        val card = state.card!!
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
        paintInteraction(group, state, palette)
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
        state.nested.forEach { (view, entry) ->
            if (view.background !== entry.baseline) view.background = entry.baseline
        }
        state.nested.clear()
        rowStates.remove(row)
    }

    private fun buildCard(context: Context, palette: Palette, position: GroupPosition): Drawable {
        val radius = RADIUS_DP.dpToPx(context).toFloat()
        val zero = 0f
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadii(
                when (position) {
                    GroupPosition.SINGLE -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
                    GroupPosition.FIRST -> floatArrayOf(radius, radius, radius, radius, zero, zero, zero, zero)
                    GroupPosition.LAST -> floatArrayOf(zero, zero, zero, zero, radius, radius, radius, radius)
                    GroupPosition.MIDDLE -> floatArrayOf(zero, zero, zero, zero, zero, zero, zero, zero)
                },
            )
            setColor(palette.surface)
            if (strokeEnabled) {
                setStroke(1.dpToPx(context).coerceAtLeast(1), palette.outline)
            }
        }
        val horizontal = HORIZONTAL_INSET_DP.dpToPx(context)
        val vertical = VERTICAL_INSET_DP.dpToPx(context)
        // 组内相接的那条边不再内缩，两张卡片才会融成一整块。
        val top = if (position == GroupPosition.MIDDLE || position == GroupPosition.LAST) 0 else vertical
        val bottom = if (position == GroupPosition.FIRST || position == GroupPosition.MIDDLE) 0 else vertical
        return RippleDrawable(
            ColorStateList.valueOf(palette.ripple),
            InsetDrawable(surface, horizontal, top, horizontal, bottom),
            null,
        )
    }

    /**
     * 卡片底色、评论区底色、描边与按压波纹跟随莫奈取色，取法与主页侧栏完全一致：
     * 见 [dev.sherry.wcs.ui.utils.theme.InjectedUiTheme] —— 没开「把所选色种作用到微信」时用
     * [lightScheme]/[darkScheme] 这套固定方案，开了才按 [SeedResolver.injectedSeed] 现场生成。
     * 卡片底取侧栏卡片那一档 `surfaceContainerLow`，评论区块取 `surfaceContainerHigh`（比卡片
     * 再深一档，保持"块在卡片里"的层次），描边 `outlineVariant`，波纹沿用原来的透明度只换主色。
     * 每趟布局只算一次（`injectedSeed` 在跟随壁纸时自己就要生成一套方案），并按输入缓存。
     */
    private fun currentPalette(context: Context): Palette {
        val dark = context.isDarkMode
        val applyCustom = ThemeSettings.applyToWechat
        val seed = SeedResolver.injectedSeed(context, dark)
        val key = "$dark|$applyCustom|$seed|${ThemeSettings.paletteStyle}|${ThemeSettings.effectiveColorSpec}"
        if (key == paletteKey) return Palette(key, paletteSurface, paletteOutline, paletteRipple, paletteNested)

        val scheme = if (applyCustom) SeedResolver.materialScheme(seed, dark)
        else if (dark) darkScheme else lightScheme
        val primary = scheme.primary.toArgb()
        val rippleAlpha = if (dark) 0x2A shl 24 else 0x18 shl 24
        paletteKey = key
        paletteSurface = scheme.surfaceContainerLow.toArgb()
        paletteNested = scheme.surfaceContainerHigh.toArgb()
        paletteOutline = scheme.outlineVariant.toArgb()
        paletteRipple = (primary and 0x00FFFFFF) or rippleAlpha
        return Palette(key, paletteSurface, paletteOutline, paletteRipple, paletteNested)
    }

    /**
     * 评论区（点赞/评论那一块）的底色是宿主自己的浅灰，卡片换成莫奈色后它会显得格格不入。
     *
     * 微信把这块底色画在哪一层并不固定（实测过的位置有互动区自己身上、它的子 View、以及父节点
     * `n94`），所以从互动区控件往下找两层、再补上父节点，把所有带背景的节点一起换成我们自己的
     * 圆角矩形；一个都没找到就直接换在互动区上。原始 Drawable 按节点记在 RowState 里，关掉功能
     * 逐个还原。
     */
    private fun paintInteraction(group: ViewGroup, state: RowState, palette: Palette) {
        if (WeMomentsApi.classImproveInteractionLayout.isPlaceholder) return
        val interaction = group.findViewWhich {
            WeMomentsApi.classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return

        val key = "${palette.nested}|$nestedStrokeEnabled"
        val radius = NESTED_RADIUS_DP.dpToPx(group.context).toFloat()
        val stroke = 1.dpToPx(group.context).coerceAtLeast(1)

        val targets = ArrayList<View>(4)
        collectBackgroundNodes(interaction, 0, targets)
        (interaction.parent as? View)?.let { parent ->
            if (parent !== group && parent.background != null && !targets.contains(parent)) targets.add(parent)
        }
        if (targets.isEmpty()) targets.add(interaction)

        for (target in targets) {
            val entry = state.nested.getOrPut(target) { NestedPaint(target.background, null, null) }
            if (target.background === entry.applied && entry.styleKey == key) continue
            if (target.background !== entry.applied) entry.baseline = target.background
            entry.applied = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(palette.nested)
                if (nestedStrokeEnabled) {
                    setStroke(stroke, palette.outline)
                }
            }
            entry.styleKey = key
            target.background = entry.applied
        }
    }

    private fun collectBackgroundNodes(node: View, depth: Int, out: ArrayList<View>) {
        if (node.background != null) {
            out.add(node)
            return
        }
        if (depth >= NESTED_SEARCH_DEPTH || node !is ViewGroup) return
        for (i in 0 until node.childCount) collectBackgroundNodes(node.getChildAt(i), depth + 1, out)
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
