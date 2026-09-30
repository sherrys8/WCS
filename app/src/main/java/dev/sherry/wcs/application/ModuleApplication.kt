package dev.sherry.wcs.application

import android.app.Application
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        WcSLocaleController.initializeModuleProcess(this)
    }
}
