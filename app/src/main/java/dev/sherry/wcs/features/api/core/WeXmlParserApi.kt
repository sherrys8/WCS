package dev.sherry.wcs.features.api.core

import dev.sherry.wcs.R
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.core.ApiFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.utils.HookParam
import dev.sherry.wcs.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

object WeXmlParserApi : ApiFeature(), IResolveDex {

    override val technicalId = "XML 解析钩子服务"
    override val nameRes = R.string.feature_we_xml_parser_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_xml_parser_api_description

    private const val TAG = "WeXmlParserApi"

    fun interface IAfterParseListener {
        fun onParse(param: HookParam, result: MutableMap<String, Any?>)
    }

    private val listeners = CopyOnWriteArrayList<IAfterParseListener>()

    fun addListener(listener: IAfterParseListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: IAfterParseListener) {
        listeners.remove(listener)
    }

    private val methodXmlParser by dexMethod {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            usingEqStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
        }
    }

    override fun onEnable() {
        methodXmlParser.hookAfter {
            val param = this

            @Suppress("UNCHECKED_CAST")
            val result = result as? MutableMap<String, Any?>? ?: return@hookAfter
            listeners.forEach { listener ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    listener.onParse(param, result)
                }.onFailure { WeLogger.e(TAG, "failed to execute listener ${listener.javaClass.name}", it) }
            }
        }
    }

    override fun onDisable() {
        listeners.clear()
    }
}
