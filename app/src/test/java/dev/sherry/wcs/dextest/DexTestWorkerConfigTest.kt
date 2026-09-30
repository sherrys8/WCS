package dev.sherry.wcs.dextest

import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DexTestWorkerConfigTest {
    @Test
    fun parsesAllWorkerProperties() {
        val config = DexTestWorkerConfig.fromSystemProperties(properties())
        assertEquals(3040L, config.versionCode)
        assertEquals("8.0.69", config.versionName)
        assertFalse(config.isGooglePlay)
        assertNull(config.featureSelectors)
    }

    @Test
    fun parsesFeatureSelectors() {
        val properties = properties().apply {
            setProperty("wcs.dexTest.features", "AntiReadReceipts, AntiSecMsg")
        }

        assertEquals(
            listOf("AntiReadReceipts", "AntiSecMsg"),
            DexTestWorkerConfig.fromSystemProperties(properties).featureSelectors,
        )
    }

    @Test
    fun rejectsInvalidBooleanAndNumber() {
        val booleanProperties = properties().apply { setProperty("wcs.dexTest.isGooglePlay", "maybe") }
        assertThrows(IllegalStateException::class.java) {
            DexTestWorkerConfig.fromSystemProperties(booleanProperties)
        }
        val numberProperties = properties().apply { setProperty("wcs.dexTest.versionCode", "not-a-number") }
        assertThrows(IllegalStateException::class.java) {
            DexTestWorkerConfig.fromSystemProperties(numberProperties)
        }
    }

    private fun properties() = Properties().apply {
        setProperty("wcs.dexTest.apk", "/tmp/wechat.apk")
        setProperty("wcs.dexTest.nativeLibrary", "/tmp/libdexkit.so")
        setProperty("wcs.dexTest.report", "/tmp/report.json")
        setProperty("wcs.dexTest.dexKitVersion", "2.2.0")
        setProperty("wcs.dexTest.dexKitRevision", "revision")
        setProperty("wcs.dexTest.versionCode", "3040")
        setProperty("wcs.dexTest.versionName", "8.0.69")
        setProperty("wcs.dexTest.buildTag", "Android_Wechat_RELEASE")
        setProperty("wcs.dexTest.isGooglePlay", "false")
    }
}
