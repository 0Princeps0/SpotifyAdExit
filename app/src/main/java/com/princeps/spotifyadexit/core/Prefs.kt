package com.princeps.spotifyadexit.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences. Kept deliberately simple (no
 * DataStore/Room) since the app has almost no persistent state.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var monitoringEnabled: Boolean
        get() = sp.getBoolean(KEY_MONITORING_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    /** Minimum seconds between two skip attempts, to avoid duplicate triggers. */
    var cooldownSeconds: Int
        get() = sp.getInt(KEY_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS)
        set(value) = sp.edit().putInt(KEY_COOLDOWN_SECONDS, value).apply()

    // --- Ad-detection heuristic toggles -------------------------------
    // All default to "on"; the user can disable any signal that turns out
    // to be unreliable on their account/region once they've read the log.

    var useAdMetadataFlagSignal: Boolean
        get() = sp.getBoolean(KEY_SIGNAL_METADATA_FLAG, true)
        set(value) = sp.edit().putBoolean(KEY_SIGNAL_METADATA_FLAG, value).apply()

    var useMissingSkipActionsSignal: Boolean
        get() = sp.getBoolean(KEY_SIGNAL_MISSING_SKIP, true)
        set(value) = sp.edit().putBoolean(KEY_SIGNAL_MISSING_SKIP, value).apply()

    var useEmptyMetadataSignal: Boolean
        get() = sp.getBoolean(KEY_SIGNAL_EMPTY_METADATA, true)
        set(value) = sp.edit().putBoolean(KEY_SIGNAL_EMPTY_METADATA, value).apply()

    var useLearnedPatternsSignal: Boolean
        get() = sp.getBoolean(KEY_SIGNAL_LEARNED, true)
        set(value) = sp.edit().putBoolean(KEY_SIGNAL_LEARNED, value).apply()

    /** Fingerprints of metadata the user has manually confirmed were ads. */
    var learnedAdFingerprints: Set<String>
        get() = sp.getStringSet(KEY_LEARNED_FINGERPRINTS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_LEARNED_FINGERPRINTS, value).apply()

    fun addLearnedFingerprint(fingerprint: String) {
        val updated = learnedAdFingerprints.toMutableSet()
        updated.add(fingerprint)
        learnedAdFingerprints = updated
    }

    companion object {
        private const val FILE_NAME = "spotify_ad_exit_prefs"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_COOLDOWN_SECONDS = "cooldown_seconds"
        private const val KEY_SIGNAL_METADATA_FLAG = "signal_metadata_flag"
        private const val KEY_SIGNAL_MISSING_SKIP = "signal_missing_skip"
        private const val KEY_SIGNAL_EMPTY_METADATA = "signal_empty_metadata"
        private const val KEY_SIGNAL_LEARNED = "signal_learned"
        private const val KEY_LEARNED_FINGERPRINTS = "learned_fingerprints"
        const val DEFAULT_COOLDOWN_SECONDS = 12
    }
}
