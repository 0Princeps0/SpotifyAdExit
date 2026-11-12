package com.princeps.spotifyadexit.core

import android.media.MediaMetadata
import android.media.session.PlaybackState

/**
 * Result of evaluating one metadata/playback-state snapshot from Spotify's
 * MediaSession.
 *
 * This app does not assume a fixed Spotify ad-metadata format. Instead it
 * inspects whatever MediaMetadata/PlaybackState Spotify's own MediaSession
 * actually publishes, computes a handful of independent signals from that
 * real data, and combines them. [reasons] and [rawMetadataDump] are always
 * populated so the log can be used to sanity-check (and, via the learned
 * signal, correct) the heuristic on the real device.
 */
data class AdDetectionResult(
    val isAd: Boolean,
    val score: Int,
    val reasons: List<String>,
    val rawMetadataDump: String,
    val fingerprint: String
)

private data class Signal(
    val name: String,
    val enabled: Boolean,
    val fired: Boolean,
    val weight: Int
)

object AdDetector {

    /** Combined signal weight required before we call it an ad. */
    private const val AD_THRESHOLD = 2

    fun evaluate(
        metadata: MediaMetadata?,
        playbackState: PlaybackState?,
        prefs: Prefs
    ): AdDetectionResult {
        if (metadata == null) {
            return AdDetectionResult(
                isAd = false,
                score = 0,
                reasons = listOf("no metadata available yet"),
                rawMetadataDump = "(none)",
                fingerprint = ""
            )
        }

        val title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
        val artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
        val album = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString()
        val duration = if (metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION))
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) else -1L
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val fingerprint = fingerprintOf(title, artist, album)
        val dump = dumpMetadata(metadata, playbackState)

        val signals = mutableListOf<Signal>()

        // Signal 1: an explicit ad flag somewhere in the metadata's own
        // key set. Spotify (like several other media apps) has been
        // observed exposing an ad indicator as a custom MediaMetadata key
        // rather than through the standard fields - we don't hardcode one
        // specific key name, we scan every key actually present for
        // anything that looks like an ad flag.
        val adFlagKey = metadata.keySet().firstOrNull { key ->
            key.contains("advert", ignoreCase = true) || key.contains("is_ad", ignoreCase = true)
        }
        val adFlagFired = adFlagKey != null && run {
            val text = metadata.getText(adFlagKey)?.toString()?.lowercase()
            val long = metadata.getLong(adFlagKey)
            text == "true" || text == "1" || text == "yes" || long == 1L
        }
        signals += Signal(
            name = "explicit ad-flag key in metadata" + (adFlagKey?.let { " ($it)" } ?: ""),
            enabled = prefs.useAdMetadataFlagSignal,
            fired = adFlagFired,
            weight = 3
        )

        // Signal 2: standard skip actions are withheld while playing.
        // Spotify (and most media apps) removes ACTION_SKIP_TO_NEXT /
        // ACTION_SKIP_TO_PREVIOUS from PlaybackState while an ad is
        // playing, since ads cannot be skipped. On its own this is weak
        // (e.g. end of queue looks the same), so it only carries partial
        // weight.
        val actions = playbackState?.actions ?: 0L
        val missingSkip = isPlaying &&
            (actions and PlaybackState.ACTION_SKIP_TO_NEXT) == 0L &&
            (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) == 0L
        signals += Signal(
            name = "skip-to-next/previous withheld while playing",
            enabled = prefs.useMissingSkipActionsSignal,
            fired = missingSkip,
            weight = 1
        )

        // Signal 3: title/artist are both blank while actively playing.
        // Ad slots are frequently reported with empty/generic track info.
        // Also weak alone (a mistagged real track could match).
        val emptyMeta = isPlaying && title.isNullOrBlank() && artist.isNullOrBlank()
        signals += Signal(
            name = "empty title+artist while playing",
            enabled = prefs.useEmptyMetadataSignal,
            fired = emptyMeta,
            weight = 1
        )

        // Signal 4: this exact (title, artist, album) combination was
        // previously confirmed as an ad via the "mark as ad" action.
        val learned = fingerprint.isNotBlank() && prefs.learnedAdFingerprints.contains(fingerprint)
        signals += Signal(
            name = "matches a previously confirmed ad fingerprint",
            enabled = prefs.useLearnedPatternsSignal,
            fired = learned,
            weight = 3
        )

        val score = signals.filter { it.enabled && it.fired }.sumOf { it.weight }
        val firedDescriptions = signals.filter { it.fired }.map {
            "${it.name}${if (!it.enabled) " [signal disabled]" else ""}"
        }
        val reasons = if (firedDescriptions.isEmpty()) {
            listOf("no ad signals matched")
        } else {
            firedDescriptions
        }

        return AdDetectionResult(
            isAd = score >= AD_THRESHOLD,
            score = score,
            reasons = reasons,
            rawMetadataDump = dump + "\nduration=$duration isPlaying=$isPlaying",
            fingerprint = fingerprint
        )
    }

    private fun fingerprintOf(title: String?, artist: String?, album: String?): String {
        val t = title.orEmpty().trim().lowercase()
        val a = artist.orEmpty().trim().lowercase()
        val al = album.orEmpty().trim().lowercase()
        if (t.isEmpty() && a.isEmpty() && al.isEmpty()) return ""
        return "$t|$a|$al"
    }

    /**
     * Dumps every key Spotify's MediaMetadata actually carries, so the
     * heuristics above (and the user, via the log) can be checked against
     * real data instead of an assumed format.
     */
    private fun dumpMetadata(metadata: MediaMetadata, playbackState: PlaybackState?): String {
        val sb = StringBuilder()
        val artKeys = setOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )
        for (key in metadata.keySet().sorted()) {
            if (key in artKeys) {
                val hasArt = metadata.getBitmap(key) != null
                sb.append("$key=<art present=$hasArt>\n")
                continue
            }
            val text = metadata.getText(key)
            if (text != null) {
                sb.append("$key=$text\n")
            } else {
                sb.append("$key=${metadata.getLong(key)} (numeric)\n")
            }
        }
        if (playbackState != null) {
            sb.append("playbackState.state=${playbackState.state} ")
            sb.append("actions=${playbackState.actions.toString(2)}\n")
        }
        return sb.toString().trim()
    }
}
