package dev.sherry.wcs.features.items.chat

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.utils.fastJavaMethod
import dev.sherry.wcs.R
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature

object DisableSpeechToTextButton : SwitchFeature() {

    override val technicalId = "禁用输入框快捷语音转文字"
    override val nameRes = R.string.feature_disable_speech_to_text_button_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_speech_to_text_button_description

    override fun onEnable() {
        ChatFooter::getV2TBtnLayout.fastJavaMethod!!.hookBefore {
            result = null
        }
    }
}
