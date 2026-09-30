package dev.sherry.wcs.features.items.debug

import android.content.Context
import androidx.annotation.StringRes
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.LocalizedContextFactory
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.utils.HostInfo

fun localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    HostInfo.application.debugLocalizedContext().getString(id, *args)

fun Context.localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    debugLocalizedContext().getString(id, *args)

private fun Context.debugLocalizedContext(): Context = LocalizedContextFactory.create(
    this,
    WcSLocaleController.resolvedLocale,
    LocaleResourceMode.InjectedHost,
)
