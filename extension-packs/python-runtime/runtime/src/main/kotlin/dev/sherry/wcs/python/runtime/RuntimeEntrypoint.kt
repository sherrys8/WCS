package dev.sherry.wcs.python.runtime

import dev.sherry.wcs.python.api.PythonPluginHost
import dev.sherry.wcs.python.api.PythonRuntimeApi
import dev.sherry.wcs.python.api.PythonRuntimeBackend
import dev.sherry.wcs.python.api.PythonRuntimeConfig
import dev.sherry.wcs.python.api.PythonRuntimeStartupException

/** Loader-neutral bootstrap. Chaquopy classes are resolved only after native loading completes. */
object RuntimeEntrypoint {
    @JvmStatic
    fun bootstrap(apiVersion: Int, config: PythonRuntimeConfig, host: PythonPluginHost): PythonRuntimeBackend {
        if (apiVersion != PythonRuntimeApi.API_VERSION) {
            throw PythonRuntimeStartupException(
                "API",
                message = "Unsupported Python runtime API version: $apiVersion",
            )
        }
        try {
            RuntimeNativeLoader.load(config)
        } catch (error: PythonRuntimeStartupException) {
            throw error
        } catch (error: Throwable) {
            throw PythonRuntimeStartupException(
                phase = "NATIVE",
                message = "Python native runtime bootstrap failed",
                cause = error,
            )
        }
        return try {
            ChaquopyRuntimeBackend()
        } catch (error: Throwable) {
            throw PythonRuntimeStartupException(
                phase = "BACKEND",
                message = "Python runtime backend could not be created",
                cause = error,
            )
        }
    }
}
