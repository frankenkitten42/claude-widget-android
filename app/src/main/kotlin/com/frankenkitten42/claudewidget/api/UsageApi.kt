package com.frankenkitten42.claudewidget.api

import com.frankenkitten42.claudewidget.auth.OAuthManager
import com.frankenkitten42.claudewidget.auth.TokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UsageWindow(
    val utilization: Double,  // 0.0–100.0
    val resetsAt: String      // ISO 8601
)

data class UsageData(
    val fiveHour: UsageWindow,
    val sevenDay: UsageWindow,
    val fetchedAt: Long = System.currentTimeMillis()
)

sealed class UsageResult {
    data class Success(val data: UsageData) : UsageResult()
    data object RateLimited : UsageResult()
    data object AuthRequired : UsageResult()
    data class Error(val message: String) : UsageResult()
}

/**
 * Fetches Claude usage data from the API.
 * Handles token refresh automatically before each request.
 */
class UsageApi(
    private val tokenStore: TokenStore,
    private val oauthManager: OAuthManager,
    private val httpClient: OkHttpClient
) {
    suspend fun fetchUsage(): UsageResult {
        if (!tokenStore.isLoggedIn) return UsageResult.AuthRequired

        // Refresh token proactively if expiring soon
        if (tokenStore.isExpiredOrExpiring) {
            val refreshResult = oauthManager.refreshTokens()
            if (refreshResult.isFailure) return UsageResult.AuthRequired
        }

        val token = tokenStore.accessToken ?: return UsageResult.AuthRequired

        val request = Request.Builder()
            .url(USAGE_URL)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "claude-widget/1.0")
            .header("Authorization", "Bearer $token")
            .header("anthropic-beta", OAuthManager.OAUTH_BETA)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val json = JSONObject(response.body!!.string())
                        UsageResult.Success(parseUsageData(json))
                    }
                    429 -> UsageResult.RateLimited
                    401 -> {
                        tokenStore.clear()
                        UsageResult.AuthRequired
                    }
                    else -> UsageResult.Error("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            UsageResult.Error(e.message ?: "Network error")
        }
    }

    private fun parseUsageData(json: JSONObject): UsageData {
        val fiveHour = json.getJSONObject("five_hour")
        val sevenDay = json.getJSONObject("seven_day")
        return UsageData(
            fiveHour = UsageWindow(
                utilization = fiveHour.getDouble("utilization"),
                resetsAt    = fiveHour.getString("resets_at")
            ),
            sevenDay = UsageWindow(
                utilization = sevenDay.getDouble("utilization"),
                resetsAt    = sevenDay.getString("resets_at")
            )
        )
    }

    companion object {
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    }
}
