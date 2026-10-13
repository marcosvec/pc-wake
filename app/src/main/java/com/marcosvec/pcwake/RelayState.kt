package com.marcosvec.pcwake

/** Live relay state, written by RelayService and shown on the TV screen. Same process, so plain fields. */
object RelayState {
    @Volatile var startedAt = 0L
    @Volatile var lastPollAt = 0L
    @Volatile var pollError: String? = null
    @Volatile var authProblem: String? = null
    @Volatile var pcAwake: Boolean? = null
    @Volatile var pcCheckedAt = 0L
    @Volatile var lastResult: String? = null
    @Volatile var lastResultAt = 0L
    @Volatile var lastStatusWriteAt = 0L
    @Volatile var waking = false
}