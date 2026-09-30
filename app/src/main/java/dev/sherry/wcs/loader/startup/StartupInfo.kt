package dev.sherry.wcs.loader.startup

import dev.sherry.wcs.loader.abc.IHookBridge
import dev.sherry.wcs.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
