package com.marcosvec.pcwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires every ~15 min. Restarts the relay if Fire OS killed it quietly. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!RelayService.running) RelayService.start(context, "watchdog")
    }
}