package com.princeps.spotifyadexit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.princeps.spotifyadexit.LockScreenWakeActivity
import com.princeps.spotifyadexit.core.AdSkipController
import com.princeps.spotifyadexit.core.LogRepository
import com.princeps.spotifyadexit.core.SpotifySessionWatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduces the manual "swipe Spotify away from Recents, then reopen it"
 * action using only standard AccessibilityService APIs:
 *  - performGlobalAction(GLOBAL_ACTION_RECENTS) to open the Recents/Overview
 *    screen, exactly as the Recents button/gesture would,
 *  - a dispatched swipe gesture on Spotify's own task card to dismiss it -
 *    this *is* "remove from recents", not a force-stop and not merely
 *    backgrounding the app,
 *  - then a normal launch Intent to reopen Spotify.
 *
 * This never touches Spotify's process, code, or files - it only operates
 * the System UI the same way the user's finger would.
 *
 * Third-party apps have no public API to force-stop another app or to
 * remove another app's task from Recents directly (those require system
 * signature permissions the OS reserves for itself); driving the Recents UI
 * via AccessibilityService is the closest supported mechanism to the manual
 * action described.
 */
class SpotifyAdSkipAccessibilityService : AccessibilityService() {

    private lateinit var workerThread: HandlerThread
    private lateinit var workHandler: Handler
    private lateinit var mainHandler: Handler

    override fun onServiceConnected() {
        super.onServiceConnected()
        workerThread = HandlerThread("SpotifyAdSkipWorker").also { it.start() }
        workHandler = Handler(workerThread.looper)
        mainHandler = Handler(Looper.getMainLooper())
        AdSkipController.attachAccessibilityService(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AdSkipController.detachAccessibilityService(this)
        if (::workerThread.isInitialized) {
            workerThread.quitSafely()
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The skip sequence is triggered on demand (see performAdSkipSequence)
        // rather than reacting to every accessibility event, so there is
        // nothing to do here.
    }

    override fun onInterrupt() {
        LogRepository.warn("Accessibility service was interrupted by the system")
    }

    /**
     * Entry point called by [AdSkipController]. Runs on a dedicated
     * background thread since the sequence needs short waits between
     * system-UI transitions.
     */
    fun performAdSkipSequence(onComplete: (success: Boolean, detail: String) -> Unit) {
        workHandler.post {
            val (success, detail) = runSkipSequence()
            mainHandler.post { onComplete(success, detail) }
        }
    }

    private fun runSkipSequence(): Pair<Boolean, String> {
        return try {
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager != null && !powerManager.isInteractive) {
                LogRepository.info("Screen is off/locked - waking it before opening Recents")
                wakeScreen()
                sleepQuiet(700)
            }

            if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) {
                return false to "the system rejected performGlobalAction(RECENTS)"
            }
            sleepQuiet(500)

            val spotifyCard = findSpotifyRecentsNode()
                ?: run {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return false to "couldn't find a Spotify card in Recents (different " +
                        "launcher layout, secure lock screen hiding content, or Spotify " +
                        "wasn't actually in Recents)"
                }

            val bounds = Rect()
            spotifyCard.getBoundsInScreen(bounds)

            if (!dispatchSwipeUp(bounds)) {
                return false to "the Recents-dismiss swipe gesture was rejected by the system"
            }
            sleepQuiet(400)

            performGlobalAction(GLOBAL_ACTION_HOME)
            sleepQuiet(200)

            if (relaunchSpotify()) {
                true to "removed Spotify from Recents and reopened it"
            } else {
                false to "Spotify's launch intent is unavailable (not installed?)"
            }
        } catch (t: Throwable) {
            false to "unexpected error: ${t.message}"
        }
    }

    private fun wakeScreen() {
        val intent = Intent(this, LockScreenWakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    /** Searches every currently interactive window, retrying once. */
    private fun findSpotifyRecentsNode(): AccessibilityNodeInfo? {
        repeat(2) { attempt ->
            val roots = buildList {
                rootInActiveWindow?.let { add(it) }
                windows?.forEach { window -> window.root?.let { add(it) } }
            }
            for (root in roots) {
                val match = searchForSpotify(root)
                if (match != null) return match
            }
            if (attempt == 0) sleepQuiet(350)
        }
        return null
    }

    private fun searchForSpotify(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if ((text != null && text.contains("Spotify", ignoreCase = true)) ||
            (desc != null && desc.contains("Spotify", ignoreCase = true))
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchForSpotify(child)
            if (found != null) return found
        }
        return null
    }

    private fun dispatchSwipeUp(bounds: Rect): Boolean {
        val startX = bounds.centerX().toFloat()
        val startY = bounds.centerY().toFloat()
        val endY = (bounds.top - bounds.height()).toFloat().coerceAtLeast(0f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 140L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val latch = CountDownLatch(1)
        var completed = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed = false
                latch.countDown()
            }
        }

        // Deliver the callback on the main looper (not workHandler) since
        // this method itself blocks on workHandler's thread below.
        val accepted = dispatchGesture(gesture, callback, mainHandler)
        if (!accepted) return false
        latch.await(2, TimeUnit.SECONDS)
        return completed
    }

    private fun relaunchSpotify(): Boolean {
        val launchIntent = packageManager
            .getLaunchIntentForPackage(SpotifySessionWatcher.SPOTIFY_PACKAGE)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return true
    }

    private fun sleepQuiet(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
