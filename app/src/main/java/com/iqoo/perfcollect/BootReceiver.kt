package com.iqoo.perfcollect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("perfcollect", Context.MODE_PRIVATE)
            if (prefs.getBoolean("autostart", false)) {
                try {
                    val i = Intent(context, CollectorService::class.java).setAction(CollectorService.ACTION_START)
                    context.startForegroundService(i)
                } catch (e: Exception) {
                    android.util.Log.w("BootReceiver", "FGS start denied at boot: ${e.message}")
                }
            }
            // Accessibility detector auto-persists across reboots once enabled —
            // no boot restart needed for auto-run.
        }
    }
}