package com.marcosvec.pcwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the relay after a reboot, a power cut, or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RelayService.start(context, "boot:${intent.action?.substringAfterLast('.')}")
    }
}