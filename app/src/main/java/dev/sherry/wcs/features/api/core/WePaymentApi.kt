package dev.sherry.wcs.features.api.core

import dev.sherry.wcs.R
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.data
import dev.sherry.wcs.dexkit.dsl.dexClass
import dev.sherry.wcs.dexkit.dsl.dexConstructor
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.api.net.WeNetSceneApi
import dev.sherry.wcs.features.core.ApiFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.utils.WeLogger

object WePaymentApi : ApiFeature(), IResolveDex {

    override val technicalId = "支付服务"
    override val nameRes = R.string.feature_we_payment_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_payment_api_description

    private const val TAG = "WePaymentApi"

    private val ctorNetSceneTransferOperation by dexConstructor {
        searchPackages("com.tencent.mm.plugin.remittance.model")
        matcher {
            declaredClass {
                usingEqStrings("Micromsg.NetSceneTenpayRemittanceConfirm", "/cgi-bin/mmpay-bin/transferoperation")
            }
            usingEqStrings("account click info , key is %s, value is %s")
        }
    }
    val classReceiveLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneReceiveLuckyMoney")
                }
            }
        }
    }
    val classOpenLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneOpenLuckyMoney")
                }
            }
        }
    }
    val methodReceiveLuckyMoneyOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classReceiveLuckyMoney.data.name)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }
    val methodOpenLuckyMoneyOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classOpenLuckyMoney.data.name)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }

    fun confirmTransfer(transactionId: String, transferId: String, payerUsername: String, invalidTime: Int): Boolean {
        return executeTransferOperation("confirm", transactionId, transferId, payerUsername, invalidTime)
    }

    fun refuseTransfer(transactionId: String, transferId: String, payerUsername: String, invalidTime: Int): Boolean {
        return executeTransferOperation("refuse", transactionId, transferId, payerUsername, invalidTime)
    }

    private fun executeTransferOperation(
        operation: String,
        transactionId: String,
        transferId: String,
        payerUsername: String,
        invalidTime: Int
    ): Boolean {
        return try {
            val ctor = ctorNetSceneTransferOperation.constructor
            val netScene = when (ctor.parameterCount) {
                10 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null)
                12 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null, 0L, "")
                13 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null, 0L, "", "")
                14 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, "", null, 0L, "", "")
                else -> error("unknown NetSceneTransferOperation constructor variant")
            }
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "${operation}Transfer failed", e)
            false
        }
    }
}
