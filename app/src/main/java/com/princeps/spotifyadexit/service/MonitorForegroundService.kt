package com.princeps.spotifyadexit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.princeps.spotifyadexit.MainActivity
import com.princeps.spotifyadexit.R
import com.princeps.spotifyadexit.core.LogRepository
import com.princeps.spotifyadexit.core.Prefs
import com.princeps.spotifyadexit.core.SpotifySessionWatcher
import com.princeps.spotifyadexit.model.SpotifyStatus

/**
 * Keeps a SpotifySessionWatcher alive as a foreground service so detection
 * keeps running while the user is in another app or the screen is
 * locked/asleep. This service does not itself play or touch any media - it
 * only observes Spotify's own MediaSession.
 */
class MonitorForegroundService : Service() {

    private val binder = LocalBinder()
    private lateinit var prefs: Prefs
    private var watcher: SpotifySessionWatcher? = null
    private var watcherStarted = false

    var currentStatus: SpotifyStatus = SpotifyStatus.NOT_RUNNING
        private set
    var currentTitle: String? = null
        private set
    var currentArtist: String? = null
        private set

    private val statusListeners = mutableListOf<(SpotifyStatus, String?, String?) -> Unit>()

    inner class LocalBinder : Binder() {
        fun getService(): MonitorForegroundService = this@MonitorForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()
        watcher = SpotifySessionWatcher(this, prefs) { status, title, artist ->
            currentStatus = status
            currentTitle = title
            currentArtist = artist
            updateNotification(status, title)
            statusListeners.toList().forEach { it(status, title, artist) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground(...) is safe to call again (e.g. this service being
        // (re)started by MainActivity, BootReceiver, and the notification
        // listener's self-heal check) - it just refreshes the notification.
        startForegroundCompat(buildNotification(currentStatus, currentTitle))
        // Registering the media-session listener twice would double-fire
        // every callback, so that part only ever runs once per service
        // instance.
        if (!watcherStarted) {
            watcher?.start()
            watcherStarted = true
            LogRepository.info("Monitoring service started")
        }
        prefs.monitoringEnabled = true
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.stop()
        LogRepository.warn("Monitoring service stopped")
        super.onDestroy()
    }

    fun stopMonitoring() {
        prefs.monitoringEnabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun forceSkipForTesting() {
        watcher?.forceSkipForTesting()
    }

    fun markCurrentTrackAsAd() {
        watcher?.markCurrentTrackAsAd()
    }

    fun addStatusListener(listener: (SpotifyStatus, String?, String?) -> Unit) {
        statusListeners.add(listener)
        listener(currentStatus, currentTitle, currentArtist)
    }

    fun removeStatusListener(listener: (SpotifyStatus, String?, String?) -> Unit) {
        statusListeners.remove(listener)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notif_channel_desc)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: SpotifyStatus, title: String?): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val headline = when (status) {
            SpotifyStatus.AD_PLAYING, SpotifyStatus.SKIPPING -> getString(R.string.notif_title_ad)
            SpotifyStatus.PLAYING -> getString(R.string.notif_title_playing)
            else -> getString(R.string.notif_title_idle)
        }
        val detail = if (status == SpotifyStatus.PLAYING && !title.isNullOrBlank()) {
            title
        } else {
            status.displayName
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(headline)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateNotification(status: SpotifyStatus, title: String?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_ID, buildNotification(status, title))
    }

    companion object {
        private const val CHANNEL_ID = "spotify_ad_exit_monitor"
        private const val NOTIF_ID = 42
    }
}
