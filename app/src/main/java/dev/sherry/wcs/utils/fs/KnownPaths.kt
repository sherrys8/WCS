package dev.sherry.wcs.utils.fs

import android.os.Environment
import dev.sherry.wcs.BuildConfig
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.utils.HostInfo
import java.nio.file.Path
import kotlin.io.path.div

object KnownPaths {

    val internalStorage: Path by lazy {
        Environment.getExternalStorageDirectory().asPath
    }

    val moduleData by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT) /
                BuildConfig.TAG).createDirsSafe()
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    /**
     * 可再生缓存。挂在 [moduleData] 下面而不是宿主的 `cache/` 目录里：
     * `Android/data/<包名>/cache` 是微信的外部缓存目录，微信「清理缓存」和系统的
     * 「清除缓存」都会整目录清掉，`WcS/Wcs_Cache` 不在它们的清理范围内。
     */
    val moduleCache by lazy {
        (moduleData / "Wcs_Cache").createDirsSafe()
    }

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val userAssets by lazy {
        (moduleAssets / "user").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / BuildConfig.TAG)
            .createDirsSafe()
    }
}
