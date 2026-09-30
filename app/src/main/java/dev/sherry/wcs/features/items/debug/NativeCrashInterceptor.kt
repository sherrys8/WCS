package dev.sherry.wcs.features.items.debug

import com.tencent.mm.ui.LauncherUI
import dev.sherry.wcs.R
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.crash.CrashLogsManager
import dev.sherry.wcs.utils.crash.NativeCrashHandler

object NativeCrashInterceptor : SwitchFeature() {

    override val technicalId = "崩溃拦截 (Native)"
    override val nameRes = R.string.feature_native_crash_interceptor_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_native_crash_interceptor_description

    private const val TAG = "NativeCrashInterceptor"

    override fun onEnable() {
        if (!NativeCrashHandler.install()) {
            WeLogger.e(TAG, "failed to install native crash interceptor")
        }

        checkPendingCrash()
    }

    private fun checkPendingCrash() {
        runCatching {
            if (!CrashInterceptorUtils.isMainProcess(HostInfo.application)) {
                WeLogger.d(TAG, "skipping pending crash check in non-main process")
                return
            }

            if (CrashLogsManager.hasPendingNativeCrash()) {
                WeLogger.i(
                    TAG,
                    "pending native crash detected, will show dialog when Activity is ready"
                )
                showToast(localizedDebugString(R.string.debug_native_crash_preparing_report))
                CrashInterceptorUtils.startActivityPolling(TAG) {
                    showPendingNativeCrashDialog()
                }
            }
        }.onFailure { WeLogger.e(TAG, "failed to check for pending crash", it) }
    }

    private fun showPendingNativeCrashDialog() {
        runCatching {
            val activity = LauncherUI.getInstance()
            if (activity == null || activity.isFinishing || activity.isDestroyed) return
            val crashLogFile = CrashLogsManager.pendingNativeCrashLogFile ?: return
            CrashInterceptorUtils.showPendingCrashDialog(
                activity = activity,
                crashLogFile = crashLogFile,
                titleSummaryRes = R.string.debug_native_crash_detected,
                titleDetailRes = R.string.debug_native_crash_details,
                clearPendingFlag = CrashLogsManager::clearPendingNativeCrashFlag,
                extractSummary = ::extractCrashSummary
            )
        }.onFailure { WeLogger.e(TAG, "failed to show pending crash dialog", it) }
    }

    private fun extractCrashSummary(crashInfo: String): String {
        val lines = crashInfo.lines()
        val summary = StringBuilder()

        var foundStackTrace = false
        var stackTraceLineCount = 0

        for (line in lines) {
            when {
                line.startsWith("Crash Time:") -> {
                    summary.append(line).append("\n")
                }

                line.startsWith("Crash Type:") -> {
                    summary.append(line).append("\n\n")
                }

                line.startsWith("Signal:") -> {
                    summary.append(line).append("\n")
                }

                line.startsWith("Description:") -> {
                    summary.append(line).append("\n")
                }

                line.startsWith("Fault Address:") -> {
                    summary.append(line).append("\n\n")
                }

                line.contains("Stack Trace") -> {
                    foundStackTrace = true
                    summary.append(localizedDebugString(R.string.debug_crash_stack_preview)).append("\n")
                }

                foundStackTrace -> {
                    if (line.trim().isNotEmpty() && !line.contains("====")) {
                        summary.append(line).append("\n")
                        stackTraceLineCount++
                    }
                }
            }

            if (stackTraceLineCount >= 5) break
        }

        if (summary.isEmpty()) {
            return localizedDebugString(R.string.debug_crash_summary_parse_failed)
        }

        summary.append("\n").append(localizedDebugString(R.string.debug_crash_view_full_log_hint))
        return summary.toString()
    }

    override fun onDisable() {
        NativeCrashHandler.uninstall()
    }
}
