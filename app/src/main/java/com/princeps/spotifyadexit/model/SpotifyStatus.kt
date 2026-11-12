package com.princeps.spotifyadexit.model

/**
 * High-level state of Spotify as observed through its MediaSession.
 * This reflects only what the platform's MediaSession/PlaybackState APIs
 * expose - it is not derived from anything private to Spotify.
 */
enum class SpotifyStatus(val displayName: String) {
    NOT_RUNNING("Not running"),
    SESSION_IDLE("Session found, idle"),
    PLAYING("Playing"),
    PAUSED("Paused"),
    AD_PLAYING("Ad playing"),
    SKIPPING("Skipping ad…")
}
