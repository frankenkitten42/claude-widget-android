package com.frankenkitten42.claudewidget.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.frankenkitten42.claudewidget.R
import com.frankenkitten42.claudewidget.data.FileReadResult
import com.frankenkitten42.claudewidget.data.UsageFileReader
import com.frankenkitten42.claudewidget.worker.UsageFetchWorker
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var setupText: TextView
    private lateinit var debugText: TextView
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText    = findViewById(R.id.tv_status)
        setupText     = findViewById(R.id.tv_setup)
        debugText     = findViewById(R.id.tv_debug)
        refreshButton = findViewById(R.id.btn_refresh)

        // Request storage permission on older Android versions
        requestStorageIfNeeded()

        // Schedule the periodic worker
        UsageFetchWorker.schedule(this)

        refreshButton.setOnClickListener {
            WorkManager.getInstance(this)
                .enqueue(OneTimeWorkRequestBuilder<UsageFetchWorker>().build())
            updateDisplay()
        }

        updateDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateDisplay()
    }

    private fun updateDisplay() {
        val reader = UsageFileReader()
        val diag = StringBuilder()

        val dir = UsageFileReader.DATA_DIR
        val jsonFile = File(dir, "latest.json")
        val statusFile = File(dir, "status")

        diag.appendLine("File: ${jsonFile.absolutePath}")
        diag.appendLine("Exists: ${jsonFile.exists()}")
        if (jsonFile.exists()) {
            diag.appendLine("Size: ${jsonFile.length()} bytes")
            diag.appendLine("Modified: ${formatEpoch(jsonFile.lastModified() / 1000)}")
        }
        if (statusFile.exists()) {
            diag.appendLine("Status: ${statusFile.readText().trim()}")
        }
        diag.appendLine()

        when (val result = reader.readUsage()) {
            is FileReadResult.Success -> {
                val d = result.data
                statusText.text = "Data is flowing"
                setupText.visibility = View.GONE
                diag.appendLine("5hr:  ${d.fiveHour.utilization}%")
                diag.appendLine("7day: ${d.sevenDay.utilization}%")
                diag.appendLine("Fetched: ${formatEpoch(d.fetchedAtEpoch)}")
            }
            is FileReadResult.FileNotFound -> {
                statusText.text = "No data file found"
                setupText.visibility = View.VISIBLE
            }
            is FileReadResult.ParseError -> {
                statusText.text = "Error reading data"
                setupText.visibility = View.GONE
                diag.appendLine("Parse error: ${result.message}")
                if (jsonFile.exists()) {
                    diag.appendLine()
                    diag.appendLine("Raw JSON:")
                    diag.appendLine(jsonFile.readText().take(500))
                }
            }
        }

        debugText.text = diag.toString()
    }

    private fun formatEpoch(epoch: Long): String {
        return try {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm:ss a")
                .withZone(ZoneId.systemDefault())
            fmt.format(Instant.ofEpochSecond(epoch))
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun requestStorageIfNeeded() {
        // On API 29 and below, we need READ_EXTERNAL_STORAGE to read Documents/
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    100
                )
            }
        }
        // On API 30+, MANAGE_EXTERNAL_STORAGE may be needed for Documents/ access
        // But first try without it — many devices allow reading Documents/ without special permission
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            updateDisplay()
        }
    }
}
