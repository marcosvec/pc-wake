package com.marcosvec.pcwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the relay after a reboot or power cut. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RelayService.start(context, "boot:${intent.action}")
    }
}