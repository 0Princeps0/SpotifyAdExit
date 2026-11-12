# SpotifyAdExit

Automatically reproduces the manual "swipe Spotify away from Recents, then
reopen it" trick whenever a Spotify ad starts, so the ad disappears and
playback resumes.

## How it works

Four components, all built on plain Android platform APIs (no AndroidX, no
third-party libraries, nothing that touches Spotify's own process/files):

1. **`SpotifyNotificationListenerService`** — a `NotificationListenerService`.
   Once you grant it Notification Access, the app is allowed to call
   `MediaSessionManager.getActiveSessions()`, which is what makes it possible
   to read Spotify's own `MediaMetadata`/`PlaybackState` at all.
2. **`SpotifySessionWatcher`** — finds Spotify's `MediaController` among the
   active sessions and registers a callback on it. Every metadata/playback
   change runs through `AdDetector`, which does **not** assume a fixed ad
   format. It scores several independent, real signals read directly off
   Spotify's own session data (see "Calibrating detection" below), and logs
   the *entire* raw metadata dump every time, so you can see exactly what
   Spotify is actually sending on your account/region.
3. **`SpotifyAdSkipAccessibilityService`** — the only component that touches
   system UI. Android gives third-party apps **no** API to force-stop
   another app or pull its task out of Recents directly (those need
   system-signature permissions the OS reserves for itself). The closest
   supported mechanism to your manual action is driving the real Recents UI
   with an `AccessibilityService`: open Recents
   (`performGlobalAction(GLOBAL_ACTION_RECENTS)`), find Spotify's card in
   the accessibility node tree, dispatch the same swipe-up gesture your
   finger would make, then relaunch Spotify with a normal launch `Intent`.
   That *is* "remove from recents" — it's a different operation from "stop"
   or "force-stop", and there's no supported API for either of those against
   another app, so this is the mechanism the app actually uses.
4. **`MonitorForegroundService`** — a foreground service (Android 14
   `specialUse` type) that owns the watcher so detection keeps running while
   you're in another app or the screen is off. `BootReceiver` restarts it
   after a reboot if you left monitoring on. `SpotifyNotificationListenerService.onListenerConnected()`
   also re-arms it if the system had to kill and restart the app's process.

`AdSkipController` sits between (2) and (3): it applies a cooldown so one ad
doesn't trigger repeated skip attempts, and safely no-ops (with a log line
explaining why) if the accessibility service isn't enabled yet.

## Permissions this app asks for, and why

| Permission | Why |
|---|---|
| Notification Access (`NotificationListenerService`) | Required by Android before `MediaSessionManager.getActiveSessions()` will return anything. Read-only — this app doesn't do anything with your notifications. |
| Accessibility Service | The only way to open Recents and dismiss Spotify's card programmatically. Only acts on-screen after an ad is detected. |
| `POST_NOTIFICATIONS` | To show the "Monitoring" foreground-service notification (Android 13+). |
| `RECEIVE_BOOT_COMPLETED` | Restart monitoring after a reboot if you left it on. |
| Battery optimization exemption | Optional but recommended — without it, some OEMs (Xiaomi, Huawei, OnePlus, etc.) may kill the monitoring service in the background more aggressively than stock Android does. |

## Building it

You need Android Studio (Koala/2024.1+ recommended) with network access to
Google's Maven repo — the sandbox this project was written in does not have
that access, so the build below has **not** been run end-to-end here. What
*was* verified locally: every `.kt` file compiles cleanly (zero errors) with
`kotlinc` against a hand-written stub of the exact Android APIs the app
calls, and every manifest/layout/resource XML file is well-formed. That
catches typos, wrong signatures, and logic bugs, but it isn't a substitute
for a real Android Gradle Plugin build against the real SDK.

**Android Studio:** File → Open → select the `SpotifyAdExit` folder → let
Gradle sync (first sync downloads Gradle 8.11.1 + AGP + the Android 34
platform) → Run.

**Command line**, from this folder:
```
./gradlew assembleDebug
```
The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`. If
Gradle sync or the build reports an error, paste it back to me and I'll fix
it — I can't run this exact command myself in this environment, so treat
this build as the first real compile of this project.

## First run checklist

1. Install and open the app. Turn **Monitoring** on.
2. Tap **Grant** next to Notification access → find "SpotifyAdExit" →
   enable it.
3. Tap **Grant** next to Accessibility service → find "SpotifyAdExit Ad
   Skipper" → enable it. Android will show a warning about what
   accessibility services can do — that's standard for any accessibility
   service, this one only acts on Spotify's Recents card after an ad is
   detected.
4. Tap **Grant** next to battery optimization and allow it.
5. Play something on Spotify with ads enabled (free tier). Watch the log.

## Calibrating detection

`AdDetector` combines four signals, each toggleable in `Prefs.kt` (all on
by default) and each logged with a reason whenever it fires:

- an explicit ad-flag key found in Spotify's own metadata keyset,
- Spotify withholding skip-to-next/previous while playing,
- empty title+artist while playing,
- a fingerprint you've manually confirmed via **"Mark current track as
  ad"**.

Because ad metadata isn't publicly documented and can differ by Spotify
version/account/region, watch the log during a real ad on your device. If a
signal is firing on real songs (false positive) or not firing on real ads
(false negative), the raw metadata dump in the log tells you exactly what
Spotify sent, and `AdDetector.kt` is a single, short, readable file to
adjust from there. The **"Mark current track as ad"** button exists
specifically so you don't have to guess — it teaches the detector from your
actual account's data instead.

## Known limitations (read before relying on this)

- **Secure lock screens:** if an ad starts while the phone is locked with a
  PIN/pattern/biometric, the app wakes the screen and attempts the sequence,
  but Android will still show the real lock screen for anything sensitive —
  no app can bypass that. Recents may show hidden/blurred cards until you
  unlock. This is a platform limitation, not something fixable in-app.
- **Launcher/OEM variance:** the accessibility node search looks for a card
  whose text/content-description contains "Spotify" inside the current
  Recents window. This works on stock/Pixel-style launchers; heavily
  customized OEM launchers (some Samsung/Xiaomi/etc. skins) may lay out
  Recents differently. If the log says it couldn't find a Spotify card,
  that's what's happening — it's designed to fail loudly rather than swipe
  the wrong thing.
- **Detection heuristics are unverified against a live Spotify ad** because
  this was built without a device actually playing one — see "Calibrating
  detection" above.
- This automates something Spotify's ad-supported tier is designed to show
  you, which likely conflicts with Spotify's Terms of Service even though
  no Spotify code, traffic, or files are touched — that's a matter between
  you and Spotify, not a technical restriction this app enforces.

## Project layout

```
app/src/main/java/com/princeps/spotifyadexit/
  MainActivity.kt                     UI
  LockScreenWakeActivity.kt           wakes the screen when locked/asleep
  core/
    SpotifySessionWatcher.kt          attaches to Spotify's MediaSession
    AdDetector.kt                     the detection heuristics
    AdSkipController.kt               cooldown + coordinates the skip
    Prefs.kt / LogRepository.kt       settings + the on-screen log
  service/
    MonitorForegroundService.kt       keeps everything alive
    SpotifyNotificationListenerService.kt   grants MediaSession access
    SpotifyAdSkipAccessibilityService.kt    drives Recents + relaunch
    BootReceiver.kt                   restart after reboot
  model/                              LogEntry, SpotifyStatus
```
