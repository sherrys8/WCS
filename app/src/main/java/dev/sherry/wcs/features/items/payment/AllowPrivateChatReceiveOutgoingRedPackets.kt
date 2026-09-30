package dev.sherry.wcs.features.items.payment

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sherry.wcs.R
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.features.core.SwitchFeature

object AllowPrivateChatReceiveOutgoingRedPackets : SwitchFeature() {

    override val technicalId = "允许领取私聊红包"
    override val nameRes = R.string.feature_allow_private_chat_receive_outgoing_red_packets_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_allow_private_chat_receive_outgoing_red_packets_description

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("key_type", 1)
            }
        }
    }
}
