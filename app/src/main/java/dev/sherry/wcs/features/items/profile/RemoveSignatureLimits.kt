package dev.sherry.wcs.features.items.profile

import android.view.View
import android.widget.TextView
import com.tencent.mm.plugin.setting.ui.setting.EditSignatureUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sherry.wcs.R
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature
import dev.sherry.wcs.utils.HookHandle
import dev.sherry.wcs.utils.hookBeforeDirectly

object RemoveSignatureLimits : SwitchFeature(), IResolveDex {

    override val technicalId = "移除个性签名限制"
    override val nameRes = R.string.feature_remove_signature_limits_name
    override val categoryIds = listOf(FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_remove_signature_limits_description

    private lateinit var stringMatchesMethodUnhook: HookHandle

    private lateinit var setFiltersUnhook: HookHandle

    override fun onEnable() {
        EditSignatureUI::class.reflekt()
            .firstMethod { name = "initView" }.apply {
                hookBefore {
                    setFiltersUnhook = "${PackageNames.WECHAT}.ui.widget.MMEditText".toClass().reflekt()
                        .firstMethod {
                            name = "setFilters"
                        }.hookBeforeDirectly {
                            result = null
                        }
                }

                hookAfter {
                    val activity = thisObject as EditSignatureUI
                    activity.enableOptionMenu(true)
                    (activity.reflekt()
                        .firstField { type = TextView::class }
                        .get()!! as TextView).visibility = View.GONE
                }
            }

        methodTextWatcherAfterTextChanged.hookBefore {
            result = null
        }

        methodConfirmButtonOnClickListenerOnClick.apply {
            hookBefore {
                stringMatchesMethodUnhook = String::class.java.reflekt()
                    .firstMethod { name = "matches" }
                    .hookBeforeDirectly { result = false }
            }
            hookAfter {
                stringMatchesMethodUnhook.unhook()
                setFiltersUnhook.unhook()
            }
        }
    }

    private val methodTextWatcherAfterTextChanged by dexMethod {
        searchPackages("${PackageNames.WECHAT}.plugin.setting.ui.setting")
        matcher {
            declaredClass {
                addMethod {
                    name = "<init>"
                    paramTypes("${PackageNames.WECHAT}.plugin.setting.ui.setting.EditSignatureUI", "java.lang.String")
                }
                addInterface { className = "android.text.TextWatcher" }
            }

            name = "afterTextChanged"
        }
    }

    private val methodConfirmButtonOnClickListenerOnClick by dexMethod {
        searchPackages("${PackageNames.WECHAT}.plugin.setting.ui.setting")
        matcher {
            declaredClass {
                addMethod {
                    name = "<init>"
                    paramTypes("${PackageNames.WECHAT}.plugin.setting.ui.setting.EditSignatureUI")
                }
                addInterface { className = $$"android.view.MenuItem$OnMenuItemClickListener" }
            }

            name = "onMenuItemClick"
            usingEqStrings(".*[", "].*")
        }
    }
}
