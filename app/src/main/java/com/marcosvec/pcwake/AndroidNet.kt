package com.marcosvec.pcwake

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramSocket

/** Binds wake packets to the Stick's current network, then runs the shared wake loop. */
object AndroidNet {
    fun bind(context: Context, socket: DatagramSocket) {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            cm.activeNetwork?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }

    fun wake(context: Context, config: Config, onBurst: (Int) -> Unit = {}): Waker.Result =
        Waker.wakeAndConfirm(config, onBurst = onBurst) { socket -> bind(context, socket) }
}
