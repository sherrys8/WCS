package dev.sherry.wcs.utils

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * breezeblue.ai 网页侧的积分接口。
 *
 * 与 api.breeze.blue 的开发者接口是两套东西：这里靠登录会话 cookie 鉴权，
 * 不带 cookie 一律 401 code=AUTH_REQUIRED，xi-api-key 用不上。会话 cookie 是
 * httpOnly 的，只能由用户在浏览器 DevTools 里把整条 Cookie 复制进设置。
 *
 * 领取接口按天幂等：重复调用只会返回 already_claimed=true、credits_issued=0。
 */
object BreezeCreditsClient {

    private const val BASE = "https://breezeblue.ai"
    private const val SUMMARY_PATH = "/api/credits/summary"
    private const val CLAIM_PATH = "/api/credits/daily-claim"
    private val JSON_TYPE = "application/json".toMediaType()

    class CreditsError(message: String) : Exception(message)

    data class Summary(
        val balance: Double,
        val amount: Double,
        val available: Boolean,
        val claimedToday: Boolean,
        val nextResetAt: String,
    )

    data class Claim(
        val claimed: Boolean,
        val alreadyClaimed: Boolean,
        val creditsIssued: Double,
        val balance: Double,
        val rewardTier: String,
        val nextResetAt: String,
    )

    fun summary(cookie: String): Summary {
        val json = request("GET", SUMMARY_PATH, cookie, null)
        return Summary(
            balance = json.optDouble("balance", 0.0),
            amount = json.optDouble("daily_claim_amount", 0.0),
            available = json.optBoolean("daily_claim_available"),
            claimedToday = json.optBoolean("daily_claim_claimed_today"),
            nextResetAt = json.optString("daily_claim_next_reset_at"),
        )
    }

    /** 档位由服务端按账号状态定（实测传未知 tier 字段会被忽略），所以请求体留空对象。 */
    fun claim(cookie: String): Claim {
        val json = request("POST", CLAIM_PATH, cookie, "{}")
        return Claim(
            claimed = json.optBoolean("claimed"),
            alreadyClaimed = json.optBoolean("already_claimed"),
            creditsIssued = json.optDouble("credits_issued", 0.0),
            balance = json.optDouble("balance", 0.0),
            rewardTier = json.optString("daily_claim_reward_tier"),
            nextResetAt = json.optString("daily_claim_next_reset_at"),
        )
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun request(method: String, path: String, cookie: String, body: String?): JSONObject {
        if (cookie.isBlank()) throw CreditsError("未填写 breezeblue.ai 登录 Cookie")
        val builder = Request.Builder()
            .url(BASE + path)
            .header("Cookie", cookie.trim())
            .header("Accept", "application/json")
            .header("Referer", "$BASE/zh/app/home")
            .header("X-Requested-With", "XMLHttpRequest")
        builder.method(method, body?.toRequestBody(JSON_TYPE))

        val response = runCatching { http.newCall(builder.build()).execute() }
            .getOrElse { throw CreditsError("网络失败：${it.message}") }
        response.use {
            val text = it.body?.bytes()?.toString(Charsets.UTF_8).orEmpty()
            if (it.code == 401) throw CreditsError("登录 cookie 无效或已过期 (AUTH_REQUIRED)")
            if (!it.isSuccessful) throw CreditsError("HTTP ${it.code} ${text.take(160)}")
            return runCatching { JSONObject(text) }
                .getOrElse { throw CreditsError("响应不是 JSON：${text.take(120)}") }
        }
    }
}
