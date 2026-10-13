package com.marcosvec.pcwake

/**
 * Burst, then keep re-bursting every ~10 s until the PC answers or the window closes.
 * Covers both Wi-Fi WoL quirks: the card ignores packets for ~20-30 s right after
 * the PC goes to sleep, and single packets get dropped.
 */
object Waker {
    const val WINDOW_MS = 60_000L
    const val REBURST_MS = 10_000L

    data class Result(val awake: Boolean, val secs: Long, val bursts: Int)

    fun wakeAndConfirm(c: Config, windowMs: Long = WINDOW_MS, onBurst: (Int) -> Unit = {}): Result {
        val t0 = System.currentTimeMillis()
        var bursts = 0
        var lastBurst = 0L
        while (true) {
            if (bursts == 0 || System.currentTimeMillis() - lastBurst >= REBURST_MS) {
                bursts++
                onBurst(bursts)
                Wol.burst(c.pcMac, c.broadcast, c.pcIp)
                lastBurst = System.currentTimeMillis()
            }
            if (Net.isAwake(c.pcIp)) return Result(true, (System.currentTimeMillis() - t0) / 1000, bursts)
            if (System.currentTimeMillis() - t0 >= windowMs) return Result(false, windowMs / 1000, bursts)
            Thread.sleep(1000)
        }
    }
}