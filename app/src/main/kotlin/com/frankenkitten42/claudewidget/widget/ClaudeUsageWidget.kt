package com.frankenkitten42.claudewidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.frankenkitten42.claudewidget.R
import com.frankenkitten42.claudewidget.worker.UsageFetchWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ClaudeUsageWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Trigger a fresh fetch when the widget is tapped or updated
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<UsageFetchWorker>().build())
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Handle forced refresh broadcasts
        if (intent.action == ACTION_FORCE_REFRESH) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<UsageFetchWorker>().build())
        }
    }

    companion object {
        const val ACTION_FORCE_REFRESH = "com.frankenkitten42.claudewidget.FORCE_REFRESH"

        // Color thresholds — match PoC exactly
        fun usageColor(utilization: Double): Int = when {
            utilization <= 50.0 -> Color.parseColor("#4CAF50")  // green
            utilization <= 75.0 -> Color.parseColor("#FFC107")  // yellow
            utilization <= 90.0 -> Color.parseColor("#F44336")  // red
            else                -> Color.parseColor("#B71C1C")  // critical red
        }

        /**
         * Push new data to all widget instances. Called by UsageFetchWorker
         * after a successful fetch.
         */
        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            fiveHrUtil: Double,
            fiveHrResetsAt: String,
            sevenDayUtil: Double,
            sevenDayResetsAt: String,
            isOffline: Boolean = false
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_claude_usage)

            val fiveColor  = usageColor(fiveHrUtil)
            val sevenColor = usageColor(sevenDayUtil)

            // Progress bars
            views.setProgressBar(R.id.pb_5hr,  100, fiveHrUtil.toInt(),  false)
            views.setProgressBar(R.id.pb_7day, 100, sevenDayUtil.toInt(), false)

            // Percentage labels
            views.setTextViewText(R.id.tv_5hr_pct,  "${fiveHrUtil.toInt()}%")
            views.setTextViewText(R.id.tv_7day_pct, "${sevenDayUtil.toInt()}%")
            views.setTextColor(R.id.tv_5hr_pct,  fiveColor)
            views.setTextColor(R.id.tv_7day_pct, sevenColor)

            // Reset countdowns
            views.setTextViewText(R.id.tv_5hr_reset,  "Resets: ${formatCountdown(fiveHrResetsAt)}")
            views.setTextViewText(R.id.tv_7day_reset, "Resets: ${formatCountdown(sevenDayResetsAt)}")

            // Updated timestamp
            val updatedText = if (isOffline) {
                "⚠ Offline"
            } else {
                "Updated: ${DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(Instant.now())}"
            }
            views.setTextViewText(R.id.tv_updated, updatedText)

            appWidgetIds.forEach { id ->
                appWidgetManager.updateAppWidget(id, views)
            }
        }

        fun showAuthRequired(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_claude_usage)
            views.setTextViewText(R.id.tv_title, "Claude Usage — Tap to sign in")
            appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
        }

        private fun formatCountdown(resetsAt: String): String {
            return try {
                val reset = Instant.parse(resetsAt)
                val now = Instant.now()
                val diff = now.until(reset, ChronoUnit.SECONDS)
                if (diff <= 0) return "Available"
                val days  = diff / 86400
                val hours = (diff % 86400) / 3600
                val mins  = (diff % 3600) / 60
                when {
                    days > 0  -> "${days}d ${hours}h"
                    hours > 0 -> "${hours}h ${mins}m"
                    else      -> "${mins}m"
                }
            } catch (e: Exception) {
                "Available"
            }
        }
    }
}
