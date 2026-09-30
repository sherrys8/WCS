package dev.sherry.wcs.features.items.shortvideos

import android.content.Context
import androidx.annotation.StringRes
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.LocalizedContextFactory
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.utils.HostInfo

fun localizedShortVideoString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.shortVideoLocalizedContext().getString(id, *formatArgs)

private fun Context.shortVideoLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WcSLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
