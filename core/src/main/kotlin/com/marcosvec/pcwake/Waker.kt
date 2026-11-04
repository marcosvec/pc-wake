package com.marcosvec.pcwake

import java.net.DatagramSocket

/**
 * Burst, then keep re-bursting every ~10 s until the PC answers or the window closes.
 * Covers both Wi-Fi WoL quirks: the card ignores packets for ~20-30 s right after
 * the PC goes to sleep, and single packets get dropped.
 */
object Waker {
    const val WINDOW_MS = 60_000L
    const val REBURST_MS = 10_000L

    data class Result(val awake: Boolean, val secs: Long, val bursts: Int)

    fun wakeAndConfirm(
        c: Config,
        windowMs: Long = WINDOW_MS,
        onBurst: (Int) -> Unit = {},
        prepareSocket: (DatagramSocket) -> Unit = {},
    ): Result = wakeLoop(
        windowMs = windowMs,
        onBurst = onBurst,
        isAwake = { Net.isAwake(c.pcIp) },
        burst = { Wol.burst(c.pcMac, c.broadcast, c.pcIp, prepare = prepareSocket) },
    )

    fun wakeLoop(
        windowMs: Long = WINDOW_MS,
        reburstMs: Long = REBURST_MS,
        onBurst: (Int) -> Unit = {},
        isAwake: () -> Boolean,
        burst: () -> Unit,
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        val t0 = now()
        var bursts = 0
        var lastBurst = 0L
        while (true) {
            val t = now()
            if (bursts == 0 || t - lastBurst >= reburstMs) {
                bursts++
                onBurst(bursts)
                burst()
                lastBurst = now()
            }
            if (isAwake()) return Result(true, (now() - t0) / 1000, bursts)
            if (now() - t0 >= windowMs) return Result(false, windowMs / 1000, bursts)
            sleep(1_000)
        }
    }
}
