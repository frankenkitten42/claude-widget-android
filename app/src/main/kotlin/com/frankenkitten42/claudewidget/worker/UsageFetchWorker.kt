package com.frankenkitten42.claudewidget.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
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

        return when (val result = usageApi.fetchUsage()) {
            is UsageResult.Success -> {
                val data = result.data
                Log.d(TAG, "doWork: SUCCESS 5hr=${data.fiveHour.utilization}% 7d=${data.sevenDay.utilization}%")
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
                Log.d(TAG, "doWork: RATE LIMITED")
                Result.success()
            }
            is UsageResult.AuthRequired -> {
                Log.d(TAG, "doWork: AUTH REQUIRED")
                ClaudeUsageWidget.showAuthRequired(context, appWidgetManager, widgetIds)
                Result.success()
            }
            is UsageResult.Error -> {
                Log.e(TAG, "doWork: ERROR ${result.message}")
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
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
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
