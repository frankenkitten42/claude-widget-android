package com.frankenkitten42.claudewidget.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.*
import com.frankenkitten42.claudewidget.data.FileReadResult
import com.frankenkitten42.claudewidget.data.UsageFileReader
import com.frankenkitten42.claudewidget.widget.ClaudeUsageWidget
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that reads cached usage data from shared storage
 * (written by the Termux bash script) and updates all widget instances.
 */
class UsageFetchWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val reader = UsageFileReader()

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, ClaudeUsageWidget::class.java)
        )

        Log.d(TAG, "doWork: widgetIds=${widgetIds.toList()}")
        if (widgetIds.isEmpty()) return Result.success()

        return when (val result = reader.readUsage()) {
            is FileReadResult.Success -> {
                val data = result.data
                Log.d(TAG, "doWork: SUCCESS 5hr=${data.fiveHour.utilization}% 7d=${data.sevenDay.utilization}%")
                ClaudeUsageWidget.updateWidgets(
                    context          = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds     = widgetIds,
                    fiveHrUtil       = data.fiveHour.utilization,
                    fiveHrResetsAt   = data.fiveHour.resetsAt,
                    sevenDayUtil     = data.sevenDay.utilization,
                    sevenDayResetsAt = data.sevenDay.resetsAt,
                    fetchedAtEpoch   = data.fetchedAtEpoch
                )
                Result.success()
            }
            is FileReadResult.FileNotFound -> {
                Log.d(TAG, "doWork: FILE NOT FOUND ${result.path}")
                ClaudeUsageWidget.showSetupRequired(context, appWidgetManager, widgetIds)
                Result.success()
            }
            is FileReadResult.ParseError -> {
                Log.e(TAG, "doWork: PARSE ERROR ${result.message}")
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
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "UsageFetchWorker"
        private const val WORK_NAME = "claude_usage_fetch"

        /** Schedule periodic file reads every 15 minutes (Android minimum). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageFetchWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }
    }
}
