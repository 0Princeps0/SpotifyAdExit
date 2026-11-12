package com.princeps.spotifyadexit

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.princeps.spotifyadexit.core.LogRepository
import com.princeps.spotifyadexit.core.Prefs
import com.princeps.spotifyadexit.databinding.ActivityMainBinding
import com.princeps.spotifyadexit.model.LogEntry
import com.princeps.spotifyadexit.model.LogLevel
import com.princeps.spotifyadexit.model.SpotifyStatus
import com.princeps.spotifyadexit.service.MonitorForegroundService
import com.princeps.spotifyadexit.service.SpotifyAdSkipAccessibilityService
import com.princeps.spotifyadexit.service.SpotifyNotificationListenerService

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var logAdapter: LogAdapter

    private var monitorService: MonitorForegroundService? = null
    private var isBound = false

    private val statusListener: (SpotifyStatus, String?, String?) -> Unit =
        { status, title, artist ->
            binding.textSpotifyStatus.text = status.displayName
            binding.textCurrentTrack.text = when {
                title.isNullOrBlank() -> getString(R.string.none_placeholder)
                !artist.isNullOrBlank() -> "$title — $artist"
                else -> title
            }
        }

    private val logListener: (List<LogEntry>) -> Unit = { entries ->
        // Oldest first, newest last - the ListView is configured to stack
        // from the bottom and auto-scroll, like a terminal/logcat view.
        logAdapter.replaceAll(entries)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MonitorForegroundService.LocalBinder
            monitorService = binder.getService().also { it.addStatusListener(statusListener) }
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitorService?.removeStatusListener(statusListener)
            monitorService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        logAdapter = LogAdapter(this)
        binding.listViewLogs.adapter = logAdapter

        binding.switchMonitoring.isChecked = prefs.monitoringEnabled
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startMonitoring() else stopMonitoring()
        }

        binding.buttonGrantNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.buttonGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.buttonGrantBattery.setOnClickListener { requestBatteryOptimizationExemption() }
        binding.buttonForceSkip.setOnClickListener {
            if (isBound) {
                monitorService?.forceSkipForTesting()
            } else {
                LogRepository.warn("Turn Monitoring ON first so the service is running")
            }
        }
        binding.buttonClearLog.setOnClickListener { LogRepository.clear() }
        binding.buttonMarkAsAd.setOnClickListener {
            if (isBound) {
                monitorService?.markCurrentTrackAsAd()
            } else {
                LogRepository.warn("Turn Monitoring ON first so the service is running")
            }
        }

        maybeRequestPostNotificationsPermission()
    }

    override fun onStart() {
        super.onStart()
        LogRepository.addListener(logListener)
        if (prefs.monitoringEnabled) {
            // Make sure onStartCommand actually runs (not just onBind) so the
            // service is guaranteed to be a real foreground service, even if
            // it wasn't already running for some reason.
            startForegroundService(Intent(this, MonitorForegroundService::class.java))
        }
        bindService(
            Intent(this, MonitorForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        refreshPermissionStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatuses()
    }

    override fun onStop() {
        super.onStop()
        LogRepository.removeListener(logListener)
        if (isBound) {
            monitorService?.removeStatusListener(statusListener)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Nothing extra needed here - onResume() re-checks every status.
    }

    private fun startMonitoring() {
        val intent = Intent(this, MonitorForegroundService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopMonitoring() {
        monitorService?.stopMonitoring()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        binding.textSpotifyStatus.text = getString(R.string.status_not_running)
        binding.textCurrentTrack.text = getString(R.string.none_placeholder)
    }

    private fun maybeRequestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun refreshPermissionStatuses() {
        val notifGranted = isNotificationListenerEnabled()
        binding.textNotifAccessStatus.text =
            getString(if (notifGranted) R.string.status_granted else R.string.status_not_granted)
        binding.textNotifAccessStatus.setTextColor(statusColor(notifGranted))

        val accessibilityGranted = isAccessibilityServiceEnabled()
        binding.textAccessibilityStatus.text =
            getString(if (accessibilityGranted) R.string.status_granted else R.string.status_not_granted)
        binding.textAccessibilityStatus.setTextColor(statusColor(accessibilityGranted))

        val batteryExempt = isIgnoringBatteryOptimizations()
        binding.textBatteryStatus.text =
            getString(if (batteryExempt) R.string.status_granted else R.string.status_not_granted)
        binding.textBatteryStatus.setTextColor(statusColor(batteryExempt))
    }

    private fun statusColor(granted: Boolean) =
        getColor(if (granted) R.color.log_event else R.color.log_warn)

    private fun isNotificationListenerEnabled(): Boolean {
        val expected = ComponentName(this, SpotifyNotificationListenerService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(":").any { it == expected }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, SpotifyAdSkipAccessibilityService::class.java)
            .flattenToString()
        // Using the raw Settings key (rather than the symbolic constant)
        // since only the key string itself, not a stable public field, is
        // guaranteed across SDK versions for this check.
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            "enabled_accessibility_services"
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            LogRepository.warn("This device has no battery-optimization exemption screen")
        }
    }

    companion object {
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
    }
}

/** Colour-codes each line by [LogLevel] while reusing item_log.xml. */
private class LogAdapter(context: Context) :
    ArrayAdapter<LogEntry>(context, R.layout.item_log) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_log, parent, false)
        val entry = getItem(position) ?: return view
        val textView = view.findViewById<TextView>(R.id.textLogLine)
        textView.text = entry.formatted()
        val colorRes = when (entry.level) {
            LogLevel.INFO -> R.color.log_info
            LogLevel.EVENT -> R.color.log_event
            LogLevel.WARN -> R.color.log_warn
            LogLevel.ERROR -> R.color.log_error
        }
        textView.setTextColor(context.getColor(colorRes))
        return view
    }

    fun replaceAll(newEntries: List<LogEntry>) {
        setNotifyOnChange(false)
        clear()
        addAll(newEntries)
        notifyDataSetChanged()
    }
}
