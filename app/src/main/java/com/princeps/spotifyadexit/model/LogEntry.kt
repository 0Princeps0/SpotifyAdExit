package com.princeps.spotifyadexit.model

import java.text.SimpleDateFormat
import java.util.Locale

enum class LogLevel { INFO, EVENT, WARN, ERROR }

/**
 * One line in the on-screen detection log.
 */
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String
) {
    fun formatted(): String {
        val time = TIME_FORMAT.format(timestampMillis)
        val tag = when (level) {
            LogLevel.INFO -> "INFO "
            LogLevel.EVENT -> "EVENT"
            LogLevel.WARN -> "WARN "
            LogLevel.ERROR -> "ERROR"
        }
        return "$time  $tag  $message"
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
