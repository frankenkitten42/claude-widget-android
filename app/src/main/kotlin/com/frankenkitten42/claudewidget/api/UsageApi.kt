package com.frankenkitten42.claudewidget.api

import android.util.Log
import com.frankenkitten42.claudewidget.auth.OAuthManager
import com.frankenkitten42.claudewidget.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // Save token before any refresh attempt — refresh failure (401) clears the store
        val savedToken = tokenStore.accessToken
        if (savedToken == null) {
            Log.d(TAG, "fetchUsage: not logged in")
            return UsageResult.AuthRequired
        }

        // Refresh token proactively if expiring soon
        var refreshFailed = false
        if (tokenStore.isExpiredOrExpiring) {
            Log.d(TAG, "fetchUsage: token expired/expiring, refreshing...")
            val refreshResult = oauthManager.refreshTokens()
            if (refreshResult.isFailure) {
                Log.e(TAG, "fetchUsage: refresh failed: ${refreshResult.exceptionOrNull()?.message}")
                refreshFailed = true
                // Don't give up — try the API call with the saved token anyway.
            } else {
                Log.d(TAG, "fetchUsage: refresh succeeded")
            }
        }

        // Use refreshed token if available, otherwise fall back to saved token
        val token = tokenStore.accessToken ?: savedToken

        val request = Request.Builder()
            .url(USAGE_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "claude-code/2.1.83")
            .header("Authorization", "Bearer $token")
            .header("anthropic-beta", OAuthManager.OAUTH_BETA)
            .build()

        Log.d(TAG, "fetchUsage: calling $USAGE_URL")

        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "fetchUsage: HTTP ${response.code}, body=${body.take(300)}")
                    when (response.code) {
                        200 -> {
                            val json = JSONObject(body)
                            UsageResult.Success(parseUsageData(json))
                        }
                        429 -> UsageResult.RateLimited
                        401 -> {
                            tokenStore.clear()
                            UsageResult.AuthRequired
                        }
                        else -> UsageResult.Error("HTTP ${response.code}: ${body.take(150)}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchUsage: exception", e)
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
        private const val TAG = "UsageApi"
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    }
}
