package dev.sherry.wcs.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import dev.sherry.wcs.R

object MeowResourceFilter {
    private val wcSPackageId = R.string.res_inject_success ushr 24

    /**
     * 冒充别的表面的一律不加「喵」。这两条是通知栏里冒充「微信团队」的标题与正文：加了后缀既把
     * 模块自己暴露出来，又让那条通知一眼看上去就不像微信发的 —— 而它的全部用处就是让人以为
     * 是微信团队在提醒。渠道名不在这里，那是我们自己的名字。
     */
    private val neverMeowed = setOf(
        R.string.contacts_hide_alert_title,
        R.string.contacts_hide_alert_body,
    )

    fun isWcSResource(id: Int): Boolean = id ushr 24 == wcSPackageId

    fun shouldMeow(id: Int): Boolean = isWcSResource(id) && id !in neverMeowed
}

@Suppress("DEPRECATION")
class MeowResources(
    private val delegate: Resources,
) : Resources(delegate.assets, delegate.displayMetrics, delegate.configuration) {
    override fun getText(id: Int): CharSequence =
        delegate.getText(id).let { text ->
            if (MeowResourceFilter.shouldMeow(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getText(id: Int, def: CharSequence?): CharSequence? {
        if (!MeowResourceFilter.shouldMeow(id)) return delegate.getText(id, def)
        return try {
            MeowTextTransformer.transform(delegate.getText(id))
        } catch (_: NotFoundException) {
            def
        }
    }

    override fun getString(id: Int): String =
        delegate.getString(id).let { text ->
            if (MeowResourceFilter.shouldMeow(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getString(id: Int, vararg formatArgs: Any): String =
        delegate.getString(id, *formatArgs).let { text ->
            if (MeowResourceFilter.shouldMeow(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getQuantityText(id: Int, quantity: Int): CharSequence =
        delegate.getQuantityText(id, quantity).let { text ->
            if (MeowResourceFilter.shouldMeow(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getQuantityString(id: Int, quantity: Int): String =
        delegate.getQuantityString(id, quantity).let { text ->
            if (MeowResourceFilter.shouldMeow(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any): String =
        delegate.getQuantityString(id, quantity, *formatArgs).let { text ->
            if (MeowResourceFilter.shouldMeow(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getTextArray(id: Int): Array<CharSequence> =
        delegate.getTextArray(id).let { texts ->
            if (MeowResourceFilter.shouldMeow(id)) {
                texts.map(MeowTextTransformer::transform).toTypedArray()
            } else {
                texts
            }
        }

    override fun getStringArray(id: Int): Array<String> =
        delegate.getStringArray(id).let { texts ->
            if (MeowResourceFilter.shouldMeow(id)) {
                texts.map(MeowTextTransformer::transform).toTypedArray()
            } else {
                texts
            }
        }
}

class MeowResourcesContext(
    base: Context,
) : ContextWrapper(base) {
    private val meowResources = MeowResources(base.resources)

    override fun getResources(): Resources = meowResources
}
