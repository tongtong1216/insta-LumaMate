package com.example.insta_auto_adjust.camera.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Small, persistent diagnostic trail for camera connection and preview failures.
 *
 * Files live in the app-specific external directory, so they can be recovered after a USB
 * reconnect without requesting storage permission. Wi-Fi credentials and raw SDK objects must
 * never be passed to this logger.
 */
internal class CameraDiagnosticLogger(context: Context) {
    private val appContext = context.applicationContext
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-diagnostic-writer").apply { isDaemon = true }
    }

    fun info(event: String, detail: String? = null) = record("INFO", event, detail)

    fun warn(event: String, detail: String? = null) = record("WARN", event, detail)

    fun error(event: String, throwable: Throwable? = null, detail: String? = null) {
        val exceptionDetail = throwable?.let {
            buildString {
                append(it.javaClass.simpleName)
                it.message?.let { message -> append(": ").append(message) }
                append('\n')
                append(Log.getStackTraceString(it))
            }
        }
        record("ERROR", event, listOfNotNull(detail, exceptionDetail).joinToString("\n"))
    }

    private fun record(level: String, event: String, detail: String? = null) {
        val line = buildString {
            append(timestamp())
            append(' ')
            append(level)
            append(" [")
            append(event)
            append(']')
            if (!detail.isNullOrBlank()) {
                append(' ')
                append(detail)
            }
            append('\n')
        }
        Log.println(
            when (level) {
                "ERROR" -> Log.ERROR
                "WARN" -> Log.WARN
                else -> Log.INFO
            },
            LOG_TAG,
            line.trimEnd(),
        )
        writer.execute {
            runCatching {
                val directory = File(appContext.getExternalFilesDir("logs") ?: appContext.filesDir, LOG_DIRECTORY)
                if (!directory.exists()) directory.mkdirs()
                val logFile = File(directory, LOG_FILE_NAME)
                rotateIfNeeded(directory, logFile)
                logFile.appendText(line, Charsets.UTF_8)
            }.onFailure { Log.w(LOG_TAG, "Unable to persist camera diagnostic log", it) }
        }
    }

    private fun rotateIfNeeded(directory: File, activeFile: File) {
        if (!activeFile.exists() || activeFile.length() < MAX_FILE_BYTES) return
        File(directory, "$LOG_FILE_NAME.2").delete()
        File(directory, "$LOG_FILE_NAME.1").renameTo(File(directory, "$LOG_FILE_NAME.2"))
        activeFile.renameTo(File(directory, "$LOG_FILE_NAME.1"))
    }

    @Synchronized
    private fun timestamp(): String = timestampFormat.format(Date())

    private companion object {
        const val LOG_TAG = "InstaAutoCamera"
        const val LOG_DIRECTORY = "camera"
        const val LOG_FILE_NAME = "camera-runtime.log"
        const val MAX_FILE_BYTES = 512 * 1024L
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
