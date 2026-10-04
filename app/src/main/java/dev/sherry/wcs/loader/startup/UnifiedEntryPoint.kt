package dev.sherry.wcs.loader.startup

import android.app.Application
import android.content.Context
import dalvik.system.InMemoryDexClassLoader
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sherry.wcs.loader.abc.IHookBridge
import dev.sherry.wcs.loader.abc.ILoaderService
import dev.sherry.wcs.loader.environment.EnvironmentHider
import dev.sherry.wcs.loader.utils.HybridClassLoader
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.hookAfterDirectly
import dev.sherry.wcs.utils.reflection.ClassLoaders

object UnifiedEntryPoint {

    private const val TAG = "UnifiedEntryPoint"

    fun entry(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        initialClassLoader: ClassLoader,
        modulePath: String
    ) {
        StartupInfo.hookBridge = hookBridge

        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        if (self is InMemoryDexClassLoader) {
            // The Zygisk payload's parent is the system loader. Keep the payload loader
            // separately so HybridClassLoader can search its DEX without parent delegation.
            HybridClassLoader.moduleClassLoader = self
        }
        HybridClassLoader.moduleParentClassLoader = selfParent
        self.reflekt()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)

        "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                val context = thisObject as Context
                EnvironmentHider.install(context, modulePath)
                val currentClassLoader = context.classLoader
                "android.app.Instrumentation".toClass(currentClassLoader).reflekt()
                    .firstMethod("callApplicationOnCreate").hookAfterDirectly {
                        runCatching {
                            StartupAgent.startup(
                                loaderService,
                                hookBridge,
                                modulePath,
                                args[0] as Application
                            )
                        }.onFailure { WeLogger.e(TAG, "StartupAgent failed", it) }
                    }
            }
    }
}
