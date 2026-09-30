package dev.sherry.wcs.features.items.chat

import dev.sherry.wcs.R
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature

object DisablePat : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用拍一拍"
    override val nameRes = R.string.feature_disable_pat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_pat_description

    private val methodAvatarDoubleClick by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.AvatarDoubleClickListener", "onDoubleClick: %s")
        }
    }

    override fun onEnable() {
        methodAvatarDoubleClick.hookBefore {
            result = true
        }
    }
}
