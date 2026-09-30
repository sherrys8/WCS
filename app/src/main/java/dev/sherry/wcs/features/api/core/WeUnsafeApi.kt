package dev.sherry.wcs.features.api.core

import android.annotation.SuppressLint
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.sherry.wcs.R
import dev.sherry.wcs.features.core.ApiFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import java.lang.reflect.Method

object WeUnsafeApi : ApiFeature() {

    override val technicalId = "Unsafe 服务"
    override val nameRes = R.string.feature_we_unsafe_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_unsafe_api_description

    private lateinit var theUnsafe: Any
    private lateinit var mAllocateInstance: Method

    @SuppressLint("DiscouragedPrivateApi")
    override fun onEnable() {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
        theUnsafe = theUnsafeField.makeAccessible().get(null)!!
        mAllocateInstance = unsafeClass.getMethod(
            "allocateInstance",
            Class::class.java
        )
    }

    fun allocateInstance(clazz: Class<*>): Any? = mAllocateInstance.invoke(theUnsafe, clazz)
}
