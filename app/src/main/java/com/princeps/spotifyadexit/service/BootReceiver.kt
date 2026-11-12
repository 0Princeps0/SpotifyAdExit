package com.princeps.spotifyadexit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.princeps.spotifyadexit.core.LogRepository
import com.princeps.spotifyadexit.core.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.monitoringEnabled) {
            LogRepository.info("Device booted - restarting monitoring (was left ON)")
            context.startForegroundService(Intent(context, MonitorForegroundService::class.java))
        }
    }
}
