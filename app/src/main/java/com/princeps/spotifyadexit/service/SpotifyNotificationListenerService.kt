package com.princeps.spotifyadexit.service

import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.princeps.spotifyadexit.core.LogRepository
import com.princeps.spotifyadexit.core.Prefs
import com.princeps.spotifyadexit.core.SpotifySessionWatcher

/**
 * This service's only real purpose is to be an enabled NotificationListener
 * so the app is allowed to call MediaSessionManager.getActiveSessions() and
 * read Spotify's MediaMetadata/PlaybackState - that access is what makes ad
 * detection possible without touching Spotify itself.
 *
 * It also acts as a restart hook: the system reconnects notification
 * listeners fairly aggressively after the process is killed, so
 * onListenerConnected() is used here to make sure the foreground monitor
 * service is (re)started if the user had left monitoring on.
 */
class SpotifyNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogRepository.info("Notification access connected")
        val prefs = Prefs(this)
        if (prefs.monitoringEnabled) {
            ensureMonitoringServiceRunning()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogRepository.warn("Notification access disconnected - requesting rebind")
        NotificationListenerService.requestRebind(
            ComponentName(this, SpotifyNotificationListenerService::class.java)
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // MediaSession callbacks are the source of truth for detection; this
        // is only a diagnostic breadcrumb to help correlate log lines with
        // what Spotify is doing.
        if (sbn?.packageName == SpotifySessionWatcher.SPOTIFY_PACKAGE) {
            LogRepository.info("Spotify notification posted (id=${sbn.id})")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == SpotifySessionWatcher.SPOTIFY_PACKAGE) {
            LogRepository.info("Spotify notification removed (id=${sbn.id})")
        }
    }

    private fun ensureMonitoringServiceRunning() {
        val intent = Intent(this, MonitorForegroundService::class.java)
        startForegroundService(intent)
    }
}
