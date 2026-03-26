package com.frankenkitten42.claudewidget.data

import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

data class UsageWindow(
    val utilization: Double,
    val resetsAt: String
)

data class UsageData(
    val fiveHour: UsageWindow,
    val sevenDay: UsageWindow,
    val fetchedAtEpoch: Long
)

sealed class FileReadResult {
    data class Success(val data: UsageData) : FileReadResult()
    data class FileNotFound(val path: String) : FileReadResult()
    data class ParseError(val message: String) : FileReadResult()
}

/**
 * Reads Claude usage data from the JSON file that the Termux bash script
 * deposits in shared storage at Documents/claude-usage/latest.json.
 */
class UsageFileReader {

    fun readUsage(): FileReadResult {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "claude-usage"
        )
        val jsonFile = File(dir, "latest.json")
        val fetchFile = File(dir, "last_fetch")

        if (!jsonFile.exists()) {
            Log.d(TAG, "readUsage: file not found at ${jsonFile.absolutePath}")
            return FileReadResult.FileNotFound(jsonFile.absolutePath)
        }

        return try {
            val raw = jsonFile.readText()
            val json = JSONObject(raw)

            val fiveHour = json.getJSONObject("five_hour")
            val sevenDay = json.getJSONObject("seven_day")

            val fetchedAt = if (fetchFile.exists()) {
                fetchFile.readText().trim().toLongOrNull() ?: (jsonFile.lastModified() / 1000)
            } else {
                jsonFile.lastModified() / 1000
            }

            Log.d(TAG, "readUsage: 5hr=${fiveHour.getDouble("utilization")}% 7d=${sevenDay.getDouble("utilization")}% fetched=$fetchedAt")

            FileReadResult.Success(
                UsageData(
                    fiveHour = UsageWindow(
                        utilization = fiveHour.getDouble("utilization"),
                        resetsAt = fiveHour.getString("resets_at")
                    ),
                    sevenDay = UsageWindow(
                        utilization = sevenDay.getDouble("utilization"),
                        resetsAt = sevenDay.getString("resets_at")
                    ),
                    fetchedAtEpoch = fetchedAt
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "readUsage: parse error", e)
            FileReadResult.ParseError(e.message ?: "Unknown parse error")
        }
    }

    companion object {
        private const val TAG = "UsageFileReader"
        val DATA_DIR = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "claude-usage"
        )
    }
}
