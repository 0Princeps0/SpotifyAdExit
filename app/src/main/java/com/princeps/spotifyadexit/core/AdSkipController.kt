package com.princeps.spotifyadexit.core

import com.princeps.spotifyadexit.service.SpotifyAdSkipAccessibilityService
import java.lang.ref.WeakReference

/**
 * Single point of coordination between:
 *  - the detector (SpotifySessionWatcher / AdDetector), which decides an ad
 *    is playing, and
 *  - SpotifyAdSkipAccessibilityService, which is the only component able to
 *    actually drive the Recents UI.
 *
 * Also owns duplicate-detection suppression (a cooldown window) so a single
 * ad doesn't trigger the skip sequence multiple times while metadata is
 * still settling.
 */
object AdSkipController {

    private var accessibilityServiceRef: WeakReference<SpotifyAdSkipAccessibilityService>? = null

    @Volatile
    private var skipInProgress = false

    @Volatile
    private var lastSkipAtMillis = 0L

    fun attachAccessibilityService(service: SpotifyAdSkipAccessibilityService) {
        accessibilityServiceRef = WeakReference(service)
        LogRepository.info("Accessibility skip-service connected")
    }

    fun detachAccessibilityService(service: SpotifyAdSkipAccessibilityService) {
        if (accessibilityServiceRef?.get() === service) {
            accessibilityServiceRef = null
        }
        LogRepository.warn("Accessibility skip-service disconnected")
    }

    fun isAccessibilityServiceConnected(): Boolean = accessibilityServiceRef?.get() != null

    @Synchronized
    fun requestSkip(prefs: Prefs, reason: String) {
        val now = System.currentTimeMillis()
        val cooldownMs = prefs.cooldownSeconds * 1000L

        if (skipInProgress) {
            LogRepository.info("Skip already running - ignoring duplicate trigger ($reason)")
            return
        }
        if (now - lastSkipAtMillis < cooldownMs) {
            val remainingSec = (cooldownMs - (now - lastSkipAtMillis)) / 1000
            LogRepository.info("Skip suppressed by cooldown (${remainingSec}s left) - $reason")
            return
        }

        val service = accessibilityServiceRef?.get()
        if (service == null) {
            LogRepository.error(
                "Ad detected ($reason) but the Accessibility Service isn't enabled, " +
                    "so Spotify can't be removed from Recents. Enable it from Permissions."
            )
            return
        }

        skipInProgress = true
        lastSkipAtMillis = now
        LogRepository.event(
            "Ad detected ($reason) - removing Spotify from Recents and reopening it"
        )
        service.performAdSkipSequence { success, detail ->
            skipInProgress = false
            if (success) {
                LogRepository.event("Skip sequence finished: $detail")
            } else {
                LogRepository.error("Skip sequence failed: $detail")
            }
        }
    }
}
