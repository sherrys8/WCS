package dev.sherry.wcs.i18n

import dev.sherry.wcs.R
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeowResourceFilterTest {
    @Test
    fun acceptsOnlyWcSResourcePackageIds() {
        assertTrue(MeowResourceFilter.isWcSResource(R.string.settings_title))
        assertFalse(MeowResourceFilter.isWcSResource(0x7f010001))
        assertFalse(MeowResourceFilter.isWcSResource(android.R.string.ok))
    }
}
