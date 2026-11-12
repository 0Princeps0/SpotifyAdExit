package com.princeps.spotifyadexit

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Invisible helper activity. Its only job is to turn the screen on and show
 * above the keyguard for a couple of seconds so SpotifyAdSkipAccessibilityService
 * has an active display to drive Recents on when an ad starts while the
 * phone is locked/asleep.
 *
 * Note: on a device with a secure lock (PIN/pattern/biometric), Android
 * will still show the actual lock screen for anything sensitive - this
 * activity cannot and does not attempt to bypass that. In that situation
 * Recents may show hidden/blurred content until the user unlocks, which is
 * a genuine platform limitation, not something this app can work around.
 */
class LockScreenWakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, 2500)
    }
}
