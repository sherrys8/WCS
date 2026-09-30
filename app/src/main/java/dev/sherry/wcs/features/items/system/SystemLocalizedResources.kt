package dev.sherry.wcs.features.items.system

import android.content.Context
import androidx.annotation.StringRes
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.LocalizedContextFactory
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.utils.HostInfo

fun localizedSystemString(@StringRes id: Int, vararg args: Any): String =
    HostInfo.application.systemLocalizedContext().getString(id, *args)

fun Context.localizedSystemString(@StringRes id: Int, vararg args: Any): String =
    systemLocalizedContext().getString(id, *args)

private fun Context.systemLocalizedContext(): Context = LocalizedContextFactory.create(
    this,
    WcSLocaleController.resolvedLocale,
    LocaleResourceMode.InjectedHost,
)
