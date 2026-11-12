package com.princeps.spotifyadexit.core

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.princeps.spotifyadexit.model.SpotifyStatus
import com.princeps.spotifyadexit.service.SpotifyNotificationListenerService

/**
 * Watches Spotify's MediaSession (and only Spotify's - every other active
 * session is ignored) via the platform MediaSessionManager, runs each
 * metadata/playback-state update through [AdDetector], and asks
 * [AdSkipController] to act when an ad is detected.
 *
 * Reading another app's MediaSession this way is a supported, documented
 * use of MediaSessionManager.getActiveSessions() once the calling app is an
 * enabled notification listener - Spotify's process, code and data are
 * never touched.
 */
class SpotifySessionWatcher(
    private val context: Context,
    private val prefs: Prefs,
    private val onStatusChanged: (status: SpotifyStatus, title: String?, artist: String?) -> Unit
) {

    private val mediaSessionManager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    private val listenerComponent =
        ComponentName(context, SpotifyNotificationListenerService::class.java)

    private var currentController: MediaController? = null
    private var lastKnownIsAd = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            evaluate()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            evaluate()
        }

        override fun onSessionDestroyed() {
            LogRepository.warn("Spotify's media session ended (closed or restarting)")
            currentController = null
            lastKnownIsAd = false
            onStatusChanged(SpotifyStatus.NOT_RUNNING, null, null)
            // Spotify commonly recreates its session moments later
            // (including right after our own skip sequence relaunches it),
            // so immediately look for a replacement.
            refreshActiveSessions()
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            LogRepository.info("Active media sessions changed (${controllers?.size ?: 0} total)")
            attachToSpotifyController(controllers)
        }

    fun start() {
        val manager = mediaSessionManager
        if (manager == null) {
            LogRepository.error("MediaSessionManager unavailable on this device")
            return
        }
        try {
            manager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent)
            LogRepository.info("Subscribed to active-media-session changes")
        } catch (e: SecurityException) {
            LogRepository.error(
                "Notification access not granted - can't read Spotify's media session. " +
                    "Grant it from Permissions."
            )
        }
        refreshActiveSessions()
    }

    fun stop() {
        mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    fun refreshActiveSessions() {
        val manager = mediaSessionManager ?: return
        try {
            attachToSpotifyController(manager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            LogRepository.error(
                "Notification access not granted - can't read Spotify's media session."
            )
        }
    }

    /** Lets the UI's manual test button exercise the skip sequence directly. */
    fun forceSkipForTesting() {
        AdSkipController.requestSkip(prefs, "manual test trigger from UI")
    }

    /**
     * Lets the user teach the detector directly from the real device: the
     * fingerprint of whatever Spotify is playing right now is remembered as
     * a confirmed ad, so the "learned pattern" signal in AdDetector will
     * flag it (and only it) next time. This is the mechanism for correcting
     * the built-in heuristics without this app assuming a fixed ad format.
     */
    fun markCurrentTrackAsAd() {
        val fingerprint = lastFingerprint
        if (fingerprint.isBlank()) {
            LogRepository.warn("Nothing playing right now to mark as an ad")
            return
        }
        prefs.addLearnedFingerprint(fingerprint)
        LogRepository.event("Marked current track metadata as a confirmed ad pattern")
    }

    private var lastFingerprint: String = ""

    private fun attachToSpotifyController(controllers: List<MediaController>?) {
        val spotifyController = controllers?.firstOrNull { it.packageName == SPOTIFY_PACKAGE }

        if (spotifyController == null) {
            if (currentController != null) {
                currentController?.unregisterCallback(controllerCallback)
                currentController = null
                lastKnownIsAd = false
                onStatusChanged(SpotifyStatus.NOT_RUNNING, null, null)
            }
            return
        }

        if (currentController?.sessionToken == spotifyController.sessionToken) {
            return // Already attached to this exact session.
        }

        currentController?.unregisterCallback(controllerCallback)
        currentController = spotifyController
        spotifyController.registerCallback(controllerCallback)
        LogRepository.info("Attached to Spotify's media session")
        evaluate()
    }

    private fun evaluate() {
        val controller = currentController ?: return
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val result = AdDetector.evaluate(metadata, playbackState, prefs)
        lastFingerprint = result.fingerprint
        LogRepository.info("Metadata snapshot:\n${result.rawMetadataDump}")

        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()

        when {
            result.isAd -> {
                if (!lastKnownIsAd) {
                    LogRepository.event(
                        "Ad signals fired (score=${result.score}): ${result.reasons.joinToString("; ")}"
                    )
                }
                lastKnownIsAd = true
                onStatusChanged(SpotifyStatus.AD_PLAYING, title, artist)
                AdSkipController.requestSkip(prefs, result.reasons.joinToString("; "))
            }

            playbackState?.state == PlaybackState.STATE_PLAYING -> {
                lastKnownIsAd = false
                onStatusChanged(SpotifyStatus.PLAYING, title, artist)
            }

            playbackState?.state == PlaybackState.STATE_PAUSED -> {
                lastKnownIsAd = false
                onStatusChanged(SpotifyStatus.PAUSED, title, artist)
            }

            else -> {
                lastKnownIsAd = false
                onStatusChanged(SpotifyStatus.SESSION_IDLE, title, artist)
            }
        }
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
