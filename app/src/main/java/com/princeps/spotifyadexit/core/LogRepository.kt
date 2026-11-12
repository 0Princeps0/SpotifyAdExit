package com.princeps.spotifyadexit.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.princeps.spotifyadexit.model.LogEntry
import com.princeps.spotifyadexit.model.LogLevel

/**
 * Process-wide, in-memory log of everything the detector/skip-controller do.
 * Deliberately dependency-free (no LiveData/Flow) - just a synchronized list
 * plus a tiny listener list, dispatched on the main thread so the UI can
 * bind to it safely.
 */
object LogRepository {

    private const val TAG = "SpotifyAdExit"
    private const val MAX_ENTRIES = 500

    private val entries = ArrayList<LogEntry>()
    private val listeners = ArrayList<(List<LogEntry>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    private fun push(level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        Log.d(TAG, "[${level.name}] $message")
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = synchronized(this) { entries.toList() }
        val currentListeners = synchronized(this) { listeners.toList() }
        mainHandler.post {
            currentListeners.forEach { it(snapshot) }
        }
    }

    fun info(message: String) = push(LogLevel.INFO, message)
    fun event(message: String) = push(LogLevel.EVENT, message)
    fun warn(message: String) = push(LogLevel.WARN, message)
    fun error(message: String) = push(LogLevel.ERROR, message)

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        notifyListeners()
    }

    @Synchronized
    fun addListener(listener: (List<LogEntry>) -> Unit) {
        listeners.add(listener)
        // Immediately hand the current snapshot to the new listener.
        val snapshot = entries.toList()
        mainHandler.post { listener(snapshot) }
    }

    @Synchronized
    fun removeListener(listener: (List<LogEntry>) -> Unit) {
        listeners.remove(listener)
    }
}
