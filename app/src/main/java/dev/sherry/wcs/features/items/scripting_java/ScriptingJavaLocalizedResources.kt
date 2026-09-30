package dev.sherry.wcs.features.items.scripting_java

import androidx.annotation.StringRes
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.LocalizedContextFactory
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.utils.HostInfo

fun localizedScriptingJavaString(@StringRes id: Int, vararg args: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        WcSLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *args)
