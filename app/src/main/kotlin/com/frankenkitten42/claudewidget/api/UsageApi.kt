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
        if (!tokenStore.isLoggedIn) {
            Log.d(TAG, "fetchUsage: not logged in")
            return UsageResult.AuthRequired
        }

        // Refresh token proactively if expiring soon
        if (tokenStore.isExpiredOrExpiring) {
            Log.d(TAG, "fetchUsage: token expired/expiring, refreshing...")
            val refreshResult = oauthManager.refreshTokens()
            if (refreshResult.isFailure) {
                Log.e(TAG, "fetchUsage: refresh failed: ${refreshResult.exceptionOrNull()?.message}")
                return UsageResult.AuthRequired
            }
            Log.d(TAG, "fetchUsage: refresh succeeded")
        }

        val token = tokenStore.accessToken ?: return UsageResult.AuthRequired

        // Try without beta header first (feature may have graduated),
        // then retry with beta header if we get 403
        for (useBeta in listOf(false, true)) {
            val reqBuilder = Request.Builder()
                .url(USAGE_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "claude-widget/1.0")
                .header("Authorization", "Bearer $token")
            if (useBeta) {
                reqBuilder.header("anthropic-beta", OAuthManager.OAUTH_BETA)
            }
            val request = reqBuilder.build()

            Log.d(TAG, "fetchUsage: calling $USAGE_URL (beta=$useBeta)")

            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        Log.d(TAG, "fetchUsage: HTTP ${response.code} (beta=$useBeta), body=${body.take(300)}")
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
                            403 -> null // will retry with/without beta
                            else -> UsageResult.Error("HTTP ${response.code}: ${body.take(150)}")
                        }
                    }
                }
                if (result != null) return result
                // 403 → try the other beta setting
            } catch (e: Exception) {
                Log.e(TAG, "fetchUsage: exception (beta=$useBeta)", e)
                if (useBeta) return UsageResult.Error(e.message ?: "Network error")
                // first attempt failed with exception → try with beta header
            }
        }
        return UsageResult.Error("HTTP 403 on both attempts")
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
