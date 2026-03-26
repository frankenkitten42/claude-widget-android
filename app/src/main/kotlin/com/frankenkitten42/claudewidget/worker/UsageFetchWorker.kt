package com.frankenkitten42.claudewidget.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.*
import com.frankenkitten42.claudewidget.api.UsageApi
import com.frankenkitten42.claudewidget.api.UsageResult
import com.frankenkitten42.claudewidget.auth.OAuthManager
import com.frankenkitten42.claudewidget.auth.TokenStore
import com.frankenkitten42.claudewidget.widget.ClaudeUsageWidget
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that fetches Claude usage and updates all widget instances.
 * Scheduled every 10 minutes. On 429, skips update and keeps current widget state.
 */
class UsageFetchWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val httpClient = OkHttpClient()
        val tokenStore = TokenStore(context)
        val oauthManager = OAuthManager(context, tokenStore, httpClient)
        val usageApi = UsageApi(tokenStore, oauthManager, httpClient)

        Log.d(TAG, "doWork: isLoggedIn=${tokenStore.isLoggedIn}, isExpired=${tokenStore.isExpiredOrExpiring}")

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, ClaudeUsageWidget::class.java)
        )

        Log.d(TAG, "doWork: widgetIds=${widgetIds.toList()}")
        if (widgetIds.isEmpty()) return Result.success()

        val cache = context.getSharedPreferences("usage_cache", Context.MODE_PRIVATE)

        return when (val result = usageApi.fetchUsage()) {
            is UsageResult.Success -> {
                val data = result.data
                Log.d(TAG, "doWork: SUCCESS 5hr=${data.fiveHour.utilization}% 7d=${data.sevenDay.utilization}%")
                // Cache the successful result
                cache.edit()
                    .putFloat("five_hr_util", data.fiveHour.utilization.toFloat())
                    .putString("five_hr_resets", data.fiveHour.resetsAt)
                    .putFloat("seven_day_util", data.sevenDay.utilization.toFloat())
                    .putString("seven_day_resets", data.sevenDay.resetsAt)
                    .putLong("cached_at", System.currentTimeMillis())
                    .apply()
                ClaudeUsageWidget.updateWidgets(
                    context          = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds     = widgetIds,
                    fiveHrUtil       = data.fiveHour.utilization,
                    fiveHrResetsAt   = data.fiveHour.resetsAt,
                    sevenDayUtil     = data.sevenDay.utilization,
                    sevenDayResetsAt = data.sevenDay.resetsAt
                )
                Result.success()
            }
            is UsageResult.RateLimited -> {
                Log.d(TAG, "doWork: RATE LIMITED — showing cached data")
                showCachedOrFallback(cache, appWidgetManager, widgetIds)
                Result.success()
            }
            is UsageResult.AuthRequired -> {
                Log.d(TAG, "doWork: AUTH REQUIRED")
                ClaudeUsageWidget.showAuthRequired(context, appWidgetManager, widgetIds)
                Result.success()
            }
            is UsageResult.Error -> {
                Log.e(TAG, "doWork: ERROR ${result.message}")
                // Show cached data if available, otherwise show error
                val cachedAt = cache.getLong("cached_at", 0L)
                if (cachedAt > 0) {
                    Log.d(TAG, "doWork: showing cached data from error path")
                    showCachedOrFallback(cache, appWidgetManager, widgetIds)
                } else {
                    ClaudeUsageWidget.updateWidgets(
                        context          = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetIds     = widgetIds,
                        fiveHrUtil       = 0.0,
                        fiveHrResetsAt   = "",
                        sevenDayUtil     = 0.0,
                        sevenDayResetsAt = "",
                        isOffline        = true,
                        errorDetail      = result.message
                    )
                }
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        }
    }

    private fun showCachedOrFallback(
        cache: SharedPreferences,
        appWidgetManager: AppWidgetManager,
        widgetIds: IntArray
    ) {
        val cachedAt = cache.getLong("cached_at", 0L)
        if (cachedAt > 0) {
            ClaudeUsageWidget.updateWidgets(
                context          = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds     = widgetIds,
                fiveHrUtil       = cache.getFloat("five_hr_util", 0f).toDouble(),
                fiveHrResetsAt   = cache.getString("five_hr_resets", "") ?: "",
                sevenDayUtil     = cache.getFloat("seven_day_util", 0f).toDouble(),
                sevenDayResetsAt = cache.getString("seven_day_resets", "") ?: ""
            )
        } else {
            ClaudeUsageWidget.updateWidgets(
                context          = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds     = widgetIds,
                fiveHrUtil       = 0.0,
                fiveHrResetsAt   = "",
                sevenDayUtil     = 0.0,
                sevenDayResetsAt = "",
                isOffline        = true,
                errorDetail      = "No cached data yet"
            )
        }
    }

    companion object {
        private const val TAG = "UsageFetchWorker"
        private const val WORK_NAME = "claude_usage_fetch"

        /** Schedule periodic fetches every 10 minutes. Call once on app start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageFetchWorker>(10, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
