package dev.sherry.wcs.utils

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sherry.wcs.R
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.LocalizedContextFactory
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.utils.android.showToast
import kotlin.system.exitProcess

fun restartHost() {
    WeLogger.i("KillHostUtils", "restarting host")
    val context = LocalizedContextFactory.create(
        HostInfo.application,
        WcSLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
    showToast(context, context.getString(R.string.noncompose_restarting_host))
    val instance = "com.tencent.mm.process.KillProcessHelperActivity".toClass()
        .reflekt().firstField().getStatic()!!
    instance.reflekt().firstMethod().invoke(HostInfo.application, true)
}

fun killHost() {
    WeLogger.i("KillHostUtils", "killing host")
    exitProcess(0)
}
