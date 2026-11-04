package com.marcosvec.pcwake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakerTest {
    @Test fun reburstsUntilThePcAnswers() {
        var now = 0L
        var awake = false
        val seen = mutableListOf<Int>()
        val result = Waker.wakeLoop(
            windowMs = 60_000,
            reburstMs = 10_000,
            onBurst = { seen += it },
            isAwake = { awake },
            burst = { if (seen.size == 2) awake = true },
            now = { now },
            sleep = { now += it },
        )
        assertTrue(result.awake)
        assertEquals(2, result.bursts)
        assertEquals(10L, result.secs)
        assertEquals(listOf(1, 2), seen)
    }

    @Test fun stopsAtTheWindowWhenNothingAnswers() {
        var now = 0L
        val result = Waker.wakeLoop(
            windowMs = 25_000,
            reburstMs = 10_000,
            isAwake = { false },
            burst = {},
            now = { now },
            sleep = { now += it },
        )
        assertFalse(result.awake)
        assertEquals(3, result.bursts)
        assertEquals(25L, result.secs)
    }
}
