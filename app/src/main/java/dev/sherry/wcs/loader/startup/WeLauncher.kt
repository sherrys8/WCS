package dev.sherry.wcs.loader.startup

import android.content.Context
import com.tencent.mm.boot.BuildConfig
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.constants.Preferences
import dev.sherry.wcs.dexkit.cache.DexCacheManager
import dev.sherry.wcs.features.core.FeaturesLoader
import dev.sherry.wcs.i18n.WeKitLocaleController
import dev.sherry.wcs.loader.utils.ActivityProxy
import dev.sherry.wcs.loader.utils.ParcelableFixer
import dev.sherry.wcs.loader.utils.ResourcesInjector
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.RuntimeConfig
import dev.sherry.wcs.utils.TargetProcesses
import dev.sherry.wcs.utils.WeLogger

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        WeKitLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
